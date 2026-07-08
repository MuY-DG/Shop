package org.muybaby.shopserver.payment.config;

public enum PaymentVerifyMode {
    PUBLIC_KEY,
    // Kept only so legacy DB values can be rejected with a validation error.
    CERTIFICATE
}
