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
 * PayNow.gg's Orders API (verified against docs.paynow.gg):
 * GET /v1/stores/{storeId}/orders, header "Authorization: APIKey <key>", cursor pagination via
 * ?after=<lastId>&asc=true. Order lines carry the product names; the parent order's customer
 * object carries the minecraft uuid/username.
 *
 * The cursor only moves past orders that are finished. An order that is still pending when it is
 * read is read again next time, so it is not lost when it completes a minute later.
 */
final class PaynowProvider implements PaymentProvider {
    private static final Set<String> FINISHED = Set.of("completed", "canceled", "cancelled", "failed", "expired",
        "refunded", "chargeback", "voided", "declined");
    /** An order that is still not finished after this long is not going to be, and must not block the ones after it. */
    private static final long STALE_MILLIS = 48L * 60 * 60 * 1000;

    private final Config cfg;
    private final Database db;
    private final Logger log;

    PaynowProvider(Config cfg, Database db, Logger log) {
        this.cfg = cfg;
        this.db = db;
        this.log = log;
    }

    @Override
    public synchronized List<Payment> poll() {
        List<Payment> out = new ArrayList<>();
        String after = db.meta("paynow-cursor", "");
        try {
            String url = "https://api.paynow.gg/v1/stores/" + cfg.paynowStoreId + "/orders?limit=25&asc=true"
                + (after.isBlank() ? "" : "&after=" + after);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "APIKey " + cfg.paynowApiKey).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("Paynow poll returned " + res.statusCode() + (res.statusCode() == 401 || res.statusCode() == 403 ? " (are paynow.api-key and paynow.store-id right?)" : ""));
                return out;
            }
            JsonElement root = JsonParser.parseString(res.body());
            JsonArray orders = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().has("data") ? root.getAsJsonObject().getAsJsonArray("data") : new JsonArray();
            String lastId = after;
            for (JsonElement el : orders) {
                JsonObject o = el.getAsJsonObject();
                String id = Http.str(o, "id");
                if (id == null)
                    continue;
                String status = Http.str(o, "status");
                String lowered = status == null ? "completed" : status.toLowerCase(Locale.ROOT);
                long created = Http.date(Http.str(o, "created_at"));
                boolean stale = created >= 0 && System.currentTimeMillis() - created > STALE_MILLIS;
                if (!FINISHED.contains(lowered) && !stale)
                    break; // still being paid: stop here, and look at it again next time
                lastId = id;
                if (!lowered.equals("completed") || db.isProcessed("paynow-" + id))
                    continue;
                try {
                    Payment payment = read(o, id);
                    if (payment != null)
                        out.add(payment);
                } catch (RuntimeException e) {
                    log.warning("A Paynow order could not be read and was skipped: " + e);
                }
            }
            if (!lastId.equals(after))
                db.setMeta("paynow-cursor", lastId);
        } catch (Exception e) {
            log.warning("Paynow poll failed: " + e.getMessage());
        }
        return out;
    }

    private Payment read(JsonObject o, String id) {
        JsonObject customer = Http.obj(o, "customer");
        UUID uuid = null;
        String name = null;
        if (customer != null) {
            String uuidStr = Http.str(customer, "minecraft_uuid");
            JsonObject minecraft = Http.obj(customer, "minecraft");
            if (uuidStr == null && minecraft != null)
                uuidStr = Http.str(minecraft, "id");
            uuid = Http.uuid(uuidStr);
            name = minecraft != null ? Http.str(minecraft, "name") : Http.str(customer, "name");
        }
        double amount = 0;
        String packages = "Unknown";
        if (o.has("lines") && o.get("lines").isJsonArray()) {
            List<String> names = new ArrayList<>();
            for (JsonElement lineEl : o.getAsJsonArray("lines")) {
                if (!lineEl.isJsonObject())
                    continue;
                JsonObject line = lineEl.getAsJsonObject();
                String product = Http.str(line, "product_name");
                if (product != null)
                    names.add(product);
                amount += Http.num(line, "total_amount");
            }
            if (!names.isEmpty())
                packages = String.join(", ", names);
        }
        amount = amount / 100.0; // paynow amounts are in cents
        long date = Http.date(Http.str(o, "completed_at", "created_at"));
        if (date < 0)
            date = System.currentTimeMillis();
        if (date < cfg.ignoreBefore)
            return null;
        return new Payment("paynow-" + id, uuid, name, amount, "USD", packages, date, "completed");
    }
}
