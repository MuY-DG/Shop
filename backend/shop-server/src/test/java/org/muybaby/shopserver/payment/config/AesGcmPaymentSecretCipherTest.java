package org.muybaby.shopserver.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.secret.SecretEncryptionProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmPaymentSecretCipherTest {

    private static final String LEGACY_KEY = "0123456789abcdef0123456789abcdef";
    private static final byte[] OLD_V2_KEY = "11111111111111111111111111111111"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_V2_KEY = "22222222222222222222222222222222"
            .getBytes(StandardCharsets.UTF_8);
    private static final PaymentSecretCipher.SecretContext SNAPSHOT_API_KEY =
            new PaymentSecretCipher.SecretContext(
                    "payment-config-snapshot",
                    "a".repeat(64),
                    "api-v3-key"
            );

    @Test
    void decryptsHistoricalV1EnvelopeWithLegacyKeyWhileWritingV2() throws Exception {
        AesGcmPaymentSecretCipher cipher = cipher(
                2,
                "new-key",
                ring("old-key", OLD_V2_KEY, "new-key", NEW_V2_KEY),
                LEGACY_KEY
        );
        String fixture = historicalV1Envelope("historical-api-v3-key", LEGACY_KEY);

        PaymentSecretCipher.DecryptedSecret decrypted = cipher.decrypt(SNAPSHOT_API_KEY, fixture);

        assertThat(decrypted.plaintext()).isEqualTo("historical-api-v3-key");
        assertThat(decrypted.version()).isEqualTo(1);
        assertThat(decrypted.keyId()).isEmpty();
        assertThat(cipher.shouldReencrypt(decrypted.version(), decrypted.keyId())).isTrue();
    }

    @Test
    void v2RoundTripReturnsEnvelopeMetadata() {
        AesGcmPaymentSecretCipher cipher = cipher(
                2,
                "current-key",
                ring("current-key", NEW_V2_KEY),
                LEGACY_KEY
        );

        PaymentSecretCipher.EncryptedSecret encrypted = cipher.encrypt(
                SNAPSHOT_API_KEY,
                "api-v3-secret\nwith-unicode-密钥"
        );
        PaymentSecretCipher.DecryptedSecret decrypted = cipher.decrypt(
                SNAPSHOT_API_KEY,
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
                2,
                "current-key",
                ring("current-key", NEW_V2_KEY),
                LEGACY_KEY
        );
        String ciphertext = cipher.encrypt(SNAPSHOT_API_KEY, "bound-secret").ciphertext();
        PaymentSecretCipher.SecretContext anotherRow = new PaymentSecretCipher.SecretContext(
                SNAPSHOT_API_KEY.domain(),
                "b".repeat(64),
                SNAPSHOT_API_KEY.fieldName()
        );
        PaymentSecretCipher.SecretContext anotherField = new PaymentSecretCipher.SecretContext(
                SNAPSHOT_API_KEY.domain(),
                SNAPSHOT_API_KEY.rowIdentity(),
                "private-key-pem"
        );

        assertValidationFailure(() -> cipher.decrypt(anotherRow, ciphertext));
        assertValidationFailure(() -> cipher.decrypt(anotherField, ciphertext));
    }

    @Test
    void unknownV2KeyIdFailsClosedWithoutTryingAnotherKey() {
        AesGcmPaymentSecretCipher encryptingCipher = cipher(
                2,
                "old-key",
                ring("old-key", OLD_V2_KEY),
                LEGACY_KEY
        );
        String ciphertext = encryptingCipher.encrypt(SNAPSHOT_API_KEY, "old-secret").ciphertext();
        AesGcmPaymentSecretCipher cipherWithoutOldKey = cipher(
                2,
                "new-key",
                ring("new-key", OLD_V2_KEY),
                LEGACY_KEY
        );

        assertValidationFailure(() -> cipherWithoutOldKey.decrypt(SNAPSHOT_API_KEY, ciphertext));
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
                2,
                "key",
                ring("key", NEW_V2_KEY),
                LEGACY_KEY
        );

        assertValidationFailure(() -> cipher.decrypt(SNAPSHOT_API_KEY, ciphertext));
    }

    @Test
    void keyRotationReadsOldV2AndWritesOnlyWithNewActiveKey() {
        AesGcmPaymentSecretCipher oldCipher = cipher(
                2,
                "old-key",
                ring("old-key", OLD_V2_KEY),
                LEGACY_KEY
        );
        String oldCiphertext = oldCipher.encrypt(SNAPSHOT_API_KEY, "rotating-secret").ciphertext();

        AesGcmPaymentSecretCipher rotatedCipher = cipher(
                2,
                "new-key",
                ring("old-key", OLD_V2_KEY, "new-key", NEW_V2_KEY),
                LEGACY_KEY
        );
        PaymentSecretCipher.DecryptedSecret oldDecrypted = rotatedCipher.decrypt(
                SNAPSHOT_API_KEY,
                oldCiphertext
        );
        PaymentSecretCipher.EncryptedSecret newlyEncrypted = rotatedCipher.encrypt(
                SNAPSHOT_API_KEY,
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
        assertThatThrownBy(() -> cipher(3, "key", ring("key", NEW_V2_KEY), LEGACY_KEY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(2, "", ring("key", NEW_V2_KEY), LEGACY_KEY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(2, "missing", ring("key", NEW_V2_KEY), LEGACY_KEY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(
                2,
                "bad$key",
                "bad$key=base64:" + Base64.getEncoder().encodeToString(NEW_V2_KEY),
                LEGACY_KEY
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(
                2,
                "Key-A",
                ring("Key-A", NEW_V2_KEY),
                LEGACY_KEY
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(
                2,
                "short-key",
                ring("short-key", "too-short".getBytes(StandardCharsets.UTF_8)),
                LEGACY_KEY
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(
                2,
                "duplicate",
                ring("duplicate", OLD_V2_KEY) + ";" + ring("duplicate", NEW_V2_KEY),
                LEGACY_KEY
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void v1ConfigurationWithoutLegacyKeyFailsWhenEncryptionIsFirstUsed() throws Exception {
        AesGcmPaymentSecretCipher cipher = cipher(1, "", "", "");
        String historicalCiphertext = historicalV1Envelope("historical-secret", LEGACY_KEY);

        assertValidationFailure(() -> cipher.encrypt(SNAPSHOT_API_KEY, "new-secret"));
        assertValidationFailure(() -> cipher.decrypt(SNAPSHOT_API_KEY, historicalCiphertext));
    }

    private static AesGcmPaymentSecretCipher cipher(
            int writeVersion,
            String activeKeyId,
            String keyRing,
        String legacyKey
    ) {
        return new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        writeVersion,
                        activeKeyId,
                        keyRing,
                        legacyKey,
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

    private static String historicalV1Envelope(String plaintext, String legacyKey) throws Exception {
        byte[] nonce = new byte[12];
        for (int index = 0; index < nonce.length; index++) {
            nonce[index] = (byte) index;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(legacyKey.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce)
        );
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return "v1:" + Base64.getEncoder().encodeToString(nonce)
                + ":" + Base64.getEncoder().encodeToString(encrypted);
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
