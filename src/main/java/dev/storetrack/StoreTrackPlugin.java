package dev.storetrack;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

public final class StoreTrackPlugin extends JavaPlugin {

    private Config config;
    private FileConfiguration messages;
    private Database db;
    private PaymentService paymentService;
    private GgWave ggwave;
    private StoreTrackExpansion expansion;
    private BukkitTask pollTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        for (String event : new String[]{"purchase", "goal-reached", "milestone", "sale-activated"})
            saveResource("webhooks/" + event + ".json", false);
        start();
    }

    @Override
    public void onDisable() {
        stop();
    }

    void reloadStoreTrackConfig() {
        stop();
        reloadConfig();
        start();
    }

    private void start() {
        config = Config.load(getConfig());
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));

        db = new Database(this, config);
        PaymentProvider provider = switch (config.provider) {
            case TEBEX -> new TebexProvider(config, db, getLogger());
            case CRAFTINGSTORE -> new CraftingStoreProvider(config, db, getLogger());
            case PAYNOW -> new PaynowProvider(config, db, getLogger());
            case MANUAL -> new ManualProvider();
        };

        Webhooks webhooks = new Webhooks(this, config);
        Rankings rankings = new Rankings(db);
        GoalService goals = new GoalService(this, db, config, webhooks);
        DonorTiers donorTiers = new DonorTiers(this, db, config);

        NpcSkins npcSkins = null;
        if (getServer().getPluginManager().isPluginEnabled("FancyNpcs")) {
            npcSkins = new NpcSkins(this, config, rankings);
            getServer().getPluginManager().registerEvents(npcSkins, this);
        }

        ggwave = new GgWave(this, config);
        getServer().getPluginManager().registerEvents(ggwave, this);

        paymentService = new PaymentService(this, config, db, provider, rankings, goals, donorTiers, webhooks, npcSkins);
        paymentService.primeCaches();

        StoreTrackCommand command = new StoreTrackCommand(this, paymentService, goals, ggwave, webhooks, npcSkins);
        getCommand("storetrack").setExecutor(command);
        getCommand("storetrack").setTabCompleter(command);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new StoreTrackExpansion(this, rankings, goals, config);
            expansion.register();
        }

        pollTask = getServer().getScheduler().runTaskTimerAsynchronously(this, paymentService::pollOnce, 20L, config.pollIntervalSeconds * 20L);
    }

    private void stop() {
        if (pollTask != null)
            pollTask.cancel();
        if (ggwave != null)
            ggwave.stop();
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        if (expansion != null)
            expansion.unregister();
        if (db != null)
            db.close();
    }

    Config config() {
        return config;
    }

    FileConfiguration messages() {
        return messages;
    }
}
