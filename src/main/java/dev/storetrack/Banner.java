package dev.storetrack;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** The console startup banner. Same emblem and gradient as every other Groovified/Blockie Studios plugin. */
final class Banner {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Banner() {
    }

    static void print(JavaPlugin plugin, String thankYou) {
        var console = Bukkit.getConsoleSender();
        console.sendMessage(MINI.deserialize("<#9D4EDD>   ▲"));
        console.sendMessage(MINI.deserialize("<#4E81DA>  ▐█▌"));
        console.sendMessage(MINI.deserialize("<#00B4D8>   ▼"));
        console.sendMessage(MINI.deserialize("<white><bold>" + plugin.getName() + "</bold> <gray>v" + plugin.getPluginMeta().getVersion()));
        console.sendMessage(MINI.deserialize("<gray>by Groovified — Blockie Studios"));
        console.sendMessage(MINI.deserialize("<gray>Running on <white>" + Bukkit.getName() + " " + Bukkit.getMinecraftVersion()));
        console.sendMessage(MINI.deserialize(""));
        console.sendMessage(MINI.deserialize("<gray>Support: <aqua>discord.gg/blockie"));
        console.sendMessage(MINI.deserialize("<italic><gray>" + thankYou));
    }
}
