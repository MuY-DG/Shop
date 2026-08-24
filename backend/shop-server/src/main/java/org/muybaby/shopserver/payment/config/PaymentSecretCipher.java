package org.muybaby.shopserver.payment.config;

public interface PaymentSecretCipher {

    EncryptionMetadata activeEncryptionMetadata();

    EncryptedSecret encrypt(SecretContext context, String plaintext);

    DecryptedSecret decrypt(SecretContext context, String ciphertext);

    boolean shouldReencrypt(int cipherVersion, String keyId);

    default String encrypt(String plaintext) {
        return encrypt(SecretContext.generic(), plaintext).ciphertext();
    }

    default String decrypt(String ciphertext) {
        return decrypt(SecretContext.generic(), ciphertext).plaintext();
    }

    record SecretContext(String domain, String rowIdentity, String fieldName) {

        public static SecretContext generic() {
            return new SecretContext("generic", "0", "value");
        }
    }

    record EncryptedSecret(String ciphertext, int version, String keyId) {
        @Override
        public String toString() {
            return "EncryptedSecret[ciphertext=<redacted>, version=" + version
                    + ", keyIdConfigured=" + (keyId != null && !keyId.isBlank()) + "]";
        }
    }

    record EncryptionMetadata(int version, String keyId) {
    }

    record DecryptedSecret(String plaintext, int version, String keyId) {
        @Override
        public String toString() {
            return "DecryptedSecret[plaintext=<redacted>, version=" + version
                    + ", keyIdConfigured=" + (keyId != null && !keyId.isBlank()) + "]";
        }
    }
}
