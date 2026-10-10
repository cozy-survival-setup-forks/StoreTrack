package dev.storetrack;

import dev.storetrack.safe.ConfigMigrator;
import dev.storetrack.safe.Doctor;
import dev.storetrack.safe.FileBackups;
import dev.storetrack.safe.Guard;
import dev.storetrack.safe.Health;
import dev.storetrack.safe.Prep;
import dev.storetrack.safe.ServerId;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class StoreTrackPlugin extends JavaPlugin {

    private static final String[] WEBHOOK_EVENTS = {"purchase", "goal-reached", "milestone", "sale-activated"};

    private static final int CONFIG_VERSION = 1;
    private static final int LANG_VERSION = 1;

    private final List<Prep.Spec> files = List.of(
        new Prep.Spec("config.yml", "config-version", CONFIG_VERSION, Prep.configMigrator(CONFIG_VERSION), rules -> {
            rules.range("poll-interval-seconds", 5, 86_400);
            rules.range("min-amount-for-broadcast", 0, 1_000_000_000);
            rules.range("storage.port", 1, 65_535);
            rules.range("storage.pool-size", 2, 100);
            rules.range("recent.size", 1, 100);
            rules.range("goal.bar.length", 1, 200);
            rules.range("ggwave.duration-seconds", 1, 3600);
            rules.range("backup.interval-hours", 1, 168);
            rules.range("backup.keep", 1, 90);
        }),
        new Prep.Spec("messages.yml", "lang-version", LANG_VERSION, new ConfigMigrator("lang-version", LANG_VERSION), null));

    private BukkitTask backupTask;
    private boolean started;
    private Config config;
    private FileConfiguration messages;
    private Database db;
    private PaymentService paymentService;
    private Rankings rankings;
    private GoalService goals;
    private PaymentProvider provider;
    private GgWave ggwave;
    private StoreTrackExpansion expansion;
    private BukkitTask pollTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveIfMissing("messages.yml");
        for (String event : WEBHOOK_EVENTS)
            saveIfMissing("webhooks/" + event + ".json");
        Health.storage("SQLite storetrack.db (or the MySQL/MariaDB database in config.yml) for payments, goals and claimed rewards; YAML for config.yml and messages.yml");
        Prep.startup(this, files);

        FileConfiguration loadedConfig;
        FileConfiguration loadedMessages;
        try {
            loadedConfig = strict("config.yml");
            loadedMessages = strict("messages.yml");
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("StoreTrack cannot start: " + e.getMessage());
            getLogger().severe("Fix the file (the line is named above) or delete it to get a fresh one, then start the server again.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!start(loadedConfig, loadedMessages)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        boolean beacon = getConfig().getBoolean("metrics.enabled", true);
        Metrics.start(this, ServerId.resolve(getDataFolder().toPath(), db.serverIdSlot(), beacon, getLogger()));
        started = true;
        Banner.print(this, "Thank you for supporting the server.");
        summary();
    }

    @Override
    public void onDisable() {
        stop();
    }

    /** Reads a file of the data folder on top of the one in the jar. A file with a mistake is an error here, not silently empty. */
    private FileConfiguration strict(String name) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), name));
        try (var in = getResource(name)) {
            if (in != null)
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        return yaml;
    }

    private void saveIfMissing(String path) {
        if (!new File(getDataFolder(), path).exists())
            saveResource(path, false);
    }

    /** Returns false (and leaves everything as it was) when a file has a mistake or the database cannot be opened. */
    boolean reloadStoreTrackConfig() {
        if (started) {
            List<Guard.Problem> problems = Prep.validate(this, files);
            if (!problems.isEmpty()) {
                Prep.logRejected(this, problems);
                return false;
            }
        }
        FileConfiguration loadedConfig;
        FileConfiguration loadedMessages;
        try {
            loadedConfig = strict("config.yml");
            loadedMessages = strict("messages.yml");
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("The reload was cancelled and the old settings stay: " + e.getMessage());
            return false;
        }
        stop();
        reloadConfig();
        if (!start(loadedConfig, loadedMessages)) {
            getLogger().severe("StoreTrack is switching itself off. Fix the problem above and restart the server.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        summary();
        return true;
    }

    private boolean start(FileConfiguration loadedConfig, FileConfiguration loadedMessages) {
        config = Config.load(loadedConfig, getLogger());
        messages = loadedMessages;

        try {
            db = new Database(getDataFolder(), config, getLogger(), backupKeep(loadedConfig));
        } catch (RuntimeException | IOException e) {
            Throwable cause = e;
            while (cause.getCause() != null)
                cause = cause.getCause();
            getLogger().severe("The database could not be opened (storage.type is " + config.storageType.name().toLowerCase() + "): " + cause.getMessage());
            if (config.storageType != Config.StorageType.SQLITE)
                getLogger().severe("Check storage.host, storage.port, storage.database, storage.username and storage.password.");
            getLogger().severe("StoreTrack is switching itself off so no payment or reward is lost or run twice. Nothing was changed in the database.");
            Health.failure("the database could not be opened: " + cause.getMessage());
            db = null;
            return false;
        }
        db.recover();

        // The first time StoreTrack runs on an empty database, everything the store already holds is history:
        // it is counted but not announced. An existing install keeps working as it did.
        long installedAt;
        try {
            String installed = db.meta("installed-at", "");
            if (installed.isEmpty()) {
                installed = String.valueOf(db.hasPayments() ? 0L : System.currentTimeMillis());
                db.setMeta("installed-at", installed);
            }
            installedAt = Long.parseLong(installed);
        } catch (RuntimeException e) {
            getLogger().severe("The database could not be read: " + e.getMessage());
            return false;
        }

        provider = switch (config.provider) {
            case TEBEX -> usable("tebex.secret", config.tebexSecret) ? new TebexProvider(config, db, getLogger()) : new ManualProvider();
            case CRAFTINGSTORE -> usable("craftingstore.secret", config.craftingstoreSecret) ? new CraftingStoreProvider(config, db, getLogger()) : new ManualProvider();
            case PAYNOW -> usable("paynow.api-key", config.paynowApiKey) && usable("paynow.store-id", config.paynowStoreId)
                ? new PaynowProvider(config, db, getLogger()) : new ManualProvider();
            case MANUAL -> new ManualProvider();
        };

        Webhooks webhooks = new Webhooks(this, config);
        rankings = new Rankings(db);
        goals = new GoalService(this, db, config, webhooks);
        DonorTiers donorTiers = new DonorTiers(this, db, config);

        NpcSkins npcSkins = null;
        if (getServer().getPluginManager().isPluginEnabled("FancyNpcs")) {
            npcSkins = new NpcSkins(this, config, rankings);
            getServer().getPluginManager().registerEvents(npcSkins, this);
        }

        ggwave = new GgWave(this, config);
        getServer().getPluginManager().registerEvents(ggwave, this);
        getServer().getPluginManager().registerEvents(new Joins(), this);

        paymentService = new PaymentService(this, config, db, provider, rankings, goals, donorTiers, webhooks, npcSkins, installedAt);
        try {
            paymentService.primeCaches();
        } catch (RuntimeException e) {
            getLogger().severe("The database could not be read: " + e.getMessage());
            return false;
        }

        StoreTrackCommand command = new StoreTrackCommand(this, paymentService, goals, ggwave, webhooks, npcSkins);
        getCommand("storetrack").setExecutor(command);
        getCommand("storetrack").setTabCompleter(command);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new StoreTrackExpansion(this, rankings, goals, config);
            expansion.register();
        }

        pollTask = getServer().getScheduler().runTaskTimerAsynchronously(this, paymentService::pollOnce, 20L, config.pollIntervalSeconds * 20L);
        if (db.isSqlite()) {
            Database copying = db;
            long hours = Math.max(1, Math.min(168, loadedConfig.getInt("backup.interval-hours", 6)));
            backupTask = getServer().getScheduler().runTaskTimerAsynchronously(this, copying::backup, 20L * 60, hours * 3600L * 20L);
        }
        return true;
    }

    private boolean usable(String path, String value) {
        if (value != null && !value.isBlank() && !value.equalsIgnoreCase("paste-here"))
            return true;
        getLogger().warning("provider is " + config.provider.name().toLowerCase() + " but " + path
            + " is not filled in, so no payments will be fetched. Paste it into config.yml and use /storetrack reload.");
        return false;
    }

    private void summary() {
        List<String> hooks = new ArrayList<>();
        config.webhooks.forEach((name, def) -> {
            if (def.enabled() && !def.url().isBlank())
                hooks.add(name);
        });
        String how;
        if (!(provider instanceof ManualProvider))
            how = ", checked every " + config.pollIntervalSeconds + "s";
        else if (config.provider == Config.Provider.MANUAL)
            how = " (payments come from /storetrack manual)";
        else
            how = " (not connected, see above)";
        getLogger().info("Ready: provider " + config.provider.name().toLowerCase() + how
            + ", storage " + config.storageType.name().toLowerCase()
            + ", goal " + (long) goals.current() + "/" + (long) goals.target()
            + ", " + config.milestones.size() + " milestone(s), " + config.donorTiers.size() + " donor tier(s)"
            + ", webhooks " + (hooks.isEmpty() ? "off" : String.join(", ", hooks)));
        getLogger().info("PlaceholderAPI " + (expansion != null ? "found, placeholders are on" : "not found, no placeholders")
            + "; FancyNpcs " + (getServer().getPluginManager().isPluginEnabled("FancyNpcs") ? "found, NPC skins are on" : "not found"));
    }

    private static int backupKeep(FileConfiguration c) {
        return Math.max(1, Math.min(90, c.getInt("backup.keep", 7)));
    }

    /** Marks the reward commands of these records as run. The records are written off the main thread. */
    void rewardsDone(Database from, Collection<String> records) {
        List<String> list = records.stream().filter(Objects::nonNull).toList();
        if (list.isEmpty() || from == null)
            return;
        Runnable work = () -> list.forEach(from::succeeded);
        try {
            getServer().getScheduler().runTaskAsynchronously(this, work);
        } catch (IllegalPluginAccessException e) {
            work.run(); // the plugin is shutting down
        }
    }

    /** The text of /storetrack doctor. */
    List<String> doctor() {
        List<String> extra = new ArrayList<>(Prep.versionLines(this, files));
        Database current = db;
        if (current == null)
            return Doctor.report(getName(), getPluginMeta().getVersion(), List.of("The database is not open."));
        if (current.isSqlite()) {
            extra.add("Database: storetrack.db, schema " + current.schemaVersion() + " (this plugin writes " + Database.SCHEMA + ")");
            String newest = current.newestBackup();
            extra.add("Newest database copy on disk: " + (newest == null ? "none yet" : newest));
        } else {
            extra.add("Database: " + config.storageType.name().toLowerCase() + " server (copies are made by the database server)");
        }
        List<String[]> unknown = current.unknownRecords();
        extra.add("Reward commands that may or may not have run (not repeated): " + unknown.size());
        for (String[] row : unknown)
            extra.add("  " + row[0] + " " + row[1] + " at " + row[2] + "   (after checking: /storetrack doctor resolve <id>)");
        return Doctor.report(getName(), getPluginMeta().getVersion(), extra);
    }

    boolean resolveReward(String id) {
        return db != null && db.resolve(id);
    }

    /** /storetrack backup now: a checked copy of the database (SQLite) and of the settings and template files. */
    boolean backupNow() {
        boolean database = db == null || db.backup();
        List<String> names = new ArrayList<>(Prep.fileNames(files));
        for (String event : WEBHOOK_EVENTS)
            names.add("webhooks/" + event + ".json");
        boolean settingsFiles = FileBackups.snapshot(getDataFolder().toPath(), names, 5, getLogger());
        return database && settingsFiles;
    }

    private void stop() {
        if (pollTask != null)
            pollTask.cancel();
        pollTask = null;
        if (backupTask != null)
            backupTask.cancel();
        backupTask = null;
        if (ggwave != null)
            ggwave.stop();
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        if (expansion != null)
            expansion.unregister();
        expansion = null;
        if (db != null)
            db.close();
        db = null;
    }

    Config config() {
        return config;
    }

    FileConfiguration messages() {
        return messages;
    }

    /** Keeps the placeholder totals of the players who are online up to date, so they are right from the moment someone joins. */
    private final class Joins implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTaskAsynchronously(StoreTrackPlugin.this, () -> {
                try {
                    rankings.refreshOnlineTotals(List.of(player));
                } catch (RuntimeException ignored) {
                    // the database is busy or closed during a reload; the next payment refreshes it
                }
            });
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            rankings.forget(event.getPlayer().getUniqueId());
        }
    }
}
