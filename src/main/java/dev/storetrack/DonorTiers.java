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

    /**
     * Call off the main thread - does its own DB reads. Synchronized because a poll and an admin command can both get
     * here, and checking and then claiming a tier must not happen twice for the same player.
     */
    synchronized void check(UUID uuid, String playerName) {
        if (uuid == null)
            return;
        double total = db.totalFor(uuid, 0);
        String who = Payment.validName(playerName) ? playerName : Bukkit.getOfflinePlayer(uuid).getName();
        for (Config.DonorTier tier : cfg.donorTiers) {
            if (total < tier.threshold() || db.donorClaimed(uuid, tier.threshold()))
                continue;
            if (!Payment.validName(who)) {
                // Not claimed, so it is tried again when the name is known
                plugin.getLogger().warning("Donor tier " + tier.threshold() + " for " + uuid + " waits: the name of the player is not known.");
                continue;
            }
            // the record that the commands are about to run is written with the claim, so a stop in between is listed
            String record = db.claimDonor(uuid, tier.threshold(),
                tier.commands().isEmpty() ? null : "tier=" + tier.threshold() + " player=" + who + " uuid=" + uuid);
            java.util.List<String> filled = new java.util.ArrayList<>();
            for (String command : tier.commands())
                filled.add(command.replace("{player}", who));
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String command : filled)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                plugin.rewardsDone(db, java.util.Collections.singletonList(record));
            });
        }
    }
}
