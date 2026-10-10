package dev.storetrack.safe;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the settings migration against the real files this plugin ships. */
class RepoSafetyTest {

    private static final Logger LOG = Logger.getAnonymousLogger();
    /** file name, version key */
    private static final String[][] FILES = {{"config.yml", "config-version"}, {"messages.yml", "lang-version"}};

    @TempDir
    Path dir;

    private static String shipped(String name) throws Exception {
        try (InputStream in = RepoSafetyTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name + " is not in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration parse(String text) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void shippedFilesCarryAVersionAndHaveNoBeaconSettings() throws Exception {
        for (String[] f : FILES) {
            YamlConfiguration y = parse(shipped(f[0]));
            assertTrue(y.getInt(f[1], 0) >= 1, f[0] + " has no " + f[1]);
            assertFalse(y.contains("metrics.interval-minutes"), f[0]);
            assertFalse(y.contains("metrics.endpoint"), f[0]);
        }
    }

    @Test
    void shippedFilesPassTheChecks() throws Exception {
        for (String[] f : FILES) {
            YamlConfiguration y = parse(shipped(f[0]));
            Guard.Checked checked = Guard.check(f[0], y, y, null);
            assertTrue(checked.ok(), checked.problems.toString());
        }
    }

    @Test
    void everyKeyComesBackWhenItIsMissing() throws Exception {
        for (String[] f : FILES) {
            String text = shipped(f[0]);
            YamlConfiguration defaults = parse(text);
            int version = defaults.getInt(f[1]);
            for (String key : defaults.getKeys(true)) {
                if (key.equals(f[1]))
                    continue;
                String without = ConfigMigrator.withoutKey(text, key).replaceFirst(f[1] + ":\\s*\\d+", f[1] + ": 0");
                Path file = dir.resolve("t.yml");
                Files.writeString(file, without);
                ConfigMigrator.Result result = new ConfigMigrator(f[1], version).run(file, text, LOG);
                assertNotEquals(ConfigMigrator.Outcome.FAILED, result.outcome, f[0] + " without " + key);
                YamlConfiguration back = parse(Files.readString(file));
                for (String k : defaults.getKeys(true))
                    if (!defaults.isConfigurationSection(k))
                        assertEquals(defaults.get(k), back.get(k), f[0] + ": '" + k + "' after '" + key + "' was removed");
            }
        }
    }

    @Test
    void anOldFileKeepsItsValuesAndLosesTheBeaconSettings() throws Exception {
        String text = shipped("config.yml");
        YamlConfiguration defaults = parse(text);
        // an older file: no version, the beacon settings present
        String old = text.replaceFirst("config-version:\\s*\\d+\\s*\\R", "")
            .replaceFirst("(?m)^metrics:\\s*$", "metrics:\n  interval-minutes: 45\n  endpoint: \"http://localhost:4100/api/metrics/ingest\"");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, old);
        ConfigMigrator.Result result = Prep.configMigrator(defaults.getInt("config-version")).run(file, text, LOG);
        YamlConfiguration back = parse(Files.readString(file));
        assertFalse(back.contains("metrics.interval-minutes"));
        assertFalse(back.contains("metrics.endpoint"));
        assertEquals(defaults.getInt("config-version"), back.getInt("config-version"));
        assertEquals(defaults.get("metrics.enabled"), back.get("metrics.enabled"));
        assertNotEquals(ConfigMigrator.Outcome.FAILED, result.outcome);
    }
}
