package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.List;

/** Runs on the main thread only - call after hopping back from the async payment poll. */
final class Fireworks {

    private Fireworks() {
    }

    static void launch(Config cfg, Player buyer) {
        if (!cfg.fireworksEnabled || cfg.fireworkEffects.isEmpty())
            return;
        var targets = "all".equalsIgnoreCase(cfg.fireworksMode) ? new ArrayList<Player>(Bukkit.getOnlinePlayers()) : List.of(buyer);
        for (Player player : targets) {
            Firework firework = player.getWorld().spawn(player.getLocation().add(0, 1, 0), Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.setPower(Math.max(0, Math.min(5, cfg.fireworksPower)));
            for (Config.FireworkEffectDef def : cfg.fireworkEffects.values()) {
                FireworkEffect.Builder builder = FireworkEffect.builder().with(def.type()).flicker(def.flicker()).trail(def.trail());
                boolean any = false;
                for (String hex : def.colors()) {
                    Color color = hexColor(hex);
                    if (color != null) {
                        builder.withColor(color);
                        any = true;
                    }
                }
                if (any) // an effect needs at least one colour
                    meta.addEffect(builder.build());
            }
            firework.setFireworkMeta(meta);
        }
    }

    /** #RRGGBB, or null when the text is not a colour. */
    static Color hexColor(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
