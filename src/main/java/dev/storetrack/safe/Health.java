package dev.storetrack.safe;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the plugin knows about its own files and saves, for the doctor command. Holds status words and short
 * messages only, never any player data. One copy per plugin (each plugin has its own class loader).
 */
public final class Health {

    private static final int MAX_FAILURES = 8;

    private static final Map<String, String> FILES = new ConcurrentHashMap<>();
    private static final Deque<String> FAILURES = new ArrayDeque<>();
    private static volatile String backup = "no backup has been made yet";
    private static volatile boolean backupFailed;
    private static volatile String storage = "not set";

    private Health() {
    }

    /** One line per file: "parses OK", "restored from backup", "reset to defaults", ... */
    public static void file(String name, String status) {
        FILES.put(name, status);
    }

    public static void storage(String description) {
        storage = description;
    }

    public static void failure(String message) {
        synchronized (FAILURES) {
            FAILURES.addFirst(Instant.now() + " " + message);
            while (FAILURES.size() > MAX_FAILURES)
                FAILURES.removeLast();
        }
    }

    public static void backupDone(String detail) {
        backup = Instant.now() + " - " + detail;
        backupFailed = false;
    }

    public static void backupFailed(String detail) {
        backup = Instant.now() + " - FAILED: " + detail;
        backupFailed = true;
        failure("backup: " + detail);
    }

    public static boolean lastBackupFailed() {
        return backupFailed;
    }

    public static String storageLine() {
        return storage;
    }

    public static String backupLine() {
        return backup;
    }

    public static Map<String, String> files() {
        return new TreeMap<>(FILES);
    }

    public static List<String> failures() {
        synchronized (FAILURES) {
            return new ArrayList<>(FAILURES);
        }
    }

    /** For tests. */
    public static void reset() {
        FILES.clear();
        synchronized (FAILURES) {
            FAILURES.clear();
        }
        backup = "no backup has been made yet";
        backupFailed = false;
        storage = "not set";
    }
}
