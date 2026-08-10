package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.aftersale.service.RefundCallbackService;
import org.muybaby.shopserver.aftersale.service.RefundRecoveryService;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatRefundRequest;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RefundRecoveryServiceTest extends PaymentTestSupport {

    @Autowired
    private RefundRecoveryService refundRecoveryService;

    @Autowired
    private RefundCallbackService refundCallbackService;

    @MockitoSpyBean
    private MockWechatPayProvider refundProvider;

    @Test
    void successfulProviderQueryFinalizesAStaleRefund() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-success");
        switchToClonedPaymentConfig(91002L);
        makeRecoveryDue(approved.outRefundNo());
        LocalDateTime successAt = LocalDateTime.of(2026, 7, 8, 14, 0);
        mockWechatPayProvider.markRefundStatus(approved.outRefundNo(), "SUCCESS", successAt);

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'SUCCESS'
                          and callback_status = 'SUCCESS'
                          and success_at = :successAt
                          and recovery_claim_token is null
                          and recovery_claimed_at is null
                          and recovery_attempts = 1
                          and restock_required = true
                          and restocked_at is not null
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .param("successAt", successAt)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(statusOf("after_sale_request", approved.afterSaleId())).isEqualTo("REFUNDED");
        assertThat(statusOf("shop_order", approved.orderId())).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("""
                        select count(*) from stock_lock
                        where order_id = :orderId
                          and status = 'RESTOCKED'
                          and restock_refund_order_id = :refundOrderId
                          and restocked_at is not null
                        """)
                .param("orderId", approved.orderId())
                .param("refundOrderId", approved.refundOrderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select stock_available
                        from product_sku sku
                        join stock_lock stock_lock_entry on stock_lock_entry.sku_id = sku.id
                        where stock_lock_entry.order_id = :orderId
                        """)
                .param("orderId", approved.orderId())
                .query(Integer.class)
                .single()).isEqualTo(10);
        assertThat(jdbcClient.sql("""
                        select count(*) from stock_log
                        where refund_order_id = :refundOrderId
                          and change_type = 'REFUND_RESTOCK'
                        """)
                .param("refundOrderId", approved.refundOrderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isZero();
        assertThat(mockWechatPayProvider.queriedOutRefundNos()).containsExactly(approved.outRefundNo());
        ArgumentCaptor<ResolvedPaymentConfig> configCaptor = ArgumentCaptor.forClass(ResolvedPaymentConfig.class);
        verify(refundProvider).queryRefund(configCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(approved.outRefundNo()));
        assertThat(configCaptor.getValue().configId()).isEqualTo(91001L);
    }

    @Test
    void processingRefundSchedulesTheNextQueryAndIsNotImmediatelyClaimedAgain() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-processing");
        makeRecoveryDue(approved.outRefundNo());

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isEqualTo(1);
        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isZero();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'PROCESSING'
                          and callback_status = 'PROCESSING'
                          and recovery_claim_token is null
                          and recovery_claimed_at is null
                          and recovery_attempts = 1
                          and next_recovery_at > current_timestamp
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(mockWechatPayProvider.queriedOutRefundNos()).containsExactly(approved.outRefundNo());
    }

    @Test
    void missingUncertainRefundIsResubmittedWithTheSameMerchantRefundNumber() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-resubmit");
        mockWechatPayProvider.forgetRefund(approved.outRefundNo());
        jdbcClient.sql("""
                        update refund_order
                        set callback_status = 'REQUEST_UNKNOWN'
                        where out_refund_no = :outRefundNo
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .update();
        makeRecoveryDue(approved.outRefundNo());

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isEqualTo(1);

        assertThat(mockWechatPayProvider.queriedOutRefundNos()).containsExactly(approved.outRefundNo());
        assertThat(mockWechatPayProvider.requestedOutRefundNos())
                .containsExactly(approved.outRefundNo(), approved.outRefundNo());
        ArgumentCaptor<WechatRefundRequest> requestCaptor =
                ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(refundProvider, times(2)).requestRefund(any(), requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .extracting(WechatRefundRequest::reason)
                .containsOnly("同意退款");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'PROCESSING'
                          and callback_status = 'PROCESSING'
                          and refund_id <> ''
                          and recovery_claim_token is null
                          and recovery_claimed_at is null
                          and next_recovery_at > current_timestamp
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void legacyRefundWithoutProviderReasonReusesItsOriginalReason() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-legacy-reason");
        jdbcClient.sql("""
                        update refund_order
                        set provider_reason = null,
                            callback_status = 'REQUEST_UNKNOWN'
                        where out_refund_no = :outRefundNo
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .update();
        mockWechatPayProvider.forgetRefund(approved.outRefundNo());
        makeRecoveryDue(approved.outRefundNo());
        org.mockito.Mockito.clearInvocations(refundProvider);

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isEqualTo(1);

        ArgumentCaptor<WechatRefundRequest> requestCaptor =
                ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(refundProvider).requestRefund(any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().reason()).isEqualTo("同意退款");
    }

    @Test
    void unknownProviderStatusRemainsRecoverableInsteadOfBecomingTerminalFailure() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-future-status");
        makeRecoveryDue(approved.outRefundNo());
        mockWechatPayProvider.markRefundStatus(approved.outRefundNo(), "FUTURE_PROVIDER_STATE", null);

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'PROCESSING'
                          and callback_status = 'FUTURE_PROVIDER_STATE'
                          and next_recovery_at > current_timestamp
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(statusOf("after_sale_request", approved.afterSaleId())).isEqualTo("REFUNDING");
        assertThat(statusOf("shop_order", approved.orderId())).isEqualTo("REFUNDING");
    }

    @Test
    void abnormalRefundIsReconciledAfterManualProviderResolution() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-manual");
        makeRecoveryDue(approved.outRefundNo());
        mockWechatPayProvider.markRefundStatus(approved.outRefundNo(), "ABNORMAL", null);

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isEqualTo(1);
        assertThat(statusOf("after_sale_request", approved.afterSaleId())).isEqualTo("REFUND_FAILED");
        assertThat(statusOf("shop_order", approved.orderId())).isEqualTo("REFUNDING");

        LocalDateTime successAt = LocalDateTime.of(2026, 7, 19, 16, 0);
        mockWechatPayProvider.markRefundStatus(approved.outRefundNo(), "SUCCESS", successAt);
        makeRecoveryDue(approved.outRefundNo());

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isEqualTo(1);
        assertThat(statusOf("after_sale_request", approved.afterSaleId())).isEqualTo("REFUNDED");
        assertThat(statusOf("shop_order", approved.orderId())).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'SUCCESS'
                          and callback_status = 'SUCCESS'
                          and success_at = :successAt
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .param("successAt", successAt)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void providerClosedRefreshesAnEarlierAbnormalFailureIntoTheRetryableTerminalState() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-abnormal-closed");
        makeRecoveryDue(approved.outRefundNo());
        mockWechatPayProvider.markRefundStatus(approved.outRefundNo(), "ABNORMAL", null);
        assertThat(refundRecoveryService.recoverPendingRefunds(1)).isEqualTo(1);
        LocalDateTime failedAt = jdbcClient.sql("""
                        select failed_at
                        from refund_order
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", approved.refundOrderId())
                .query(LocalDateTime.class)
                .single();

        mockWechatPayProvider.markRefundStatus(approved.outRefundNo(), "CLOSED", null);
        makeRecoveryDue(approved.outRefundNo());

        assertThat(refundRecoveryService.recoverPendingRefunds(1)).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where id = :refundOrderId
                          and status = 'FAILED'
                          and callback_status = 'CLOSED'
                          and failed_at = :failedAt
                          and recovery_claim_token is null
                        """)
                .param("refundOrderId", approved.refundOrderId())
                .param("failedAt", failedAt)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(statusOf("after_sale_request", approved.afterSaleId())).isEqualTo("REFUND_FAILED");
        assertThat(statusOf("shop_order", approved.orderId())).isEqualTo("REFUNDING");
    }

    @Test
    void schedulerNeverResubmitsAnAbnormalRefundWhenProviderLaterReportsMissing() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-abnormal-missing");
        makeRecoveryDue(approved.outRefundNo());
        mockWechatPayProvider.markRefundStatus(approved.outRefundNo(), "ABNORMAL", null);

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isEqualTo(1);
        mockWechatPayProvider.forgetRefund(approved.outRefundNo());
        makeRecoveryDue(approved.outRefundNo());

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isZero();

        assertThat(mockWechatPayProvider.requestedOutRefundNos())
                .containsExactly(approved.outRefundNo());
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'FAILED'
                          and callback_status = 'ABNORMAL'
                          and recovery_claim_token is null
                          and recovery_claimed_at is null
                          and next_recovery_at > current_timestamp
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void providerFailureReleasesTheLeaseAndAppliesBackoff() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-failure");
        makeRecoveryDue(approved.outRefundNo());
        mockWechatPayProvider.failRefundQueryFor(approved.outRefundNo());

        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isZero();
        assertThat(refundRecoveryService.recoverPendingRefunds(10)).isZero();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where out_refund_no = :outRefundNo
                          and status = 'PROCESSING'
                          and recovery_claim_token is null
                          and recovery_claimed_at is null
                          and recovery_attempts = 1
                          and next_recovery_at > current_timestamp
                          and last_error_code = 'IllegalStateException'
                          and last_error_message = 'Refund status query failed; retry scheduled'
                        """)
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(mockWechatPayProvider.queriedOutRefundNos()).containsExactly(approved.outRefundNo());
    }

    @Test
    void concurrentScansOnlyQueryOneClaimedRefundOnce() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-concurrent");
        makeRecoveryDue(approved.outRefundNo());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> recoverAfter(start));
            Future<Integer> second = executor.submit(() -> recoverAfter(start));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(mockWechatPayProvider.queriedOutRefundNos()).containsExactly(approved.outRefundNo());
        assertThat(jdbcClient.sql("select recovery_attempts from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", approved.outRefundNo())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void callbackCannotCloseRefundWhileManualRecoveryOwnsTheClaim() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-callback-race");
        mockWechatPayProvider.forgetRefund(approved.outRefundNo());
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        doAnswer(invocation -> {
            queryStarted.countDown();
            if (!releaseQuery.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Refund query was not released in time");
            }
            return invocation.callRealMethod();
        }).when(refundProvider).queryRefund(any(), eq(approved.outRefundNo()));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RefundRecoveryService.ManualRecoveryResult> recovery = executor.submit(
                    () -> refundRecoveryService.resubmitRefundNow(approved.refundOrderId()));
            assertThat(queryStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> refundCallbackService.handleRefundNotification(
                    currentWechatpayTimestamp(),
                    "mock-refund-notify-nonce",
                    "mock-refund-serial",
                    "mock-valid-signature",
                    refundNotifyBody(
                            "notify-refund-recovery-race",
                            "REFUND.CLOSED",
                            approved.outTradeNo(),
                            approved.outRefundNo(),
                            "wx-refund-recovery-race",
                            "CLOSED"
                    )
            )).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.ORDER_STATE_CONFLICT));

            releaseQuery.countDown();
            RefundRecoveryService.ManualRecoveryResult result = recovery.get(10, TimeUnit.SECONDS);
            assertThat(result.resubmitted()).isTrue();
            assertThat(result.providerStatus()).isEqualTo("PROCESSING");
        } finally {
            releaseQuery.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where id = :refundOrderId
                          and status = 'PROCESSING'
                          and callback_status = 'PROCESSING'
                          and recovery_claim_token is null
                        """)
                .param("refundOrderId", approved.refundOrderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_callback_log
                        where callback_type = 'REFUND'
                          and notify_id = 'notify-refund-recovery-race'
                          and status = 'FAILED'
                          and error_code = 'ORDER_STATE_CONFLICT'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(mockWechatPayProvider.requestedOutRefundNos())
                .containsExactly(approved.outRefundNo(), approved.outRefundNo());
    }

    @Test
    void schedulerDoesNotResubmitAfterLosingClaimOwnershipDuringProviderQuery() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-stolen-claim");
        mockWechatPayProvider.forgetRefund(approved.outRefundNo());
        jdbcClient.sql("""
                        update refund_order
                        set callback_status = 'REQUEST_UNKNOWN'
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", approved.refundOrderId())
                .update();
        makeRecoveryDue(approved.outRefundNo());
        int requestCountBefore = mockWechatPayProvider.requestedOutRefundNos().size();
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        doAnswer(invocation -> {
            queryStarted.countDown();
            if (!releaseQuery.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Refund query was not released in time");
            }
            return invocation.callRealMethod();
        }).when(refundProvider).queryRefund(any(), eq(approved.outRefundNo()));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> recovery = executor.submit(() -> refundRecoveryService.recoverPendingRefunds(1));
            assertThat(queryStarted.await(5, TimeUnit.SECONDS)).isTrue();
            jdbcClient.sql("""
                            update refund_order
                            set recovery_claim_token = 'replacement-owner',
                                recovery_claimed_at = current_timestamp
                            where id = :refundOrderId
                            """)
                    .param("refundOrderId", approved.refundOrderId())
                    .update();

            releaseQuery.countDown();
            assertThat(recovery.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            releaseQuery.countDown();
            executor.shutdownNow();
        }

        assertThat(mockWechatPayProvider.requestedOutRefundNos()).hasSize(requestCountBefore);
        assertThat(jdbcClient.sql("""
                        select recovery_claim_token
                        from refund_order
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", approved.refundOrderId())
                .query(String.class)
                .single()).isEqualTo("replacement-owner");
    }

    @Test
    void manualResubmitRejectsAClosedRefundBeforeCallingProvider() throws Exception {
        ApprovedRefund approved = approveRefund("refund-recovery-closed-resubmit");
        jdbcClient.sql("""
                        update refund_order
                        set status = 'FAILED',
                            callback_status = 'CLOSED',
                            failed_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", approved.refundOrderId())
                .update();
        int queryCountBefore = mockWechatPayProvider.queriedOutRefundNos().size();
        int requestCountBefore = mockWechatPayProvider.requestedOutRefundNos().size();

        assertThatThrownBy(() -> refundRecoveryService.resubmitRefundNow(approved.refundOrderId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.ORDER_STATE_CONFLICT));

        assertThat(mockWechatPayProvider.queriedOutRefundNos()).hasSize(queryCountBefore);
        assertThat(mockWechatPayProvider.requestedOutRefundNos()).hasSize(requestCountBefore);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where id = :refundOrderId
                          and status = 'FAILED'
                          and callback_status = 'CLOSED'
                          and recovery_claim_token is null
                          and recovery_attempts = 0
                        """)
                .param("refundOrderId", approved.refundOrderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private int recoverAfter(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent recovery did not start in time");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent recovery was interrupted", ex);
        }
        return refundRecoveryService.recoverPendingRefunds(1);
    }

    private ApprovedRefund approveRefund(String code) throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin(code + "-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-" + code);
        long evidenceFileId = insertAppEvidenceFile(appUser.userId(), order.orderId());
        String applyResponse = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + appUser.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "退款恢复测试", 6980L, "recovery test", evidenceFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(applyResponse).path("data").path("id").asLong();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"));

        return jdbcClient.sql("""
                        select id, out_refund_no
                        from refund_order
                        where after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new ApprovedRefund(
                        afterSaleId,
                        order.orderId(),
                        order.outTradeNo(),
                        rs.getLong("id"),
                        rs.getString("out_refund_no")
                ))
                .single();
    }

    private void makeRecoveryDue(String outRefundNo) {
        jdbcClient.sql("""
                        update refund_order
                        set updated_at = :updatedAt,
                            next_recovery_at = null,
                            recovery_claim_token = null,
                            recovery_claimed_at = null
                        where out_refund_no = :outRefundNo
                        """)
                .param("updatedAt", LocalDateTime.now().minusMinutes(5))
                .param("outRefundNo", outRefundNo)
                .update();
    }

    private String statusOf(String tableName, long id) {
        if (!("after_sale_request".equals(tableName) || "shop_order".equals(tableName))) {
            throw new IllegalArgumentException("Unsupported status table");
        }
        return jdbcClient.sql("select status from %s where id = :id".formatted(tableName))
                .param("id", id)
                .query(String.class)
                .single();
    }

    private String applyBody(String type, String reason, long requestedAmountCent, String description, long... fileIds) {
        String evidenceFileIds = Arrays.stream(fileIds)
                .mapToObj(Long::toString)
                .collect(Collectors.joining(","));
        return """
                {"afterSaleType":"%s","reason":"%s","requestedAmountCent":%d,
                 "description":"%s","evidenceFileIds":[%s]}
                """.formatted(type, reason, requestedAmountCent, description, evidenceFileIds);
    }

    private String refundNotifyBody(
            String notifyId,
            String eventType,
            String outTradeNo,
            String outRefundNo,
            String refundId,
            String refundStatus
    ) {
        return """
                {
                  "id":"%s",
                  "event_type":"%s",
                  "resource":{
                    "out_trade_no":"%s",
                    "out_refund_no":"%s",
                    "refund_id":"%s",
                    "refund_status":"%s",
                    "success_time":"2026-07-08T14:00:00+08:00",
                    "amount":{"refund":6980,"total":6980,"currency":"CNY"}
                  }
                }
                """.formatted(notifyId, eventType, outTradeNo, outRefundNo, refundId, refundStatus);
    }

    private record ApprovedRefund(
            long afterSaleId,
            long orderId,
            String outTradeNo,
            long refundOrderId,
            String outRefundNo
    ) {
    }
}
