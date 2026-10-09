package dev.storetrack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * CraftingStore's classic public plugin API is command-queue based (it hands you rendered
 * console commands, not always a clean amount/package split) - verify these field names against
 * a live store secret before relying on amount/package here, CraftingStore's docs site returned
 * 403 to an unauthenticated fetch while writing this. Falls back to amount 0 / package "Unknown"
 * rather than dropping the payment, so on-purchase commands still fire either way.
 */
final class CraftingStoreProvider implements PaymentProvider {
    private final Config cfg;
    private final Database db;
    private final Logger log;

    CraftingStoreProvider(Config cfg, Database db, Logger log) {
        this.cfg = cfg;
        this.db = db;
        this.log = log;
    }

    @Override
    public synchronized List<Payment> poll() {
        List<Payment> out = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.craftingstore.net/v4/queue?secret=" + cfg.craftingstoreSecret))
                .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("CraftingStore poll returned " + res.statusCode());
                return out;
            }
            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonArray queue = root.has("queue") && root.get("queue").isJsonArray() ? root.getAsJsonArray("queue") : new JsonArray();
            for (JsonElement el : queue) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    String id = Http.str(o, "id", "payment_id");
                    if (id == null || db.isProcessed("cs-" + id))
                        continue;
                    String player = Http.str(o, "player", "uuid", "username", "name");
                    UUID uuid = Http.uuid(player);
                    double amount = Http.num(o, "amount", "price", "total");
                    String currency = Http.str(o, "currency");
                    String pkg = Http.str(o, "package", "package_name");
                    out.add(new Payment("cs-" + id, uuid, uuid == null ? player : null, amount,
                        currency == null ? "USD" : currency, pkg == null ? "Unknown" : pkg, System.currentTimeMillis(), "completed"));
                } catch (RuntimeException e) {
                    log.warning("A CraftingStore payment could not be read and was skipped: " + e);
                }
            }
        } catch (Exception e) {
            log.warning("CraftingStore poll failed: " + e.getMessage());
        }
        return out;
    }
}
