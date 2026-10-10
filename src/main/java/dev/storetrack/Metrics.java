package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

/**
 * Anonymous usage beacon: plugin name/version, server software/version and player counts, nothing else.
 * One line in config.yml (metrics.enabled) turns it off. Same shape for every Groovified/Blockie Studios plugin.
 * The address and the interval are fixed here on purpose, they are not settings.
 */
final class Metrics {

    private static final String ENDPOINT = "http://localhost:4100/api/metrics/ingest";
    private static final int INTERVAL_MINUTES = 45;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final JavaPlugin plugin;
    private final String serverId;

    private Metrics(JavaPlugin plugin, String serverId) {
        this.plugin = plugin;
        this.serverId = serverId;
    }

    /**
     * Schedules the first beacon and every one after it, unless metrics.enabled is false.
     *
     * @param serverId the id kept by the plugin's own storage (see ServerId); null switches the beacon off
     */
    static void start(JavaPlugin plugin, String serverId) {
        if (!plugin.getConfig().getBoolean("metrics.enabled", true) || serverId == null)
            return;
        Metrics metrics = new Metrics(plugin, serverId);
        long ticks = 20L * 60 * INTERVAL_MINUTES;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, metrics::send, 20L * 120, ticks);
    }

    private void send() {
        try {
            String json = "{"
                    + "\"schema\":1,"
                    + "\"server_id\":\"" + serverId + "\","
                    + "\"plugin\":\"" + escape(plugin.getName()) + "\","
                    + "\"plugin_version\":\"" + escape(plugin.getPluginMeta().getVersion()) + "\","
                    + "\"server_software\":\"" + escape(Bukkit.getName()) + "\","
                    + "\"mc_version\":\"" + escape(Bukkit.getMinecraftVersion()) + "\","
                    + "\"online_players\":" + Bukkit.getOnlinePlayers().size() + ","
                    + "\"max_players\":" + Bukkit.getMaxPlayers()
                    + "}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "gzip")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(json)))
                    .build();
            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(e -> {
                        plugin.getLogger().log(Level.FINE, "Metrics beacon failed", e);
                        return null;
                    });
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "Metrics beacon failed", e);
        }
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
