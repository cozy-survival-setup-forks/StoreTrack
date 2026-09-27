package dev.storetrack;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads chat only, at LOWEST with ignoreCancelled = false, so it still sees the raw typed
 * message even if Quill (our chat plugin) reformats or cancels the vanilla broadcast. Never
 * touches event.message() or calls setCancelled - Quill keeps full control of what actually
 * gets shown.
 */
final class GgWave implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final StoreTrackPlugin plugin;
    private final Config cfg;
    private final Set<UUID> rewarded = ConcurrentHashMap.newKeySet();
    private volatile boolean active;
    private BukkitTask timeout;

    GgWave(StoreTrackPlugin plugin, Config cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    boolean isActive() {
        return active;
    }

    void start() {
        active = true;
        rewarded.clear();
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', cfg.ggwaveStartMessage));
        timeout = Bukkit.getScheduler().runTaskLater(plugin, this::stop, cfg.ggwaveDurationSeconds * 20L);
    }

    void stop() {
        if (!active)
            return;
        active = false;
        if (timeout != null)
            timeout.cancel();
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', cfg.ggwaveEndMessage));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        if (!active)
            return;
        Player player = event.getPlayer();
        if (rewarded.contains(player.getUniqueId()))
            return;
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (!plain.equalsIgnoreCase(cfg.ggwaveTriggerText))
            return;
        rewarded.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getServer().sendMessage(MM.deserialize(cfg.ggwaveMessageFormat
                .replace("%message%", cfg.ggwaveMessageText.replace("%player%", player.getName()))));
            for (String command : cfg.ggwaveRewardCommands)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        });
    }
}
