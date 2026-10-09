package dev.storetrack;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fire-and-forget Discord sends. Never lets a slow/unreachable webhook host delay payment
 * processing - every call runs on its own async future, one retry on failure, then it's logged
 * and dropped. A webhook address holds a secret token, so it is never written to the log.
 */
final class Webhooks {

    private final StoreTrackPlugin plugin;
    private final Config cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Webhooks(StoreTrackPlugin plugin, Config cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    void send(String eventKey, Map<String, String> placeholders) {
        Config.WebhookDef def = cfg.webhooks.get(eventKey);
        if (def == null || !def.enabled() || def.url().isBlank())
            return;
        Map<String, String> full = new java.util.HashMap<>(placeholders);
        full.putIfAbsent("store_url", cfg.storeUrl);
        full.computeIfPresent("goal_bar", (k, v) -> v.replaceAll("&[0-9a-fk-orA-FK-OR]", "")); // Discord has no colour codes
        CompletableFuture.runAsync(() -> attempt(eventKey, def, full, 0));
    }

    private void attempt(String eventKey, Config.WebhookDef def, Map<String, String> placeholders, int retry) {
        try {
            String body = render(def, placeholders);
            HttpRequest req = HttpRequest.newBuilder(URI.create(def.url()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300)
                retryOrLog(eventKey, def, placeholders, retry, "status " + res.statusCode());
        } catch (Exception e) {
            retryOrLog(eventKey, def, placeholders, retry, e.getClass().getSimpleName());
        }
    }

    private void retryOrLog(String eventKey, Config.WebhookDef def, Map<String, String> placeholders, int retry, String reason) {
        if (retry == 0)
            attempt(eventKey, def, placeholders, 1);
        else
            plugin.getLogger().warning("The '" + eventKey + "' webhook failed: " + reason);
    }

    private String render(Config.WebhookDef def, Map<String, String> placeholders) throws IOException {
        String raw = def.mode() == Config.WebhookMode.EMBED
            ? legacyEmbed(placeholders)
            : Files.readString(new File(plugin.getDataFolder(), def.template()).toPath());
        raw = raw.replace("{accent_color}", String.valueOf(def.accentColor()));
        for (Map.Entry<String, String> e : placeholders.entrySet())
            raw = raw.replace("{" + e.getKey() + "}", escape(e.getValue()));
        return raw.replaceAll("\\{[a-z_]+}", ""); // any placeholder this event didn't supply
    }

    /** Legacy fallback used when mode: embed - a single plain Discord embed, no Components V2. */
    private String legacyEmbed(Map<String, String> placeholders) {
        String title = placeholders.getOrDefault("goal_name", placeholders.getOrDefault("sale_name", "StoreTrack"));
        String description = placeholders.getOrDefault("player", "") + " " + placeholders.getOrDefault("package", "");
        return "{\"embeds\":[{\"title\":\"" + escape(title) + "\",\"description\":\"" + escape(description.trim())
            + "\",\"color\":{accent_color},\"allowed_mentions\":{\"parse\":[]}}]}";
    }

    /**
     * Makes a value safe inside a JSON string: quotes, backslashes and control characters are escaped, and an @ gets a
     * zero-width space after it so a package or name called @everyone does not ping the channel.
     */
    static String escape(String value) {
        if (value == null)
            return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '@' -> out.append("@\\u200b");
                default -> {
                    if (c < 32)
                        out.append(String.format("\\u%04x", (int) c));
                    else
                        out.append(c);
                }
            }
        }
        return out.toString();
    }
}
