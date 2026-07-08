package org.muybaby.shopserver.payment.config;

public interface PaymentSecretCipher {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
