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

    Database(StoreTrackPlugin plugin, Config cfg) {
        HikariConfig hc = new HikariConfig();
        switch (cfg.storageType) {
            case MYSQL -> {
                hc.setJdbcUrl("jdbc:mysql://" + cfg.dbHost + ":" + cfg.dbPort + "/" + cfg.dbDatabase + "?useSSL=false&autoReconnect=true");
                hc.setUsername(cfg.dbUsername);
                hc.setPassword(cfg.dbPassword);
            }
            case MARIADB -> {
                hc.setJdbcUrl("jdbc:mariadb://" + cfg.dbHost + ":" + cfg.dbPort + "/" + cfg.dbDatabase);
                hc.setUsername(cfg.dbUsername);
                hc.setPassword(cfg.dbPassword);
            }
            default -> hc.setJdbcUrl("jdbc:sqlite:" + new File(plugin.getDataFolder(), "storetrack.db").getAbsolutePath());
        }
        hc.setMaximumPoolSize(cfg.storageType == Config.StorageType.SQLITE ? 1 : cfg.dbPoolSize);
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

    // ---- payments ----

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

    /** One batched insert for a whole poll's worth of new payments, per the "no per-row writes" rule. */
    void insertBatch(List<Payment> payments) {
        if (payments.isEmpty())
            return;
        String sql = "INSERT INTO payments (id, uuid, name, amount, currency, packages, date, status) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
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
            throw new IllegalStateException(e);
        }
    }

    void updateStatus(String id, String status, String reason) {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement("UPDATE payments SET status=?, remove_reason=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, reason);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void updateAmount(String id, double amount) {
        update("UPDATE payments SET amount=? WHERE id=?", ps -> {
            ps.setDouble(1, amount);
            ps.setString(2, id);
        });
    }

    void updatePlayer(String id, UUID uuid, String name) {
        update("UPDATE payments SET uuid=?, name=? WHERE id=?", ps -> {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
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

    List<Payment> removed() {
        return query("SELECT * FROM payments WHERE status='removed' ORDER BY date DESC");
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
        return query("SELECT * FROM payments WHERE status='completed' ORDER BY date DESC LIMIT " + limit);
    }

    /** uuid, name, total - ordered highest first. monthAfter=0 means all-time. */
    List<Object[]> topSpenders(long monthAfter, int limit) {
        String sql = "SELECT uuid, name, SUM(amount) t FROM payments WHERE status='completed' AND date >= ? GROUP BY uuid ORDER BY t DESC LIMIT " + limit;
        List<Object[]> out = new ArrayList<>();
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, monthAfter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Object[]{rs.getString("uuid"), rs.getString("name"), rs.getDouble("t")});
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

    int rankOf(UUID uuid, long monthAfter) {
        String sql = "SELECT COUNT(*)+1 r FROM (SELECT uuid, SUM(amount) t FROM payments WHERE status='completed' AND date >= ? GROUP BY uuid) x WHERE x.t > ?";
        double mine = totalFor(uuid, monthAfter);
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, monthAfter);
            ps.setDouble(2, mine);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
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
        upsert(key, value, "meta", "k", "v");
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
        update("INSERT INTO goals (id, name, current, target, status) VALUES (?,?,?,?,?) "
            + "ON CONFLICT(id) DO UPDATE SET name=excluded.name, current=excluded.current, target=excluded.target, status=excluded.status", ps -> {
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
        update("INSERT INTO milestones_claimed (goal_id, percent) VALUES (?,?)", ps -> {
            ps.setString(1, goalId);
            ps.setInt(2, percent);
        });
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
        update("INSERT INTO donor_claims (uuid, threshold) VALUES (?,?)", ps -> {
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

    private void upsert(String key, String value, String table, String keyCol, String valCol) {
        String sql = "INSERT INTO " + table + " (" + keyCol + "," + valCol + ") VALUES (?,?) "
            + "ON CONFLICT(" + keyCol + ") DO UPDATE SET " + valCol + "=excluded." + valCol;
        update(sql, ps -> {
            ps.setString(1, key);
            ps.setString(2, value);
        });
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
