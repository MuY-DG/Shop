package org.muybaby.shopserver.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.secret.SecretEncryptionProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmPaymentSecretCipherTest {

    private static final byte[] OLD_V2_KEY = "11111111111111111111111111111111"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_V2_KEY = "22222222222222222222222222222222"
            .getBytes(StandardCharsets.UTF_8);
    private static final PaymentSecretCipher.SecretContext CONFIG_API_KEY =
            new PaymentSecretCipher.SecretContext(
                    "payment-config",
                    "1",
                    "api-v3-key"
            );

    @Test
    void v2RoundTripReturnsEnvelopeMetadata() {
        AesGcmPaymentSecretCipher cipher = cipher(
                "current-key",
                ring("current-key", NEW_V2_KEY)
        );

        PaymentSecretCipher.EncryptedSecret encrypted = cipher.encrypt(
                CONFIG_API_KEY,
                "api-v3-secret\nwith-unicode-密钥"
        );
        PaymentSecretCipher.DecryptedSecret decrypted = cipher.decrypt(
                CONFIG_API_KEY,
                encrypted.ciphertext()
        );

        assertThat(encrypted.ciphertext())
                .startsWith("v2:current-key:")
                .doesNotContain("api-v3-secret");
        assertThat(encrypted.version()).isEqualTo(2);
        assertThat(encrypted.keyId()).isEqualTo("current-key");
        assertThat(decrypted.plaintext()).isEqualTo("api-v3-secret\nwith-unicode-密钥");
        assertThat(decrypted.version()).isEqualTo(2);
        assertThat(decrypted.keyId()).isEqualTo("current-key");
        assertThat(cipher.shouldReencrypt(decrypted.version(), decrypted.keyId())).isFalse();
    }

    @Test
    void v2CiphertextCannotMoveAcrossRowsOrFields() {
        AesGcmPaymentSecretCipher cipher = cipher(
                "current-key",
                ring("current-key", NEW_V2_KEY)
        );
        String ciphertext = cipher.encrypt(CONFIG_API_KEY, "bound-secret").ciphertext();
        PaymentSecretCipher.SecretContext anotherRow = new PaymentSecretCipher.SecretContext(
                CONFIG_API_KEY.domain(),
                "2",
                CONFIG_API_KEY.fieldName()
        );
        PaymentSecretCipher.SecretContext anotherField = new PaymentSecretCipher.SecretContext(
                CONFIG_API_KEY.domain(),
                CONFIG_API_KEY.rowIdentity(),
                "private-key-pem"
        );

        assertValidationFailure(() -> cipher.decrypt(anotherRow, ciphertext));
        assertValidationFailure(() -> cipher.decrypt(anotherField, ciphertext));
    }

    @Test
    void unknownV2KeyIdFailsClosedWithoutTryingAnotherKey() {
        AesGcmPaymentSecretCipher encryptingCipher = cipher(
                "old-key",
                ring("old-key", OLD_V2_KEY)
        );
        String ciphertext = encryptingCipher.encrypt(CONFIG_API_KEY, "old-secret").ciphertext();
        AesGcmPaymentSecretCipher cipherWithoutOldKey = cipher(
                "new-key",
                ring("new-key", OLD_V2_KEY)
        );

        assertValidationFailure(() -> cipherWithoutOldKey.decrypt(CONFIG_API_KEY, ciphertext));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-an-envelope",
            "v3:key:AAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAAAAAAAA",
            "v1:AAAA:AAAA",
            "v2:key:AAAA:AAAA",
            "v2:key:not*base64url:AAAAAAAAAAAAAAAAAAAAAA",
            "v2:bad$key:AAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAAAAAAAA"
    })
    void malformedEnvelopeFailsAsControlledValidationError(String ciphertext) {
        AesGcmPaymentSecretCipher cipher = cipher(
                "key",
                ring("key", NEW_V2_KEY)
        );

        assertValidationFailure(() -> cipher.decrypt(CONFIG_API_KEY, ciphertext));
    }

    @Test
    void keyRotationReadsOldV2AndWritesOnlyWithNewActiveKey() {
        AesGcmPaymentSecretCipher oldCipher = cipher(
                "old-key",
                ring("old-key", OLD_V2_KEY)
        );
        String oldCiphertext = oldCipher.encrypt(CONFIG_API_KEY, "rotating-secret").ciphertext();

        AesGcmPaymentSecretCipher rotatedCipher = cipher(
                "new-key",
                ring("old-key", OLD_V2_KEY, "new-key", NEW_V2_KEY)
        );
        PaymentSecretCipher.DecryptedSecret oldDecrypted = rotatedCipher.decrypt(
                CONFIG_API_KEY,
                oldCiphertext
        );
        PaymentSecretCipher.EncryptedSecret newlyEncrypted = rotatedCipher.encrypt(
                CONFIG_API_KEY,
                "new-secret"
        );

        assertThat(oldDecrypted.plaintext()).isEqualTo("rotating-secret");
        assertThat(oldDecrypted.keyId()).isEqualTo("old-key");
        assertThat(rotatedCipher.shouldReencrypt(oldDecrypted.version(), oldDecrypted.keyId())).isTrue();
        assertThat(newlyEncrypted.ciphertext()).startsWith("v2:new-key:");
        assertThat(newlyEncrypted.keyId()).isEqualTo("new-key");
        assertThat(rotatedCipher.shouldReencrypt(newlyEncrypted.version(), newlyEncrypted.keyId())).isFalse();
    }

    @Test
    void invalidV2WriteConfigurationsFailAtConstruction() {
        assertThatThrownBy(() -> cipher("", ring("key", NEW_V2_KEY)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher("missing", ring("key", NEW_V2_KEY)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(
                "bad$key",
                "bad$key=base64:" + Base64.getEncoder().encodeToString(NEW_V2_KEY)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(
                "Key-A",
                ring("Key-A", NEW_V2_KEY)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(
                "short-key",
                ring("short-key", "too-short".getBytes(StandardCharsets.UTF_8))
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(
                "duplicate",
                ring("duplicate", OLD_V2_KEY) + ";" + ring("duplicate", NEW_V2_KEY)
        )).isInstanceOf(IllegalStateException.class);
    }

    private static AesGcmPaymentSecretCipher cipher(
            String activeKeyId,
            String keyRing
    ) {
        return new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        activeKeyId,
                        keyRing,
                        false,
                        Duration.ofMinutes(1),
                        50
                )
        );
    }

    private static String ring(String keyId, byte[] key) {
        return keyId + "=base64:" + Base64.getEncoder().encodeToString(key);
    }

    private static String ring(String firstId, byte[] firstKey, String secondId, byte[] secondKey) {
        return ring(firstId, firstKey) + ";" + ring(secondId, secondKey);
    }

    private static void assertValidationFailure(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
