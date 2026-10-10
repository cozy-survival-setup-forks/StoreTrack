package dev.storetrack.safe;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Writes and reads flat files so that a crash or a full disk cannot leave a half-written file in place:
 * the new content goes to a temporary file, is flushed to disk, and then replaces the old file in one step.
 * A readable previous version is kept as {@code name.bak}.
 */
public final class SafeIo {

    /** What may be done when a file cannot be read and has no usable backup. */
    public enum Policy {
        /** Plain settings: start again from the shipped defaults and say so. */
        SETTINGS,
        /** Balances, contributions, rewards: never start from nothing; the plugin must stay off until a person looks. */
        PROTECTED
    }

    public enum State { OK, MISSING, RESTORED, RESET, BLOCKED }

    public static final class Loaded {
        public final YamlConfiguration yaml;
        public final State state;
        public final String note;

        Loaded(YamlConfiguration yaml, State state, String note) {
            this.yaml = yaml;
            this.state = state;
            this.note = note;
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SafeIo() {
    }

    public static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }

    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /** Replaces the file with this text in one step. No backup is made. */
    public static void write(Path file, String text) throws IOException {
        writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBytes(Path file, byte[] data) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining())
                channel.write(buffer);
            channel.force(true);
        }
        move(tmp, file);
    }

    /**
     * Writes a YAML file: the readable file that is there now becomes {@code name.bak}, then the new text replaces it.
     * A file that is already unreadable is not allowed to overwrite the backup.
     */
    public static void writeYaml(Path file, String text) throws IOException {
        if (Files.exists(file) && parses(file)) {
            Path bak = backupOf(file);
            Path tmp = bak.resolveSibling(bak.getFileName() + ".tmp");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            move(tmp, bak);
        }
        write(file, text);
    }

    /** Makes the backup of a file that reads fine and is not saved as its backup yet. Call at startup. */
    public static void refreshBackup(Path file) {
        try {
            if (!Files.exists(file) || !parses(file))
                return;
            Path bak = backupOf(file);
            if (Files.exists(bak) && Files.mismatch(file, bak) == -1L)
                return;
            Path tmp = bak.resolveSibling(bak.getFileName() + ".tmp");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            move(tmp, bak);
        } catch (IOException ignored) {
            // a missing backup only means less protection; the file itself is fine
        }
    }

    public static boolean parses(Path file) {
        try {
            new YamlConfiguration().loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return true;
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Reads a YAML file that the plugin owns. If it cannot be parsed, the backup is tried; if that fails too, the
     * policy decides. The unreadable file is always kept: copied next to it as {@code name.broken-<time>}.
     */
    public static Loaded loadYaml(Path file, Policy policy, Logger log) {
        String name = file.getFileName().toString();
        if (!Files.exists(file)) {
            Health.file(name, "missing, will be created");
            return new Loaded(new YamlConfiguration(), State.MISSING, "missing");
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            Health.file(name, "parses OK");
            return new Loaded(yaml, State.OK, "ok");
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            return recover(file, policy, log, firstLine(e));
        }
    }

    private static Loaded recover(Path file, Policy policy, Logger log, String why) {
        String name = file.getFileName().toString();
        Path aside = file.resolveSibling(name + ".broken-" + stamp());
        try {
            Files.copy(file, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe(name + " is unreadable and a copy of it could not be made: " + e.getMessage());
        }
        Path bak = backupOf(file);
        if (Files.exists(bak) && parses(bak)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(bak, StandardCharsets.UTF_8));
                Path tmp = file.resolveSibling(name + ".restore.tmp");
                Files.copy(bak, tmp, StandardCopyOption.REPLACE_EXISTING);
                move(tmp, file);
                log.warning(name + " could not be read (" + why + "). It was restored from " + bak.getFileName()
                    + "; the unreadable file was kept as " + aside.getFileName() + ".");
                Health.file(name, "restored from backup");
                Health.failure(name + " was unreadable and was restored from its backup");
                return new Loaded(yaml, State.RESTORED, why);
            } catch (IOException | InvalidConfigurationException e) {
                log.severe("Restoring " + name + " from its backup failed: " + e.getMessage());
            }
        }
        if (policy == Policy.SETTINGS) {
            try {
                move(file, aside);
            } catch (IOException e) {
                log.severe("Could not move the unreadable " + name + " away: " + e.getMessage());
            }
            log.warning(name + " could not be read (" + why + ") and there is no usable backup. Starting again from the default "
                + "settings; the unreadable file is kept as " + aside.getFileName() + ".");
            Health.file(name, "reset to defaults");
            Health.failure(name + " was unreadable and was reset to defaults");
            return new Loaded(new YamlConfiguration(), State.RESET, why);
        }
        log.severe(name + " holds data that must not be lost and it cannot be read (" + why + "), and there is no usable backup.");
        log.severe("Nothing will be written over it. The file was left where it is, and a copy was kept as " + aside.getFileName() + ".");
        log.severe("Open the file, fix the mistake (or restore an older copy), and start the server again.");
        Health.file(name, "CORRUPTED - left as it is, needs manual review");
        Health.failure(name + " is corrupted and needs manual review");
        return new Loaded(new YamlConfiguration(), State.BLOCKED, why);
    }

    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String firstLine(Throwable e) {
        String message = String.valueOf(e.getMessage());
        int nl = message.indexOf('\n');
        return nl > 0 ? message.substring(0, nl) : message;
    }
}
