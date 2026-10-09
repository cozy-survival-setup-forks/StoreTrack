package dev.storetrack;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Every method here blocks on JDBC - callers are responsible for staying off the main thread. */
final class Database {

    private final HikariDataSource pool;
    /** SQLite is the only one that is not a MySQL-family server, and the two spell a few statements differently. */
    private final boolean sqlite;

    Database(File dataFolder, Config cfg) {
        HikariConfig hc = new HikariConfig();
        sqlite = cfg.storageType == Config.StorageType.SQLITE;
        if (sqlite) {
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setJdbcUrl("jdbc:sqlite:" + new File(dataFolder, "storetrack.db").getAbsolutePath());
        } else {
            // The MariaDB driver talks to MySQL servers as well, and it is the one that is bundled
            hc.setDriverClassName("org.mariadb.jdbc.Driver");
            hc.setJdbcUrl("jdbc:mariadb://" + cfg.dbHost + ":" + cfg.dbPort + "/" + cfg.dbDatabase);
            hc.setUsername(cfg.dbUsername);
            hc.setPassword(cfg.dbPassword);
        }
        hc.setMaximumPoolSize(sqlite ? 1 : cfg.dbPoolSize);
        hc.setPoolName("StoreTrack");
        pool = new HikariDataSource(hc);
        migrate();
    }

