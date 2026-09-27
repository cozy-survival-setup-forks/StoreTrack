package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Polling, batching and every side effect that follows a batch of new payments. Everything here
 * except the final `announce` hop runs off the main thread - PaymentService is only ever driven
 * by an async repeating task (see StoreTrackPlugin).
 */
final class PaymentService {

    private final StoreTrackPlugin plugin;
    private final Config cfg;
    private final Database db;
    private final PaymentProvider provider;
    private final Rankings rankings;
    private final GoalService goals;
    private final DonorTiers donorTiers;
    private final Webhooks webhooks;
    private final NpcSkins npcSkins;

    PaymentService(StoreTrackPlugin plugin, Config cfg, Database db, PaymentProvider provider,
                    Rankings rankings, GoalService goals, DonorTiers donorTiers, Webhooks webhooks, NpcSkins npcSkins) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.db = db;
        this.provider = provider;
        this.rankings = rankings;
        this.goals = goals;
        this.donorTiers = donorTiers;
        this.webhooks = webhooks;
        this.npcSkins = npcSkins;
    }

    /** Called once at startup so placeholders aren't empty before the first poll. */
    void primeCaches() {
        rankings.refresh(10);
        rankings.refreshRecent(cfg.recentSize);
    }

    /** Called on the polling task. */
    void pollOnce() {
        List<Payment> fresh = provider.poll();
        if (fresh.isEmpty())
            return;
        for (Payment p : fresh)
            if (p.uuid == null && p.name != null)
                p.uuid = Mojang.resolve(p.name);

        db.insertBatch(fresh); // one transaction for the whole batch, not one write per payment
        double totalNew = fresh.stream().filter(Payment::countsTowardTotals).mapToDouble(p -> p.amount).sum();
        if (totalNew > 0)
            goals.addProgress(totalNew); // recomputes milestones once for the batch, not per payment
        rankings.refresh(10);
        rankings.refreshRecent(cfg.recentSize);
        rankings.refreshOnlineTotals(Bukkit.getOnlinePlayers());
        for (Payment p : fresh)
            if (p.countsTowardTotals())
                donorTiers.check(p.uuid, p.name);

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Payment p : fresh)
                if (p.countsTowardTotals())
                    announce(p);
            if (npcSkins != null)
                npcSkins.refreshAll();
        });
    }

    private void announce(Payment payment) {
        if (payment.amount < cfg.minAmountForBroadcast)
            return;
        Player player = payment.uuid != null ? Bukkit.getPlayer(payment.uuid) : null;

        if (!cfg.purchaseChat.isBlank())
            Bukkit.broadcastMessage(color(cfg.purchaseChat.replace("%player%", payment.name).replace("%package%", payment.packages)));
        for (String command : cfg.onPurchaseCommands)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", payment.name)
                .replace("{amount}", String.valueOf(payment.amount)).replace("{package}", payment.packages));

        if (player != null) {
            var mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
            player.showTitle(net.kyori.adventure.title.Title.title(
                mm.deserialize(cfg.purchaseTitle), mm.deserialize(cfg.purchaseSubtitle.replace("%player%", payment.name))));
            if (!cfg.purchaseSound.isBlank())
                playSound(player, cfg.purchaseSound);
            Fireworks.launch(cfg, player);
        }

        webhooks.send("purchase", Map.of(
            "player", payment.name, "package", payment.packages,
            "skin_url", "https://minotar.net/helm/" + payment.name,
            "goal_bar", goals.bar(), "goal_percent", String.valueOf((int) goals.percent()),
            "goal_reward", cfg.goalCompletionCommands.isEmpty() ? "" : cfg.goalCompletionCommands.size() + " reward command(s)"));
    }

    private void playSound(Player player, String spec) {
        String[] parts = spec.split(":");
        try {
            player.playSound(player.getLocation(), org.bukkit.Sound.valueOf(parts[0]),
                parts.length > 1 ? Float.parseFloat(parts[1]) : 1f, parts.length > 2 ? Float.parseFloat(parts[2]) : 1f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ---- admin mutations, all funnelled through here so rankings always stay in sync ----

    void manual(UUID uuid, String name, double amount, String pkg) {
        db.insertBatch(List.of(new Payment("manual-" + java.util.UUID.randomUUID(), uuid, name, amount, "USD",
            pkg == null ? "Manual" : pkg, System.currentTimeMillis(), "completed")));
        goals.addProgress(amount);
        refreshAllCaches();
        donorTiers.check(uuid, name);
    }

    void remove(String id, String reason) {
        db.updateStatus(id, "removed", reason);
        refreshAllCaches();
        if (reason != null && (reason.toLowerCase().contains("chargeback") || reason.toLowerCase().contains("refund")))
            for (String command : cfg.onChargebackCommands)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{id}", id));
    }

    void restore(String id) {
        db.updateStatus(id, "completed", null);
        refreshAllCaches();
    }

    private void refreshAllCaches() {
        rankings.refresh(10);
        rankings.refreshRecent(cfg.recentSize);
        rankings.refreshOnlineTotals(Bukkit.getOnlinePlayers());
    }

    Payment get(String id) {
        return db.get(id);
    }

    List<Payment> history(UUID uuid) {
        return db.history(uuid);
    }

    /** Cached snapshot, safe to call from the main thread (e.g. the /storetrack recent command). */
    List<Payment> recent() {
        return rankings.recent();
    }
}
