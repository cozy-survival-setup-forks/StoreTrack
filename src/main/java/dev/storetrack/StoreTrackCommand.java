package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class StoreTrackCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("reload", "manual", "payment", "history", "recent", "goal", "test", "ggwave", "npcrefresh");

    private final StoreTrackPlugin plugin;
    private final PaymentService payments;
    private final GoalService goals;
    private final GgWave ggwave;
    private final Webhooks webhooks;
    private final NpcSkins npcSkins;

    StoreTrackCommand(StoreTrackPlugin plugin, PaymentService payments, GoalService goals, GgWave ggwave, Webhooks webhooks, NpcSkins npcSkins) {
        this.plugin = plugin;
        this.payments = payments;
        this.goals = goals;
        this.ggwave = ggwave;
        this.webhooks = webhooks;
        this.npcSkins = npcSkins;
    }

    private String msg(String key) {
        String text = plugin.messages().getString(key);
        return Text.legacy(text == null ? key : text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("storetrack.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(msg("usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                sender.sendMessage(msg(plugin.reloadStoreTrackConfig() ? "reloaded" : "reload-failed"));
            }
            case "manual" -> manual(sender, args);
            case "payment" -> payment(sender, args);
            case "history" -> history(sender, args);
            case "recent" -> recent(sender, args);
            case "goal" -> goal(sender, args);
            case "test" -> test(sender, args);
            case "ggwave" -> ggwave(sender, args);
            case "npcrefresh" -> {
                if (npcSkins != null)
                    npcSkins.refreshAll();
                sender.sendMessage(msg("npcrefresh-done"));
            }
            default -> sender.sendMessage(msg("usage"));
        }
        return true;
    }

    private void manual(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("usage"));
            return;
        }
        String typed = args[1];
        double amount = parseDoubleOr(args[2], -1);
        if (!Payment.validName(typed) || amount <= 0 || amount > 1_000_000_000) {
            sender.sendMessage(msg("usage"));
            return;
        }
        String pkg = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "Manual";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(typed);
            UUID uuid = cached != null ? cached.getUniqueId() : Mojang.resolve(typed);
            if (uuid == null) {
                sender.sendMessage(msg("player-not-found"));
                return;
            }
            String name = cached != null && cached.getName() != null ? cached.getName() : typed;
            payments.manual(uuid, name, amount, pkg);
            sender.sendMessage(msg("manual-created").replace("%player%", name));
        });
    }

    private void payment(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("usage"));
            return;
        }
        String id = args[2];
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "info" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Payment p = payments.get(id);
                if (p == null) {
                    sender.sendMessage(msg("payment-not-found").replace("%id%", id));
                    return;
                }
                sender.sendMessage(msg("payment-info-header").replace("%id%", id));
                sender.sendMessage(msg("payment-info-line").replace("%key%", "Player").replace("%value%", p.name == null ? "unknown" : p.name));
                sender.sendMessage(msg("payment-info-line").replace("%key%", "Amount").replace("%value%", p.currency + p.amount));
                sender.sendMessage(msg("payment-info-line").replace("%key%", "Status").replace("%value%", p.status));
                sender.sendMessage(msg("payment-info-line").replace("%key%", "Packages").replace("%value%", String.valueOf(p.packages)));
            });
            case "remove" -> {
                String reason = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "unspecified";
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> payments.remove(id, reason));
                sender.sendMessage(msg("payment-removed").replace("%id%", id).replace("%reason%", reason));
            }
            case "restore" -> {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> payments.restore(id));
                sender.sendMessage(msg("payment-restored").replace("%id%", id));
            }
            default -> sender.sendMessage(msg("usage"));
        }
    }

    private void history(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("usage"));
            return;
        }
        String typed = args[1];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer cached = Payment.validName(typed) ? Bukkit.getOfflinePlayerIfCached(typed) : null;
            UUID uuid = cached != null ? cached.getUniqueId() : Mojang.resolve(typed);
            if (uuid == null) {
                sender.sendMessage(msg("player-not-found"));
                return;
            }
            List<Payment> list = payments.history(uuid);
            sender.sendMessage(msg("history-header").replace("%player%", typed));
            for (Payment p : list)
                sender.sendMessage(line("-", p));
        });
    }

    private String line(String index, Payment p) {
        return msg("recent-line").replace("%index%", index).replace("%name%", p.shownName())
            .replace("%package%", p.shownPackages()).replace("%amount%", p.currency + p.amount);
    }

    private void recent(CommandSender sender, String[] args) {
        int n = Math.max(1, Math.min(100, args.length > 1 ? parseIntOr(args[1], 10) : 10));
        List<Payment> list = payments.recent();
        sender.sendMessage(msg("recent-header"));
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            Payment p = list.get(i);
            sender.sendMessage(line(String.valueOf(i + 1), p));
        }
    }

    private void goal(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("usage"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 4) {
                    sender.sendMessage(msg("usage"));
                    return;
                }
                double target = parseDoubleOr(args[args.length - 1], 0);
                if (target <= 0) {
                    sender.sendMessage(msg("usage"));
                    return;
                }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length - 1));
                goals.restart(target, name);
                sender.sendMessage(msg("goal-created").replace("%name%", name).replace("%target%", String.valueOf(target)));
            }
            case "set" -> {
                if (args.length < 4) {
                    sender.sendMessage(msg("usage"));
                    return;
                }
                double value = parseDoubleOr(args[3], -1);
                if (value < 0) {
                    sender.sendMessage(msg("usage"));
                    return;
                }
                if ("current".equalsIgnoreCase(args[2]))
                    goals.set(value, goals.target(), null);
                else if ("target".equalsIgnoreCase(args[2]))
                    goals.set(goals.current(), value, null);
                else {
                    sender.sendMessage(msg("usage"));
                    return;
                }
                sender.sendMessage(msg("goal-updated"));
            }
            case "delete" -> {
                goals.restart(0, null);
                sender.sendMessage(msg("goal-updated"));
            }
            default -> sender.sendMessage(msg("usage"));
        }
    }

    private void test(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("usage"));
            return;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        switch (type) {
            case "purchase" -> webhooks.send("purchase", Map.of("player", sender.getName(), "package", "Test Package",
                "skin_url", "https://minotar.net/helm/" + sender.getName(), "goal_bar", goals.bar(),
                "goal_percent", String.valueOf((int) goals.percent()), "goal_reward", "test reward"));
            case "goal" -> webhooks.send("goal-reached", Map.of("goal_name", "Test Goal", "goal_reward", "test reward"));
            case "milestone" -> webhooks.send("milestone", Map.of("goal_percent", "50", "goal_bar", goals.bar(), "goal_reward", "test reward"));
            case "sale" -> {
                Text.broadcast(plugin.config().saleActivatedMessage
                    .replace("%sale%", plugin.config().saleName).replace("%type%", "Discount").replace("%amount%", plugin.config().saleAmount));
                webhooks.send("sale-activated", Map.of("sale_name", plugin.config().saleName, "sale_amount", plugin.config().saleAmount));
            }
            default -> sender.sendMessage(msg("usage"));
        }
        sender.sendMessage(msg("test-sent").replace("%type%", type));
    }

    private void ggwave(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("usage"));
            return;
        }
        if ("start".equalsIgnoreCase(args[1])) {
            if (ggwave.isActive()) {
                sender.sendMessage(msg("ggwave-already-active"));
                return;
            }
            ggwave.start();
            sender.sendMessage(msg("ggwave-started"));
        } else if ("stop".equalsIgnoreCase(args[1])) {
            if (!ggwave.isActive()) {
                sender.sendMessage(msg("ggwave-not-active"));
                return;
            }
            ggwave.stop();
            sender.sendMessage(msg("ggwave-stopped"));
        } else {
            sender.sendMessage(msg("usage"));
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDoubleOr(String s, double fallback) {
        try {
            double value = Double.parseDouble(s);
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1)
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "payment" -> List.of("info", "remove", "restore");
                case "goal" -> List.of("create", "set", "delete");
                case "test" -> List.of("purchase", "goal", "milestone", "sale");
                case "ggwave" -> List.of("start", "stop");
                case "manual", "history" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                default -> List.<String>of();
            };
        }
        return List.of();
    }
}
