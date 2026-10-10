package dev.storetrack.safe;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Copies of the database in a backup folder. A copy is a consistent snapshot, it is opened and checked before it
 * counts, and the older copies are only removed after the new one has passed.
 */
public final class DbBackups {

    private final Db db;
    private final Path dir;
    private final String prefix;
    private final int keep;
    private final Logger log;

    public DbBackups(Db db, Path dir, String prefix, int keep, Logger log) {
        this.db = db;
        this.dir = dir;
        this.prefix = prefix;
        this.keep = Math.max(1, keep);
        this.log = log;
    }

    /** Makes and verifies one backup. Returns true when it succeeded. Never throws. */
    public synchronized boolean run() {
        Path tmp = dir.resolve(prefix + "-" + SafeIo.stamp() + ".db.tmp");
        try {
            Files.createDirectories(dir);
            db.snapshotTo(tmp);
            String summary = verify(tmp, db.tables());
            Path done = dir.resolve(prefix + "-" + SafeIo.stamp() + ".db");
            SafeIo.move(tmp, done);
            prune();
            Health.backupDone(done.getFileName() + " verified (" + summary + ")");
            return true;
        } catch (IOException | SQLException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // leftover temp file, removed with the next attempt
            }
            log.severe("THE BACKUP OF " + db.file().getFileName() + " FAILED: " + e.getMessage()
                + ". The earlier backups were kept. Check the disk space and the folder " + dir + ".");
            Health.backupFailed(String.valueOf(e.getMessage()));
            return false;
        }
    }

    /** Opens the copy, runs the integrity check, and reads a few rows of every table. Returns a short summary. */
    static String verify(Path backup, List<String> expectedTables) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup.toAbsolutePath())) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1)))
                    throw new SQLException("the copy failed its integrity check");
            }
            List<String> have = Db.tablesOf(c);
            for (String table : expectedTables)
                if (!have.contains(table))
                    throw new SQLException("the copy is missing the table " + table);
            long rows = 0;
            for (String table : have) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                    rows += rs.next() ? rs.getLong(1) : 0;
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 3")) {
                    int columns = rs.getMetaData().getColumnCount();
                    while (rs.next())
                        for (int i = 1; i <= columns; i++)
                            rs.getObject(i);
                }
            }
            return have.size() + " tables, " + rows + " rows";
        }
    }

    /** The backups, newest first. */
    public static List<Path> list(Path dir, String prefix) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(dir))
            return found;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "-*.db")) {
            for (Path p : stream)
                found.add(p);
        } catch (IOException ignored) {
            return found;
        }
        Collections.sort(found, Collections.reverseOrder());
        return found;
    }

    private void prune() {
        List<Path> all = list(dir, prefix);
        for (int i = keep; i < all.size(); i++) {
            try {
                Files.deleteIfExists(all.get(i));
            } catch (IOException e) {
                log.warning("Could not remove the old backup " + all.get(i).getFileName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Puts the newest backup that verifies in place of the database file. The database must not be open.
     * Returns the backup that was used, or null when there is none that verifies.
     */
    public static Path restoreNewest(Path dbFile, Path dir, String prefix, Logger log) {
        for (Path candidate : list(dir, prefix)) {
            try {
                verify(candidate, List.of());
                Path tmp = dbFile.resolveSibling(dbFile.getFileName() + ".restore.tmp");
                Files.copy(candidate, tmp, StandardCopyOption.REPLACE_EXISTING);
                Db.deleteSidecars(dbFile);
                SafeIo.move(tmp, dbFile);
                return candidate;
            } catch (IOException | SQLException e) {
                log.warning("The backup " + candidate.getFileName() + " is not usable: " + e.getMessage());
            }
        }
        return null;
    }
}
