package org.muybaby.shopserver.wechat.servicecard;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.wechat.WechatMiniProgramProperties;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryQuery;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryResponse;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardStatusResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class WechatServiceCardAdminReadService {

    private final JdbcClient jdbcClient;
    private final WechatServiceCardProperties properties;
    private final WechatMiniProgramProperties miniProgramProperties;

    public WechatServiceCardAdminReadService(
            JdbcClient jdbcClient,
            WechatServiceCardProperties properties,
            WechatMiniProgramProperties miniProgramProperties
    ) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
        this.miniProgramProperties = miniProgramProperties;
    }

    public AdminWechatServiceCardStatusResponse status() {
        boolean imageReady = properties.imageConfigurationReady();
        boolean templateConfigured = properties.templateConfigurationReady();
        boolean credentialsReady = StringUtils.hasText(miniProgramProperties.appId())
                && StringUtils.hasText(miniProgramProperties.appSecret());
        boolean captureReady = properties.enabled() && imageReady;
        boolean workerReady = captureReady && properties.workerEnabled()
                && templateConfigured && credentialsReady;
        return new AdminWechatServiceCardStatusResponse(
                properties.enabled(), properties.workerEnabled(), captureReady,
                templateConfigured, imageReady, credentialsReady, workerReady,
                properties.callback().enabled(),
                properties.callback().secureReady()
                        && StringUtils.hasText(miniProgramProperties.appId()),
                cardCount(true),
                deliveryCount("PENDING"), deliveryCount("SENDING"),
                deliveryCount("UNKNOWN") + deliveryCount("RECONCILING"),
                deliveryCount("FAILED")
        );
    }

    public PageResult<AdminWechatServiceCardDeliveryResponse> deliveries(
            AdminWechatServiceCardDeliveryQuery query
    ) {
        long current = query == null || query.current() == null ? 1L : query.current();
        long size = query == null || query.size() == null ? 20L : query.size();
        if (current < 1 || current > 1_000_000L || size < 1 || size > 200) {
            throw validation();
        }
        Long orderId = query == null ? null : query.orderId();
        if (orderId != null && orderId <= 0) {
            throw validation();
        }
        String state = normalizeState(query == null ? null : query.state());
        long total = jdbcClient.sql("""
                        select count(*)
                        from wechat_service_card_delivery delivery
                        join wechat_service_card card on card.id = delivery.card_id
                        where (:orderId is null or card.order_id = :orderId)
                          and (:state = '' or delivery.state = :state)
                        """)
                .param("orderId", orderId)
                .param("state", state)
                .query(Long.class)
                .single();
        long offset;
        try {
            offset = Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException ex) {
            throw validation();
        }
        var records = jdbcClient.sql("""
                        select delivery.id, delivery.card_id, card.order_id,
                               delivery.sequence_no, delivery.target_status, delivery.state,
                               card.send_blocked, card.send_block_reason, card.send_blocked_at,
                               delivery.attempt_count, delivery.reconcile_attempt_count,
                               delivery.not_applied_observations,
                               delivery.provider_error_code, delivery.provider_error_message,
                               delivery.next_action_at, delivery.applied_at,
                               delivery.message_result_state, delivery.message_fail_ret,
                               delivery.message_fail_message, delivery.message_result_at,
                               delivery.created_at, delivery.updated_at
                        from wechat_service_card_delivery delivery
                        join wechat_service_card card on card.id = delivery.card_id
                        where (:orderId is null or card.order_id = :orderId)
                          and (:state = '' or delivery.state = :state)
                        order by delivery.id desc
                        limit :limit offset :offset
                        """)
                .param("orderId", orderId)
                .param("state", state)
                .param("limit", size)
                .param("offset", offset)
                .query((rs, rowNum) -> new AdminWechatServiceCardDeliveryResponse(
                        rs.getLong("id"), rs.getLong("card_id"), rs.getLong("order_id"),
                        rs.getInt("sequence_no"), rs.getInt("target_status"), rs.getString("state"),
                        rs.getBoolean("send_blocked"), rs.getString("send_block_reason"),
                        rs.getObject("send_blocked_at", LocalDateTime.class),
                        rs.getInt("attempt_count"), rs.getInt("reconcile_attempt_count"),
                        rs.getInt("not_applied_observations"),
                        rs.getString("provider_error_code"), rs.getString("provider_error_message"),
                        rs.getObject("next_action_at", LocalDateTime.class),
                        rs.getObject("applied_at", LocalDateTime.class),
                        rs.getString("message_result_state"),
                        rs.getObject("message_fail_ret", Integer.class),
                        rs.getString("message_fail_message"),
                        rs.getObject("message_result_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .list();
        return PageResult.of(records, total, current, size);
    }

    private String normalizeState(String state) {
        if (!StringUtils.hasText(state)) {
            return "";
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "SENDING", "UNKNOWN", "RECONCILING",
                "SUCCEEDED", "FAILED", "SKIPPED").contains(normalized)) {
            throw validation();
        }
        return normalized;
    }

    private long deliveryCount(String state) {
        return jdbcClient.sql(
                        "select count(*) from wechat_service_card_delivery where state = :state")
                .param("state", state)
                .query(Long.class)
                .single();
    }

    private long cardCount(boolean sendBlocked) {
        return jdbcClient.sql(
                        "select count(*) from wechat_service_card where send_blocked = :sendBlocked")
                .param("sendBlocked", sendBlocked)
                .query(Long.class)
                .single();
    }

    private BusinessException validation() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
