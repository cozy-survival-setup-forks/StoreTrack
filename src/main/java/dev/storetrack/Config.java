package dev.storetrack;

import org.bukkit.FireworkEffect;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

final class Config {

    enum Provider { TEBEX, CRAFTINGSTORE, PAYNOW, MANUAL }
    enum StorageType { SQLITE, MYSQL, MARIADB }
    enum WebhookMode { COMPONENTS, EMBED }

    Provider provider;
    String storeUrl;
    long ignoreBefore;
    int pollIntervalSeconds;
    double minAmountForBroadcast;

    String tebexSecret, craftingstoreSecret, paynowApiKey, paynowStoreId;

    StorageType storageType;
    String dbHost, dbDatabase, dbUsername, dbPassword;
    int dbPort, dbPoolSize;

    List<String> onPurchaseCommands;
    String purchaseChat, purchaseTitle, purchaseSubtitle, purchaseSound;

    String goalModeExternal; // "internal" or "external", external just means an admin/API sets current manually
    String goalBarFull, goalBarEmpty;
    int goalBarLength;
    List<Milestone> milestones;
    List<String> goalCompletionCommands;

    boolean fireworksEnabled;
    String fireworksMode;
    int fireworksPower;
    Map<FireworkEffect.Type, FireworkEffectDef> fireworkEffects;

    boolean ggwaveEnabled, ggwaveHideChatMessage;
    int ggwaveDurationSeconds;
    String ggwaveTriggerText, ggwaveMessageText, ggwaveMessageFormat, ggwaveStartMessage, ggwaveEndMessage;
    List<String> ggwaveRewardCommands;

    String saleName, saleAmount, saleActivatedMessage;

    List<DonorTier> donorTiers;
    List<String> onChargebackCommands;

    Map<String, String> npcSkins;
    String fallbackSkin;

    Map<String, WebhookDef> webhooks;

    int recentSize;

    // Plain classes, not records: the Shadow plugin's ASM-based relocator (through at least 8.3.6)
    // crashes remapping record invokedynamic bootstrap args once shadowJar processes our own
    // compiled classes - see build.gradle.kts. Same accessor-method shape as a record for the
    // call sites that read these.
    static final class Milestone {
        private final int percent;
        private final String message;
        private final List<String> commands;

        Milestone(int percent, String message, List<String> commands) {
            this.percent = percent;
            this.message = message;
            this.commands = commands;
        }

        int percent() {
            return percent;
        }

        String message() {
            return message;
        }

        List<String> commands() {
            return commands;
        }
    }

    static final class DonorTier {
        private final double threshold;
        private final List<String> commands;

        DonorTier(double threshold, List<String> commands) {
            this.threshold = threshold;
            this.commands = commands;
        }

        double threshold() {
            return threshold;
        }

        List<String> commands() {
            return commands;
        }
    }

    static final class FireworkEffectDef {
        private final FireworkEffect.Type type;
        private final List<String> colors;
        private final boolean flicker;
        private final boolean trail;

        FireworkEffectDef(FireworkEffect.Type type, List<String> colors, boolean flicker, boolean trail) {
            this.type = type;
            this.colors = colors;
            this.flicker = flicker;
            this.trail = trail;
        }

        FireworkEffect.Type type() {
            return type;
        }

        List<String> colors() {
            return colors;
        }

        boolean flicker() {
            return flicker;
        }

        boolean trail() {
            return trail;
        }
    }

    static final class WebhookDef {
        private final boolean enabled;
        private final String url;
        private final WebhookMode mode;
        private final String template;
        private final String accentColor;

        WebhookDef(boolean enabled, String url, WebhookMode mode, String template, String accentColor) {
            this.enabled = enabled;
            this.url = url;
            this.mode = mode;
            this.template = template;
            this.accentColor = accentColor;
        }

        boolean enabled() {
            return enabled;
        }

        String url() {
            return url;
        }

        WebhookMode mode() {
            return mode;
        }

        String template() {
            return template;
        }

