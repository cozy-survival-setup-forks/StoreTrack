package dev.storetrack;

import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Only called when a provider gives us a username but no UUID. Cached, no persistent name-history table. */
final class Mojang {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final long MISS_MILLIS = 10 * 60 * 1000L;
    private static final Map<String, UUID> CACHE = new ConcurrentHashMap<>();
    /** Names Mojang did not know, so a wrong name is not asked about again at every poll. */
    private static final Map<String, Long> MISSES = new ConcurrentHashMap<>();

    private Mojang() {
    }

    static UUID resolve(String username) {
        // Also keeps anything that is not a name out of the address
        if (username == null || !JAVA_NAME.matcher(username).matches())
            return null;
        String key = username.toLowerCase();
        UUID cached = CACHE.get(key);
        if (cached != null)
            return cached;
        Long missed = MISSES.get(key);
        if (missed != null && System.currentTimeMillis() - missed < MISS_MILLIS)
            return null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404 || res.statusCode() == 204) {
                MISSES.put(key, System.currentTimeMillis());
                return null;
            }
            if (res.statusCode() != 200)
                return null;
            String raw = JsonParser.parseString(res.body()).getAsJsonObject().get("id").getAsString();
            UUID uuid = UUID.fromString(raw.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            CACHE.put(key, uuid);
            return uuid;
        } catch (Exception e) {
            return null;
        }
    }
}
