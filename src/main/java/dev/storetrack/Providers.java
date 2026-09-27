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
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** One implementation per provider. Never touches the main thread - PaymentService already polls async. */
interface PaymentProvider {
    List<Payment> poll();
}

/**
 * Tebex's "Payments Log" plugin API: https://plugin.tebex.io/payments, header X-Tebex-Secret.
 * Polled with the configured check-amount via ?limit, newest first - we only keep rows newer
 * than the last one we've already stored (tracked in meta).
 */
final class TebexProvider implements PaymentProvider {
    private final Config cfg;
    private final Database db;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Logger log;

    TebexProvider(Config cfg, Database db, Logger log) {
        this.cfg = cfg;
        this.db = db;
        this.log = log;
    }

    @Override
    public List<Payment> poll() {
        List<Payment> out = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://plugin.tebex.io/payments?limit=25"))
                .header("X-Tebex-Secret", cfg.tebexSecret).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("Tebex poll returned " + res.statusCode());
                return out;
            }
            JsonElement root = JsonParser.parseString(res.body());
            JsonArray payments = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().has("data") ? root.getAsJsonObject().getAsJsonArray("data") : new JsonArray();
            for (JsonElement el : payments) {
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "id", "transaction_id");
                if (id == null || db.isProcessed("tebex-" + id))
                    continue;
                String status = str(o, "status");
                if (status != null && !status.equalsIgnoreCase("Complete"))
                    continue;
                JsonObject player = o.has("player") ? o.getAsJsonObject("player") : null;
                UUID uuid = player != null && player.has("uuid") ? parseUuid(player.get("uuid").getAsString()) : null;
                String name = player != null ? str(player, "name") : null;
                double amount = num(o, "price", "amount");
                String currency = o.has("currency") && o.get("currency").isJsonObject()
                    ? str(o.getAsJsonObject("currency"), "iso_4217") : str(o, "currency");
                String packages = o.has("packages") ? joinNames(o.getAsJsonArray("packages")) : str(o, "package");
                long date = parseDate(str(o, "date"));
                if (date < cfg.ignoreBefore)
                    continue;
                out.add(new Payment("tebex-" + id, uuid, name, amount, currency == null ? "USD" : currency, packages, date, "completed"));
            }
        } catch (Exception e) {
            log.warning("Tebex poll failed: " + e.getMessage());
        }
        return out;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseDate(String iso) {
        try {
            return Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(iso)).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static String joinNames(JsonArray arr) {
        return StreamSupport.stream(arr.spliterator(), false)
            .map(e -> str(e.getAsJsonObject(), "name")).collect(Collectors.joining(", "));
    }

    static String str(JsonObject o, String... keys) {
        for (String k : keys)
            if (o.has(k) && !o.get(k).isJsonNull())
                return o.get(k).getAsString();
        return null;
    }

    static double num(JsonObject o, String... keys) {
        for (String k : keys)
            if (o.has(k) && !o.get(k).isJsonNull())
                try {
                    return o.get(k).getAsDouble();
                } catch (NumberFormatException ignored) {
                }
        return 0;
    }
}

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
    private final HttpClient http = HttpClient.newHttpClient();
    private final Logger log;

    CraftingStoreProvider(Config cfg, Database db, Logger log) {
        this.cfg = cfg;
        this.db = db;
        this.log = log;
    }

    @Override
    public List<Payment> poll() {
        List<Payment> out = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.craftingstore.net/v4/queue?secret=" + cfg.craftingstoreSecret))
                .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("CraftingStore poll returned " + res.statusCode());
                return out;
            }
            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonArray queue = root.has("queue") ? root.getAsJsonArray("queue") : new JsonArray();
            for (JsonElement el : queue) {
                JsonObject o = el.getAsJsonObject();
                String id = TebexProvider.str(o, "id", "payment_id");
                if (id == null || db.isProcessed("cs-" + id))
                    continue;
                UUID uuid = null;
                String player = TebexProvider.str(o, "player", "uuid", "username", "name");
                try {
                    uuid = player != null ? UUID.fromString(player) : null;
                } catch (Exception ignored) {
                }
                double amount = TebexProvider.num(o, "amount", "price", "total");
                String currency = TebexProvider.str(o, "currency");
                String pkg = TebexProvider.str(o, "package", "package_name");
                out.add(new Payment("cs-" + id, uuid, uuid == null ? player : null, amount,
                    currency == null ? "USD" : currency, pkg == null ? "Unknown" : pkg, System.currentTimeMillis(), "completed"));
            }
        } catch (Exception e) {
            log.warning("CraftingStore poll failed: " + e.getMessage());
        }
        return out;
    }
}

