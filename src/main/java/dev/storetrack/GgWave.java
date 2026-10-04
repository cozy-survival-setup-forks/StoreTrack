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
 * Listens at LOWEST with ignoreCancelled = false, so it sees the raw typed message before
 * Quill (our chat plugin) formats or cancels it. Never touches event.message(). When
 * ggwave.hide-chat-message is on, the trigger message of a player who is rewarded in this wave
 * is cancelled so only the wave's own line is shown; every other message is left alone.
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
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (!plain.equalsIgnoreCase(cfg.ggwaveTriggerText))
            return;
        if (!rewarded.add(player.getUniqueId()))
            return;
        if (cfg.ggwaveHideChatMessage)
            event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getServer().sendMessage(MM.deserialize(cfg.ggwaveMessageFormat
                .replace("%message%", cfg.ggwaveMessageText.replace("%player%", player.getName()))));
            for (String command : cfg.ggwaveRewardCommands)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        });
    }
}
