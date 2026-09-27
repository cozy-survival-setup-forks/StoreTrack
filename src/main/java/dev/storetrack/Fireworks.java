package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

/** Runs on the main thread only - call after hopping back from the async payment poll. */
final class Fireworks {

    private Fireworks() {
    }

    static void launch(Config cfg, Player buyer) {
        if (!cfg.fireworksEnabled || cfg.fireworkEffects.isEmpty())
            return;
        var targets = "all".equalsIgnoreCase(cfg.fireworksMode) ? Bukkit.getOnlinePlayers() : java.util.List.of(buyer);
        for (Player player : targets) {
            Firework firework = player.getWorld().spawn(player.getLocation().add(0, 1, 0), Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.setPower(cfg.fireworksPower);
            for (Config.FireworkEffectDef def : cfg.fireworkEffects.values()) {
                FireworkEffect.Builder builder = FireworkEffect.builder().with(def.type()).flicker(def.flicker()).trail(def.trail());
                for (String hex : def.colors())
                    builder.withColor(hexColor(hex));
                meta.addEffect(builder.build());
            }
            firework.setFireworkMeta(meta);
        }
    }

    private static Color hexColor(String hex) {
        return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
    }
}
