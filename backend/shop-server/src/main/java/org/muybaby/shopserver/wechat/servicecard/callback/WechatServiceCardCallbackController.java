package org.muybaby.shopserver.wechat.servicecard.callback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfigResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/wechat/mini/message")
public class WechatServiceCardCallbackController {

    private final WechatServiceCardProperties properties;
    private final WechatServiceCardConfigResolver configResolver;
    private final WechatServiceCardCallbackCrypto crypto;
    private final WechatServiceCardCallbackService callbackService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WechatServiceCardCallbackController(
            WechatServiceCardProperties properties,
            WechatServiceCardConfigResolver configResolver,
            WechatServiceCardCallbackCrypto crypto,
            WechatServiceCardCallbackService callbackService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.configResolver = configResolver;
        this.crypto = crypto;
        this.callbackService = callbackService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam String signature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam String echostr
    ) {
        if (!readyAndFresh(timestamp)
                || !crypto.verifyHandshake(signature, timestamp, nonce)) {
            return ResponseEntity.status(403).body("");
        }
        return ResponseEntity.ok(echostr);
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<String> receive(
            @RequestParam(name = "msg_signature") String messageSignature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam(name = "encrypt_type") String encryptType,
            @RequestBody String body
    ) {
        if (!"aes".equalsIgnoreCase(encryptType) || !readyAndFresh(timestamp)) {
            return ResponseEntity.status(403).body("");
        }
        String encrypted = encrypted(body);
        if (encrypted == null
                || !crypto.verifyEncrypted(messageSignature, timestamp, nonce, encrypted)) {
            return ResponseEntity.status(403).body("");
        }
        try {
            callbackService.accept(crypto.decrypt(encrypted));
            return ResponseEntity.ok("success");
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body("");
        }
    }

    private boolean readyAndFresh(String timestamp) {
        if (configResolver.resolveFailClosed()
                .filter(config -> config.callbackSecureReady())
                .isEmpty()) {
            return false;
        }
        try {
            Instant presented = Instant.ofEpochSecond(Long.parseLong(timestamp));
            Duration skew = Duration.between(presented, Instant.now(clock)).abs();
            return skew.compareTo(properties.callback().maxTimestampSkew()) <= 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String encrypted(String body) {
        if (body == null || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128 * 1024) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode value = root == null ? null : root.get("Encrypt");
            if (value == null && root != null) {
                value = root.get("encrypt");
            }
            return value != null && value.isTextual() ? value.asText() : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
