package dev.storetrack;

import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Only called when a provider gives us a username but no UUID. Cached, no persistent name-history table. */
final class Mojang {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, UUID> CACHE = new ConcurrentHashMap<>();

    private Mojang() {
    }

    static UUID resolve(String username) {
        if (username == null || username.isBlank())
            return null;
        String key = username.toLowerCase();
        UUID cached = CACHE.get(key);
        if (cached != null)
            return cached;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
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
