package org.muybaby.shopserver.wechat.servicecard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

@Service
public class WechatServiceCardOutboxService {

    private static final Logger log = LoggerFactory.getLogger(WechatServiceCardOutboxService.class);
    private static final List<String> ACTIVE_AFTER_SALE_STATUSES = List.of(
            "REQUESTED", "APPROVED", "WAITING_RETURN", "RETURNING",
            "WAITING_INSPECTION", "REFUNDING", "REFUND_FAILED"
    );

    private final JdbcClient jdbcClient;
    private final WechatServiceCardProperties properties;
    private final WechatServiceCardPayloadFactory payloadFactory;

    public WechatServiceCardOutboxService(
            JdbcClient jdbcClient,
            WechatServiceCardProperties properties,
            WechatServiceCardPayloadFactory payloadFactory
    ) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
        this.payloadFactory = payloadFactory;
    }

    public void onOrderFact(long orderId, LocalDateTime eventTime) {
        if (!properties.enabled() || !properties.imageConfigurationReady()) {
            return;
        }
        OrderFact order = lockOrder(orderId);
        if (order == null) {
            return;
        }
        WechatServiceCardPayloadFactory.PaymentSnapshot payment = payloadFactory.paidPayment(orderId);
        if (payment == null) {
            return;
        }
        Card card = lockCard(orderId);
        if (card == null) {
            card = createCard(orderId, payment, eventTime);
            enqueue(card, orderId, payment, WechatServiceCardStatus.WAITING_SHIPMENT, true, eventTime);
            card = card.withLast(WechatServiceCardStatus.WAITING_SHIPMENT);
        }
        if (card.terminal() || card.sendBlocked()) {
            return;
        }

        int activeAfterSales = activeAfterSales(orderId);
        WechatServiceCardStatus last = card.lastStatus();
        WechatServiceCardStatus base = baseStatus(order);

        if (fullyRefunded(order)) {
            if (last != WechatServiceCardStatus.AFTER_SALE) {
                card = enterAfterSale(card, orderId, payment, base, eventTime);
                last = card.lastStatus();
            }
            if (last == WechatServiceCardStatus.AFTER_SALE) {
                enqueue(card, orderId, payment, WechatServiceCardStatus.AFTER_SALE_ENDED, false, eventTime);
            }
            return;
        }

        if ("CLOSED".equals(order.status())) {
            if (last == WechatServiceCardStatus.WAITING_SHIPMENT
                    || last == WechatServiceCardStatus.AFTER_SALE) {
                enqueue(card, orderId, payment, WechatServiceCardStatus.CANCELLED, false, eventTime);
            }
            return;
        }

        if (activeAfterSales > 0) {
            if (last != WechatServiceCardStatus.AFTER_SALE) {
                enterAfterSale(card, orderId, payment, base, eventTime);
            }
            return;
        }

        if (last == WechatServiceCardStatus.AFTER_SALE) {
            WechatServiceCardStatus restore = card.restoreStatus() == null
                    ? base : card.restoreStatus();
            if (restore != null) {
                enqueue(card, orderId, payment, restore, false, eventTime);
                clearRestore(card.id(), eventTime);
            }
            return;
        }

        if (base == WechatServiceCardStatus.SIGNED
                && last == WechatServiceCardStatus.WAITING_SHIPMENT) {
            enqueue(card, orderId, payment, WechatServiceCardStatus.SHIPPED, false, eventTime);
            card = card.withLast(WechatServiceCardStatus.SHIPPED);
            last = card.lastStatus();
        }
        if (base != null && base != last && base.canFollow(last)) {
            enqueue(card, orderId, payment, base, false, eventTime);
        }
    }

    private Card enterAfterSale(
            Card card,
            long orderId,
            WechatServiceCardPayloadFactory.PaymentSnapshot payment,
            WechatServiceCardStatus base,
            LocalDateTime eventTime
    ) {
        WechatServiceCardStatus last = card.lastStatus();
        WechatServiceCardStatus restore = restorable(base) ? base : last;
        if (!restorable(restore)) {
            restore = WechatServiceCardStatus.WAITING_SHIPMENT;
        }
        setRestore(card.id(), restore, eventTime);
        enqueue(card, orderId, payment, WechatServiceCardStatus.AFTER_SALE, false, eventTime);
        return new Card(card.id(), WechatServiceCardStatus.AFTER_SALE, restore, false, false);
    }

    private void enqueue(
            Card card,
            long orderId,
            WechatServiceCardPayloadFactory.PaymentSnapshot payment,
            WechatServiceCardStatus target,
            boolean activation,
            LocalDateTime eventTime
    ) {
        WechatServiceCardStatus previous = card.lastStatus();
        if (previous == target && target != WechatServiceCardStatus.PARTIALLY_SHIPPED) {
            return;
        }
        if (previous == null) {
            if (!activation || !target.activationAllowed()) {
                throw new IllegalStateException("First WeChat 2001 state must activate the card");
            }
        } else if (!target.canFollow(previous)) {
            throw new IllegalStateException(
                    "Illegal WeChat 2001 transition " + previous.code() + " -> " + target.code()
            );
        }
        WechatServiceCardPayloadFactory.PayloadSnapshot payload =
                payloadFactory.build(orderId, target, activation, payment);
        Integer nextSequence = jdbcClient.sql("""
                        select coalesce(max(sequence_no), 0) + 1
                        from wechat_service_card_delivery
                        where card_id = :cardId
                        """)
                .param("cardId", card.id())
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (card_id, sequence_no, target_status, content_json, check_json,
                             state, next_action_at, created_at, updated_at)
                        values
                            (:cardId, :sequenceNo, :targetStatus, :contentJson, :checkJson,
                             'PENDING', :nextActionAt, :createdAt, :updatedAt)
                        """)
                .param("cardId", card.id())
                .param("sequenceNo", nextSequence)
                .param("targetStatus", target.code())
                .param("contentJson", payload.contentJson())
                .param("checkJson", payload.checkJson())
                .param("nextActionAt", eventTime)
                .param("createdAt", eventTime)
                .param("updatedAt", eventTime)
                .update();
        jdbcClient.sql("""
                        update wechat_service_card
                        set last_enqueued_status = :targetStatus,
                            terminal = :terminal,
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :cardId
                        """)
                .param("targetStatus", target.code())
                .param("terminal", target.terminal())
                .param("updatedAt", eventTime)
                .param("cardId", card.id())
                .update();
    }

    private Card createCard(
            long orderId,
            WechatServiceCardPayloadFactory.PaymentSnapshot payment,
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        insert into wechat_service_card
                            (order_id, payment_order_id, notify_type, notify_code_digest,
                             account_template_record_id, created_at, updated_at)
                        values
                            (:orderId, :paymentOrderId, 2001, :notifyCodeDigest,
                             :templateRecordId, :createdAt, :updatedAt)
                        """)
                .param("orderId", orderId)
                .param("paymentOrderId", payment.paymentOrderId())
                .param("notifyCodeDigest", sha256(payment.transactionId()))
                .param("templateRecordId", StringUtils.hasText(properties.accountTemplateRecordId())
                        ? properties.accountTemplateRecordId().trim() : "")
                .param("createdAt", now)
                .param("updatedAt", now)
                .update(keyHolder, "id");
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("WeChat 2001 card id was not generated");
        }
        long id = generatedId.longValue();
        return new Card(id, null, null, false, false);
    }

    private Card lockCard(long orderId) {
        return jdbcClient.sql("""
                        select id, last_enqueued_status, restore_status, terminal, send_blocked
                        from wechat_service_card
                        where order_id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Card(
                        rs.getLong("id"),
                        status(rs, "last_enqueued_status"),
                        status(rs, "restore_status"),
                        rs.getBoolean("terminal"),
                        rs.getBoolean("send_blocked")
                ))
                .optional()
                .orElse(null);
    }

    private OrderFact lockOrder(long orderId) {
        return jdbcClient.sql("""
                        select id, status, paid_amount_cent, refunded_amount_cent,
                               shipped_at, completed_at
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderFact(
                        rs.getLong("id"), rs.getString("status"),
                        rs.getLong("paid_amount_cent"), rs.getLong("refunded_amount_cent"),
                        rs.getObject("shipped_at", LocalDateTime.class),
                        rs.getObject("completed_at", LocalDateTime.class)
                ))
                .optional()
                .orElse(null);
    }

    private int activeAfterSales(long orderId) {
        return jdbcClient.sql("""
                        select count(*)
                        from after_sale_request
                        where order_id = :orderId
                          and status in (:statuses)
                        """)
                .param("orderId", orderId)
                .param("statuses", ACTIVE_AFTER_SALE_STATUSES)
                .query(Integer.class)
                .single();
    }

    private void setRestore(long cardId, WechatServiceCardStatus restore, LocalDateTime now) {
        jdbcClient.sql("""
                        update wechat_service_card
                        set restore_status = :restoreStatus, updated_at = :updatedAt
                        where id = :cardId
                        """)
                .param("restoreStatus", restore.code())
                .param("updatedAt", now)
                .param("cardId", cardId)
                .update();
    }

    private void clearRestore(long cardId, LocalDateTime now) {
        jdbcClient.sql("""
                        update wechat_service_card
                        set restore_status = null, updated_at = :updatedAt
                        where id = :cardId
                        """)
                .param("updatedAt", now)
                .param("cardId", cardId)
                .update();
    }

    private static WechatServiceCardStatus baseStatus(OrderFact order) {
        if (order == null) {
            return null;
        }
        if (order.completedAt() != null || "COMPLETED".equals(order.status())) {
            return WechatServiceCardStatus.SIGNED;
        }
        if (order.shippedAt() != null || "SHIPPED".equals(order.status())) {
            return WechatServiceCardStatus.SHIPPED;
        }
        if (order.paidAmountCent() > 0 || List.of("PAID", "REFUNDING", "REFUNDED").contains(order.status())) {
            return WechatServiceCardStatus.WAITING_SHIPMENT;
        }
        return null;
    }

    private static boolean fullyRefunded(OrderFact order) {
        return "REFUNDED".equals(order.status())
                || (order.paidAmountCent() > 0
                && order.refundedAmountCent() >= order.paidAmountCent());
    }

    private static boolean restorable(WechatServiceCardStatus status) {
        return status == WechatServiceCardStatus.WAITING_SHIPMENT
                || status == WechatServiceCardStatus.SHIPPED
                || status == WechatServiceCardStatus.SIGNED;
    }

    private static WechatServiceCardStatus status(ResultSet rs, String column) throws SQLException {
        Integer code = rs.getObject(column, Integer.class);
        return code == null ? null : WechatServiceCardStatus.fromCode(code);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record OrderFact(
            long id,
            String status,
            long paidAmountCent,
            long refundedAmountCent,
            LocalDateTime shippedAt,
            LocalDateTime completedAt
    ) {
    }

    private record Card(
            long id,
            WechatServiceCardStatus lastStatus,
            WechatServiceCardStatus restoreStatus,
            boolean terminal,
            boolean sendBlocked
    ) {
        Card withLast(WechatServiceCardStatus status) {
            return new Card(
                    id, status, restoreStatus,
                    status != null && status.terminal(), sendBlocked
            );
        }
    }
}
