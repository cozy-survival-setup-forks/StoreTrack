package dev.storetrack;

import java.util.UUID;

/** One processed payment/order, however the provider originally shaped it. */
final class Payment {
    String id;
    UUID uuid;
    String name;
    double amount;
    String currency;
    String packages;
    long date;
    String status; // completed | refunded | chargeback | removed
    String removeReason;

    Payment(String id, UUID uuid, String name, double amount, String currency, String packages, long date, String status) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.amount = amount;
        this.currency = currency;
        this.packages = packages;
        this.date = date;
        this.status = status;
    }

    boolean countsTowardTotals() {
        return "completed".equals(status);
    }
}
