package dev.storetrack;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** Every value is a read off Rankings'/GoalService's cached in-memory state - never DB/IO. */
final class StoreTrackExpansion extends PlaceholderExpansion {

    private final StoreTrackPlugin plugin;
    private final Rankings rankings;
    private final GoalService goals;
    private final Config cfg;

    StoreTrackExpansion(StoreTrackPlugin plugin, Rankings rankings, GoalService goals, Config cfg) {
        this.plugin = plugin;
        this.rankings = rankings;
        this.goals = goals;
        this.cfg = cfg;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "storetrack";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Groovified";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (params.startsWith("alltime_"))
            return rankEntry(rankings.get().allTime(), params.substring("alltime_".length()));
        if (params.startsWith("monthly_"))
            return rankEntry(rankings.get().monthly(), params.substring("monthly_".length()));
        if (params.startsWith("recent_"))
            return recentEntry(params.substring("recent_".length()));

        return switch (params) {
            case "player_alltime" -> player == null ? "0" : String.valueOf(total(player, 0));
            case "player_monthly" -> player == null ? "0" : String.valueOf(total(player, 1));
            case "player_rank_alltime" -> player == null ? "-1" : rank(rankings.get().allTime(), player.getUniqueId());
            case "player_rank_monthly" -> player == null ? "-1" : rank(rankings.get().monthly(), player.getUniqueId());
            case "goal_current" -> String.valueOf((long) goals.current());
            case "goal_target" -> String.valueOf((long) goals.target());
            case "goal_percent" -> String.valueOf((int) goals.percent());
            case "goal_bar" -> Text.legacy(goals.bar());
            case "sale_name" -> cfg.saleName;
            case "sale_amount" -> cfg.saleAmount;
            default -> null;
        };
    }

    private double total(Player player, int monthly) {
        double[] cached = rankings.onlineTotal(player.getUniqueId());
        return cached == null ? 0 : cached[monthly];
    }

    private String rank(List<Rankings.Entry> list, java.util.UUID uuid) {
        for (int i = 0; i < list.size(); i++)
            if (uuid.equals(list.get(i).uuid()))
                return String.valueOf(i + 1);
        return "-1";
    }

    private String rankEntry(List<Rankings.Entry> list, String suffix) {
        int split = suffix.lastIndexOf('_');
        if (split < 0)
            return null;
        int index;
        try {
            index = Integer.parseInt(suffix.substring(0, split)) - 1;
        } catch (NumberFormatException e) {
            return null;
        }
        String field = suffix.substring(split + 1);
        if (index < 0 || index >= list.size())
            return "name".equals(field) ? "" : "0";
        Rankings.Entry entry = list.get(index);
        return "name".equals(field) ? (entry.name() == null ? "" : entry.name()) : String.valueOf((long) entry.amount());
    }

    private String recentEntry(String suffix) {
        int split = suffix.lastIndexOf('_');
        if (split < 0)
            return null;
        int index;
        try {
            index = Integer.parseInt(suffix.substring(0, split)) - 1;
        } catch (NumberFormatException e) {
            return null;
        }
        String field = suffix.substring(split + 1);
        List<Payment> list = rankings.recent();
        if (index < 0 || index >= list.size())
            return field.equals("name") || field.equals("package") || field.equals("date") ? "" : "0";
        Payment p = list.get(index);
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "name" -> p.name == null ? "" : p.name;
            case "package" -> p.packages == null ? "" : p.packages;
            case "amount" -> String.valueOf((long) p.amount);
            case "date" -> java.time.Instant.ofEpochMilli(p.date).toString();
            default -> null;
        };
    }
}
