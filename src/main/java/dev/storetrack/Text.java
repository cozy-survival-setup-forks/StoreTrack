package dev.storetrack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;

/** Text written with & colour codes, the way config.yml and messages.yml spell it. */
final class Text {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private Text() {
    }

    static Component component(String text) {
        return AMP.deserialize(text);
    }

    /** The same text with section-sign colour codes, for the places that still take a String. */
    static String legacy(String text) {
        return SECTION.serialize(AMP.deserialize(text));
    }

    static void broadcast(String text) {
        Bukkit.getServer().sendMessage(component(text));
    }
}
