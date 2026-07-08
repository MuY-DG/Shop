package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminAfterSaleControllerTest extends PaymentTestSupport {

    @Test
    void adminListAndDetailReturnPagedEnvelopeAndRequireReadAuthority() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-list-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-list");
        long afterSaleId = applyAfterSale(appUser, order, 3980L);
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
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10));

        mockMvc.perform(get("/admin/after-sales/{afterSaleId}", afterSaleId)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(afterSaleId))
                .andExpect(jsonPath("$.data.orderNo").value(order.orderNo()));
    }

    @Test
    void adminRejectSetsAuditFieldsAndLeavesOrderPaid() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession appUser = appLogin("after-sale-admin-reject-app");
        SeedPaidOrder order = seedPaidOrder(appUser, 6980L, "PAID", "wx-refund-admin-reject");
        long afterSaleId = applyAfterSale(appUser, order, 3980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/reject", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auditNote":"凭证不足，暂不退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.auditNote").value("凭证不足，暂不退款"));

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
        long afterSaleId = applyAfterSale(appUser, order, 3980L);
        String adminToken = adminLogin();

        mockMvc.perform(post("/admin/after-sales/{afterSaleId}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvedAmountCent":3980,"auditNote":"同意退款"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.approvedAmountCent").value(3980))
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
                          and refund_amount_cent = 3980
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

    private long applyAfterSale(AppLoginSession appUser, SeedPaidOrder order, long requestedAmountCent) throws Exception {
        long evidenceFileId = insertAppEvidenceFile(appUser.userId(), "AFTER_SALE_IMAGE");
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
}
