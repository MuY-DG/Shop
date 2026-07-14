package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppAfterSaleControllerTest extends PaymentTestSupport {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4x8AAAAASUVORK5CYII="
    );

    @Test
    void appUploadsOrderScopedPrivateEvidenceThroughTheAfterSaleEndpoint() throws Exception {
        AppLoginSession session = appLogin("after-sale-evidence-upload");
        SeedPaidOrder order = seedPaidOrder(session, 6980L, "PAID", "wx-evidence-upload");

        String response = mockMvc.perform(multipart(
                                "/app/orders/{orderId}/after-sale-evidence",
                                order.orderId())
                        .file(new MockMultipartFile("file", "evidence.png", "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("ATTACHMENT"))
                .andExpect(jsonPath("$.data.mediaKind").value("IMAGE"))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.uploadedByType").value("APP"))
                .andExpect(jsonPath("$.data.url").doesNotExist())
                .andExpect(jsonPath("$.data.publicUrl").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long assetId = objectMapper.readTree(response).path("data").path("id").asLong();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_asset
                        where id = :assetId
                          and scope = 'ATTACHMENT'
                          and media_kind = 'IMAGE'
                          and visibility = 'PRIVATE'
                          and upload_context_type = 'ORDER'
                          and upload_context_id = :orderId
                          and expires_at > current_timestamp
                          and public_url is null
                        """)
                .param("assetId", assetId)
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void appUserCanApplyRefundOnlyForOwnPaidOrderWithPrivateEvidenceAndReadItBack() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("after-sale-app-owner");
        SeedPaidOrder order = seedPaidOrder(session, 6980L, "PAID", "wx-refund-app-paid");
        long evidenceFileId = insertAppEvidenceFile(session.userId(), order.orderId());

        String response = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "商品未发货想退款", 3980L, "请退部分金额", evidenceFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value(order.orderId()))
                .andExpect(jsonPath("$.data.afterSaleType").value("REFUND_ONLY"))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.requestedAmountCent").value(3980))
                .andExpect(jsonPath("$.data.evidenceFileIds[0]").value(evidenceFileId))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(response).path("data").path("id").asLong();

        mockMvc.perform(get("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(afterSaleId))
                .andExpect(jsonPath("$.data[0].status").value("REQUESTED"));

        mockMvc.perform(get("/app/after-sales/{afterSaleId}", afterSaleId)
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(afterSaleId))
                .andExpect(jsonPath("$.data.orderNo").value(order.orderNo()));

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from after_sale_evidence ase
                        join storage_asset_usage sau on sau.asset_id = ase.file_id
                        where ase.after_sale_id = :afterSaleId
                          and ase.file_id = :fileId
                          and sau.usage_type = 'AFTER_SALE_EVIDENCE'
                          and sau.owner_type = 'AFTER_SALE'
                          and sau.owner_id = :afterSaleId
                          and sau.protected = true
                          and sau.status = 'ACTIVE'
                        """)
                .param("afterSaleId", afterSaleId)
                .param("fileId", evidenceFileId)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void appApplyRejectsWrongOwnerInvalidOrderStateInvalidAmountInvalidEvidenceAndDuplicateActiveRequest() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession owner = appLogin("after-sale-app-owner-rules");
        AppLoginSession other = appLogin("after-sale-app-other-rules");
        SeedPaidOrder paidOrder = seedPaidOrder(owner, 6980L, "PAID", "wx-refund-app-rules");
        SeedOrder createdOrder = seedCreatedOrder(owner.userId(), 6980L, false);
        long ownerEvidenceFileId = insertAppEvidenceFile(owner.userId(), paidOrder.orderId());
        long otherEvidenceFileId = insertStorageAsset(
                other.userId(), "ATTACHMENT", "IMAGE", "PRIVATE", "ACTIVE", paidOrder.orderId());
        long publicEvidenceFileId = insertStorageAsset(
                owner.userId(), "LIBRARY", "IMAGE", "PUBLIC", "ACTIVE", null);
        long deletedEvidenceFileId = insertStorageAsset(
                owner.userId(), "ATTACHMENT", "IMAGE", "PRIVATE", "DELETED", paidOrder.orderId());
        long wrongMediaFileId = insertStorageAsset(
                owner.userId(), "ATTACHMENT", "DOCUMENT", "PRIVATE", "ACTIVE", paidOrder.orderId());

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "他人订单", 100L, "not owner", ownerEvidenceFileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", createdOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "未支付订单", 100L, "created order", ownerEvidenceFileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "金额为零", 0L, "zero amount", ownerEvidenceFileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "金额超限", 6981L, "too much", ownerEvidenceFileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        for (long invalidFileId : new long[]{otherEvidenceFileId, publicEvidenceFileId, deletedEvidenceFileId, wrongMediaFileId}) {
            mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                            .header("Authorization", "Bearer " + owner.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(applyBody("RETURN_REFUND", "凭证不合法", 100L, "invalid evidence", invalidFileId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(800001));
        }

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("RETURN_REFUND", "需要退货退款", 100L, "valid request", ownerEvidenceFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "重复申请", 100L, "duplicate active", ownerEvidenceFileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    void appCanApplyReturnRefundForOwnShippedOrder() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("after-sale-app-shipped");
        SeedPaidOrder order = seedPaidOrder(session, 8980L, "SHIPPED", "wx-refund-app-shipped");
        long evidenceFileId = insertAppEvidenceFile(session.userId(), order.orderId());

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("RETURN_REFUND", "已发货退货退款", 8980L, "need return", evidenceFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSaleType").value("RETURN_REFUND"))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @Test
    void completedOrderCanApplyAndCurrentUserPageAndDetailAreOwned() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession owner = appLogin("after-sale-app-completed-owner");
        AppLoginSession other = appLogin("after-sale-app-completed-other");
        SeedPaidOrder completedOrder = seedPaidOrder(owner, 9980L, "COMPLETED", "wx-completed-owner");
        SeedPaidOrder otherOrder = seedPaidOrder(other, 7980L, "PAID", "wx-page-other");
        jdbcClient.sql("""
                        update shop_order
                        set shipped_at = timestamp '2026-07-08 14:00:00',
                            completed_at = timestamp '2026-07-09 09:00:00'
                        where id = :orderId
                        """)
                .param("orderId", completedOrder.orderId())
                .update();
        long ownerFileId = insertAppEvidenceFile(owner.userId(), completedOrder.orderId());
        long otherFileId = insertAppEvidenceFile(other.userId(), otherOrder.orderId());

        String ownerResponse = mockMvc.perform(post("/app/orders/{orderId}/after-sales", completedOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("RETURN_REFUND", "已确认收货仍需售后", 9980L,
                                "completed order protection", ownerFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        long ownerAfterSaleId = objectMapper.readTree(ownerResponse).path("data").path("id").asLong();

        String otherResponse = mockMvc.perform(post("/app/orders/{orderId}/after-sales", otherOrder.orderId())
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "他人售后", 100L, "other record", otherFileId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long otherAfterSaleId = objectMapper.readTree(otherResponse).path("data").path("id").asLong();

        mockMvc.perform(get("/app/after-sales")
                        .param("current", "1")
                        .param("size", "1")
                        .param("status", "REQUESTED")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(ownerAfterSaleId))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(1));

        mockMvc.perform(get("/app/after-sales/{afterSaleId}", otherAfterSaleId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
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
