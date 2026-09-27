package dev.storetrack;

import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to FancyNpcs entirely through reflection - its current release is built for a newer JDK
 * than this project's Java 21 toolchain can even read as a compile-time dependency (its class
 * files report major version 69 / Java 25), so a normal compileOnly jar isn't an option. This
 * also happens to be exactly the "check the FancyNpcs version on the classpath" defensiveness
 * the setter needs anyway: any method here that goes missing on some other FancyNpcs build just
 * throws, gets caught, and this feature quietly no-ops instead of crashing StoreTrack.
 *
 * Never resolves a skin from a raw placeholder string like CrownStoreData's NPC skin bug did -
 * npc-skins maps an NPC name straight to an internal rank key, and this class resolves the
 * actual UUID at that rank itself before ever touching FancyNpcs.
 */
final class NpcSkins implements Listener {

    private final StoreTrackPlugin plugin;
    private final Config cfg;
    private final Rankings rankings;
    private final Map<String, UUID> currentlyShown = new ConcurrentHashMap<>();

    NpcSkins(StoreTrackPlugin plugin, Config cfg, Rankings rankings) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.rankings = rankings;
        hookNpcsLoadedEvent();
    }

    /** Bukkit can't register a typed @EventHandler for a class we don't compile against - register raw instead. */
    private void hookNpcsLoadedEvent() {
        try {
            Class<?> eventClass = Class.forName("de.oliver.fancynpcs.api.events.NpcsLoadedEvent");
            HandlerList handlers = (HandlerList) eventClass.getMethod("getHandlerList").invoke(null);
            EventExecutor executor = (listener, event) -> Bukkit.getScheduler().runTaskLater(plugin, this::refreshAll, 100L);
            handlers.register(new RegisteredListener(this, executor, EventPriority.NORMAL, plugin, false));
        } catch (Exception e) {
            plugin.getLogger().info("FancyNpcs NpcsLoadedEvent hook not available (" + e.getMessage() + ") - relying on the poll-cycle refresh only.");
        }
    }

    /** Call after every ranking refresh completes - cheap no-op when nobody's rank changed. */
    void refreshAll() {
        for (Map.Entry<String, String> entry : cfg.npcSkins.entrySet())
            refreshOne(entry.getKey(), entry.getValue());
    }

    private void refreshOne(String npcName, String rankKey) {
        try {
            Class<?> pluginClass = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
            Class<?> npcDataClass = Class.forName("de.oliver.fancynpcs.api.NpcData");
            Class<?> skinDataClass = Class.forName("de.oliver.fancynpcs.api.skins.SkinData");
            Class<?> variantClass = Class.forName("de.oliver.fancynpcs.api.skins.SkinData$SkinVariant");
            Object auto = Enum.valueOf((Class<Enum>) (Class) variantClass, "AUTO");

            Object fancyNpcs = pluginClass.getMethod("get").invoke(null);
            Object npcManager = call(fancyNpcs, "getNpcManager");
            Object npc = call(npcManager, "getNpc", new Class<?>[]{String.class}, npcName);
            if (npc == null)
                return;
            Object npcData = call(npc, "getData");
            Object skinManager = call(fancyNpcs, "getSkinManager");
            Method setSkinData = npcDataClass.getMethod("setSkinData", skinDataClass);
            Method updateForAll = npc.getClass().getMethod("updateForAll");
            Method getByUsername = skinManager.getClass().getMethod("getByUsername", String.class, variantClass);
            Method getByUUID = skinManager.getClass().getMethod("getByUUID", UUID.class, variantClass);

            UUID target = resolveRank(rankKey);
            UUID shown = currentlyShown.get(npcName);
            if (target == null) {
                Object fallback = getByUsername.invoke(skinManager, cfg.fallbackSkin, auto);
                if (fallback != null) {
                    setSkinData.invoke(npcData, fallback);
                    updateForAll.invoke(npc);
                }
                currentlyShown.remove(npcName);
                return;
            }
            if (target.equals(shown))
                return; // already showing the right player - don't re-set every refresh

            Object skin = getByUUID.invoke(skinManager, target, auto);
            if (skin == null)
                skin = getByUsername.invoke(skinManager, cfg.fallbackSkin, auto);
            if (skin != null) {
                setSkinData.invoke(npcData, skin);
                updateForAll.invoke(npc);
            }
            currentlyShown.put(npcName, target);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not refresh FancyNpcs skin for '" + npcName + "': " + e.getMessage());
        }
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Object call(Object target, String method, Class<?>[] params, Object... args) throws Exception {
        return target.getClass().getMethod(method, params).invoke(target, args);
    }

    private UUID resolveRank(String rankKey) {
        int split = rankKey.lastIndexOf('_');
        if (split < 0)
            return null;
        String kind = rankKey.substring(0, split);
        int index;
        try {
            index = Integer.parseInt(rankKey.substring(split + 1)) - 1;
        } catch (NumberFormatException e) {
            return null;
        }
        List<Rankings.Entry> list = switch (kind) {
            case "alltime" -> rankings.get().allTime();
            case "monthly" -> rankings.get().monthly();
            default -> null;
        };
        return list != null && index >= 0 && index < list.size() ? list.get(index).uuid() : null;
    }
}
