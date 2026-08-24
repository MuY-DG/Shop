package org.muybaby.shopserver.wechat.servicecard.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentials;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardTestConfigs;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfigResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WechatServiceCardCallbackControllerTest {

    private static final String APP_ID = "wx-service-card-test";
    private static final String TOKEN = "CallbackToken2026";
    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    private byte[] aesKey;
    private String encodingAesKey;
    private WechatServiceCardCallbackService callbackService;
    private WechatServiceCardCallbackController controller;

    @BeforeEach
    void setUp() {
        encodingAesKey = "A".repeat(43);
        aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        WechatServiceCardProperties properties = properties();
        WechatServiceCardConfigResolver configResolver =
                () -> WechatServiceCardTestConfigs.fromProperties(properties);
        WechatServiceCardCallbackCrypto crypto = new WechatServiceCardCallbackCrypto(
                configResolver, () -> credentials()
        );
        callbackService = mock(WechatServiceCardCallbackService.class);
        controller = new WechatServiceCardCallbackController(
                properties, configResolver, crypto, callbackService, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void safeJsonCallbackVerifiesSignatureDecryptsAndAcknowledges() throws Exception {
        String timestamp = Long.toString(NOW.getEpochSecond());
        String nonce = "nonce-1";
        String decrypted = failureEvent("transaction-1", "openid-1", 2, -1004);
        String encrypted = encrypt(decrypted, APP_ID);
        String signature = signature(TOKEN, timestamp, nonce, encrypted);

        ResponseEntity<String> response = controller.receive(
                signature, timestamp, nonce, "aes", jsonEnvelope(encrypted)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("success");
        verify(callbackService).accept(decrypted);
    }

    @Test
    void wrongSignatureStaleTimestampAndPlaintextModeAreRejectedBeforeDispatch() throws Exception {
        String timestamp = Long.toString(NOW.getEpochSecond());
        String stale = Long.toString(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());
        String nonce = "nonce-2";
        String encrypted = encrypt(
                failureEvent("transaction-2", "openid-2", 4, -10002), APP_ID
        );

        assertThat(controller.receive(
                "bad-signature", timestamp, nonce, "aes", jsonEnvelope(encrypted)
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.receive(
                signature(TOKEN, stale, nonce, encrypted), stale, nonce,
                "aes", jsonEnvelope(encrypted)
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.receive(
                signature(TOKEN, timestamp, nonce, encrypted), timestamp, nonce,
                "raw", jsonEnvelope(encrypted)
        ).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        verify(callbackService, never()).accept(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void ciphertextForDifferentAppIdIsRejectedAndNeverDispatched() throws Exception {
        String timestamp = Long.toString(NOW.getEpochSecond());
        String nonce = "nonce-3";
        String encrypted = encrypt(
                failureEvent("transaction-3", "openid-3", 6, -10001),
                "wx-wrong-app-id"
        );

        ResponseEntity<String> response = controller.receive(
                signature(TOKEN, timestamp, nonce, encrypted), timestamp, nonce,
                "aes", jsonEnvelope(encrypted)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(callbackService, never()).accept(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void handshakeUsesPlainSignatureAndReturnsExactEchoString() {
        String timestamp = Long.toString(NOW.getEpochSecond());
        String nonce = "nonce-4";
        String echo = "encrypted-console-echo";

        ResponseEntity<String> accepted = controller.verify(
                signature(TOKEN, timestamp, nonce), timestamp, nonce, echo
        );
        ResponseEntity<String> rejected = controller.verify(
                "bad-signature", timestamp, nonce, echo
        );

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody()).isEqualTo(echo);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cryptoRejectsInvalidPkcs7Padding() throws Exception {
        WechatServiceCardCallbackCrypto crypto = new WechatServiceCardCallbackCrypto(
                () -> WechatServiceCardTestConfigs.fromProperties(properties()),
                () -> credentials()
        );
        byte[] invalidPlaintext = new byte[32];
        invalidPlaintext[31] = 0;
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE);
        String encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(invalidPlaintext));

        assertThatThrownBy(() -> crypto.decrypt(encrypted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("padding");
    }

    private WechatServiceCardProperties properties() {
        return new WechatServiceCardProperties(
                true, true, "template-record", Duration.ofSeconds(15), 50,
                Duration.ofMinutes(2), 8, Duration.ofMinutes(1), Duration.ofMinutes(30),
                Duration.ofMinutes(1), Duration.ofHours(6), 2,
                Duration.ofSeconds(3), Duration.ofSeconds(15),
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(64),
                "https://admin.junxiangshiping.cn/wechat/service-card-placeholder.png",
                false, List.of("admin.junxiangshiping.cn"),
                new WechatServiceCardProperties.Callback(
                        true, TOKEN, encodingAesKey, Duration.ofMinutes(5)
                )
        );
    }

    private WechatPlatformCredentials credentials() {
        return new WechatPlatformCredentials(
                APP_ID, "secret", WechatPlatformCredentials.Source.DATABASE);
    }

    private String encrypt(String message, String appId) throws Exception {
        byte[] random = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] appIdBytes = appId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(20 + messageBytes.length + appIdBytes.length);
        buffer.put(random);
        buffer.putInt(messageBytes.length);
        buffer.put(messageBytes);
        buffer.put(appIdBytes);
        byte[] plaintext = pkcs7(buffer.array());
        return Base64.getEncoder().encodeToString(
                cipher(Cipher.ENCRYPT_MODE).doFinal(plaintext)
        );
    }

    private Cipher cipher(int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(mode, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(aesKey, 0, 16));
        return cipher;
    }

    private byte[] pkcs7(byte[] plaintext) {
        int pad = 32 - Math.floorMod(plaintext.length, 32);
        byte[] padded = java.util.Arrays.copyOf(plaintext, plaintext.length + pad);
        java.util.Arrays.fill(padded, plaintext.length, padded.length, (byte) pad);
        return padded;
    }

    private String signature(String... values) {
        try {
            List<String> sorted = new ArrayList<>(List.of(values));
            sorted.sort(String::compareTo);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", sorted).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String jsonEnvelope(String encrypted) throws Exception {
        return new ObjectMapper().writeValueAsString(java.util.Map.of("Encrypt", encrypted));
    }

    private String failureEvent(
            String notifyCode,
            String openid,
            int status,
            int failureCode
    ) {
        return """
                {"MsgType":"event","Event":"notify_service_msg_send_result",
                 "openid":"%s","notify_type":2001,"notify_code":"%s",
                 "card_status":%d,"fail_ret":%d}
                """.formatted(openid, notifyCode, status, failureCode).strip();
    }
}
