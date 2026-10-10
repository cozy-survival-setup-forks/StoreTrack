package dev.storetrack;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.storetrack.safe.Db;
import dev.storetrack.safe.DbBackups;
import dev.storetrack.safe.Health;
import dev.storetrack.safe.ServerId;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Every method here blocks on JDBC - callers are responsible for staying off the main thread.
 *
 * <p>SQLite goes through the checked, locked connection of {@link Db} (verified at start, restored from a backup when
 * damaged, copied on a schedule). MySQL and MariaDB go through a pool, and keeping copies of those is the job of the
 * database server.
 */
final class Database {

    static final int SCHEMA = 1;
    private static final String BACKUP_PREFIX = "storetrack";

    private interface Query<T> {
        T run(Connection c) throws SQLException;
    }

    private interface Step {
        void run(Connection c) throws SQLException;
    }

    private final Logger log;
    private final File dataFolder;
    /** SQLite is the only one that is not a MySQL-family server, and the two spell a few statements differently. */
    private final boolean sqlite;
    private final HikariDataSource pool;
    private final Db local;
    private final Path backupDir;
    private DbBackups backups;

    Database(File dataFolder, Config cfg, Logger log, int backupKeep) throws IOException {
        this.log = log;
        this.dataFolder = dataFolder;
        sqlite = cfg.storageType == Config.StorageType.SQLITE;
        backupDir = dataFolder.toPath().toAbsolutePath().resolve("backups");
        if (sqlite) {
            pool = null;
            try {
                Class.forName("org.sqlite.JDBC");
                local = Db.openRecovering(new File(dataFolder, "storetrack.db").toPath().toAbsolutePath(), SCHEMA, backupDir, BACKUP_PREFIX, true, log);
            } catch (ClassNotFoundException | SQLException e) {
                throw new IOException(e.getMessage(), e);
            }
        } else {
            local = null;
            HikariConfig hc = new HikariConfig();
            // The MariaDB driver talks to MySQL servers as well, and it is the one that is bundled
            hc.setDriverClassName("org.mariadb.jdbc.Driver");
            hc.setJdbcUrl("jdbc:mariadb://" + cfg.dbHost + ":" + cfg.dbPort + "/" + cfg.dbDatabase);
            hc.setUsername(cfg.dbUsername);
            hc.setPassword(cfg.dbPassword);
            hc.setMaximumPoolSize(cfg.dbPoolSize);
            hc.setPoolName("StoreTrack");
            pool = new HikariDataSource(hc);
        }
        try {
            migrate();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
        if (sqlite) {
            configureBackups(backupKeep);
            Health.file("storetrack.db", "in use");
        }
    }

    private void migrate() {
        write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS payments (id VARCHAR(64) PRIMARY KEY, uuid VARCHAR(36), name VARCHAR(64), amount DOUBLE, currency VARCHAR(8), packages VARCHAR(512), date BIGINT, status VARCHAR(16), remove_reason VARCHAR(256))");
                s.execute("CREATE TABLE IF NOT EXISTS goals (id VARCHAR(64) PRIMARY KEY, name VARCHAR(128), target DOUBLE, current DOUBLE, status VARCHAR(16))");
                s.execute("CREATE TABLE IF NOT EXISTS meta (k VARCHAR(64) PRIMARY KEY, v VARCHAR(256))");
                s.execute("CREATE TABLE IF NOT EXISTS donor_claims (uuid VARCHAR(36), threshold DOUBLE, PRIMARY KEY (uuid, threshold))");
                s.execute("CREATE TABLE IF NOT EXISTS milestones_claimed (goal_id VARCHAR(64), percent INT, PRIMARY KEY (goal_id, percent))");
                s.execute("CREATE TABLE IF NOT EXISTS op_journal (id VARCHAR(64) PRIMARY KEY, kind VARCHAR(32) NOT NULL, state VARCHAR(16) NOT NULL, "
                    + "detail VARCHAR(512), note VARCHAR(256), started_at BIGINT NOT NULL, finished_at BIGINT)");
            }
        });
        if (sqlite) {
            try {
                if (local.userVersion() < SCHEMA)
                    local.setUserVersion(SCHEMA);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    void close() {
        if (pool != null)
            pool.close();
        if (local != null)
            local.close();
    }

    // ---- connections ----

    private <T> T read(Query<T> query) {
        try {
            if (sqlite)
                return local.read(query::run);
            try (Connection c = pool.getConnection()) {
                return query.run(c);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One transaction: all of it is kept, or none of it. */
    private void write(Step step) {
        try {
            if (sqlite) {
                local.tx(step::run);
                return;
            }
            try (Connection c = pool.getConnection()) {
                c.setAutoCommit(false);
                try {
                    step.run(c);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** INSERT that skips a row that is already there instead of failing. */
    private String insertIgnore() {
        return sqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
    }

    boolean isSqlite() {
        return sqlite;
    }

    // ---- care (SQLite) ----

    void configureBackups(int keep) {
        if (local != null)
            backups = new DbBackups(local, backupDir, BACKUP_PREFIX, keep, log);
    }

    /** A checked copy of the database. True when there is nothing to copy (MySQL keeps its own backups). */
    boolean backup() {
        return backups == null || backups.run();
    }

    int schemaVersion() {
        try {
            return local == null ? -1 : local.userVersion();
        } catch (SQLException e) {
            return -1;
        }
    }

    String newestBackup() {
        List<Path> found = DbBackups.list(backupDir, BACKUP_PREFIX);
        return found.isEmpty() ? null : found.get(0).getFileName().toString();
    }

    /** Where the id of this server is kept: with the data for SQLite, in data.yml for a database that other servers may share. */
    ServerId.Slot serverIdSlot() {
        if (!sqlite)
            return ServerId.inYaml(new File(dataFolder, "data.yml").toPath(), log);
        return new ServerId.Slot() {
            @Override
            public String read() {
                return meta("server-id", null);
            }

            @Override
            public void write(String id) {
                setMeta("server-id", id);
            }
        };
    }

    // ---- the record of rewards that are about to run ----

    /** Writes the entry as pending inside the transaction of the change it belongs to. */
    private static String begin(Connection c, String kind, String detail) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO op_journal (id, kind, state, detail, started_at) VALUES (?,?,'pending',?,?)")) {
            ps.setString(1, id);
            ps.setString(2, kind);
            ps.setString(3, cut(detail, 500));
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
        return id;
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    /** The commands of this entry ran. */
    void succeeded(String record) {
        finish(record, "succeeded", null);
    }

    /** They could not be run at all, and a person has to look. */
    void flag(String record, String note) {
        finish(record, "unknown", note);
    }

    private void finish(String record, String state, String note) {
        if (record == null)
            return;
        try {
            write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE op_journal SET state=?, note=?, finished_at=? WHERE id=? AND state='pending'")) {
                    ps.setString(1, state);
                    ps.setString(2, cut(note, 250));
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setString(4, record);
                    ps.executeUpdate();
                }
            });
        } catch (IllegalStateException e) {
            log.warning("The reward record could not be finished: " + e.getMessage());
        }
    }

    /**
     * At startup: entries that were still pending become unknown and are never run again. They are listed in the
     * console with their details and by /storetrack doctor without them. Old finished entries are dropped.
     *
     * @return how many entries need a person
     */
    int recover() {
        write(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE op_journal SET state='unknown', finished_at=" + System.currentTimeMillis() + " WHERE state='pending'");
                s.executeUpdate("DELETE FROM op_journal WHERE state='succeeded' AND finished_at < " + (System.currentTimeMillis() - 30L * 86_400_000L));
            }
        });
        List<String[]> unknown = unknownRecords();
        for (String[] row : unknown)
            log.severe("Reward commands may or may not have run, and were not run again: " + row[0] + " " + row[1] + " [" + row[3]
                + "] - check it by hand, then resolve it with /storetrack doctor resolve <id>.");
        return unknown.size();
    }

    /** id, kind, time, detail of every entry that needs a person. */
    List<String[]> unknownRecords() {
        return read(c -> {
            List<String[]> out = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, kind, started_at, detail FROM op_journal WHERE state='unknown' ORDER BY started_at")) {
                while (rs.next())
                    out.add(new String[]{rs.getString(1), rs.getString(2), java.time.Instant.ofEpochMilli(rs.getLong(3)).toString(),
                        rs.getString(4) == null ? "" : rs.getString(4)});
            }
            return out;
        });
    }

    /** A person has looked at it. */
    boolean resolve(String record) {
        boolean[] changed = new boolean[1];
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE op_journal SET state='reviewed' WHERE id=? AND state='unknown'")) {
                ps.setString(1, record);
                changed[0] = ps.executeUpdate() > 0;
            }
        });
        return changed[0];
    }

    // ---- payments ----

    boolean hasPayments() {
        return read(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 1 FROM payments LIMIT 1")) {
                return rs.next();
            }
        });
    }

    boolean isProcessed(String id) {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM payments WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * One batched insert for a whole poll's worth of new payments, per the "no per-row writes" rule. A payment whose id
     * is already stored is skipped, so one repeat cannot make the whole batch fail again on every poll.
     */
    void insertBatch(List<Payment> payments) {
        insertBatch(payments, p -> false);
    }

    /**
     * Like {@link #insertBatch(List)}, and for each payment that {@code track} accepts a record that its purchase
     * commands are about to run is written in the same transaction.
     *
     * @return the record of each tracked payment, by payment id
     */
    Map<String, String> insertBatch(List<Payment> payments, Predicate<Payment> track) {
        Map<String, String> records = new LinkedHashMap<>();
        if (payments.isEmpty())
            return records;
        String sql = insertIgnore() + "payments (id, uuid, name, amount, currency, packages, date, status) VALUES (?,?,?,?,?,?,?,?)";
        write(c -> {
            records.clear();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
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
            }
            for (Payment p : payments)
                if (track.test(p))
                    records.put(p.id, begin(c, "purchase", "payment=" + p.id + " player=" + p.name + " amount=" + p.amount));
        });
        return records;
    }

    void updateStatus(String id, String status, String reason) {
        updateStatus(id, status, reason, null);
    }

    /** @return the record that commands are about to run for this change, if {@code detail} was given */
    String updateStatus(String id, String status, String reason, String detail) {
        String[] record = new String[1];
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE payments SET status=?, remove_reason=? WHERE id=?")) {
                ps.setString(1, status);
                ps.setString(2, reason);
                ps.setString(3, id);
                ps.executeUpdate();
            }
            if (detail != null)
                record[0] = begin(c, "chargeback", detail);
        });
        return record[0];
    }

    Payment get(String id) {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM payments WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readPayment(rs) : null;
                }
            }
        });
    }

    List<Payment> history(UUID uuid) {
        return read(c -> {
            List<Payment> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM payments WHERE uuid=? AND status='completed' ORDER BY date DESC")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        out.add(readPayment(rs));
                }
            }
            return out;
        });
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
        return read(c -> {
            List<Object[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, monthAfter);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        out.add(new Object[]{rs.getString("u"), rs.getString("n"), rs.getDouble("t")});
                }
            }
            return out;
        });
    }

    double totalFor(UUID uuid, long monthAfter) {
        String sql = "SELECT COALESCE(SUM(amount),0) t FROM payments WHERE status='completed' AND uuid=? AND date >= ?";
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, monthAfter);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getDouble(1) : 0.0;
                }
            }
        });
    }

    private Payment readPayment(ResultSet rs) throws SQLException {
        String uuidStr = rs.getString("uuid");
        Payment p = new Payment(rs.getString("id"), uuidStr == null ? null : UUID.fromString(uuidStr),
            rs.getString("name"), rs.getDouble("amount"), rs.getString("currency"), rs.getString("packages"),
            rs.getLong("date"), rs.getString("status"));
        p.removeReason = rs.getString("remove_reason");
        return p;
    }

    // ---- meta (cursors, sale, etc.) ----

    String meta(String key, String fallback) {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : fallback;
                }
            }
        });
    }

    void setMeta(String key, String value) {
        update(upsert("meta", "k", "v"), ps -> {
            ps.setString(1, key);
            ps.setString(2, value);
        });
    }

    // ---- goals ----

    double[] goal(String id) { // [current, target] or null
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT current, target FROM goals WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new double[]{rs.getDouble(1), rs.getDouble(2)} : null;
                }
            }
        });
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
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM milestones_claimed WHERE goal_id=? AND percent=?")) {
                ps.setString(1, goalId);
                ps.setInt(2, percent);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    void claimMilestone(String goalId, int percent) {
        claimMilestone(goalId, percent, null);
    }

    /** @return the record that commands are about to run for this milestone, if {@code detail} was given */
    String claimMilestone(String goalId, int percent, String detail) {
        String[] record = new String[1];
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement(insertIgnore() + "milestones_claimed (goal_id, percent) VALUES (?,?)")) {
                ps.setString(1, goalId);
                ps.setInt(2, percent);
                ps.executeUpdate();
            }
            if (detail != null)
                record[0] = begin(c, "milestone", detail);
        });
        return record[0];
    }

    void clearMilestones(String goalId) {
        update("DELETE FROM milestones_claimed WHERE goal_id=?", ps -> ps.setString(1, goalId));
    }

    // ---- donor tiers ----

    boolean donorClaimed(UUID uuid, double threshold) {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM donor_claims WHERE uuid=? AND threshold=?")) {
                ps.setString(1, uuid.toString());
                ps.setDouble(2, threshold);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    void claimDonor(UUID uuid, double threshold) {
        claimDonor(uuid, threshold, null);
    }

    /** @return the record that commands are about to run for this tier, if {@code detail} was given */
    String claimDonor(UUID uuid, double threshold, String detail) {
        String[] record = new String[1];
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement(insertIgnore() + "donor_claims (uuid, threshold) VALUES (?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setDouble(2, threshold);
                ps.executeUpdate();
            }
            if (detail != null)
                record[0] = begin(c, "donor-tier", detail);
        });
        return record[0];
    }

    // ---- helpers ----

    private List<Payment> query(String sql) {
        return read(c -> {
            List<Payment> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                while (rs.next())
                    out.add(readPayment(rs));
            }
            return out;
        });
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
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                ps.executeUpdate();
            }
        });
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
