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

/** One implementation per provider. Never touches the main thread - PaymentService already polls async. */
interface PaymentProvider {

    List<Payment> poll();

    /** Payments that were stored as completed and that the provider now says were refunded or charged back. */
    default List<Reversal> drainReversals() {
        return List.of();
    }

    /** A plain class and not a record: see the note about Shadow in build.gradle.kts. */
    final class Reversal {
        final String id;
        final String status; // refunded | chargeback

        Reversal(String id, String status) {
            this.id = id;
            this.status = status;
        }
    }
}
