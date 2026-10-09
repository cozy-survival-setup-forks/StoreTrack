package dev.storetrack;

import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A precomputed snapshot swapped in atomically after each async refresh - reads are just a volatile get, no query. */
final class Rankings {

    private final Database db;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile List<Payment> recentSnapshot = List.of();
    // online players only - refreshed on the same cadence as the leaderboard, not per placeholder call
    private final Map<UUID, double[]> onlineTotals = new ConcurrentHashMap<>();

    Rankings(Database db) {
        this.db = db;
    }

    // Plain classes, not records - see the comment in Config.java on why shadowJar needs this.
    static final class Entry {
        private final UUID uuid;
        private final String name;
        private final double amount;

        Entry(UUID uuid, String name, double amount) {
            this.uuid = uuid;
            this.name = name;
            this.amount = amount;
        }

        UUID uuid() {
            return uuid;
        }

        String name() {
            return name;
        }

        double amount() {
            return amount;
        }
    }

    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of());

        private final List<Entry> allTime;
        private final List<Entry> monthly;

        Snapshot(List<Entry> allTime, List<Entry> monthly) {
            this.allTime = allTime;
            this.monthly = monthly;
        }

        List<Entry> allTime() {
            return allTime;
        }

        List<Entry> monthly() {
            return monthly;
        }
    }

    /** Blocking DB reads - call from an async task only. */
    void refresh(int limit) {
        long monthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        snapshot = new Snapshot(toEntries(db.topSpenders(0, limit)), toEntries(db.topSpenders(monthStart, limit)));
    }

    private List<Entry> toEntries(List<Object[]> rows) {
        return rows.stream().map(r -> new Entry(
            r[0] == null ? null : UUID.fromString((String) r[0]), (String) r[1], (double) r[2])).toList();
    }

    Snapshot get() {
        return snapshot;
    }

    void refreshRecent(int limit) {
        recentSnapshot = db.recent(limit);
    }

    List<Payment> recent() {
        return recentSnapshot;
    }

    /** Blocking DB reads, one per online player - call from the same async task as refresh(). */
    void refreshOnlineTotals(Iterable<? extends Player> online) {
        for (Player player : online)
            onlineTotals.put(player.getUniqueId(), new double[]{db.totalFor(player.getUniqueId(), 0), db.totalFor(player.getUniqueId(), monthStart())});
        onlineTotals.keySet().removeIf(id -> org.bukkit.Bukkit.getPlayer(id) == null);
    }

    void forget(UUID uuid) {
        onlineTotals.remove(uuid);
    }

    double[] onlineTotal(UUID uuid) {
        return onlineTotals.get(uuid);
    }

    static long monthStart() {
        return LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