/**
 * PayNow.gg's Orders API (verified against docs.paynow.gg):
 * GET /v1/stores/{storeId}/orders, header "Authorization: APIKey <key>", cursor pagination via
 * ?after=<lastId>&asc=true. Order lines carry the product names; the parent order's customer
 * object carries the minecraft uuid/username.
 */
final class PaynowProvider implements PaymentProvider {
    private final Config cfg;
    private final Database db;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Logger log;

    PaynowProvider(Config cfg, Database db, Logger log) {
        this.cfg = cfg;
        this.db = db;
        this.log = log;
    }

    @Override
    public List<Payment> poll() {
        List<Payment> out = new ArrayList<>();
        String after = db.meta("paynow-cursor", "");
        try {
            String url = "https://api.paynow.gg/v1/stores/" + cfg.paynowStoreId + "/orders?limit=25&asc=true"
                + (after.isBlank() ? "" : "&after=" + after);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "APIKey " + cfg.paynowApiKey).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("Paynow poll returned " + res.statusCode());
                return out;
            }
            JsonElement root = JsonParser.parseString(res.body());
            JsonArray orders = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().has("data") ? root.getAsJsonObject().getAsJsonArray("data") : new JsonArray();
            String lastId = after;
            for (JsonElement el : orders) {
                JsonObject o = el.getAsJsonObject();
                String id = TebexProvider.str(o, "id");
                lastId = id == null ? lastId : id;
                if (id == null || db.isProcessed("paynow-" + id))
                    continue;
                String status = TebexProvider.str(o, "status");
                if (status != null && !status.equalsIgnoreCase("completed"))
                    continue;
                JsonObject customer = o.has("customer") ? o.getAsJsonObject("customer") : null;
                UUID uuid = null;
                String name = null;
                if (customer != null) {
                    String uuidStr = customer.has("minecraft_uuid") && !customer.get("minecraft_uuid").isJsonNull()
                        ? customer.get("minecraft_uuid").getAsString() : null;
                    if (uuidStr == null && customer.has("minecraft") && customer.get("minecraft").isJsonObject())
                        uuidStr = TebexProvider.str(customer.getAsJsonObject("minecraft"), "id");
                    try {
                        uuid = uuidStr != null ? UUID.fromString(uuidStr) : null;
                    } catch (Exception ignored) {
                    }
                    name = customer.has("minecraft") && customer.get("minecraft").isJsonObject()
                        ? TebexProvider.str(customer.getAsJsonObject("minecraft"), "name") : TebexProvider.str(customer, "name");
                }
                double amount = 0;
                String packages;
                if (o.has("lines") && o.get("lines").isJsonArray()) {
                    JsonArray lines = o.getAsJsonArray("lines");
                    List<String> names = new ArrayList<>();
                    for (JsonElement lineEl : lines) {
                        JsonObject line = lineEl.getAsJsonObject();
                        names.add(TebexProvider.str(line, "product_name"));
                        amount += TebexProvider.num(line, "total_amount");
                    }
                    packages = String.join(", ", names);
                } else {
                    packages = "Unknown";
                }
                amount = amount / 100.0; // paynow amounts are in cents
                long date = parsePaynowDate(TebexProvider.str(o, "completed_at", "created_at"));
                if (date < cfg.ignoreBefore)
                    continue;
                out.add(new Payment("paynow-" + id, uuid, name, amount, "USD", packages, date, "completed"));
            }
            if (!lastId.equals(after))
                db.setMeta("paynow-cursor", lastId);
        } catch (Exception e) {
            log.warning("Paynow poll failed: " + e.getMessage());
        }
        return out;
    }

    private static long parsePaynowDate(String iso) {
        try {
            return Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(iso)).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}

/** Manual mode: payments only ever come from /storetrack manual, there's nothing to poll. */
final class ManualProvider implements PaymentProvider {
    @Override
    public List<Payment> poll() {
        return List.of();
    }
}
