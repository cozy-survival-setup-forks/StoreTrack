package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

/**
 * Anonymous usage beacon: plugin name/version, server software/version and player counts, nothing else.
 * One line in config.yml (metrics.enabled) turns it off. Same shape for every Groovified/Blockie Studios plugin.
 */
final class Metrics {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final JavaPlugin plugin;
    private final String serverId;
    private final String endpoint;

    private Metrics(JavaPlugin plugin, String serverId, String endpoint) {
        this.plugin = plugin;
        this.serverId = serverId;
        this.endpoint = endpoint;
    }

    /** Reads config, and if enabled, schedules the first beacon and every one after it. Safe to call even if disabled. */
    static void start(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("metrics.enabled", true)) return;
        String endpoint = config.getString("metrics.endpoint", "http://localhost:4100/api/metrics/ingest");
        int intervalMinutes = Math.max(5, config.getInt("metrics.interval-minutes", 45));
        Metrics metrics = new Metrics(plugin, serverId(plugin), endpoint);
        long ticks20min = 20L * 60 * intervalMinutes;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, metrics::send, 20L * 120, ticks20min);
    }

    private static String serverId(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), ".server-id");
        try {
            if (file.exists()) return Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
            String id = UUID.randomUUID().toString();
            plugin.getDataFolder().mkdirs();
            Files.writeString(file.toPath(), id, StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            return UUID.randomUUID().toString();
        }
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
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
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