        String accentColor() {
            return accentColor;
        }
    }

    static Config load(FileConfiguration c, Logger log) {
        Config cfg = new Config();
        cfg.provider = enumOr("provider", c.getString("provider"), Provider.MANUAL, log);
        cfg.storeUrl = c.getString("store-url", "");
        cfg.ignoreBefore = parseDate(c.getString("ignore-before"));
        cfg.pollIntervalSeconds = Math.max(5, c.getInt("poll-interval-seconds", 30));
        cfg.minAmountForBroadcast = c.getDouble("min-amount-for-broadcast", 0);

        cfg.tebexSecret = c.getString("tebex.secret", "");
        cfg.craftingstoreSecret = c.getString("craftingstore.secret", "");
        cfg.paynowApiKey = c.getString("paynow.api-key", "");
        cfg.paynowStoreId = c.getString("paynow.store-id", "");

        cfg.storageType = enumOr("storage.type", c.getString("storage.type"), StorageType.SQLITE, log);
        cfg.dbHost = c.getString("storage.host", "localhost");
        cfg.dbPort = c.getInt("storage.port", 3306);
        cfg.dbDatabase = c.getString("storage.database", "storetrack");
        cfg.dbUsername = c.getString("storage.username", "storetrack");
        cfg.dbPassword = c.getString("storage.password", "");
        cfg.dbPoolSize = Math.max(2, c.getInt("storage.pool-size", 5));

        cfg.onPurchaseCommands = c.getStringList("on-purchase.commands");
        cfg.purchaseChat = c.getString("on-purchase.chat", "");
        cfg.purchaseTitle = c.getString("on-purchase.title", "");
        cfg.purchaseSubtitle = c.getString("on-purchase.subtitle", "");
        cfg.purchaseSound = c.getString("on-purchase.sound", "");

        cfg.goalModeExternal = c.getString("goal.mode", "internal");
        cfg.goalBarFull = c.getString("goal.bar.full", "&a|");
        cfg.goalBarEmpty = c.getString("goal.bar.empty", "&7|");
        cfg.goalBarLength = Math.max(1, Math.min(200, c.getInt("goal.bar.length", 40)));
        cfg.milestones = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("goal.milestones")) {
            int percent = (int) number(m.get("percent"), 0);
            if (percent < 1 || percent > 100) {
                log.warning("A goal.milestones entry has percent " + m.get("percent") + " and was skipped (use 1 to 100).");
                continue;
            }
            cfg.milestones.add(new Milestone(percent, text(m.get("message")).replace("%%", "%"), strings(m.get("commands"))));
        }
        cfg.milestones.sort(java.util.Comparator.comparingInt(Milestone::percent));
        cfg.goalCompletionCommands = c.getStringList("goal.completion-commands");

        cfg.fireworksEnabled = c.getBoolean("fireworks.enabled", true);
        cfg.fireworksMode = c.getString("fireworks.mode", "player");
        cfg.fireworksPower = c.getInt("fireworks.power", 1);
        cfg.fireworkEffects = new HashMap<>();
        ConfigurationSection effects = c.getConfigurationSection("fireworks.effects");
        if (effects != null) {
            for (String key : effects.getKeys(false)) {
                ConfigurationSection e = effects.getConfigurationSection(key);
                if (e == null)
                    continue;
                FireworkEffect.Type type = enumOr("fireworks.effects." + key, key, FireworkEffect.Type.BALL, log);
                cfg.fireworkEffects.put(type, new FireworkEffectDef(type, e.getStringList("colors"), e.getBoolean("flicker", true), e.getBoolean("trail", true)));
            }
        }

