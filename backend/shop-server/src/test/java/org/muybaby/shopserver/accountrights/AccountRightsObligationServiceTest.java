package org.muybaby.shopserver.accountrights;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.accountrights.dto.AdminAccountRightsTransitionRequest;
import org.muybaby.shopserver.accountrights.service.AccountRightsService;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.order.service.AppOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AccountRightsObligationServiceTest {

    @Autowired
    private AccountRightsService accountRightsService;

    @Autowired
    private AppOrderService appOrderService;

    @Autowired
    private AppAfterSaleService appAfterSaleService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void closedFailedRefundAttemptDoesNotPermanentlyBlockLaterSuccessfulRetry() {
        long userId = 9_003_001L;
        long orderId = 9_003_011L;
        long paymentId = 9_003_021L;
        long afterSaleId = 9_003_031L;
        long requestId = 9_003_041L;
        seedUserAndTerminalCommerce(userId, orderId, paymentId, afterSaleId);
        insertRefund(9_003_051L, afterSaleId, orderId, paymentId, "FAILED", "CLOSED");
        insertRefund(9_003_052L, afterSaleId, orderId, paymentId, "SUCCESS", "SUCCESS");
        insertApprovedDeletionRequest(requestId, userId);

        var completed = accountRightsService.adminTransition(
                adminPrincipal(),
                requestId,
                AccountRightsAdminAction.COMPLETE,
                transitionRequest()
        );

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(jdbcClient.sql("""
                        select concat(status, '|', auth_version)
                        from app_user
                        where id = :userId
                        """)
                .param("userId", userId)
                .query(String.class)
                .single()).isEqualTo("ENABLED|1");
    }

    @Test
    void unresolvedFailedRefundStillBlocksIdentityDeletion() {
        long userId = 9_004_001L;
        long orderId = 9_004_011L;
        long paymentId = 9_004_021L;
        long afterSaleId = 9_004_031L;
        long requestId = 9_004_041L;
        seedUserAndTerminalCommerce(userId, orderId, paymentId, afterSaleId);
        insertRefund(
                9_004_051L, afterSaleId, orderId, paymentId,
                "FAILED", "MANUAL_INTERVENTION"
        );
        insertApprovedDeletionRequest(requestId, userId);

        assertThatThrownBy(() -> accountRightsService.adminTransition(
                adminPrincipal(), requestId, AccountRightsAdminAction.COMPLETE, transitionRequest()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ACCOUNT_CANCELLATION_ACTIVE_OBLIGATIONS));

        assertThat(jdbcClient.sql("select auth_version from app_user where id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select status from app_user_rights_request where id = :id")
                .param("id", requestId)
                .query(String.class)
                .single()).isEqualTo("APPROVED");
    }

    @Test
    void cancelledUserCannotCreateACommerceObligationEvenWithAStalePrincipal() {
        long userId = 9_005_001L;
        jdbcClient.sql("""
                        insert into app_user(id, openid, status, auth_version, cancelled_at)
                        values(:id, :openid, 'CANCELLED', 1, current_timestamp)
                        """)
                .param("id", userId)
                .param("openid", "cancelled-commerce-gate-" + userId)
                .update();
        AuthenticatedPrincipal stalePrincipal = new AuthenticatedPrincipal(
                TokenKind.APP, userId, "stale", List.of(), List.of());

        assertThatThrownBy(() -> appOrderService.submit(stalePrincipal, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
        assertThatThrownBy(() -> appAfterSaleService.apply(stalePrincipal, 1L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    private void seedUserAndTerminalCommerce(
            long userId,
            long orderId,
            long paymentId,
            long afterSaleId
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        jdbcClient.sql("""
                        insert into app_user(id, openid, unionid, nickname, phone_number,
                                             phone_country_code, phone_authorized, status)
                        values(:id, :openid, 'union-value', '删除测试用户', '13812345678',
                               '86', true, 'ENABLED')
                        """)
                .param("id", userId)
                .param("openid", "rights-obligation-" + userId)
                .update();
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            paid_amount_cent, receiver_name, receiver_phone, receiver_address)
                        values(
                            :id, :orderNo, :userId, 'REFUNDED', 'CART', :idempotencyKey,
                            100, '测试用户', '13800138000', '测试地址')
                        """)
                .param("id", orderId)
                .param("orderNo", "RIGHTS-OBLIGATION-" + orderId)
                .param("userId", userId)
                .param("idempotencyKey", "rights-obligation-" + orderId)
                .update();
        jdbcClient.sql("""
                        insert into payment_order(
                            id, order_id, out_trade_no, transaction_id, payer_openid,
                            status, amount_cent, expires_at, paid_at)
                        values(
                            :id, :orderId, :outTradeNo, :transactionId, :openid,
                            'PAID', 100, :now, :now)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("outTradeNo", "RIGHTS-PAY-" + paymentId)
                .param("transactionId", "RIGHTS-TXN-" + paymentId)
                .param("openid", "rights-obligation-" + userId)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into after_sale_request(
                            id, after_sale_no, order_id, user_id, after_sale_type,
                            status, reason, requested_amount_cent, approved_amount_cent)
                        values(
                            :id, :afterSaleNo, :orderId, :userId, 'REFUND_ONLY',
                            'REFUNDED', '测试退款', 100, 100)
                        """)
                .param("id", afterSaleId)
                .param("afterSaleNo", "ASRIGHTS" + afterSaleId)
                .param("orderId", orderId)
                .param("userId", userId)
                .update();
    }

    private void insertRefund(
            long refundId,
            long afterSaleId,
            long orderId,
            long paymentId,
            String status,
            String callbackStatus
    ) {
        jdbcClient.sql("""
                        insert into refund_order(
                            id, after_sale_id, order_id, payment_order_id, out_refund_no,
                            refund_amount_cent, status, callback_status, requested_at)
                        values(
                            :id, :afterSaleId, :orderId, :paymentId, :outRefundNo,
                            100, :status, :callbackStatus, :requestedAt)
                        """)
                .param("id", refundId)
                .param("afterSaleId", afterSaleId)
                .param("orderId", orderId)
                .param("paymentId", paymentId)
                .param("outRefundNo", "RIGHTS-REFUND-" + refundId)
                .param("status", status)
                .param("callbackStatus", callbackStatus)
                .param("requestedAt", LocalDateTime.of(2026, 8, 9, 12, 1))
                .update();
    }

    private void insertApprovedDeletionRequest(long requestId, long userId) {
        jdbcClient.sql("""
                        insert into app_user_rights_request(
                            id, user_id, request_type, status, active_request_key,
                            request_note, version)
                        values(
                            :id, :userId, 'PERSONAL_INFORMATION_DELETION', 'APPROVED', 1,
                            '删除可选身份资料', 0)
                        """)
                .param("id", requestId)
                .param("userId", userId)
                .update();
    }

    private AdminAccountRightsTransitionRequest transitionRequest() {
        return new AdminAccountRightsTransitionRequest(
                0L,
                "核验完成",
                "本次没有声明额外保留的数据类别",
                List.of()
        );
    }

    private AuthenticatedPrincipal adminPrincipal() {
        return new AuthenticatedPrincipal(
                TokenKind.ADMIN, 1L, "Super", List.of("R_SUPER"),
                List.of("account-rights:read", "account-rights:manage")
        );
    }
}
