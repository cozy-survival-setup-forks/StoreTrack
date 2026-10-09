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
 * Tebex's "Payments Log" plugin API: https://plugin.tebex.io/payments, header X-Tebex-Secret.
 * Polled with the configured check-amount via ?limit, newest first - we only keep rows we have
 * not stored yet. A stored payment that Tebex now reports as refunded or charged back is passed
 * on through {@link #drainReversals()}.
 */
final class TebexProvider implements PaymentProvider {
    private final Config cfg;
    private final Database db;
    private final Logger log;
    private final List<Reversal> reversals = new ArrayList<>();

    TebexProvider(Config cfg, Database db, Logger log) {
        this.cfg = cfg;
        this.db = db;
        this.log = log;
    }

    @Override
    public synchronized List<Reversal> drainReversals() {
        List<Reversal> out = new ArrayList<>(reversals);
        reversals.clear();
        return out;
    }

    @Override
    public synchronized List<Payment> poll() {
        List<Payment> out = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://plugin.tebex.io/payments?limit=25"))
                .header("X-Tebex-Secret", cfg.tebexSecret).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("Tebex poll returned " + res.statusCode() + (res.statusCode() == 403 ? " (is tebex.secret right?)" : ""));
                return out;
            }
            JsonElement root = JsonParser.parseString(res.body());
            JsonArray payments = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().has("data") ? root.getAsJsonObject().getAsJsonArray("data") : new JsonArray();
            for (JsonElement el : payments) {
                try {
                    read(el.getAsJsonObject(), out);
                } catch (RuntimeException e) {
                    log.warning("A Tebex payment could not be read and was skipped: " + e);
                }
            }
        } catch (Exception e) {
            log.warning("Tebex poll failed: " + e.getMessage());
        }
        return out;
    }

    private void read(JsonObject o, List<Payment> out) {
        String id = Http.str(o, "id", "transaction_id");
        if (id == null)
            return;
        String key = "tebex-" + id;
        String status = Http.str(o, "status");
        String lowered = status == null ? "" : status.toLowerCase(Locale.ROOT);
        boolean reversed = lowered.contains("refund") || lowered.contains("chargeback");
        if (db.isProcessed(key)) {
            if (reversed)
                reversals.add(new Reversal(key, lowered.contains("charge") ? "chargeback" : "refunded"));
            return;
        }
        if (status != null && !lowered.equals("complete"))
            return;
        JsonObject player = Http.obj(o, "player");
        UUID uuid = player != null ? Http.uuid(Http.str(player, "uuid")) : null;
        String name = player != null ? Http.str(player, "name") : null;
        double amount = Http.num(o, "price", "amount");
        JsonObject currencyObject = Http.obj(o, "currency");
        String currency = currencyObject != null ? Http.str(currencyObject, "iso_4217") : Http.str(o, "currency");
        String packages = o.has("packages") && o.get("packages").isJsonArray() ? joinNames(o.getAsJsonArray("packages")) : Http.str(o, "package");
        long date = Http.date(Http.str(o, "date"));
        if (date < 0)
            date = System.currentTimeMillis();
        if (date < cfg.ignoreBefore)
            return;
        out.add(new Payment(key, uuid, name, amount, currency == null ? "USD" : currency, packages, date, "completed"));
    }

    private static String joinNames(JsonArray arr) {
        return StreamSupport.stream(arr.spliterator(), false)
            .filter(JsonElement::isJsonObject)
            .map(e -> Http.str(e.getAsJsonObject(), "name"))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.joining(", "));
    }
}