        cfg.ggwaveEnabled = c.getBoolean("ggwave.enabled", false);
        cfg.ggwaveHideChatMessage = c.getBoolean("ggwave.hide-chat-message", true);
        cfg.ggwaveDurationSeconds = Math.max(1, c.getInt("ggwave.duration-seconds", 10));
        cfg.ggwaveTriggerText = c.getString("ggwave.trigger-text", "gg").toLowerCase(Locale.ROOT);
        cfg.ggwaveMessageText = c.getString("ggwave.message-text", "GG - %player%");
        cfg.ggwaveMessageFormat = c.getString("ggwave.message-format", "%message%");
        cfg.ggwaveStartMessage = c.getString("ggwave.start-message", "");
        cfg.ggwaveEndMessage = c.getString("ggwave.end-message", "");
        cfg.ggwaveRewardCommands = c.getStringList("ggwave.reward-commands");

        cfg.saleName = c.getString("sale.name", "");
        cfg.saleAmount = c.getString("sale.amount", "");
        cfg.saleActivatedMessage = c.getString("sale.activated-message", "");

        cfg.donorTiers = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("donor-tiers")) {
            double threshold = number(m.get("threshold"), 0);
            if (threshold <= 0) {
                log.warning("A donor-tiers entry has threshold " + m.get("threshold") + " and was skipped (use an amount above 0).");
                continue;
            }
            cfg.donorTiers.add(new DonorTier(threshold, strings(m.get("commands"))));
        }
        cfg.donorTiers.sort(java.util.Comparator.comparingDouble(DonorTier::threshold));
        cfg.onChargebackCommands = c.getStringList("on-chargeback.commands");

        cfg.npcSkins = new HashMap<>();
        ConfigurationSection npc = c.getConfigurationSection("npc-skins");
        if (npc != null)
            for (String key : npc.getKeys(false))
                cfg.npcSkins.put(key, npc.getString(key));
        cfg.fallbackSkin = c.getString("fallback-skin", "MHF_Question");

        cfg.webhooks = new HashMap<>();
        ConfigurationSection webhooksSection = c.getConfigurationSection("webhooks");
        if (webhooksSection != null) {
            for (String key : webhooksSection.getKeys(false)) {
                ConfigurationSection w = webhooksSection.getConfigurationSection(key);
                if (w == null)
                    continue;
                boolean enabled = w.getBoolean("enabled", false);
                String url = w.getString("webhook-url", "").trim();
                if (enabled && !url.startsWith("https://"))
                    log.warning("Webhook '" + key + "' is on but webhook-url is not an https address, so nothing will be sent.");
                cfg.webhooks.put(key, new WebhookDef(
                    enabled, url,
                    "embed".equalsIgnoreCase(w.getString("mode")) ? WebhookMode.EMBED : WebhookMode.COMPONENTS,
                    w.getString("template", ""), colorNumber(w.getString("accent-color", ""))));
            }
        }

        cfg.recentSize = Math.max(1, Math.min(100, c.getInt("recent.size", 10)));
        return cfg;
    }

    private static long parseDate(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(value).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static <E extends Enum<E>> E enumOr(String path, String name, E fallback, Logger log) {
        if (name == null || name.isBlank())
            return fallback;
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning(path + " is '" + name + "', which is not one of " + java.util.Arrays.toString(fallback.getDeclaringClass().getEnumConstants())
                + ". Using " + fallback + ".");
            return fallback;
        }
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number n && Double.isFinite(n.doubleValue()))
            return n.doubleValue();
        if (value instanceof String text)
            try {
                double parsed = Double.parseDouble(text.trim());
                return Double.isFinite(parsed) ? parsed : fallback;
            } catch (NumberFormatException ignored) {
                // falls through
            }
        return fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list)
            for (Object o : list)
                if (o != null)
                    out.add(String.valueOf(o));
        return out;
    }

    /** Discord wants the colour as a whole number: #RRGGBB (or a plain number) becomes that number, as text. */
    static String colorNumber(String value) {
        String fallback = String.valueOf(0x5865F2);
        if (value == null || value.isBlank())
            return fallback;
        String v = value.trim();
        try {
            return String.valueOf(v.startsWith("#") ? Integer.parseInt(v.substring(1), 16) & 0xFFFFFF : Integer.parseInt(v) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
