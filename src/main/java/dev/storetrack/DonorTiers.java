package dev.storetrack;

import org.bukkit.Bukkit;

import java.util.UUID;

/** Checked once per processed payment for that player - each threshold's commands run exactly once, ever. */
final class DonorTiers {

    private final StoreTrackPlugin plugin;
    private final Database db;
    private final Config cfg;

    DonorTiers(StoreTrackPlugin plugin, Database db, Config cfg) {
        this.plugin = plugin;
        this.db = db;
        this.cfg = cfg;
    }

    /** Call off the main thread - does its own DB reads. */
    void check(UUID uuid, String playerName) {
        if (uuid == null)
            return;
        double total = db.totalFor(uuid, 0);
        for (Config.DonorTier tier : cfg.donorTiers) {
            if (total < tier.threshold() || db.donorClaimed(uuid, tier.threshold()))
                continue;
            db.claimDonor(uuid, tier.threshold());
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String command : tier.commands())
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", playerName));
            });
        }
    }
}
