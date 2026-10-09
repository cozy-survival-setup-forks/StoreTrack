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

/** What the providers have in common: a client with timeouts, and the readers for the JSON they send. */
final class Http {

    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Http() {
    }

    static String str(JsonObject o, String... keys) {
        for (String k : keys)
            if (o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive())
                return o.get(k).getAsString();
        return null;
    }

    static double num(JsonObject o, String... keys) {
        for (String k : keys)
            if (o.has(k) && !o.get(k).isJsonNull())
                try {
                    double value = o.get(k).getAsDouble();
                    if (Double.isFinite(value))
                        return value;
                } catch (RuntimeException ignored) {
                    // not a number, try the next key
                }
        return 0;
    }

    static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    static UUID uuid(String raw) {
        if (raw == null)
            return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            try {
                return UUID.fromString(raw.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            } catch (IllegalArgumentException again) {
                return null;
            }
        }
    }

    /** An ISO date as milliseconds, or -1 when it is missing or not one. */
    static long date(String iso) {
        if (iso == null)
            return -1;
        try {
            return Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(iso)).toEpochMilli();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
