package org.muybaby.shopserver.aftersale.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppAfterSaleServiceTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(96_000L);

    @Autowired
    private AppAfterSaleService appAfterSaleService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearState() {
        jdbcClient.sql("delete from refund_order").update();
        jdbcClient.sql("delete from after_sale_evidence").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from shop_order").update();
    }

    @Test
    void currentUserListPagesStablyFiltersStatusAndUsesOrderOwnershipForCountAndRecords() {
        long ownerId = insertUser("after-sale-page-owner");
        long otherId = insertUser("after-sale-page-other");
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 10, 12, 0);
        long ownerOrderA = insertOrder(ownerId, "COMPLETED", createdAt);
        long ownerOrderB = insertOrder(ownerId, "PAID", createdAt.plusMinutes(1));
        long otherOrder = insertOrder(otherId, "PAID", createdAt.plusMinutes(2));

        long olderOwned = insertAfterSale(ownerOrderA, otherId, "REQUESTED", createdAt);
        long newerOwned = insertAfterSale(ownerOrderB, otherId, "REJECTED", createdAt.plusMinutes(1));
        long otherRecord = insertAfterSale(otherOrder, ownerId, "REQUESTED", createdAt.plusMinutes(2));

        PageResult<AfterSaleResponse> first = appAfterSaleService.list(appPrincipal(ownerId), 1L, 1L, null);
        PageResult<AfterSaleResponse> second = appAfterSaleService.list(appPrincipal(ownerId), 2L, 1L, null);
        PageResult<AfterSaleResponse> requested = appAfterSaleService.list(
                appPrincipal(ownerId), 1L, 10L, " REQUESTED ");

        assertThat(first.total()).isEqualTo(2L);
        assertThat(first.current()).isEqualTo(1L);
        assertThat(first.size()).isEqualTo(1L);
        assertThat(first.records()).extracting(AfterSaleResponse::id).containsExactly(newerOwned);
        assertThat(second.records()).extracting(AfterSaleResponse::id).containsExactly(olderOwned);
        assertThat(requested.total()).isEqualTo(1L);
        assertThat(requested.records()).extracting(AfterSaleResponse::id).containsExactly(olderOwned);
        assertThat(first.records()).extracting(AfterSaleResponse::id).doesNotContain(otherRecord);

        PageResult<AfterSaleResponse> otherPage = appAfterSaleService.list(appPrincipal(otherId), 1L, 10L, null);
        assertThat(otherPage.records()).extracting(AfterSaleResponse::id).containsExactly(otherRecord);
    }

    @Test
    void detailUsesOrderOwnershipAndPageArgumentsAreValidatedAndClamped() {
        long ownerId = insertUser("after-sale-detail-owner");
        long otherId = insertUser("after-sale-detail-other");
        long orderId = insertOrder(ownerId, "COMPLETED", LocalDateTime.of(2026, 7, 10, 13, 0));
        long afterSaleId = insertAfterSale(orderId, otherId, "REQUESTED", LocalDateTime.of(2026, 7, 10, 13, 1));

        AfterSaleResponse detail = appAfterSaleService.detail(appPrincipal(ownerId), afterSaleId);
        PageResult<AfterSaleResponse> clamped = appAfterSaleService.list(appPrincipal(ownerId), 1L, 1_000L, null);

        assertThat(detail.id()).isEqualTo(afterSaleId);
        assertThat(clamped.size()).isEqualTo(100L);
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appAfterSaleService.detail(appPrincipal(otherId), afterSaleId));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appAfterSaleService.list(appPrincipal(ownerId), 0L, 10L, null));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appAfterSaleService.list(appPrincipal(ownerId), 1L, 0L, null));
    }

    private long insertUser(String suffix) {
        long userId = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into app_user (id, openid, unionid, status, last_login_at, created_at, updated_at)
                        values (:id, :openid, :unionid, 'ENABLED', :now, :now, :now)
                        """)
                .param("id", userId)
                .param("openid", suffix + "-" + userId)
                .param("unionid", suffix + "-union-" + userId)
                .param("now", now)
                .update();
        return userId;
    }

    private long insertOrder(long userId, String status, LocalDateTime createdAt) {
        long orderId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, :status, 'CART', :idempotencyKey,
                             1000, 1000, 0, 0, 1000, 1000, :createdAt, :createdAt)
                        """)
                .param("orderId", orderId)
                .param("orderNo", "AS-ORDER-" + orderId)
                .param("userId", userId)
                .param("status", status)
                .param("idempotencyKey", "as-order-" + orderId)
                .param("createdAt", createdAt)
                .update();
        return orderId;
    }

    private long insertAfterSale(long orderId, long denormalizedUserId, String status, LocalDateTime createdAt) {
        long afterSaleId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, order_id, user_id, after_sale_type, status, reason,
                             description, requested_amount_cent, created_at, updated_at)
                        values
                            (:afterSaleId, :orderId, :userId, 'REFUND_ONLY', :status, 'page ownership',
                             '', 100, :createdAt, :createdAt)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", orderId)
                .param("userId", denormalizedUserId)
                .param("status", status)
                .param("createdAt", createdAt)
                .update();
        return afterSaleId;
    }

    private AuthenticatedPrincipal appPrincipal(long userId) {
        return new AuthenticatedPrincipal(TokenKind.APP, userId, "app-user-" + userId, List.of(), List.of());
    }

    private void assertBusiness(ErrorCode expected, Runnable action) {
        BusinessException exception = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(expected);
    }
}
