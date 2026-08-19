package org.muybaby.shopserver.payment.config;

public enum PaymentConfigSource {
    DB,
    /** Read-only encrypted snapshot used only to replay historical payments. */
    HISTORICAL_SNAPSHOT
}
