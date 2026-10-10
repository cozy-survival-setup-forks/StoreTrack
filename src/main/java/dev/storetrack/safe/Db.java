package dev.storetrack.safe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A small SQLite database: one file, WAL mode, one connection used one caller at a time (SQLite allows a single writer
 * anyway). It checks itself when it opens, and it can be copied while in use through a consistent snapshot.
 */
public final class Db implements AutoCloseable {

    /** The file is damaged. */
    public static final class CorruptException extends IOException {
        private static final long serialVersionUID = 1L;

        public CorruptException(String message) {
            super(message);
        }
    }

    /** The file was made by a newer version of the plugin. */
    public static final class NewerSchemaException extends IOException {
        private static final long serialVersionUID = 1L;

        public NewerSchemaException(String message) {
            super(message);
        }
    }

    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    public interface Unit {
        void run(Connection connection) throws SQLException;
    }

    private final Path file;
    private final Connection connection;
    private final Object lock = new Object();

    private Db(Path file, Connection connection) {
        this.file = file;
        this.connection = connection;
    }

    /**
     * Opens the file (making it if it is not there), checks it with {@code PRAGMA quick_check} and refuses one whose
     * {@code user_version} is higher than {@code schemaVersion}.
     */
    public static Db open(Path file, int schemaVersion) throws IOException, SQLException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            harden(c);
            String check = quickCheck(c);
            if (!"ok".equalsIgnoreCase(check))
                throw new CorruptException("the database file failed its integrity check: " + check);
            int version = userVersion(c);
            if (version > schemaVersion)
                throw new NewerSchemaException("the database was made by a newer version of the plugin (schema " + version
                    + ", this one understands " + schemaVersion + ")");
        } catch (IOException | RuntimeException e) {
            closeQuietly(c);
            throw e;
        } catch (SQLException e) {
            closeQuietly(c);
            if (looksCorrupt(e))
                throw new CorruptException(e.getMessage());
            throw e;
        }
        Health.storage("SQLite " + file.getFileName() + ", WAL mode, integrity check passed at start");
        return new Db(file, c);
    }

    /**
     * Like {@link #open}, but a damaged file is replaced by the newest backup that verifies.
     * With {@code protectedData} and no usable backup the exception is passed on and the file is left untouched;
     * otherwise a new empty database is started and the damaged one is kept next to it.
     */
    public static Db openRecovering(Path file, int schemaVersion, Path backupDir, String prefix, boolean protectedData, Logger log)
        throws IOException, SQLException {
        try {
            return open(file, schemaVersion);
        } catch (CorruptException e) {
            log.severe(file.getFileName() + " is damaged: " + e.getMessage());
            Health.failure(file.getFileName() + " is damaged");
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + SafeIo.stamp());
            Files.copy(file, aside, StandardCopyOption.REPLACE_EXISTING);
            Path restored = DbBackups.restoreNewest(file, backupDir, prefix, log);
            if (restored != null) {
                log.severe(file.getFileName() + " was restored from " + restored.getFileName() + ". Anything saved after that backup is lost. "
                    + "The damaged file was kept as " + aside.getFileName() + ".");
                Health.storage("SQLite " + file.getFileName() + " RESTORED from backup " + restored.getFileName());
                return open(file, schemaVersion);
            }
            if (protectedData) {
                log.severe("There is no backup that verifies, and this database holds data that must not be reset. "
                    + "The file was left as it is (copy: " + aside.getFileName() + ").");
                throw e;
            }
            Files.deleteIfExists(file);
            deleteSidecars(file);
            log.warning("There is no backup that verifies, so a new empty " + file.getFileName() + " was started. "
                + "The damaged file was kept as " + aside.getFileName() + ".");
            Health.storage("SQLite " + file.getFileName() + " started EMPTY after damage");
            return open(file, schemaVersion);
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // the original failure is the one that matters
        }
    }

    private static boolean looksCorrupt(SQLException e) {
        int code = e.getErrorCode() & 0xFF;
        String m = String.valueOf(e.getMessage()).toLowerCase();
        return code == 11 || code == 26 || m.contains("not a database") || m.contains("malformed") || m.contains("corrupt");
    }

    static void deleteSidecars(Path file) throws IOException {
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-wal"));
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-shm"));
    }

    private static void harden(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA foreign_keys=ON");
        }
    }

    private static String quickCheck(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA quick_check")) {
            StringBuilder out = new StringBuilder();
            while (rs.next()) {
                if (out.length() > 0)
                    out.append("; ");
                out.append(rs.getString(1));
                if (out.length() > 300)
                    break;
            }
            return out.toString();
        }
    }

    private static int userVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public int userVersion() throws SQLException {
        synchronized (lock) {
            return userVersion(connection);
        }
    }

    public void setUserVersion(int version) throws SQLException {
        synchronized (lock) {
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA user_version=" + version);
            }
        }
    }

    public Path file() {
        return file;
    }

    public <T> T read(Work<T> work) throws SQLException {
        synchronized (lock) {
            return work.run(connection);
        }
    }

    /** Runs the work as one transaction: all of it is kept, or none of it. */
    public void tx(Unit work) throws SQLException {
        synchronized (lock) {
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // the original failure is reported
                }
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
        }
    }

    public <T> T txResult(Work<T> work) throws SQLException {
        Object[] box = new Object[1];
        tx(c -> box[0] = work.run(c));
        @SuppressWarnings("unchecked")
        T result = (T) box[0];
        return result;
    }

    /** The names of the tables of this database, without SQLite's own. */
    public List<String> tables() throws SQLException {
        return read(c -> tablesOf(c));
    }

    static List<String> tablesOf(Connection c) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next())
                names.add(rs.getString(1));
        }
        return names;
    }

    /** A consistent copy of the whole database, taken with VACUUM INTO (never a copy of the file itself). */
    public void snapshotTo(Path target) throws SQLException, IOException {
        Files.deleteIfExists(target);
        synchronized (lock) {
            try (Statement s = connection.createStatement()) {
                s.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // closing anyway
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing more can be done
            }
        }
    }
}
