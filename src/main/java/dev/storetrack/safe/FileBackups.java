package dev.storetrack.safe;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/** A dated copy of a plugin's small data files, for plugins that keep their state in YAML. */
public final class FileBackups {

    private FileBackups() {
    }

    /**
     * Copies each existing file into {@code backups/files-<time>/}, reads every YAML copy back to check it, and only
     * then removes the oldest folders beyond {@code keep}. Never throws.
     */
    public static boolean snapshot(Path dataFolder, List<String> names, int keep, Logger log) {
        Path root = dataFolder.resolve("backups");
        Path target = root.resolve("files-" + SafeIo.stamp());
        int count = 0;
        try {
            Files.createDirectories(target);
            for (String name : names) {
                Path source = dataFolder.resolve(name);
                if (!Files.exists(source))
                    continue;
                Path copy = target.resolve(source.getFileName());
                Path tmp = target.resolve(source.getFileName() + ".tmp");
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                SafeIo.move(tmp, copy);
                if (name.endsWith(".yml") && !SafeIo.parses(copy))
                    throw new IOException("the copy of " + name + " cannot be read back (the file itself may be damaged)");
                if (Files.size(copy) != Files.size(source))
                    throw new IOException("the copy of " + name + " has a different size than the file");
                count++;
            }
            prune(root, keep, log);
            Health.backupDone(target.getFileName() + " verified (" + count + " file(s))");
            return true;
        } catch (IOException | RuntimeException e) {
            log.severe("THE BACKUP OF THE DATA FILES FAILED: " + e.getMessage() + ". Earlier backups were kept.");
            Health.backupFailed(String.valueOf(e.getMessage()));
            return false;
        }
    }

    private static void prune(Path root, int keep, Logger log) {
        List<Path> all = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "files-*")) {
            for (Path p : stream)
                all.add(p);
        } catch (IOException e) {
            return;
        }
        Collections.sort(all, Collections.reverseOrder());
        for (int i = Math.max(1, keep); i < all.size(); i++) {
            try (var inside = Files.walk(all.get(i))) {
                for (Path p : (Iterable<Path>) inside.sorted(Collections.reverseOrder())::iterator)
                    Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warning("Could not remove the old backup " + all.get(i).getFileName() + ": " + e.getMessage());
            }
        }
    }
}
