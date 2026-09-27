package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * One internal-mode community goal. Progress and milestone checks are single comparisons
 * against these cached fields (updated after a payment batch applies), never a re-read from
 * disk per payment. Claimed milestones are tracked in the DB so a burst of payments or a manual
 * jump past several thresholds only fires each one once.
 */
final class GoalService {

    private static final String GOAL_ID = "default";

    private final StoreTrackPlugin plugin;
    private final Database db;
    private final Config cfg;
    private final Webhooks webhooks;
    private final Set<Integer> claimed = new HashSet<>();
    private volatile double current;
    private volatile double target;
    private volatile String name = "Community Goal";

    GoalService(StoreTrackPlugin plugin, Database db, Config cfg, Webhooks webhooks) {
        this.plugin = plugin;
        this.db = db;
        this.cfg = cfg;
        this.webhooks = webhooks;
        double[] saved = db.goal(GOAL_ID);
        if (saved != null) {
            current = saved[0];
            target = saved[1];
        }
        for (Config.Milestone m : cfg.milestones)
            if (db.milestoneClaimed(GOAL_ID, m.percent()))
                claimed.add(m.percent());
    }

    double current() {
        return current;
    }

    double target() {
        return target;
    }

    double percent() {
        return target <= 0 ? 0 : Math.min(100, current / target * 100);
    }

    String bar() {
        int filled = (int) Math.round(percent() / 100 * cfg.goalBarLength);
        return cfg.goalBarFull.repeat(Math.max(0, filled)) + cfg.goalBarEmpty.repeat(Math.max(0, cfg.goalBarLength - filled));
    }

    /** Internal-mode progress from newly-processed payments. Called off the main thread. */
    void addProgress(double amount) {
        if (!"internal".equalsIgnoreCase(cfg.goalModeExternal) || target <= 0)
            return;
        current += amount;
        db.saveGoal(GOAL_ID, name, current, target, "active");
        checkMilestones();
    }

    void set(double newCurrent, double newTarget, String newName, Logger log) {
        current = newCurrent;
        target = newTarget;
        if (newName != null)
            name = newName;
        db.saveGoal(GOAL_ID, name, current, target, "active");
        checkMilestones();
    }

    private void checkMilestones() {
        double pct = percent();
        for (Config.Milestone m : cfg.milestones) {
            if (pct < m.percent() || claimed.contains(m.percent()))
                continue;
            claimed.add(m.percent());
            db.claimMilestone(GOAL_ID, m.percent());
            fireBroadcast(m.percent(), m.message(), m.commands(), m.percent() == 100);
        }
    }

    private void fireBroadcast(int percent, String message, java.util.List<String> commands, boolean isCompletion) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (message != null && !message.isBlank())
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
            for (String command : (isCompletion ? cfg.goalCompletionCommands : commands))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            webhooks.send(isCompletion ? "goal-reached" : "milestone", java.util.Map.of(
                "goal_name", name, "goal_percent", String.valueOf(percent), "goal_bar", bar(),
                "goal_reward", commandsSummary(isCompletion ? cfg.goalCompletionCommands : commands)));
        });
    }

    private String commandsSummary(java.util.List<String> commands) {
        return commands.isEmpty() ? "" : commands.size() + " reward command(s) run for every online player.";
    }
}
