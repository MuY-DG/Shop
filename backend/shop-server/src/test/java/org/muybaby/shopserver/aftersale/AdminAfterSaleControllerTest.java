package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatRefundRequest;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminAfterSaleControllerTest extends PaymentTestSupport {

    @MockitoSpyBean
    private MockWechatPayProvider refundProvider;

    @Autowired
    private StorageProvider storageProvider;

    @Test
    void adminListAndDetailReturnPagedEnvelopeAndRequireReadAuthority() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-list-app");
        jdbcClient.sql("update app_user set nickname = '售后详情用户' where id = :userId")
                .param("userId", appUser.userId())
                .update();
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-list");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        long evidenceFileId = firstEvidenceFileId(afterSaleId);
        String readToken = limitedAdminToken(List.of("aftersale:read"));
        String auditOnlyToken = limitedAdminToken(List.of("aftersale:audit"));

        mockMvc.perform(get("/admin/after-sales")
                        .header("Authorization", "Bearer " + auditOnlyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(get("/admin/after-sales")
                        .param("current", "1")
                        .param("size", "10")
                        .param("status", "REQUESTED")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value(afterSaleId))
                .andExpect(jsonPath("$.data.records[0].orderId").value(order.orderId()))
                .andExpect(jsonPath("$.data.records[0].userId").isString())
                .andExpect(jsonPath("$.data.records[0].userId").value(Long.toString(appUser.userId())))
                .andExpect(jsonPath("$.data.records[0].userNickname").value("售后详情用户"))
                .andExpect(jsonPath("$.data.records[0].requestedAmountCent").isNumber())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10));

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}", afterSaleId)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(afterSaleId))
                .andExpect(jsonPath("$.data.orderNo").value(order.orderNo()))
                .andExpect(jsonPath("$.data.userNickname").value("售后详情用户"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].fileId").value(evidenceFileId))
                .andExpect(jsonPath("$.data.evidenceFiles[0].originalFilename").value("after-sale-" + evidenceFileId + ".png"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].sizeBytes").value(68))
                .andExpect(jsonPath("$.data.evidenceFiles[0].scope").value("ATTACHMENT"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].mediaKind").value("IMAGE"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].status").value("ACTIVE"));
    }

    @Test
    void adminListSupportsBusinessStatusGroupsRichFiltersCountsAndLightweightRows() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-v2-app");
        jdbcClient.sql("""
                        update app_user
                        set nickname = '售后筛选用户',
                            phone_number = '13800138000', phone_authorized = true
                        where id = :userId
                        """)
                .param("userId", appUser.userId())
                .update();

        SeedPaidOrder requestedOrder = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-v2-requested");
        long requestedId = applyAfterSale(appUser, requestedOrder, 6980L);
        SeedPaidOrder approvedOrder = seedPaidOrder(appUser, 7980L, "PAID", "wx-refund-admin-v2-approved");
        long approvedId = applyAfterSale(appUser, approvedOrder, 7980L);
        SeedPaidOrder refundingOrder = seedPaidOrder(appUser, 8980L, "PAID", "wx-refund-admin-v2-refunding");
        long refundingId = applyAfterSale(appUser, refundingOrder, 8980L);

        jdbcClient.sql("""
                        update after_sale_request
                        set status = case id
                                when :approvedId then 'APPROVED'
                                when :refundingId then 'REFUNDING'
                                else 'REQUESTED'
                            end,
                            created_at = case id
                                when :requestedId then timestamp '2026-07-10 09:00:00'
                                when :approvedId then timestamp '2026-07-11 10:00:00'
                                else timestamp '2026-07-12 11:00:00'
                            end
                        where id in (:requestedId, :approvedId, :refundingId)
                        """)
                .param("requestedId", requestedId)
                .param("approvedId", approvedId)
                .param("refundingId", refundingId)
                .update();
        insertRefundOrder(approvedId, approvedOrder.orderId(), "REF-APPROVED-V2");
        insertRefundOrder(refundingId, refundingOrder.orderId(), "REF-REFUNDING-V2");

        String readToken = limitedAdminToken(List.of("aftersale:read"));

        mockMvc.perform(get("/admin/after-sales/status-counts")
                        .param("userSearchType", "USER_NAME")
                        .param("userKeyword", "售后筛选")
                        .param("statusGroup", "PENDING_REVIEW")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all").value(3))
                .andExpect(jsonPath("$.data.pendingReview").value(1))
                .andExpect(jsonPath("$.data.refunding").value(2))
                .andExpect(jsonPath("$.data.refunded").value(0))
                .andExpect(jsonPath("$.data.rejected").value(0))
                .andExpect(jsonPath("$.data.refundFailed").value(0));

        mockMvc.perform(get("/admin/after-sales")
                        .param("statusGroup", "REFUNDING")
                        .param("afterSaleId", Long.toString(approvedId))
                        .param("orderNo", approvedOrder.orderNo())
                        .param("userSearchType", "USER_PHONE")
                        .param("userKeyword", "13800138000")
                        .param("afterSaleType", "REFUND_ONLY")
                        .param("createdStart", "2026-07-11 00:00:00")
                        .param("createdEnd", "2026-07-11 23:59:59")
                        .param("refundNo", "REF-APPROVED")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(approvedId))
                .andExpect(jsonPath("$.data.records[0].userNickname").value("售后筛选用户"))
                .andExpect(jsonPath("$.data.records[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.records[0].reason").value("申请退款"))
                .andExpect(jsonPath("$.data.records[0].requestedAmountCent").value(7980))
                .andExpect(jsonPath("$.data.records[0].evidenceFileIds").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].evidenceFiles").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].refundOrder").doesNotExist());
    }

    @Test
    void adminCanReadOnlyEvidenceAttachedToTheRequestedAfterSale() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-evidence-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-evidence");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        long evidenceFileId = firstEvidenceFileId(afterSaleId);
        byte[] evidenceContent = "private-evidence-image".getBytes(StandardCharsets.UTF_8);
        storageProvider.put(
                "private/after-sale-flow/" + evidenceFileId + ".png",
                "image/png",
                new ByteArrayInputStream(evidenceContent),
                evidenceContent.length
        );
        long unrelatedFileId = insertAppEvidenceFile(appUser.userId(), order.orderId());
        String readToken = limitedAdminToken(List.of("aftersale:read"));
        String auditOnlyToken = limitedAdminToken(List.of("aftersale:audit"));

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}/evidence/{fileId}", afterSaleId, evidenceFileId)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(evidenceContent));

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}/evidence/{fileId}", afterSaleId, evidenceFileId)
                        .header("Authorization", "Bearer " + auditOnlyToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}/evidence/{fileId}", afterSaleId, unrelatedFileId)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(800001));
    }

    @Test
    void adminRejectSetsAuditFieldsAndLeavesOrderPaid() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-reject-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-reject");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/reject", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auditNote":"凭证不足，暂不退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.auditNote").value("凭证不足，暂不退款"))
                .andExpect(jsonPath("$.data.reviewedBy").isString())
                .andExpect(jsonPath("$.data.reviewedBy").value("1"));

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from after_sale_request
                        where id = :afterSaleId
                          and status = 'REJECTED'
                          and audit_note = '凭证不足，暂不退款'
                          and reviewed_by is not null
                          and reviewed_at is not null
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAID");
    }

    @Test
    void adminApproveCreatesRefundOrderCallsMockProviderAndMovesOrderToRefunding() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-approve-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-approve");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.approvedAmountCent").value(6980))
                .andExpect(jsonPath("$.data.refundOrder.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.refundOrder.outRefundNo").isNotEmpty());

        String outRefundNo = jdbcClient.sql("select out_refund_no from refund_order where after_sale_id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single();
        assertThat(outRefundNo).hasSizeLessThanOrEqualTo(64).startsWith("RF");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where after_sale_id = :afterSaleId
                          and order_id = :orderId
                          and out_refund_no = :outRefundNo
                          and refund_id = :refundId
                          and refund_amount_cent = 6980
                          and status = 'PROCESSING'
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", order.orderId())
                .param("outRefundNo", outRefundNo)
                .param("refundId", "mock-refund-" + outRefundNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
    }

    @Test
    void adminCannotChangeTheFullOrderRefundAmount() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-full-amount-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-full-amount");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();
        clearInvocations(refundProvider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6979,"auditNote":"尝试修改退款金额"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertThat(refundOrders(afterSaleId)).isEmpty();
        verify(refundProvider, never()).requestRefund(any(), any());
    }

    @Test
    void adminApproveUsesStableOutRefundNoAndDoesNotCallProviderAgainOnRetry() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-approve-retry-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-approve-retry");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        long paymentOrderId = paymentOrderId(order.orderId());
        String expectedOutRefundNo = expectedOutRefundNo(afterSaleId, order.orderId(), paymentOrderId);
        String adminToken = adminLogin();
        clearInvocations(refundProvider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"));

        List<RefundOrderSnapshot> rows = refundOrders(afterSaleId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outRefundNo()).isEqualTo(expectedOutRefundNo);
        ArgumentCaptor<WechatRefundRequest> refundRequestCaptor = ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(refundProvider, times(1)).requestRefund(any(), refundRequestCaptor.capture());
        assertThat(refundRequestCaptor.getValue().outRefundNo()).isEqualTo(expectedOutRefundNo);

        clearInvocations(refundProvider);
        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(refundOrders(afterSaleId))
                .hasSize(1)
                .first()
                .extracting(RefundOrderSnapshot::outRefundNo)
                .isEqualTo(expectedOutRefundNo);
        verify(refundProvider, never()).requestRefund(any(), any());
    }

    @Test
    void adminApproveProviderFailureKeepsLocalRefundOrderAndSanitizesErrorBeforeRetry() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-approve-provider-failure-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-approve-provider-failure");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        long paymentOrderId = paymentOrderId(order.orderId());
        String expectedOutRefundNo = expectedOutRefundNo(afterSaleId, order.orderId(), paymentOrderId);
        String adminToken = adminLogin();
        String sensitiveProviderMessage = "synthetic-provider-sensitive-detail";
        doThrow(new RuntimeException(sensitiveProviderMessage))
                .when(refundProvider)
                .requestRefund(any(), any());
        clearInvocations(refundProvider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(700001))
                .andExpect(jsonPath("$.msg").value("WeChat refund failed"));

        List<RefundOrderSnapshot> rows = refundOrders(afterSaleId);
        assertThat(rows).hasSize(1);
        RefundOrderSnapshot refundOrder = rows.get(0);
        assertThat(refundOrder.outRefundNo()).isEqualTo(expectedOutRefundNo).hasSizeLessThanOrEqualTo(64);
        assertThat(refundOrder.status()).isEqualTo("FAILED");
        assertThat(refundOrder.lastErrorCode()).isEqualTo("WECHAT_REFUND_FAILED");
        assertThat(refundOrder.lastErrorMessage())
                .isEqualTo("WeChat refund failed")
                .doesNotContain(sensitiveProviderMessage);
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REFUND_FAILED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("PAID");
        verify(refundProvider, times(1)).requestRefund(any(), any());

        clearInvocations(refundProvider);
        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(refundOrders(afterSaleId))
                .hasSize(1)
                .first()
                .extracting(RefundOrderSnapshot::outRefundNo)
                .isEqualTo(expectedOutRefundNo);
        verify(refundProvider, never()).requestRefund(any(), any());
    }

    @Test
    void completedOrderAfterSaleCanBeApprovedIntoExistingRefundFlow() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-completed-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "COMPLETED", "wx-refund-admin-completed");
        jdbcClient.sql("""
                        update shop_order
                        set shipped_at = timestamp '2026-07-08 14:00:00',
                            completed_at = timestamp '2026-07-09 09:00:00'
                        where id = :orderId
                        """)
                .param("orderId", order.orderId())
                .update();
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"确认收货后仍同意退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.refundOrder.status").value("PROCESSING"));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
    }

    private long applyAfterSale(AppLoginSession appUser, SeedPaidOrder order, long requestedAmountCent) throws Exception {
        long evidenceFileId = insertAppEvidenceFile(appUser.userId(), order.orderId());
        String response = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + appUser.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "申请退款", requestedAmountCent, "admin test", evidenceFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
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

    private long paymentOrderId(long orderId) {
        return jdbcClient.sql("select id from payment_order where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private void insertRefundOrder(long afterSaleId, long orderId, String outRefundNo) {
        jdbcClient.sql("""
                        insert into refund_order
                            (after_sale_id, order_id, payment_order_id, out_refund_no, refund_id,
                             refund_amount_cent, status, callback_status, requested_at)
                        values
                            (:afterSaleId, :orderId, :paymentOrderId, :outRefundNo, '',
                             100, 'PROCESSING', 'PROCESSING', current_timestamp)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", orderId)
                .param("paymentOrderId", paymentOrderId(orderId))
                .param("outRefundNo", outRefundNo)
                .update();
    }

    private long firstEvidenceFileId(long afterSaleId) {
        return jdbcClient.sql("""
                        select file_id
                        from after_sale_evidence
                        where after_sale_id = :afterSaleId
                        order by sort_order asc, id asc
                        limit 1
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Long.class)
                .single();
    }

    private String expectedOutRefundNo(long afterSaleId, long orderId, long paymentOrderId) {
        return "RF" + afterSaleId + "O" + orderId + "P" + paymentOrderId;
    }

    private List<RefundOrderSnapshot> refundOrders(long afterSaleId) {
        return jdbcClient.sql("""
                        select out_refund_no, status, last_error_code, last_error_message
                        from refund_order
                        where after_sale_id = :afterSaleId
                        order by id
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new RefundOrderSnapshot(
                        rs.getString("out_refund_no"),
                        rs.getString("status"),
                        rs.getString("last_error_code"),
                        rs.getString("last_error_message")
                ))
                .list();
    }

    private record RefundOrderSnapshot(
            String outRefundNo,
            String status,
            String lastErrorCode,
            String lastErrorMessage
    ) {
    }
}
