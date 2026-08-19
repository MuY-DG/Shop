package org.muybaby.shopserver.payment.config;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSecretRecordRedactionTest {

    private static final String SENSITIVE = "sensitive-identifier-path-or-secret";

    @Test
    void paymentRecordsNeverRenderCredentialsIdentifiersPathsOrUrls() {
        AdminPaymentConfigRequest request = new AdminPaymentConfigRequest(
                "config", SENSITIVE, SENSITIVE, SENSITIVE, SENSITIVE, SENSITIVE,
                "PUBLIC_KEY", SENSITIVE, SENSITIVE, SENSITIVE, SENSITIVE);
        ResolvedPaymentConfig resolved = new ResolvedPaymentConfig(
                PaymentConfigSource.DB, 1L, "config", true, SENSITIVE, SENSITIVE, SENSITIVE,
                SENSITIVE, SENSITIVE, SENSITIVE, SENSITIVE, PaymentVerifyMode.PUBLIC_KEY,
                SENSITIVE, SENSITIVE, 2L, 3L, 4L);
        PaymentSecretCipher.EncryptedSecret encrypted =
                new PaymentSecretCipher.EncryptedSecret(SENSITIVE, 2, SENSITIVE);
        PaymentSecretCipher.DecryptedSecret decrypted =
                new PaymentSecretCipher.DecryptedSecret(SENSITIVE, 2, SENSITIVE);

        assertThat(request.toString()).doesNotContain(SENSITIVE).contains("apiV3KeyConfigured=true");
        assertThat(resolved.toString()).doesNotContain(SENSITIVE).contains("wechatPublicKeyPemConfigured=true");
        assertThat(encrypted.toString()).doesNotContain(SENSITIVE).contains("ciphertext=<redacted>");
        assertThat(decrypted.toString()).doesNotContain(SENSITIVE).contains("plaintext=<redacted>");
    }
}
