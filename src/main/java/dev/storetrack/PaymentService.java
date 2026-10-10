package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polling, batching and every side effect that follows a batch of new payments. Everything here
 * except the final hop to the main thread runs off the main thread - PaymentService is only ever
 * driven by an async repeating task (see StoreTrackPlugin) and by admin commands.
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
    private final long installedAt;
    /** One poll at a time: two overlapping ones would both see a payment as new and reward it twice. */
    private final AtomicBoolean polling = new AtomicBoolean();

    PaymentService(StoreTrackPlugin plugin, Config cfg, Database db, PaymentProvider provider,
                    Rankings rankings, GoalService goals, DonorTiers donorTiers, Webhooks webhooks, NpcSkins npcSkins,
                    long installedAt) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.db = db;
        this.provider = provider;
        this.rankings = rankings;
        this.goals = goals;
        this.donorTiers = donorTiers;
        this.webhooks = webhooks;
        this.npcSkins = npcSkins;
        this.installedAt = installedAt;
    }

    /** Called once at startup so placeholders aren't empty before the first poll. */
    void primeCaches() {
        refreshAllCaches();
    }

    /** Called on the polling task. */
    void pollOnce() {
        if (!polling.compareAndSet(false, true))
            return; // the last poll is still running
        try {
            poll();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("A poll failed and will be tried again: " + e);
        } finally {
            polling.set(false);
        }
    }

    /** When the last poll finished and how many payments it found, for /storetrack status. */
    volatile long lastPollAt;
    volatile int lastPollNew;

    String providerName() {
        return provider.getClass().getSimpleName().replace("Provider", "");
    }

    private void poll() {
        List<Payment> polled = provider.poll();
        lastPollAt = System.currentTimeMillis();
        for (PaymentProvider.Reversal reversal : provider.drainReversals())
            reverse(reversal.id, reversal.status, "reported by " + cfg.provider.name().toLowerCase(Locale.ROOT));

        // Each payment once: a provider can repeat an id, and an id we already hold is not new
        Map<String, Payment> unique = new LinkedHashMap<>();
        for (Payment p : polled)
            if (p.id != null && !unique.containsKey(p.id) && !db.isProcessed(p.id))
                unique.put(p.id, p);
        List<Payment> fresh = new ArrayList<>(unique.values());
        lastPollNew = fresh.size();
        if (fresh.isEmpty())
            return;

        for (Payment p : fresh) {
            if (p.uuid == null && Payment.validName(p.name))
                p.uuid = Mojang.resolve(p.name);
            if (p.name == null && p.uuid != null)
                p.name = Bukkit.getOfflinePlayer(p.uuid).getName();
            p.silent = p.date < installedAt;
        }

        // one transaction for the whole batch, not one write per payment. A payment whose purchase commands are
        // about to run gets a record in the same transaction, so a stop before they ran is listed, never lost quietly.
        Map<String, String> records = db.insertBatch(fresh, this::runsCommands);
        double totalNew = fresh.stream().filter(p -> p.countsTowardTotals() && !p.silent).mapToDouble(p -> p.amount).sum();
        if (totalNew > 0)
            goals.addProgress(totalNew); // recomputes milestones once for the batch, not per payment
        refreshAllCaches();
        for (Payment p : fresh)
            if (p.countsTowardTotals() && !p.silent)
                donorTiers.check(p.uuid, p.name);

        Bukkit.getScheduler().runTask(plugin, () -> {
            List<String> done = new ArrayList<>();
            for (Payment p : fresh) {
                if (!p.countsTowardTotals() || p.silent)
                    continue;
                String record = records.get(p.id);
                try {
                    announce(p);
                    if (record != null)
                        done.add(record);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Announcing payment " + p.id + " failed: " + e);
                    if (record != null)
                        db.flag(record, "announcing failed: " + e);
                }
            }
            plugin.rewardsDone(db, done);
            if (npcSkins != null)
                npcSkins.refreshAll();
        });
    }

    /** Whether the purchase commands will run for this payment, which is when it needs a record. */
    private boolean runsCommands(Payment p) {
        return p.countsTowardTotals() && !p.silent && p.amount >= cfg.minAmountForBroadcast
            && !cfg.onPurchaseCommands.isEmpty() && p.hasValidName();
    }

    private void announce(Payment payment) {
        if (payment.amount < cfg.minAmountForBroadcast)
            return;
        Player player = payment.uuid != null ? Bukkit.getPlayer(payment.uuid) : null;
        String who = payment.shownName();
        String what = payment.shownPackages();

        if (!cfg.purchaseChat.isBlank())
            Text.broadcast(cfg.purchaseChat.replace("%player%", who).replace("%package%", what));

        if (payment.hasValidName()) {
            for (String command : cfg.onPurchaseCommands)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", payment.name)
                    .replace("{amount}", String.valueOf(payment.amount)).replace("{package}", what));
        } else if (!cfg.onPurchaseCommands.isEmpty()) {
            plugin.getLogger().warning("Payment " + payment.id + " has no usable player name, so the on-purchase commands were not run for it.");
        }

        if (player != null) {
            var mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
            if (!cfg.purchaseTitle.isBlank() || !cfg.purchaseSubtitle.isBlank())
                player.showTitle(net.kyori.adventure.title.Title.title(
                    mm.deserialize(cfg.purchaseTitle), mm.deserialize(cfg.purchaseSubtitle.replace("%player%", who))));
            if (!cfg.purchaseSound.isBlank())
                playSound(player, cfg.purchaseSound);
            Fireworks.launch(cfg, player);
        }

        webhooks.send("purchase", Map.of(
            "player", who, "package", what,
            "skin_url", "https://minotar.net/helm/" + (payment.hasValidName() ? payment.name : "MHF_Question"),
            "goal_bar", goals.bar(), "goal_percent", String.valueOf((int) goals.percent()),
            "goal_reward", cfg.goalCompletionCommands.isEmpty() ? "" : cfg.goalCompletionCommands.size() + " reward command(s)"));
    }

    private void playSound(Player player, String spec) {
        String[] parts = spec.split(":");
        org.bukkit.Sound sound = sound(parts[0]);
        if (sound == null)
            return;
        try {
            player.playSound(player.getLocation(), sound,
                parts.length > 1 ? Float.parseFloat(parts[1]) : 1f, parts.length > 2 ? Float.parseFloat(parts[2]) : 1f);
        } catch (NumberFormatException ignored) {
            // a volume or pitch that is not a number: no sound
        }
    }

    /** ENTITY_PLAYER_LEVELUP style names, as written in config.yml. */
    static org.bukkit.Sound sound(String name) {
        String wanted = name.trim().replace('.', '_').toUpperCase(Locale.ROOT);
        for (org.bukkit.Sound sound : org.bukkit.Registry.SOUNDS)
            if (org.bukkit.Registry.SOUNDS.getKey(sound).getKey().replace('.', '_').toUpperCase(Locale.ROOT).equals(wanted))
                return sound;
        return null;
    }

    // ---- admin mutations, all funnelled through here so rankings always stay in sync ----

    void manual(UUID uuid, String name, double amount, String pkg) {
        Payment payment = new Payment("manual-" + UUID.randomUUID(), uuid, name, amount, "USD",
            Payment.clean(pkg, 80, "Manual"), System.currentTimeMillis(), "completed");
        db.insertBatch(List.of(payment));
        goals.addProgress(amount);
        refreshAllCaches();
        donorTiers.check(uuid, name);
    }

    /** An admin takes a payment out of the totals. Chargeback commands run when the reason says refund or chargeback. */
    void remove(String id, String reason) {
        String lower = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        reverse(id, "removed", reason, lower.contains("chargeback") || lower.contains("refund"));
    }

    /** The provider says a payment was refunded or charged back. */
    private void reverse(String id, String status, String reason) {
        reverse(id, status, reason, true);
    }

    private void reverse(String id, String status, String reason, boolean runCommands) {
        Payment payment = db.get(id);
        if (payment == null || !payment.countsTowardTotals()) {
            if (payment == null)
                return;
            db.updateStatus(id, status, reason); // already out of the totals, only the label changes
            return;
        }
        String record = db.updateStatus(id, status, reason,
            runCommands && !cfg.onChargebackCommands.isEmpty() ? "payment=" + id + " player=" + payment.name + " amount=" + payment.amount : null);
        if (payment.date >= installedAt)
            goals.subtractProgress(payment.amount);
        refreshAllCaches();
        if (runCommands)
            runChargebackCommands(payment, record);
    }

    private void runChargebackCommands(Payment payment, String record) {
        if (cfg.onChargebackCommands.isEmpty())
            return;
        // The commands run on the main thread, and only with a name that cannot carry other arguments into them
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String command : cfg.onChargebackCommands) {
                if (command.contains("{player}") && !payment.hasValidName()) {
                    plugin.getLogger().warning("Payment " + payment.id + " has no usable player name, so a chargeback command was skipped.");
                    continue;
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{id}", payment.id)
                    .replace("{player}", String.valueOf(payment.name)).replace("{amount}", String.valueOf(payment.amount))
                    .replace("{package}", payment.shownPackages()));
            }
            plugin.rewardsDone(db, java.util.Collections.singletonList(record));
        });
    }

    void restore(String id) {
        Payment payment = db.get(id);
        if (payment == null)
            return;
        boolean wasOut = !payment.countsTowardTotals();
        db.updateStatus(id, "completed", null);
        if (wasOut && payment.date >= installedAt)
            goals.addProgress(payment.amount);
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
