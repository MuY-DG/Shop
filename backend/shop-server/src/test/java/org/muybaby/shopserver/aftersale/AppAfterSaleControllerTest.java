package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    void unshippedOrderOffersRefundOnlyAndRejectsReturnQuoteAndApplication() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("return-before-shipment");
        SeedPaidOrder order = seedPaidOrder(session, 2000L, "PAID", "wx-return-before-shipment");
        long itemId = jdbcClient.sql("select id from order_item where order_id = :id")
                .param("id", order.orderId()).query(Long.class).single();

        mockMvc.perform(get("/app/orders/{orderId}/after-sales/eligibility", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableTypes.length()").value(1))
                .andExpect(jsonPath("$.data.availableTypes[0]").value("REFUND_ONLY"))
                .andExpect(jsonPath("$.data.items[0].returnableQuantity").value(0));
        mockMvc.perform(post("/app/orders/{orderId}/after-sales/quote", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"afterSaleType":"RETURN_REFUND","items":[{"orderItemId":%d,"quantity":1}]}
                                """.formatted(itemId)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"afterSaleType\":\"RETURN_REFUND\",\"reason\":\"退货退款\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcClient.sql("select count(*) from after_sale_request where order_id = :id")
                .param("id", order.orderId()).query(Integer.class).single()).isZero();

        jdbcClient.sql("update shop_order set status = 'PARTIALLY_SHIPPED' where id = :id")
                .param("id", order.orderId()).update();
        jdbcClient.sql("update order_item set refunded_quantity = 1 where id = :id")
                .param("id", itemId).update();
        mockMvc.perform(get("/app/orders/{orderId}/after-sales/eligibility", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(0))
                .andExpect(jsonPath("$.data.items[0].returnableQuantity").value(0));
        for (String endpoint : new String[]{"/after-sales/quote", "/after-sales"}) {
            mockMvc.perform(post("/app/orders/" + order.orderId() + endpoint)
                            .header("Authorization", "Bearer " + session.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"afterSaleType":"REFUND_ONLY","reason":"再次退款",
                                     "items":[{"orderItemId":%d,"quantity":1}]}
                                    """.formatted(itemId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_FULFILLMENT_UNRESOLVED.code()));
        }
    }

    @Test
    void partialShipmentLimitsReturnQuoteAndDefaultApplicationToShippedUnits() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("return-partial-shipment");
        SeedPaidOrder order = seedPaidOrder(session, 2000L, "PARTIALLY_SHIPPED", "wx-return-partial-shipment");
        long itemId = jdbcClient.sql("select id from order_item where order_id = :id")
                .param("id", order.orderId()).query(Long.class).single();
        long shipmentId = order.orderId() + 70_000_000L;
        jdbcClient.sql("""
                        insert into order_shipment (id, order_id, status, wechat_upload_status, shipped_at)
                        values (:id, :orderId, 'SHIPPED', 'SKIPPED', current_timestamp)
                        """).param("id", shipmentId).param("orderId", order.orderId()).update();
        jdbcClient.sql("""
                        insert into order_shipment_item (shipment_id, order_item_id, quantity)
                        values (:shipmentId, :itemId, 1)
                        """).param("shipmentId", shipmentId).param("itemId", itemId).update();

        mockMvc.perform(get("/app/orders/{orderId}/after-sales/eligibility", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableTypes.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(2))
                .andExpect(jsonPath("$.data.items[0].returnableQuantity").value(1));
        mockMvc.perform(post("/app/orders/{orderId}/after-sales/quote", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"afterSaleType":"RETURN_REFUND","items":[{"orderItemId":%d,"quantity":2}]}
                                """.formatted(itemId)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/app/orders/{orderId}/after-sales/quote", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"afterSaleType":"RETURN_REFUND","items":[{"orderItemId":%d,"quantity":1}]}
                                """.formatted(itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedAmountCent").value(1000));
        String defaultApplication = """
                {"requestKey":"default-return-replay","afterSaleType":"RETURN_REFUND","reason":"已发商品退货"}
                """;
        String applied = mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultApplication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].requestedQuantity").value(1))
                .andExpect(jsonPath("$.data.requestedAmountCent").value(1000))
                .andReturn().getResponse().getContentAsString();
        long afterSaleId = objectMapper.readTree(applied).path("data").path("id").asLong();
        String admin = adminLogin();
        String address = mockMvc.perform(post("/admin/after-sale-return-addresses")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactName":"售后仓","contactPhone":"13800138000",
                                 "province":"广东省","city":"深圳市","district":"南山区",
                                 "detailAddress":"科技园 1 号","enabled":true,"defaultAddress":true}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long addressId = objectMapper.readTree(address).path("data").path("id").asLong();
        mockMvc.perform(post("/admin/after-sales/{id}/approve", afterSaleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"returnAddressId\":%d,\"auditNote\":\"同意退货\"}".formatted(addressId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_RETURN"));
        mockMvc.perform(post("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON).content(defaultApplication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(afterSaleId))
                .andExpect(jsonPath("$.data.status").value("WAITING_RETURN"));
    }

    @Test
    void appUploadsOrderScopedPrivateEvidenceThroughTheAfterSaleEndpoint() throws Exception {
        seedEnabledPaymentConfig();
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
                        .content(applyBody("REFUND_ONLY", "商品未发货想退款", 6980L, "申请整单全额退款", evidenceFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderId").value(order.orderId()))
                .andExpect(jsonPath("$.data.afterSaleType").value("REFUND_ONLY"))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.requestedAmountCent").value(6980))
                .andExpect(jsonPath("$.data.evidenceFileIds[0]").value(evidenceFileId))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long afterSaleId = objectMapper.readTree(response).path("data").path("id").asLong();
        String afterSaleNo = objectMapper.readTree(response).path("data").path("afterSaleNo").asText();
        assertThat(afterSaleNo).matches("^AS\\d{14}[0-9A-Z]{14}$");

        String orderStatusLog = jdbcClient.sql("""
                        select after_sale_id, from_status, to_status, event_type, operator_type,
                               operator_id, description
                        from order_status_log
                        where order_id = :orderId
                          and event_type = 'AFTER_SALE_REQUESTED'
                        """)
                .param("orderId", order.orderId())
                .query((rs, rowNum) -> "%d|%s|%s|%s|%s|%d|%s".formatted(
                        rs.getLong("after_sale_id"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("event_type"),
                        rs.getString("operator_type"),
                        rs.getLong("operator_id"),
                        rs.getString("description")))
                .single();
        assertThat(orderStatusLog).isEqualTo(
                afterSaleId + "|PAID|PAID|AFTER_SALE_REQUESTED|APP|"
                        + session.userId() + "|用户申请售后");

        mockMvc.perform(get("/app/orders/{orderId}/after-sales", order.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(afterSaleId))
                .andExpect(jsonPath("$.data[0].afterSaleNo").value(afterSaleNo))
                .andExpect(jsonPath("$.data[0].status").value("REQUESTED"));

        mockMvc.perform(get("/app/after-sales/{afterSaleId}", afterSaleId)
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(afterSaleId))
                .andExpect(jsonPath("$.data.afterSaleNo").value(afterSaleNo))
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

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "部分退款", 6979L, "partial amount", ownerEvidenceFileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        for (long invalidFileId : new long[]{otherEvidenceFileId, publicEvidenceFileId, deletedEvidenceFileId, wrongMediaFileId}) {
            mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                            .header("Authorization", "Bearer " + owner.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(applyBody("REFUND_ONLY", "凭证不合法", 6980L, "invalid evidence", invalidFileId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(800001));
        }

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("EXCHANGE", "不支持换货", 6980L, "unsupported type", ownerEvidenceFileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "整单退款", 6980L, "valid request", ownerEvidenceFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", paidOrder.orderId())
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "重复申请", 6980L, "duplicate active", ownerEvidenceFileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    void shippedOrderAllowsReturnRefundAndRefundOnly() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("after-sale-app-shipped");
        SeedPaidOrder returnOrder = seedPaidOrder(
                session, 8980L, "SHIPPED", "wx-return-refund-app-shipped");
        SeedPaidOrder refundOnlyOrder = seedPaidOrder(
                session, 7980L, "SHIPPED", "wx-refund-only-app-shipped");
        mockMvc.perform(get("/app/orders/{orderId}/after-sales/eligibility", returnOrder.orderId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].returnableQuantity").value(2));
        long returnEvidenceFileId = insertAppEvidenceFile(
                session.userId(), returnOrder.orderId());
        long refundEvidenceFileId = insertAppEvidenceFile(
                session.userId(), refundOnlyOrder.orderId());

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", returnOrder.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(
                                "RETURN_REFUND", "已发货退货退款", 8980L,
                                "need return", returnEvidenceFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSaleType").value("RETURN_REFUND"))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        mockMvc.perform(post("/app/orders/{orderId}/after-sales", refundOnlyOrder.orderId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(
                                "REFUND_ONLY", "已发货仅退款", 7980L,
                                "refund only", refundEvidenceFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSaleType").value("REFUND_ONLY"))
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
                        .content(applyBody("REFUND_ONLY", "已确认收货仍需售后", 9980L,
                                "completed order protection", ownerFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        long ownerAfterSaleId = objectMapper.readTree(ownerResponse).path("data").path("id").asLong();

        String otherResponse = mockMvc.perform(post("/app/orders/{orderId}/after-sales", otherOrder.orderId())
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REFUND_ONLY", "他人售后", 7980L, "other record", otherFileId)))
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

        mockMvc.perform(delete("/app/after-sales/{afterSaleId}", ownerAfterSaleId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        mockMvc.perform(post("/app/after-sales/{afterSaleId}/cancel", ownerAfterSaleId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mockMvc.perform(get("/app/after-sales")
                        .param("statusGroup", "COMPLETED")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(ownerAfterSaleId))
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(delete("/app/after-sales/{afterSaleId}", ownerAfterSaleId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/after-sales")
                        .param("statusGroup", "COMPLETED")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(0))
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/app/after-sales/{afterSaleId}", ownerAfterSaleId)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ownerAfterSaleId));
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
