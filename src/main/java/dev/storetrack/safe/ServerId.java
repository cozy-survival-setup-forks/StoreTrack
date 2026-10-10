package dev.storetrack.safe;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The random id the usage beacon sends, kept inside the plugin's own data instead of in a file of its own.
 * An id that already exists (in the old {@code .server-id} file) is moved over unchanged, so a server keeps being the
 * same server; a new id is only made when there is none.
 */
public final class ServerId {

    /** Where the id lives: a key in a YAML data file, or a row in the plugin database. */
    public interface Slot {
        /** The stored id, or null when there is none. */
        String read() throws Exception;

        void write(String id) throws Exception;
    }

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private ServerId() {
    }

    /**
     * @param create whether a new id may be made when there is none (false while the beacon is switched off)
     * @return the id, or null when there is none and none may be made
     */
    public static String resolve(Path dataFolder, Slot slot, boolean create, Logger log) {
        Path legacy = dataFolder.resolve(".server-id");
        try {
            String stored = valid(slot.read());
            String old = null;
            if (Files.exists(legacy))
                old = valid(Files.readString(legacy, StandardCharsets.UTF_8));
            if (stored == null) {
                String id = old != null ? old : create ? UUID.randomUUID().toString() : null;
                if (id == null)
                    return null;
                slot.write(id);
                String back = valid(slot.read());
                if (!id.equals(back)) {
                    log.severe("The server id could not be saved and read back. The old .server-id file was kept.");
                    Health.failure("server id could not be verified in its new place");
                    return id;
                }
                stored = back;
                if (old != null)
                    log.info("The server id was moved from .server-id into the plugin's data and checked.");
            } else if (old != null && !old.equals(stored)) {
                log.warning("The .server-id file holds a different id than the plugin's data. The id in the data is used.");
            }
            if (Files.exists(legacy))
                Files.delete(legacy);
            return stored;
        } catch (Exception e) {
            log.warning("The server id could not be set up (" + e.getMessage() + "); the usage beacon is off until it can.");
            Health.failure("server id: " + e.getMessage());
            return null;
        }
    }

    private static String valid(String id) {
        if (id == null)
            return null;
        String t = id.trim();
        return VALID.matcher(t).matches() ? t : null;
    }

    /** A slot that is the {@code server-id} key of a YAML file. */
    public static Slot inYaml(Path file, Logger log) {
        return new Slot() {
            @Override
            public String read() {
                return SafeIo.loadYaml(file, SafeIo.Policy.SETTINGS, log).yaml.getString("server-id");
            }

            @Override
            public void write(String id) throws IOException {
                YamlConfiguration yaml = SafeIo.loadYaml(file, SafeIo.Policy.SETTINGS, log).yaml;
                yaml.set("server-id", id);
                SafeIo.writeYaml(file, yaml.saveToString());
            }
        };
    }
}
