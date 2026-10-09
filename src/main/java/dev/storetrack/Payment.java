package dev.storetrack;

import java.util.UUID;
import java.util.regex.Pattern;

/** One processed payment/order, however the provider originally shaped it. */
final class Payment {

    /** What a Minecraft name looks like: Java names, and Bedrock names with their prefix. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,24}");

    String id;
    UUID uuid;
    String name;
    double amount;
    String currency;
    String packages;
    long date;
    String status; // completed | refunded | chargeback | removed
    String removeReason;
    /** Paid before StoreTrack was installed: counted in the rankings, but nothing is announced, run or sent for it. */
    boolean silent;

    Payment(String id, UUID uuid, String name, double amount, String currency, String packages, long date, String status) {
        this.id = id;
        this.uuid = uuid;
        this.name = name == null ? null : (name.length() > 64 ? name.substring(0, 64) : name);
        this.amount = amount;
        this.currency = currency == null ? "USD" : (currency.length() > 8 ? currency.substring(0, 8) : currency);
        this.packages = packages == null ? null : (packages.length() > 500 ? packages.substring(0, 500) : packages);
        this.date = date;
        this.status = status;
    }

    boolean countsTowardTotals() {
        return "completed".equals(status);
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * True when the name is safe to put in a console command. The name of a buyer comes from the store and, for some
     * providers, is whatever they typed, so it must not carry spaces or other arguments into a command.
     */
    boolean hasValidName() {
        return validName(name);
    }

    /** The name to show in chat and in messages. */
    String shownName() {
        return hasValidName() ? name : "Someone";
    }

    /** The package names with anything that is not text taken out, short enough for a line of chat. */
    String shownPackages() {
        return clean(packages, 80, "Unknown");
    }

    /** Takes out control characters and the section sign, and cuts the text to {@code max} characters. */
    static String clean(String text, int max, String fallback) {
        if (text == null) return fallback;
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c < 32 || c == 127 || c == 167) continue;
            out.append(c);
        }
        String result = out.toString().strip();
        if (result.isEmpty()) return fallback;
        return result.length() > max ? result.substring(0, max) : result;
    }
}