    private void migrate() {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS payments (id VARCHAR(64) PRIMARY KEY, uuid VARCHAR(36), name VARCHAR(64), amount DOUBLE, currency VARCHAR(8), packages VARCHAR(512), date BIGINT, status VARCHAR(16), remove_reason VARCHAR(256))");
            s.execute("CREATE TABLE IF NOT EXISTS goals (id VARCHAR(64) PRIMARY KEY, name VARCHAR(128), target DOUBLE, current DOUBLE, status VARCHAR(16))");
            s.execute("CREATE TABLE IF NOT EXISTS meta (k VARCHAR(64) PRIMARY KEY, v VARCHAR(256))");
            s.execute("CREATE TABLE IF NOT EXISTS donor_claims (uuid VARCHAR(36), threshold DOUBLE, PRIMARY KEY (uuid, threshold))");
            s.execute("CREATE TABLE IF NOT EXISTS milestones_claimed (goal_id VARCHAR(64), percent INT, PRIMARY KEY (goal_id, percent))");
        } catch (SQLException e) {
            throw new IllegalStateException("StoreTrack could not create its tables", e);
        }
    }

    void close() {
        pool.close();
    }

    /** INSERT that skips a row that is already there instead of failing. */
    private String insertIgnore() {
        return sqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
    }

    // ---- payments ----

    boolean hasPayments() {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 1 FROM payments LIMIT 1")) {
            return rs.next();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean isProcessed(String id) {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM payments WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One batched insert for a whole poll's worth of new payments, per the "no per-row writes" rule. A payment whose id
     * is already stored is skipped, so one repeat cannot make the whole batch fail again on every poll.
     */
    void insertBatch(List<Payment> payments) {
        if (payments.isEmpty())
            return;
        String sql = insertIgnore() + "payments (id, uuid, name, amount, currency, packages, date, status) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            try {
                for (Payment p : payments) {
                    ps.setString(1, p.id);
                    ps.setString(2, p.uuid == null ? null : p.uuid.toString());
                    ps.setString(3, p.name);
                    ps.setDouble(4, p.amount);
                    ps.setString(5, p.currency);
                    ps.setString(6, p.packages);
                    ps.setLong(7, p.date);
                    ps.setString(8, p.status);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void updateStatus(String id, String status, String reason) {
        update("UPDATE payments SET status=?, remove_reason=? WHERE id=?", ps -> {
            ps.setString(1, status);
            ps.setString(2, reason);
            ps.setString(3, id);
        });
    }

    Payment get(String id) {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM payments WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    List<Payment> history(UUID uuid) {
        List<Payment> out = new ArrayList<>();
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM payments WHERE uuid=? AND status='completed' ORDER BY date DESC")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(read(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    List<Payment> recent(int limit) {
        return query("SELECT * FROM payments WHERE status='completed' ORDER BY date DESC LIMIT " + Math.max(1, limit));
    }

    /**
     * uuid, name, total - ordered highest first. monthAfter=0 means all-time. A buyer whose uuid is not known is
     * counted by name, so unresolved buyers are not lumped together as one.
     */
    List<Object[]> topSpenders(long monthAfter, int limit) {
        String sql = "SELECT MAX(uuid) u, MAX(name) n, SUM(amount) t FROM payments WHERE status='completed' AND date >= ? "
            + "GROUP BY COALESCE(uuid, name) ORDER BY t DESC LIMIT " + Math.max(1, limit);
        List<Object[]> out = new ArrayList<>();
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, monthAfter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Object[]{rs.getString("u"), rs.getString("n"), rs.getDouble("t")});
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    double totalFor(UUID uuid, long monthAfter) {
        String sql = "SELECT COALESCE(SUM(amount),0) t FROM payments WHERE status='completed' AND uuid=? AND date >= ?";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, monthAfter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Payment read(ResultSet rs) throws SQLException {
        String uuidStr = rs.getString("uuid");
        Payment p = new Payment(rs.getString("id"), uuidStr == null ? null : UUID.fromString(uuidStr),
            rs.getString("name"), rs.getDouble("amount"), rs.getString("currency"), rs.getString("packages"),
            rs.getLong("date"), rs.getString("status"));
        p.removeReason = rs.getString("remove_reason");
        return p;
    }

    // ---- meta (cursors, sale, etc.) ----

    String meta(String key, String fallback) {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : fallback;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void setMeta(String key, String value) {
        update(upsert("meta", "k", "v"), ps -> {
            ps.setString(1, key);
            ps.setString(2, value);
        });
    }

    // ---- goals ----

    double[] goal(String id) { // [current, target] or null
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT current, target FROM goals WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new double[]{rs.getDouble(1), rs.getDouble(2)} : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void saveGoal(String id, String name, double current, double target, String status) {
        update(upsert("goals", "id", "name", "current", "target", "status"), ps -> {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setDouble(3, current);
            ps.setDouble(4, target);
            ps.setString(5, status);
        });
    }

    boolean milestoneClaimed(String goalId, int percent) {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM milestones_claimed WHERE goal_id=? AND percent=?")) {
            ps.setString(1, goalId);
            ps.setInt(2, percent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void claimMilestone(String goalId, int percent) {
        update(insertIgnore() + "milestones_claimed (goal_id, percent) VALUES (?,?)", ps -> {
            ps.setString(1, goalId);
            ps.setInt(2, percent);
        });
    }

    void clearMilestones(String goalId) {
        update("DELETE FROM milestones_claimed WHERE goal_id=?", ps -> ps.setString(1, goalId));
    }

    // ---- donor tiers ----

    boolean donorClaimed(UUID uuid, double threshold) {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM donor_claims WHERE uuid=? AND threshold=?")) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void claimDonor(UUID uuid, double threshold) {
        update(insertIgnore() + "donor_claims (uuid, threshold) VALUES (?,?)", ps -> {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, threshold);
        });
    }

    // ---- helpers ----

    private List<Payment> query(String sql) {
        List<Payment> out = new ArrayList<>();
        try (Connection c = pool.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next())
                out.add(read(rs));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /**
     * An insert that replaces the row with the same key. The first column is the key, the others are replaced.
     * SQLite and the MySQL family spell this differently.
     */
    private String upsert(String table, String keyColumn, String... valueColumns) {
        StringBuilder columns = new StringBuilder(keyColumn);
        StringBuilder marks = new StringBuilder("?");
        StringBuilder set = new StringBuilder();
        for (String column : valueColumns) {
            columns.append(", ").append(column);
            marks.append(",?");
            if (set.length() > 0)
                set.append(", ");
            set.append(column).append(sqlite ? "=excluded." + column : "=VALUES(" + column + ")");
        }
        String head = "INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ") ";
        return sqlite ? head + "ON CONFLICT(" + keyColumn + ") DO UPDATE SET " + set
            : head + "ON DUPLICATE KEY UPDATE " + set;
    }

    private void update(String sql, SqlBinder binder) {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
