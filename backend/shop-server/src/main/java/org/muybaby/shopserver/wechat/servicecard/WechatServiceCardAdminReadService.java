package org.muybaby.shopserver.wechat.servicecard;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentialResolver;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfig;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfigResolver;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryQuery;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryResponse;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardStatusResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class WechatServiceCardAdminReadService {

    private final JdbcClient jdbcClient;
    private final WechatServiceCardConfigResolver configResolver;
    private final WechatPlatformCredentialResolver credentialResolver;
    private final WechatServiceCardRuntimeSettingService runtimeSettingService;
    private final Clock clock;

    public WechatServiceCardAdminReadService(
            JdbcClient jdbcClient,
            WechatServiceCardConfigResolver configResolver,
            WechatPlatformCredentialResolver credentialResolver,
            WechatServiceCardRuntimeSettingService runtimeSettingService,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.configResolver = configResolver;
        this.credentialResolver = credentialResolver;
        this.runtimeSettingService = runtimeSettingService;
        this.clock = clock;
    }

    public AdminWechatServiceCardStatusResponse status() {
        WechatServiceCardRuntimeSettingService.RuntimeSetting runtime =
                runtimeSettingService.current();
        WechatServiceCardConfig config = configResolver.resolveFailClosed().orElse(null);
        boolean imageReady = config != null && config.imageConfigurationReady();
        boolean templateConfigured = config != null && config.templateConfigurationReady();
        boolean credentialsReady = credentialResolver.readyFailClosed();
        boolean callbackReady = runtimeSettingService.callbackReady();
        boolean captureReady = runtime.captureEnabled() && imageReady;
        boolean workerReady = captureReady && runtime.workerEnabled()
                && templateConfigured && credentialsReady && callbackReady;
        RepairEligibility repairEligibility = repairEligibility();
        return new AdminWechatServiceCardStatusResponse(
                runtime.captureEnabled(), runtime.workerEnabled(), runtime.persisted(),
                runtime.version(), runtime.defaultCaptureEnabled(), runtime.defaultWorkerEnabled(),
                runtime.reason(), runtime.updatedBy(), runtime.updatedAt(), captureReady,
                templateConfigured, imageReady, credentialsReady, workerReady,
                config != null && config.callbackEnabled(),
                callbackReady,
                cardCount(true),
                deliveryCount("PENDING"), deliveryCount("SENDING"),
                deliveryCount("UNKNOWN") + deliveryCount("RECONCILING"),
                deliveryCount("FAILED"), repairEligibility.count(),
                repairEligibility.earliestPaidAt(), repairEligibility.latestPaidAt()
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
                        select delivery.id, delivery.card_id, card.order_id, order_entry.order_no,
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
                        join shop_order order_entry on order_entry.id = card.order_id
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
                        rs.getString("order_no"),
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

    private RepairEligibility repairEligibility() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        return jdbcClient.sql("""
                        select count(*) as eligible_count,
                               min(payment.paid_at) as earliest_paid_at,
                               max(payment.paid_at) as latest_paid_at
                        from payment_order payment
                        join shop_order order_entry on order_entry.id = payment.order_id
                        left join wechat_service_card card on card.order_id = payment.order_id
                        where payment.status = 'PAID'
                          and payment.transaction_id <> ''
                          and payment.payer_openid <> ''
                          and payment.paid_at is not null
                          and payment.paid_at <= :now
                          and (
                              (card.id is null and payment.paid_at >= :activationEarliest)
                              or (
                                  card.id is not null
                                  and card.terminal = false
                                  and card.send_blocked = false
                                  and (
                                      (card.activated_at is null
                                          and payment.paid_at >= :activationEarliest)
                                      or (card.remote_code_expire_at is not null
                                          and card.remote_code_expire_at >= :now)
                                      or (card.remote_code_expire_at is null
                                          and card.activated_at >= :updateEarliest)
                                  )
                              )
                          )
                        """)
                .param("activationEarliest", now.minusHours(24))
                .param("updateEarliest", now.minusDays(30))
                .param("now", now)
                .query(this::mapRepairEligibility)
                .single();
    }

    private RepairEligibility mapRepairEligibility(ResultSet rs, int rowNum) throws SQLException {
        return new RepairEligibility(
                rs.getLong("eligible_count"),
                rs.getObject("earliest_paid_at", LocalDateTime.class),
                rs.getObject("latest_paid_at", LocalDateTime.class)
        );
    }

    private BusinessException validation() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record RepairEligibility(
            long count,
            LocalDateTime earliestPaidAt,
            LocalDateTime latestPaidAt
    ) {
    }
}
