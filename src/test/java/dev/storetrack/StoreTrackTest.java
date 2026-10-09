package dev.storetrack;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StoreTrackTest {

    private static Config defaults() {
        var in = StoreTrackTest.class.getResourceAsStream("/config.yml");
        assertNotNull(in);
        return Config.load(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)), Logger.getAnonymousLogger());
    }

    private static Config fromText(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        return Config.load(y, Logger.getAnonymousLogger());
    }

    @Test
    void shippedConfigLoadsItsMilestonesAndDonorTiers() {
        Config cfg = defaults();
        assertEquals(3, cfg.milestones.size());
        assertEquals(25, cfg.milestones.get(0).percent());
        assertEquals("&e25% of the way to the community goal!", cfg.milestones.get(0).message());
        assertEquals(2, cfg.donorTiers.size());
        assertEquals(50, cfg.donorTiers.get(0).threshold());
        assertEquals(List.of("lp user {player} parent add vip_donor"), cfg.donorTiers.get(1).commands());
        assertEquals(Config.Provider.MANUAL, cfg.provider);
    }

    @Test
    void doubledPercentSignsFromTheOldDefaultAreFixed() throws Exception {
        Config cfg = fromText("goal:\n  milestones:\n    - percent: 10\n      message: \"10%% done\"\n    - percent: 500\n      message: bad\n");
        assertEquals(1, cfg.milestones.size());
        assertEquals("10% done", cfg.milestones.get(0).message());
    }

    @Test
    void badValuesFallBackInsteadOfThrowing() throws Exception {
        Config cfg = fromText("provider: nonsense\nstorage:\n  type: nope\ngoal:\n  bar:\n    length: 0\nrecent:\n  size: -4\n");
        assertEquals(Config.Provider.MANUAL, cfg.provider);
        assertEquals(Config.StorageType.SQLITE, cfg.storageType);
        assertEquals(1, cfg.goalBarLength);
        assertEquals(1, cfg.recentSize);
    }

    @Test
    void webhookColoursBecomeWholeNumbers() {
        assertEquals(String.valueOf(0x42f584), Config.colorNumber("#42f584"));
        assertEquals("255", Config.colorNumber("255"));
        assertEquals(String.valueOf(0x5865F2), Config.colorNumber("not a colour"));
        assertEquals(String.valueOf(0x5865F2), Config.colorNumber(null));
    }

    @Test
    void namesThatCouldCarryCommandsAreRejected() {
        assertTrue(Payment.validName("Steve_1"));
        assertTrue(Payment.validName(".BedrockPlayer"));
        assertFalse(Payment.validName("Steve; op Steve"));
        assertFalse(Payment.validName("a b"));
        assertFalse(Payment.validName(""));
        assertFalse(Payment.validName(null));
        Payment p = new Payment("x", null, "bad name", 1, "USD", "§cPack\nage", 0, "completed");
        assertEquals("Someone", p.shownName());
        assertEquals("cPackage", p.shownPackages());
    }

    @Test
    void webhookValuesCannotBreakTheJsonOrPingEveryone() {
        assertEquals("a\\\"b\\\\c\\n", Webhooks.escape("a\"b\\c\n"));
        assertEquals("@\\u200beveryone", Webhooks.escape("@everyone"));
        assertEquals("\\u0001", Webhooks.escape("\u0001"));
    }

    @Test
    void uuidsAreReadWithOrWithoutDashes() {
        UUID id = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertEquals(id, Http.uuid("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertEquals(id, Http.uuid("069a79f444e94726a5befca90e38aaf5"));
        assertNull(Http.uuid("nope"));
        assertNull(Http.uuid(null));
    }

    @Test
    void databaseStoresOnceRanksByBuyerAndKeepsMilestoneClaims(@TempDir Path dir) {
        Database db = new Database(dir.toFile(), defaults());
        try {
            assertFalse(db.hasPayments());
            UUID steve = UUID.randomUUID();
            long now = System.currentTimeMillis();
            db.insertBatch(List.of(
                new Payment("a", steve, "Steve", 10, "USD", "Rank", now, "completed"),
                new Payment("b", steve, "Steve", 5, "USD", "Key", now, "completed"),
                new Payment("c", null, "Alex", 7, "USD", "Key", now, "completed"),
                new Payment("d", null, "Alex", 3, "USD", "Key", now, "completed")));
            db.insertBatch(List.of(new Payment("a", steve, "Steve", 999, "USD", "Dupe", now, "completed")));
            assertTrue(db.hasPayments());
            assertTrue(db.isProcessed("a"));
            assertEquals(15, db.totalFor(steve, 0), 0.001);

            List<Object[]> top = db.topSpenders(0, 10);
            assertEquals(2, top.size());
            assertEquals("Steve", top.get(0)[1]);
            assertEquals(15, (double) top.get(0)[2], 0.001);
            assertEquals(10, (double) top.get(1)[2], 0.001); // two buyers without a uuid are grouped by name

            db.updateStatus("a", "refunded", "test");
            assertEquals(5, db.totalFor(steve, 0), 0.001);
            assertEquals("refunded", db.get("a").status);

            db.setMeta("cursor", "1");
            db.setMeta("cursor", "2");
            assertEquals("2", db.meta("cursor", ""));

            db.claimMilestone("g", 25);
            db.claimMilestone("g", 25);
            assertTrue(db.milestoneClaimed("g", 25));
            db.clearMilestones("g");
            assertFalse(db.milestoneClaimed("g", 25));
        } finally {
            db.close();
        }
    }
}
