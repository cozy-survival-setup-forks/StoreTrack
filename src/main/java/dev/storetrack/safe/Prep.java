package dev.storetrack.safe;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What a plugin does with its settings files before it reads them: make sure they are there, repair a file that
 * cannot be read, bring it up to date with the shipped one, and check the values. One {@link Spec} per file.
 */
public final class Prep {

    public static final class Spec {
        final String file;
        final String versionKey;
        final int version;
        final ConfigMigrator migrator;
        final Guard.Extra extra;

        /**
         * @param file       name in the plugin folder and in the jar, such as config.yml
         * @param versionKey the integer key at the top of the file, such as config-version
         * @param migrator   the removals and renames for this file (the version number is taken from it)
         */
        public Spec(String file, String versionKey, int version, ConfigMigrator migrator, Guard.Extra extra) {
            this.file = file;
            this.versionKey = versionKey;
            this.version = version;
            this.migrator = migrator;
            this.extra = extra;
        }

        public String file() {
            return file;
        }
    }

    private Prep() {
    }

    /** Settings that every plugin drops in version 1: the beacon interval and endpoint are no longer options. */
    public static ConfigMigrator configMigrator(int version) {
        return new ConfigMigrator("config-version", version)
            .remove(1, "metrics.interval-minutes")
            .remove(1, "metrics.endpoint");
    }

    /**
     * Runs at startup, before anything reads the files. Values with a problem are reported by file and key; the
     * plugin's own loading then falls back for them. Returns false when a file is newer than this plugin knows.
     */
    public static boolean startup(JavaPlugin plugin, List<Spec> specs) {
        boolean ok = true;
        Path folder = plugin.getDataFolder().toPath();
        for (Spec spec : specs) {
            Path file = folder.resolve(spec.file);
            if (!Files.exists(file) || !SafeIo.parses(file)) {
                SafeIo.Loaded loaded = SafeIo.loadYaml(file, SafeIo.Policy.SETTINGS, plugin.getLogger());
                if (loaded.state == SafeIo.State.RESET || loaded.state == SafeIo.State.MISSING) {
                    if (plugin.getResource(spec.file) != null)
                        plugin.saveResource(spec.file, true);
                }
            }
            String defaults = defaults(plugin, spec.file);
            if (defaults == null || !Files.exists(file))
                continue;
            ConfigMigrator.Result result = spec.migrator.run(file, defaults, plugin.getLogger());
            if (result.outcome == ConfigMigrator.Outcome.TOO_NEW)
                ok = false;
            SafeIo.refreshBackup(file);
            for (Guard.Problem p : check(plugin, spec, file, defaults))
                plugin.getLogger().warning(p + " - the shipped value is used for it until it is fixed.");
        }
        return ok;
    }

    /** For a reload: the problems of the files as they are now. Empty means the reload may go ahead. */
    public static List<Guard.Problem> validate(JavaPlugin plugin, List<Spec> specs) {
        List<Guard.Problem> all = new ArrayList<>();
        Path folder = plugin.getDataFolder().toPath();
        for (Spec spec : specs) {
            Path file = folder.resolve(spec.file);
            String defaults = defaults(plugin, spec.file);
            if (defaults == null)
                continue;
            if (!Files.exists(file))
                continue;
            try {
                YamlConfiguration probe = new YamlConfiguration();
                probe.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                String why = String.valueOf(e.getMessage());
                int nl = why.indexOf('\n');
                all.add(new Guard.Problem(spec.file, "(file)", "cannot be read: " + (nl > 0 ? why.substring(0, nl) : why)));
                continue;
            }
            all.addAll(check(plugin, spec, file, defaults));
        }
        return all;
    }

    private static List<Guard.Problem> check(JavaPlugin plugin, Spec spec, Path file, String defaults) {
        try {
            YamlConfiguration disk = new YamlConfiguration();
            disk.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            YamlConfiguration def = new YamlConfiguration();
            def.loadFromString(defaults);
            return Guard.check(spec.file, disk, def, spec.extra).problems;
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            plugin.getLogger().warning(spec.file + " could not be checked: " + e.getMessage());
            return List.of();
        }
    }

    private static String defaults(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** "config.yml: version 1 (this plugin writes 1)" for each file, for the doctor command. */
    public static List<String> versionLines(JavaPlugin plugin, List<Spec> specs) {
        List<String> out = new ArrayList<>();
        for (Spec spec : specs) {
            Path file = plugin.getDataFolder().toPath().resolve(spec.file);
            String found;
            try {
                YamlConfiguration y = new YamlConfiguration();
                y.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                found = String.valueOf(y.getInt(spec.versionKey, 0));
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                found = "unreadable";
            }
            out.add("Version of " + spec.file + ": " + found + " (this plugin writes " + spec.version + ")");
        }
        return out;
    }

    public static List<String> fileNames(List<Spec> specs) {
        List<String> names = new ArrayList<>();
        for (Spec spec : specs)
            names.add(spec.file);
        return names;
    }

    /** Logs the problems of a rejected reload, one line each. */
    public static void logRejected(JavaPlugin plugin, List<Guard.Problem> problems) {
        plugin.getLogger().severe("The reload was cancelled and the settings in use stay as they were. Fix these and reload again:");
        for (Guard.Problem p : problems)
            plugin.getLogger().severe("  " + p);
        Health.failure("a reload was rejected: " + problems.size() + " problem(s), first: " + problems.get(0));
    }
}
