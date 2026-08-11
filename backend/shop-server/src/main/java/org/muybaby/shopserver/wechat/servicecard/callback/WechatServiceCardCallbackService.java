package org.muybaby.shopserver.wechat.servicecard.callback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardDeliveryStore;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WechatServiceCardCallbackService {

    private static final String EVENT = "notify_service_msg_send_result";
    private static final Pattern PREFIXED_NOTIFY_CODE = Pattern.compile("p1\\.([0-9]{1,64})");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final WechatServiceCardDeliveryStore deliveryStore;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public WechatServiceCardCallbackService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            WechatServiceCardDeliveryStore deliveryStore,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.deliveryStore = deliveryStore;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void accept(String decryptedJson) {
        CallbackEvent event = parse(decryptedJson);
        transaction.executeWithoutResult(status -> persist(event, decryptedJson));
    }

    private CallbackEvent parse(String body) {
        if (!StringUtils.hasText(body) || body.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IllegalArgumentException("WeChat callback body is invalid");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("WeChat callback JSON must be an object");
            }
            String event = text(root, "Event", "event");
            String messageType = text(root, "MsgType", "msg_type");
            String openid = text(root, "openid", "OpenId", "FromUserName");
            String notifyCode = text(root, "notify_code", "NotifyCode");
            int notifyType = integer(root, "notify_type", "NotifyType");
            int cardStatus = integer(root, "card_status", "CardStatus");
            int failRet = integer(root, "fail_ret", "FailRet");
            if (!EVENT.equals(event) || !"event".equals(messageType) || notifyType != 2001
                    || !StringUtils.hasText(openid) || !StringUtils.hasText(notifyCode)) {
                throw new IllegalArgumentException("Unsupported WeChat callback event");
            }
            WechatServiceCardStatus.fromCode(cardStatus);
            if (failRet >= 0) {
                throw new IllegalArgumentException("WeChat result callback must contain a failure code");
            }
            return new CallbackEvent(openid.trim(), notifyCode.trim(), cardStatus, failRet);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("WeChat callback JSON is invalid", ex);
        }
    }

    private void persist(CallbackEvent event, String decryptedJson) {
        String eventDigest = sha256(decryptedJson);
        Long cardId = matchingCardId(event);
        CallbackMatch match = null;
        if (cardId != null) {
            jdbcClient.sql("select id from wechat_service_card where id = :cardId for update")
                    .param("cardId", cardId)
                    .query(Long.class)
                    .optional();
            List<Long> deliveryIds = jdbcClient.sql("""
                            select id
                            from wechat_service_card_delivery
                            where card_id = :cardId
                              and target_status = :cardStatus
                            order by sequence_no
                            limit 2
                            for update
                            """)
                    .param("cardId", cardId)
                    .param("cardStatus", event.cardStatus())
                    .query(Long.class)
                    .list();
            if (deliveryIds.size() == 1) {
                match = new CallbackMatch(cardId, deliveryIds.getFirst());
            }
        }
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        int inserted = jdbcClient.sql("""
                        insert into wechat_service_card_callback_log
                            (event_digest, card_id, delivery_id, card_status, fail_ret,
                             fail_message, matched, received_at, created_at)
                        values
                            (:eventDigest, :cardId, :deliveryId, :cardStatus, :failRet,
                             :failMessage, :matched, :receivedAt, :createdAt)
                        on duplicate key update event_digest = event_digest
                        """)
                .param("eventDigest", eventDigest)
                .param("cardId", cardId)
                .param("deliveryId", match == null ? null : match.deliveryId())
                .param("cardStatus", event.cardStatus())
                .param("failRet", event.failRet())
                .param("failMessage", callbackMessage(event.failRet()))
                .param("matched", match != null)
                .param("receivedAt", now)
                .param("createdAt", now)
                .update();
        if (inserted == 0) {
            return;
        }
        if (match != null) {
            jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set message_result_state = 'FAILED',
                            message_fail_ret = :failRet,
                            message_fail_message = :failMessage,
                            message_result_at = :resultAt,
                            updated_at = :updatedAt
                        where id = :deliveryId
                          and card_id = :cardId
                          and target_status = :cardStatus
                        """)
                    .param("failRet", event.failRet())
                    .param("failMessage", callbackMessage(event.failRet()))
                    .param("resultAt", now)
                    .param("updatedAt", now)
                    .param("deliveryId", match.deliveryId())
                    .param("cardId", match.cardId())
                    .param("cardStatus", event.cardStatus())
                    .update();
        }
        if (cardId != null && event.failRet() == -1004) {
            deliveryStore.blockUserRefused(cardId);
        }
        // Other asynchronous message-result failures deliberately do not touch card.remote_status
        // or delivery.state. A late result cannot roll back a state confirmed by set/get.
    }

    private Long matchingCardId(CallbackEvent event) {
        Set<String> digests = new LinkedHashSet<>();
        digests.add(sha256(event.notifyCode()));
        Matcher prefixed = PREFIXED_NOTIFY_CODE.matcher(event.notifyCode());
        if (prefixed.matches()) {
            digests.add(sha256(prefixed.group(1)));
        }
        List<Long> candidates = jdbcClient.sql("""
                        select card.id
                        from wechat_service_card card
                        join payment_order payment on payment.id = card.payment_order_id
                        where card.notify_code_digest in (:notifyCodeDigests)
                          and payment.payer_openid = :openid
                        order by card.id
                        limit 2
                        """)
                .param("notifyCodeDigests", digests)
                .param("openid", event.openid())
                .query(Long.class)
                .list();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static String callbackMessage(int failRet) {
        return switch (failRet) {
            case -10001 -> "WeChat message delivery system error";
            case -10002 -> "WeChat content security rejected message delivery";
            case -1003 -> "WeChat service-card template is unavailable";
            case -1004 -> "The user rejected service-card message delivery";
            case -1005 -> "WeChat message delivery frequency was exceeded";
            default -> "WeChat service-card message delivery failed";
        };
    }

    private static String text(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode value = root.path(name);
            if (value.isTextual()) {
                return value.asText();
            }
        }
        return "";
    }

    private static int integer(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode value = root.path(name);
            if (value.isIntegralNumber() && value.canConvertToInt()) {
                return value.intValue();
            }
        }
        throw new IllegalArgumentException("Required WeChat callback integer is missing");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record CallbackEvent(String openid, String notifyCode, int cardStatus, int failRet) {
    }

    private record CallbackMatch(long cardId, long deliveryId) {
    }
}
