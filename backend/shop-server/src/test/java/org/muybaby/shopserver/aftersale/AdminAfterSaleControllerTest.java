package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.aftersale.service.RefundRecoveryService;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
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

    @Autowired
    private RefundRecoveryService refundRecoveryService;

    @Test
    void adminListAndDetailReturnPagedEnvelopeAndRequireReadAuthority() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-list-app");
        jdbcClient.sql("update app_user set nickname = '售后详情用户' where id = :userId")
                .param("userId", appUser.userId())
                .update();
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-list");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String afterSaleNo = jdbcClient.sql(
                        "select after_sale_no from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single();
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
                .andExpect(jsonPath("$.data.records[0].afterSaleNo").value(afterSaleNo))
                .andExpect(jsonPath("$.data.records[0].orderId").value(order.orderId()))
                .andExpect(jsonPath("$.data.records[0].userId").isString())
                .andExpect(jsonPath("$.data.records[0].userId").value(Long.toString(appUser.userId())))
                .andExpect(jsonPath("$.data.records[0].userNickname").value("售后详情用户"))
                .andExpect(jsonPath("$.data.records[0].requestedAmountCent").isNumber())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10));

        mockMvc.perform(get("/admin/after-sales")
                        .param("afterSaleNo", afterSaleNo)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].afterSaleNo").value(afterSaleNo));

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}", afterSaleId)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(afterSaleId))
                .andExpect(jsonPath("$.data.afterSaleNo").value(afterSaleNo))
                .andExpect(jsonPath("$.data.orderNo").value(order.orderNo()))
                .andExpect(jsonPath("$.data.userNickname").value("售后详情用户"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].fileId").value(evidenceFileId))
                .andExpect(jsonPath("$.data.evidenceFiles[0].originalFilename").value("after-sale-" + evidenceFileId + ".png"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].sizeBytes").value(68))
                .andExpect(jsonPath("$.data.evidenceFiles[0].scope").value("ATTACHMENT"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].mediaKind").value("IMAGE"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].accessMode").value("AUTHENTICATED_BLOB"))
                .andExpect(jsonPath("$.data.evidenceFiles[0].accessUrl").doesNotExist())
                .andExpect(jsonPath("$.data.orderContext.orderId").value(order.orderId()))
                .andExpect(jsonPath("$.data.orderContext.orderNo").value(order.orderNo()))
                .andExpect(jsonPath("$.data.orderContext.receiverName").value("Pay User"))
                .andExpect(jsonPath("$.data.orderContext.receiverPhone").value("13800000000"))
                .andExpect(jsonPath("$.data.orderContext.receiverAddress").value("Pay Test Address"))
                .andExpect(jsonPath("$.data.orderContext.productAmountCent").value(6980))
                .andExpect(jsonPath("$.data.orderContext.paidAmountCent").value(6980))
                .andExpect(jsonPath("$.data.orderContext.itemCount").value(2))
                .andExpect(jsonPath("$.data.orderContext.items[0].productTitle").value("Payment Item"))
                .andExpect(jsonPath("$.data.orderContext.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.orderContext.items[0].specText").value("300g"));

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}/records", afterSaleId)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].afterSaleId").value(afterSaleId))
                .andExpect(jsonPath("$.data[0].orderId").value(order.orderId()))
                .andExpect(jsonPath("$.data[0].eventType").value("AFTER_SALE_REQUESTED"))
                .andExpect(jsonPath("$.data[0].description").value("用户申请售后"));

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}/records", afterSaleId)
                        .header("Authorization", "Bearer " + auditOnlyToken))
                .andExpect(status().isForbidden());
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
                        .param("createdStart", "2026-07-11T00:00:00Z")
                        .param("createdEnd", "2026-07-11T23:59:59Z")
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
    void adminRejectLogsEachReviewAndAllowsReapplication() throws Exception {
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
                .andExpect(jsonPath("$.data.reviewedBy").isNumber())
                .andExpect(jsonPath("$.data.reviewedBy").value(1));

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

        long secondAfterSaleId = applyAfterSale(appUser, order, 6980L);
        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/reject", secondAfterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auditNote":"再次审核仍不符合退款条件"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        List<String> afterSaleEvents = jdbcClient.sql("""
                        select event_type
                        from order_status_log
                        where order_id = :orderId
                          and event_type in ('AFTER_SALE_REQUESTED', 'AFTER_SALE_REJECTED')
                        order by created_at, id
                        """)
                .param("orderId", order.orderId())
                .query(String.class)
                .list();
        assertThat(afterSaleEvents).containsExactly(
                "AFTER_SALE_REQUESTED",
                "AFTER_SALE_REJECTED",
                "AFTER_SALE_REQUESTED",
                "AFTER_SALE_REJECTED"
        );

        String readToken = limitedAdminToken(List.of("aftersale:read"));
        mockMvc.perform(get("/admin/after-sales/{afterSaleId}/records", afterSaleId)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].afterSaleId").value(afterSaleId))
                .andExpect(jsonPath("$.data[0].eventType").value("AFTER_SALE_REQUESTED"))
                .andExpect(jsonPath("$.data[1].afterSaleId").value(afterSaleId))
                .andExpect(jsonPath("$.data[1].eventType").value("AFTER_SALE_REJECTED"));

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}/records", secondAfterSaleId)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].afterSaleId").value(secondAfterSaleId))
                .andExpect(jsonPath("$.data[0].eventType").value("AFTER_SALE_REQUESTED"))
                .andExpect(jsonPath("$.data[1].afterSaleId").value(secondAfterSaleId))
                .andExpect(jsonPath("$.data[1].eventType").value("AFTER_SALE_REJECTED"));
    }

    @Test
    void adminApproveCreatesRefundOrderCallsMockProviderAndMovesOrderToRefunding() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-approve-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-approve");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        switchToClonedPaymentConfig(91002L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980}
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
        assertThat(outRefundNo)
                .matches("^RF\\d{14}[0-9A-Z]{14}$")
                .hasSizeLessThanOrEqualTo(64);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where after_sale_id = :afterSaleId
                          and order_id = :orderId
                          and out_refund_no = :outRefundNo
                          and refund_id = :refundId
                          and provider_reason = ''
                          and refund_amount_cent = 6980
                          and status = 'PROCESSING'
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", order.orderId())
                .param("outRefundNo", outRefundNo)
                .param("refundId", "mock-refund-" + outRefundNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from after_sale_request
                        where id = :afterSaleId
                          and status = 'REFUNDING'
                          and audit_note = ''
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        ArgumentCaptor<ResolvedPaymentConfig> configCaptor = ArgumentCaptor.forClass(ResolvedPaymentConfig.class);
        ArgumentCaptor<WechatRefundRequest> requestCaptor = ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(refundProvider, times(1)).requestRefund(configCaptor.capture(), requestCaptor.capture());
        assertThat(configCaptor.getValue().configId()).isEqualTo(91001L);
        assertThat(requestCaptor.getValue().reason()).isEmpty();
    }

    @Test
    void adminApprovePassesAnOptionalRefundReasonToWechat() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-approve-reason-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-approve-reason");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"商品缺货"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.auditNote").value("商品缺货"));

        assertThat(jdbcClient.sql("""
                        select provider_reason
                        from refund_order
                        where after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("商品缺货");
        ArgumentCaptor<WechatRefundRequest> requestCaptor = ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(refundProvider).requestRefund(any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().reason()).isEqualTo("商品缺货");
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
        String expectedOutRefundNo = rows.get(0).outRefundNo();
        assertThat(expectedOutRefundNo).matches("^RF\\d{14}[0-9A-Z]{14}$");
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
    void adminApproveIndeterminateProviderFailureKeepsRefundPendingForRecovery() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-approve-provider-failure-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-approve-provider-failure");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();
        String sensitiveProviderMessage = "synthetic-provider-sensitive-detail";
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new RuntimeException(sensitiveProviderMessage);
        })
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
        String expectedOutRefundNo = refundOrder.outRefundNo();
        assertThat(expectedOutRefundNo).matches("^RF\\d{14}[0-9A-Z]{14}$");
        assertThat(refundOrder.outRefundNo()).isEqualTo(expectedOutRefundNo).hasSizeLessThanOrEqualTo(64);
        assertThat(refundOrder.status()).isEqualTo("PROCESSING");
        assertThat(refundOrder.lastErrorCode()).isEqualTo("RuntimeException");
        assertThat(refundOrder.lastErrorMessage())
                .isEqualTo("Refund request result is unknown; provider query scheduled")
                .doesNotContain(sensitiveProviderMessage);
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where after_sale_id = :afterSaleId
                          and callback_status = 'REQUEST_UNKNOWN'
                          and next_recovery_at is not null
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Integer.class)
                .single()).isEqualTo(1);
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

        LocalDateTime successAt = LocalDateTime.of(2026, 7, 19, 12, 0);
        refundProvider.markRefundStatus(expectedOutRefundNo, "SUCCESS", successAt);
        jdbcClient.sql("""
                        update refund_order
                        set updated_at = :updatedAt,
                            next_recovery_at = null
                        where out_refund_no = :outRefundNo
                        """)
                .param("updatedAt", LocalDateTime.now().minusMinutes(5))
                .param("outRefundNo", expectedOutRefundNo)
                .update();

        assertThat(refundRecoveryService.recoverPendingRefunds(1)).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", expectedOutRefundNo)
                .query(String.class)
                .single()).isEqualTo("SUCCESS");
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDED");
    }

    @Test
    void adminCanRetryClosedRefundWithNewMerchantRefundNumberAndOriginalPaymentConfig() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-closed-retry-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-closed-retry");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"原始退款原因"}
                                """))
                .andExpect(status().isOk());
        String originalOutRefundNo = refundOrders(afterSaleId).getFirst().outRefundNo();
        markLatestRefundTerminal(afterSaleId, "CLOSED");
        jdbcClient.sql("""
                        update refund_order
                        set recovery_claim_token = 'expired-closed-retry-claim',
                            recovery_claimed_at = :claimedAt
                        where after_sale_id = :afterSaleId
                        """)
                .param("claimedAt", LocalDateTime.now().minusMinutes(10))
                .param("afterSaleId", afterSaleId)
                .update();
        switchToClonedPaymentConfig(91002L);
        clearInvocations(refundProvider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/refund-retry", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"商户余额已补足并核对原退款已关闭"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.refundOrder.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.refundOrder.callbackStatus").value("PROCESSING"));

        List<RefundOrderSnapshot> rows = refundOrders(afterSaleId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).outRefundNo()).isEqualTo(originalOutRefundNo);
        assertThat(rows.get(0).status()).isEqualTo("FAILED");
        assertThat(rows.get(0).callbackStatus()).isEqualTo("CLOSED");
        assertThat(rows.get(1).outRefundNo())
                .isNotEqualTo(originalOutRefundNo)
                .matches("^RF\\d{14}[0-9A-Z]{14}$")
                .hasSizeLessThanOrEqualTo(64);
        assertThat(rows.get(1).status()).isEqualTo("PROCESSING");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where after_sale_id = :afterSaleId
                          and restock_required = true
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Integer.class)
                .single()).isEqualTo(2);

        ArgumentCaptor<ResolvedPaymentConfig> configCaptor = ArgumentCaptor.forClass(ResolvedPaymentConfig.class);
        ArgumentCaptor<WechatRefundRequest> requestCaptor = ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(refundProvider, times(1)).requestRefund(configCaptor.capture(), requestCaptor.capture());
        assertThat(configCaptor.getValue().configId()).isEqualTo(91001L);
        assertThat(requestCaptor.getValue().outRefundNo()).isEqualTo(rows.get(1).outRefundNo());
        assertThat(requestCaptor.getValue().refundAmountCent()).isEqualTo(6980L);
        assertThat(requestCaptor.getValue().reason()).isEqualTo("原始退款原因");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_status_log
                        where order_id = :orderId
                          and event_type = 'REFUND_RETRIED'
                          and operator_type = 'ADMIN'
                          and operator_id is not null
                        """)
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void adminRefundRetryRejectsNonFailedAndAbnormalRefunds() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-retry-reject-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-retry-reject");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk());
        clearInvocations(refundProvider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/refund-retry", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"测试非失败状态不可重试"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        assertThat(refundOrders(afterSaleId)).hasSize(1);

        markLatestRefundTerminal(afterSaleId, "ABNORMAL");
        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/refund-retry", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"ABNORMAL 应通过渠道核查而不是新单重试"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(refundOrders(afterSaleId)).hasSize(1);
        verify(refundProvider, never()).requestRefund(any(), any());
    }

    @Test
    void closedRefundPreservesTerminalEvidenceAndRejectsConflictingOperations() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-closed-exclusive-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-closed-exclusive");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk());
        long refundOrderId = latestRefundOrderId(afterSaleId);
        markLatestRefundTerminal(afterSaleId, "CLOSED");
        clearInvocations(refundProvider);

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/provider-resubmit",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"不得复用已关闭的商户退款单号"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/manual-intervention",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"不得覆盖渠道 CLOSED 终态"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        jdbcClient.sql("""
                        update refund_order
                        set recovery_claim_token = 'active-recovery-claim',
                            recovery_claimed_at = current_timestamp
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", refundOrderId)
                .update();
        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/refund-retry", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"活动恢复任务期间不得新单重试"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        List<RefundOrderSnapshot> rows = refundOrders(afterSaleId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().status()).isEqualTo("FAILED");
        assertThat(rows.getFirst().callbackStatus()).isEqualTo("CLOSED");
        verify(refundProvider, never()).queryRefund(any(), any());
        verify(refundProvider, never()).requestRefund(any(), any());
    }

    @Test
    void adminProviderQueryFinalizesAbnormalRefundAndWritesOperatorAudit() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-provider-query-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-provider-query");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk());
        long refundOrderId = latestRefundOrderId(afterSaleId);
        String outRefundNo = refundOrders(afterSaleId).getFirst().outRefundNo();
        markLatestRefundTerminal(afterSaleId, "ABNORMAL");
        refundProvider.markRefundStatus(
                outRefundNo, "SUCCESS", LocalDateTime.of(2026, 7, 19, 15, 0));
        clearInvocations(refundProvider);

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/provider-query",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"用户反馈到账异常，立即核验渠道状态"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("PROVIDER_QUERY"))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.providerStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.resubmitted").value(false))
                .andExpect(jsonPath("$.data.afterSale.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.callbackStatus").value("SUCCESS"));

        verify(refundProvider, times(1)).queryRefund(any(), any());
        verify(refundProvider, never()).requestRefund(any(), any());
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_status_log
                        where order_id = :orderId
                          and event_type in ('REFUND_QUERY_REQUESTED', 'REFUND_QUERY_COMPLETED')
                          and operator_type = 'ADMIN'
                          and operator_id is not null
                        """)
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(2);

        clearInvocations(refundProvider);
        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/provider-query",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"HTTP 重试应幂等返回，不再访问渠道"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.status").value("SUCCESS"));
        verify(refundProvider, never()).queryRefund(any(), any());

    }

    @Test
    void adminProviderResubmitQueriesFirstAndReusesOriginalMerchantRefundNumber() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-provider-resubmit-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-provider-resubmit");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk());
        long refundOrderId = latestRefundOrderId(afterSaleId);
        String outRefundNo = refundOrders(afterSaleId).getFirst().outRefundNo();
        refundProvider.forgetRefund(outRefundNo);
        clearInvocations(refundProvider);

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/provider-query",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
        verify(refundProvider, never()).queryRefund(any(), any());

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/provider-resubmit",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"原退款请求结果不确定，安全重提"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("PROVIDER_RESUBMIT"))
                .andExpect(jsonPath("$.data.result").value("PROCESSING"))
                .andExpect(jsonPath("$.data.resubmitted").value(true))
                .andExpect(jsonPath("$.data.afterSale.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.outRefundNo").value(outRefundNo))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.status").value("PROCESSING"));

        ArgumentCaptor<WechatRefundRequest> firstRequest = ArgumentCaptor.forClass(WechatRefundRequest.class);
        verify(refundProvider, times(1)).queryRefund(any(), any());
        verify(refundProvider, times(1)).requestRefund(any(), firstRequest.capture());
        assertThat(firstRequest.getValue().outRefundNo()).isEqualTo(outRefundNo);

        clearInvocations(refundProvider);
        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/provider-resubmit",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"重复点击仍应先查询，不能重复创建退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resubmitted").value(false))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.outRefundNo").value(outRefundNo));
        verify(refundProvider, times(1)).queryRefund(any(), any());
        verify(refundProvider, never()).requestRefund(any(), any());
    }

    @Test
    void adminProviderQueryDoesNotSubmitWhenProviderReportsNotFound() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-query-not-found-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-query-not-found");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk());
        long refundOrderId = latestRefundOrderId(afterSaleId);
        String outRefundNo = refundOrders(afterSaleId).getFirst().outRefundNo();
        refundProvider.forgetRefund(outRefundNo);
        clearInvocations(refundProvider);

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/provider-query",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"只查询，不授权发起退款请求"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data.providerStatus").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data.resubmitted").value(false))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.status").value("PROCESSING"));

        verify(refundProvider, times(1)).queryRefund(any(), any());
        verify(refundProvider, never()).requestRefund(any(), any());
    }

    @Test
    void adminCanPauseAutomaticRecoveryForManualInterventionWithoutForgingSuccess() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-manual-intervention-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-manual-intervention");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk());
        long refundOrderId = latestRefundOrderId(afterSaleId);
        String readOnlyToken = limitedAdminToken(List.of("aftersale:read"));

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/manual-intervention",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + readOnlyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"无权限操作"}
                                """))
                .andExpect(status().isForbidden());

        jdbcClient.sql("""
                        update refund_order
                        set recovery_claim_token = 'expired-manual-claim',
                            recovery_claimed_at = :claimedAt
                        where id = :refundOrderId
                        """)
                .param("claimedAt", LocalDateTime.now().minusMinutes(10))
                .param("refundOrderId", refundOrderId)
                .update();

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/manual-intervention",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"需要联系微信支付人工核查"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("MANUAL_INTERVENTION"))
                .andExpect(jsonPath("$.data.result").value("MANUAL_INTERVENTION"))
                .andExpect(jsonPath("$.data.afterSale.status").value("REFUND_FAILED"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.status").value("FAILED"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.callbackStatus").value("MANUAL_INTERVENTION"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.lastErrorCode").value("MANUAL_INTERVENTION"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.lastErrorMessage")
                        .value("Refund requires manual intervention"));

        LocalDateTime firstFailedAt = jdbcClient.sql("""
                        select failed_at
                        from refund_order
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", refundOrderId)
                .query(LocalDateTime.class)
                .single();

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDING");
        assertThat(refundRecoveryService.recoverPendingRefunds(1)).isZero();

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/manual-intervention",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"重复操作保持人工介入状态"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSale.refundOrder.status").value("FAILED"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.callbackStatus").value("MANUAL_INTERVENTION"));

        assertThat(jdbcClient.sql("select failed_at from refund_order where id = :refundOrderId")
                .param("refundOrderId", refundOrderId)
                .query(LocalDateTime.class)
                .single()).isEqualTo(firstFailedAt);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_status_log
                        where order_id = :orderId
                          and event_type = 'REFUND_MANUAL_INTERVENTION'
                          and operator_type = 'ADMIN'
                          and operator_id is not null
                        """)
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(2);

        mockMvc.perform(post(
                        "/admin/after-sales/{afterSaleId}/refunds/{refundOrderId}/provider-query",
                        afterSaleId, refundOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"人工核查完成，重新以渠道状态为准"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PROCESSING"))
                .andExpect(jsonPath("$.data.afterSale.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.afterSale.refundOrder.callbackStatus").value("PROCESSING"));
    }

    @Test
    void adminClosedRefundRetryProviderFailureRemainsRecoverable() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-closed-retry-failure-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-closed-retry-failure");
        long afterSaleId = applyAfterSale(appUser, order, 6980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":6980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk());
        markLatestRefundTerminal(afterSaleId, "CLOSED");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new RuntimeException("sensitive-retry-provider-detail");
        }).when(refundProvider).requestRefund(any(), any());
        clearInvocations(refundProvider);

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/refund-retry", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"故障后以新商户退款单号恢复"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(700001));

        List<RefundOrderSnapshot> rows = refundOrders(afterSaleId);
        assertThat(rows).hasSize(2);
        RefundOrderSnapshot retry = rows.get(1);
        assertThat(retry.status()).isEqualTo("PROCESSING");
        assertThat(retry.callbackStatus()).isEqualTo("REQUEST_UNKNOWN");
        assertThat(retry.lastErrorCode()).isEqualTo("RuntimeException");
        assertThat(retry.lastErrorMessage())
                .isEqualTo("Refund request result is unknown; provider query scheduled")
                .doesNotContain("sensitive-retry-provider-detail");
        assertThat(retry.nextRecoveryAt()).isNotNull();
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REFUNDING");

        LocalDateTime successAt = LocalDateTime.of(2026, 7, 19, 13, 0);
        refundProvider.markRefundStatus(retry.outRefundNo(), "SUCCESS", successAt);
        jdbcClient.sql("""
                        update refund_order
                        set updated_at = :updatedAt,
                            next_recovery_at = null
                        where out_refund_no = :outRefundNo
                        """)
                .param("updatedAt", LocalDateTime.now().minusMinutes(5))
                .param("outRefundNo", retry.outRefundNo())
                .update();

        assertThat(refundRecoveryService.recoverPendingRefunds(1)).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", retry.outRefundNo())
                .query(String.class)
                .single()).isEqualTo("SUCCESS");
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("REFUNDED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("REFUNDED");
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

    private long latestRefundOrderId(long afterSaleId) {
        return jdbcClient.sql("""
                        select id
                        from refund_order
                        where after_sale_id = :afterSaleId
                        order by id desc
                        limit 1
                        """)
                .param("afterSaleId", afterSaleId)
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

    private void markLatestRefundTerminal(long afterSaleId, String callbackStatus) {
        jdbcClient.sql("""
                        update refund_order
                        set status = 'FAILED',
                            failed_at = current_timestamp,
                            callback_status = :callbackStatus,
                            last_error_code = :callbackStatus,
                            last_error_message = concat('refund provider status ', :callbackStatus),
                            updated_at = current_timestamp
                        where after_sale_id = :afterSaleId
                        """)
                .param("callbackStatus", callbackStatus)
                .param("afterSaleId", afterSaleId)
                .update();
        jdbcClient.sql("""
                        update after_sale_request
                        set status = 'REFUND_FAILED',
                            updated_at = current_timestamp
                        where id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .update();
    }

    private List<RefundOrderSnapshot> refundOrders(long afterSaleId) {
        return jdbcClient.sql("""
                        select out_refund_no,
                               status,
                               callback_status,
                               last_error_code,
                               last_error_message,
                               next_recovery_at
                        from refund_order
                        where after_sale_id = :afterSaleId
                        order by id
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new RefundOrderSnapshot(
                        rs.getString("out_refund_no"),
                        rs.getString("status"),
                        rs.getString("callback_status"),
                        rs.getString("last_error_code"),
                        rs.getString("last_error_message"),
                        rs.getObject("next_recovery_at", LocalDateTime.class)
                ))
                .list();
    }

    private record RefundOrderSnapshot(
            String outRefundNo,
            String status,
            String callbackStatus,
            String lastErrorCode,
            String lastErrorMessage,
            LocalDateTime nextRecoveryAt
    ) {
    }
}
