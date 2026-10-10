package dev.storetrack;

import dev.storetrack.safe.Db;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSafetyTest {

    private static final Logger LOG = Logger.getAnonymousLogger();
    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path dir;

    private static Config config() {
        var in = DatabaseSafetyTest.class.getResourceAsStream("/config.yml");
        assertNotNull(in);
        return Config.load(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)), LOG);
    }

    private Database open() throws Exception {
        Database db = new Database(dir.toFile(), config(), LOG, 3);
        db.recover();
        return db;
    }

    private File file() {
        return dir.resolve("storetrack.db").toFile();
    }

    private static Payment payment(String id, double amount) {
        return new Payment(id, STEVE, "Steve", amount, "USD", "Rank", System.currentTimeMillis(), "completed");
    }

    @Test
    void aDamagedDatabaseIsNotReplacedByAnEmptyOne() throws Exception {
        open().close();
        byte[] garbage = "this is not a database, not at all, not one bit".getBytes();
        Files.write(file().toPath(), garbage);
        assertThrows(Db.CorruptException.class, () -> new Database(dir.toFile(), config(), LOG, 3));
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(file().toPath())));
    }

    @Test
    void aDamagedDatabaseComesBackFromTheNewestGoodBackup() throws Exception {
        Database db = open();
        db.insertBatch(List.of(payment("a", 10)));
        assertTrue(db.backup());
        db.close();

        Files.write(file().toPath(), "garbage garbage garbage garbage".getBytes());
        Files.deleteIfExists(dir.resolve("storetrack.db-wal"));
        Database restored = open();
        assertTrue(restored.isProcessed("a"));
        restored.close();
    }

    @Test
    void aDatabaseFromANewerVersionIsNotTouched() throws Exception {
        open().close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file().getAbsolutePath()); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }
        byte[] before = Files.readAllBytes(file().toPath());
        assertThrows(Db.NewerSchemaException.class, () -> new Database(dir.toFile(), config(), LOG, 3));
        assertTrue(Arrays.equals(before, Files.readAllBytes(file().toPath())));
    }

    @Test
    void anExistingDatabaseOfTheOlderVersionIsKept() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file().getAbsolutePath()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE payments (id VARCHAR(64) PRIMARY KEY, uuid VARCHAR(36), name VARCHAR(64), amount DOUBLE, currency VARCHAR(8), packages VARCHAR(512), date BIGINT, status VARCHAR(16), remove_reason VARCHAR(256))");
            s.execute("INSERT INTO payments VALUES ('old-1', '" + STEVE + "', 'Steve', 25.0, 'USD', 'Rank', 1, 'completed', NULL)");
        }
        Database db = open();
        assertTrue(db.isProcessed("old-1"));
        assertEquals(25, db.totalFor(STEVE, 0), 0.001);
        db.close();
    }

    @Test
    void purchaseCommandsThatWereCutShortAreListedAndNeverRepeated() throws Exception {
        Database db = open();
        Map<String, String> records = db.insertBatch(List.of(payment("a", 10), payment("b", 5)), p -> p.id.equals("a"));
        assertEquals(1, records.size());
        db.close(); // stopped before the commands were run

        Database again = open();
        assertEquals(1, again.unknownRecords().size());
        assertEquals("purchase", again.unknownRecords().get(0)[1]);
        assertTrue(again.isProcessed("a"), "the payment itself is kept");
        String id = again.unknownRecords().get(0)[0];
        assertTrue(again.resolve(id));
        assertTrue(again.unknownRecords().isEmpty());
        again.close();
    }

    @Test
    void commandsThatRanLeaveNothingToCheck() throws Exception {
        Database db = open();
        Map<String, String> records = db.insertBatch(List.of(payment("a", 10)), p -> true);
        db.succeeded(records.get("a"));
        String milestone = db.claimMilestone("g", 50, "goal=g percent=50");
        db.succeeded(milestone);
        String donor = db.claimDonor(STEVE, 50, "tier=50");
        db.succeeded(donor);
        String chargeback = db.updateStatus("a", "refunded", "test", "payment=a");
        db.succeeded(chargeback);
        db.close();

        Database again = open();
        assertTrue(again.unknownRecords().isEmpty());
        assertTrue(again.milestoneClaimed("g", 50));
        assertTrue(again.donorClaimed(STEVE, 50));
        assertEquals("refunded", again.get("a").status);
        again.close();
    }

    @Test
    void aClaimAndItsRecordAreWrittenTogether() throws Exception {
        Database db = open();
        assertNotNull(db.claimMilestone("g", 25, "goal=g percent=25"));
        assertNotNull(db.claimDonor(STEVE, 200, "tier=200"));
        assertNull(db.claimMilestone("g", 50, null));
        db.close(); // stopped before any command ran

        Database again = open();
        assertEquals(2, again.unknownRecords().size());
        assertTrue(again.milestoneClaimed("g", 25));
        assertTrue(again.milestoneClaimed("g", 50));
        again.close();
    }

    @Test
    void theServerIdIsKeptInTheDatabase() throws Exception {
        Database db = open();
        assertNull(db.serverIdSlot().read());
        db.serverIdSlot().write("0a1b2c3d-1111-2222-3333-444455556666");
        db.close();
        Database again = open();
        assertEquals("0a1b2c3d-1111-2222-3333-444455556666", again.serverIdSlot().read());
        again.close();
    }
}
