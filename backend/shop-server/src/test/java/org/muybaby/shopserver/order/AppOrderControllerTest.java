package org.muybaby.shopserver.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminProductImageUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;
import org.muybaby.shopserver.order.dto.OrderSubmitResponse;
import org.muybaby.shopserver.order.service.AppOrderService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AppOrderControllerTest.IdempotencyRaceConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private AppOrderService appOrderService;

    @Autowired
    private IdempotencyRaceProbe idempotencyRaceProbe;

    @BeforeEach
    void clearOrderState() {
        jdbcClient.sql("delete from refund_order").update();
        jdbcClient.sql("delete from after_sale_evidence").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from stock_lock").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from stock_log").update();
        jdbcClient.sql("delete from cart_item").update();
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_template").update();
        jdbcClient.sql("delete from user_address").update();
    }

    @AfterEach
    void clearStorageState() {
        jdbcClient.sql("delete from storage_file_usage").update();
        jdbcClient.sql("delete from storage_file").update();
    }

    @Test
    void orderApisRequireAppToken() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(post("/app/orders/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/app/orders/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));
    }

    @Test
    void checkoutHttpContractSupportsDirectAndConditionallyRejectsMixedRequests() throws Exception {
        AppLoginSession session = appLogin("order-http-contract-user");
        AppLoginSession other = appLogin("order-http-contract-other");
        long skuId = createPublishedSku("ORDER-HTTP-DIRECT", 3990L, 4990L, 10, "ENABLED");
        long cartItemId = cartItemId(addCartItem(session.token(), skuId, 1));
        long addressId = insertAddress(session.userId(), "http-contract");
        long otherAddressId = insertAddress(other.userId(), "http-contract-other");

        mockMvc.perform(post("/app/orders/preview")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"DIRECT","skuId":%d,"quantity":2,"addressId":"%d"}
                                """.formatted(skuId, addressId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].cartItemId").doesNotExist());

        mockMvc.perform(post("/app/orders/preview")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d]}
                                """.formatted(cartItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].cartItemId").value(cartItemId));

        List<String> invalidPayloads = List.of(
                "{\"source\":\"CART\",\"cartItemIds\":[%d],\"skuId\":%d}".formatted(cartItemId, skuId),
                "{\"source\":\"DIRECT\",\"cartItemIds\":[%d],\"skuId\":%d,\"quantity\":1}".formatted(cartItemId, skuId),
                "{\"source\":\"DIRECT\",\"skuId\":%d,\"quantity\":0}".formatted(skuId),
                "{\"source\":\"DIRECT\",\"skuId\":%d,\"quantity\":1000}".formatted(skuId)
        );
        for (String payload : invalidPayloads) {
            mockMvc.perform(post("/app/orders/preview")
                            .header("Authorization", "Bearer " + session.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(100400));
        }

        mockMvc.perform(post("/app/orders/preview")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"DIRECT","skuId":%d,"quantity":1,"addressId":%d}
                                """.formatted(skuId, otherAddressId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"DIRECT","skuId":%d,"quantity":1,"idempotencyKey":"missing-address"}
                                """.formatted(skuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    @Test
    void previewUsesCurrentUserCartRowsAndSelectsBestCouponWhenCouponOmitted() throws Exception {
        AppLoginSession session = appLogin("order-preview-user");
        String appToken = session.token();
        long userId = session.userId();
        long skuId = createPublishedSku("ORDER-PREVIEW-SKU", 3990L, 4990L, 10, "ENABLED");
        long userCouponA = seedUserCoupon(userId,
                seedTemplate("Five Off", "ENABLED", 10, 0, 2, 0L, 500L),
                "Five Off",
                "CLAIMED",
                "2026-07-07 08:00:00");
        long userCouponB = seedUserCoupon(userId,
                seedTemplate("Ten Off Threshold", "ENABLED", 10, 0, 2, 7000L, 1000L),
                "Ten Off Threshold",
                "CLAIMED",
                "2026-07-07 09:00:00");

        String cartResponse = addCartItem(appToken, skuId, 2);
        long cartItemId = objectMapper.readTree(cartResponse).path("data").path("id").asLong();

        mockMvc.perform(post("/app/orders/preview")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d]}
                                """.formatted(cartItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].cartItemId").value(cartItemId))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].productTitle").value("Order SPU ORDER-PREVIEW-SKU"))
                .andExpect(jsonPath("$.data.items[0].skuCode").value("ORDER-PREVIEW-SKU"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].originalPriceCent").value(4990))
                .andExpect(jsonPath("$.data.items[0].unitPriceCent").value(3990))
                .andExpect(jsonPath("$.data.items[0].lineOriginalAmountCent").value(9980))
                .andExpect(jsonPath("$.data.items[0].lineAmountCent").value(7980))
                .andExpect(jsonPath("$.data.productOriginalAmountCent").value(9980))
                .andExpect(jsonPath("$.data.productAmountCent").value(7980))
                .andExpect(jsonPath("$.data.userCouponId").value(userCouponB))
                .andExpect(jsonPath("$.data.couponName").value("Ten Off Threshold"))
                .andExpect(jsonPath("$.data.couponDiscountCent").value(1000))
                .andExpect(jsonPath("$.data.freightCent").value(0))
                .andExpect(jsonPath("$.data.payableAmountCent").value(6980));

        assertThat(userCouponA).isPositive();
    }

    @Test
    void submitCreatesOrderLocksStockAndCouponDeletesCartRowsAndIsIdempotent() throws Exception {
        AppLoginSession session = appLogin("order-submit-user");
        String appToken = session.token();
        long userId = session.userId();
        long skuId = createPublishedSku("ORDER-SUBMIT-SKU", 3990L, 4990L, 10, "ENABLED");
        seedUserCoupon(userId,
                seedTemplate("Five Off Submit", "ENABLED", 10, 0, 2, 0L, 500L),
                "Five Off Submit",
                "CLAIMED",
                "2026-07-07 08:00:00");
        long bestCouponId = seedUserCoupon(userId,
                seedTemplate("Ten Off Submit", "ENABLED", 10, 0, 2, 7000L, 1000L),
                "Ten Off Submit",
                "CLAIMED",
                "2026-07-07 09:00:00");

        long cartItemId = cartItemId(addCartItem(appToken, skuId, 2));
        long addressId = insertAddress(userId, "submit");

        String submitPayload = """
                {"cartItemIds":[%d],"addressId":%d,"idempotencyKey":"checkout-20260707-001"}
                """.formatted(cartItemId, addressId);
        String submitResponse = mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.payableAmountCent").value(6980))
                .andExpect(jsonPath("$.data.couponDiscountCent").value(1000))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode firstSubmit = objectMapper.readTree(submitResponse).path("data");
        long orderId = firstSubmit.path("orderId").asLong();
        String orderNo = firstSubmit.path("orderNo").asText();

        Integer orderCount = jdbcClient.sql("select count(*) from shop_order where id = :orderId and user_id = :userId")
                .param("orderId", orderId)
                .param("userId", userId)
                .query(Integer.class)
                .single();
        Integer orderItemCount = jdbcClient.sql("select count(*) from order_item where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        Integer stockLockCount = jdbcClient.sql("""
                        select count(*)
                        from stock_lock
                        where order_id = :orderId
                          and status = 'LOCKED'
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        Integer stockLogCount = jdbcClient.sql("""
                        select count(*)
                        from stock_log
                        where sku_id = :skuId
                          and change_type = 'ORDER_LOCK'
                          and quantity_before = 10
                          and quantity_delta = -2
                          and quantity_after = 8
                        """)
                .param("skuId", skuId)
                .query(Integer.class)
                .single();
        Integer remainingCartRows = jdbcClient.sql("select count(*) from cart_item where user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        Integer lockedCouponRows = jdbcClient.sql("""
                        select count(*)
                        from user_coupon
                        where id = :userCouponId
                          and status = 'LOCKED'
                          and locked_order_id = :orderId
                        """)
                .param("userCouponId", bestCouponId)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        Integer stockAvailable = jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single();

        assertThat(orderCount).isEqualTo(1);
        assertThat(orderItemCount).isEqualTo(1);
        assertThat(stockLockCount).isEqualTo(1);
        assertThat(stockLogCount).isEqualTo(1);
        assertThat(remainingCartRows).isEqualTo(0);
        assertThat(lockedCouponRows).isEqualTo(1);
        assertThat(stockAvailable).isEqualTo(8);

        String repeatResponse = mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode repeatedSubmit = objectMapper.readTree(repeatResponse).path("data");
        assertThat(repeatedSubmit.path("orderId").asLong()).isEqualTo(orderId);
        assertThat(repeatedSubmit.path("orderNo").asText()).isEqualTo(orderNo);
        assertThat(jdbcClient.sql("select count(*) from shop_order where user_id = :userId and idempotency_key = :key")
                .param("userId", userId)
                .param("key", "checkout-20260707-001")
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select count(*) from stock_lock where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single()).isEqualTo(8);
    }

    @Test
    void submitWithOversizedCouponLeavesOneCentPayable() throws Exception {
        AppLoginSession session = appLogin("order-submit-one-cent-payable-user");
        String appToken = session.token();
        long userId = session.userId();
        long skuId = createPublishedSku("ORDER-MIN-PAYABLE-SKU", 300L, 300L, 10, "ENABLED");
        long userCouponId = seedUserCoupon(userId,
                seedTemplate("Oversized No Threshold", "ENABLED", 10, 0, 2, 0L, 500L),
                "Oversized No Threshold",
                "CLAIMED",
                "2026-07-07 10:00:00");

        long cartItemId = cartItemId(addCartItem(appToken, skuId, 1));
        long addressId = insertAddress(userId, "one-cent");

        mockMvc.perform(post("/app/orders/preview")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"userCouponId":%d}
                                """.formatted(cartItemId, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productAmountCent").value(300))
                .andExpect(jsonPath("$.data.userCouponId").value(userCouponId))
                .andExpect(jsonPath("$.data.couponDiscountCent").value(299))
                .andExpect(jsonPath("$.data.payableAmountCent").value(1));

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"userCouponId":%d,"idempotencyKey":"checkout-one-cent-payable-001"}
                                """.formatted(cartItemId, addressId, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.couponDiscountCent").value(299))
                .andExpect(jsonPath("$.data.payableAmountCent").value(1));
    }

    @Test
    void submitSnapshotsFileIdsAndCreatesProtectedStorageUsages() throws Exception {
        AppLoginSession session = appLogin("order-file-usage-user");
        String appToken = session.token();
        long addressId = insertAddress(session.userId(), "file-usage");
        StoredFile mainFile = insertStorageFile("order-main-file.png");
        StoredFile skuFile = insertStorageFile("order-sku-file.png");
        ProductFixture product = createPublishedSkuWithFiles("ORDER-FILE-SKU", 3990L, 4990L, 10, "ENABLED", mainFile, skuFile);

        long cartItemId = cartItemId(addCartItem(appToken, product.skuId(), 2));

        mockMvc.perform(post("/app/orders/preview")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d]}
                                """.formatted(cartItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].mainImageFileId").value(mainFile.id()))
                .andExpect(jsonPath("$.data.items[0].skuImageFileId").value(skuFile.id()))
                .andExpect(jsonPath("$.data.items[0].displayImageFileId").value(skuFile.id()));

        String submitResponse = mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"idempotencyKey":"checkout-file-usage-001"}
                                """.formatted(cartItemId, addressId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long orderId = objectMapper.readTree(submitResponse).path("data").path("orderId").asLong();
        Long orderItemId = jdbcClient.sql("""
                        select id
                        from order_item
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();

        mockMvc.perform(get("/app/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].mainImageFileId").value(mainFile.id()))
                .andExpect(jsonPath("$.data.items[0].skuImageFileId").value(skuFile.id()))
                .andExpect(jsonPath("$.data.items[0].displayImageFileId").value(skuFile.id()));

        assertThat(jdbcClient.sql("""
                        select main_image_file_id
                        from order_item
                        where id = :orderItemId
                        """)
                .param("orderItemId", orderItemId)
                .query(Long.class)
                .single()).isEqualTo(mainFile.id());
        assertThat(jdbcClient.sql("""
                        select sku_image_file_id
                        from order_item
                        where id = :orderItemId
                        """)
                .param("orderItemId", orderItemId)
                .query(Long.class)
                .single()).isEqualTo(skuFile.id());
        assertThat(jdbcClient.sql("""
                        select display_image_file_id
                        from order_item
                        where id = :orderItemId
                        """)
                .param("orderItemId", orderItemId)
                .query(Long.class)
                .single()).isEqualTo(skuFile.id());

        assertThat(protectedSnapshotUsageCount(mainFile.id(), orderItemId, mainFile.publicUrl())).isEqualTo(1);
        assertThat(protectedSnapshotUsageCount(skuFile.id(), orderItemId, skuFile.publicUrl())).isEqualTo(2);
    }

    @Test
    void submitCreatesProtectedSnapshotUsageWhenSkuImageUrlIsBlank() throws Exception {
        AppLoginSession session = appLogin("order-blank-snapshot-user");
        String appToken = session.token();
        long addressId = insertAddress(session.userId(), "blank-snapshot");
        StoredFile mainFile = insertStorageFile("ord-main-blank.png");
        StoredFile skuFile = insertStorageFile("ord-sku-blank.png");
        ProductFixture product = createPublishedSkuWithCustomImages(
                "ORDER-BLANK-SNAPSHOT-SKU",
                3990L,
                4990L,
                10,
                "ENABLED",
                mainFile.publicUrl(),
                mainFile.id(),
                "",
                skuFile.id()
        );

        long cartItemId = cartItemId(addCartItem(appToken, product.skuId(), 1));

        String submitResponse = mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"idempotencyKey":"checkout-blank-snapshot-001"}
                                """.formatted(cartItemId, addressId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long orderId = objectMapper.readTree(submitResponse).path("data").path("orderId").asLong();
        Long orderItemId = jdbcClient.sql("""
                        select id
                        from order_item
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();

        assertThat(jdbcClient.sql("""
                        select sku_image_file_id
                        from order_item
                        where id = :orderItemId
                        """)
                .param("orderItemId", orderItemId)
                .query(Long.class)
                .single()).isEqualTo(skuFile.id());
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_file_usage
                        where file_id = :fileId
                          and usage_type = 'ORDER_ITEM_SNAPSHOT'
                          and owner_type = 'ORDER_ITEM'
                          and owner_id = :orderItemId
                          and protected = true
                          and status = 'ACTIVE'
                        """)
                .param("fileId", skuFile.id())
                .param("orderItemId", orderItemId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(protectedSnapshotUsageCount(mainFile.id(), orderItemId, mainFile.publicUrl())).isEqualTo(2);
    }

    @Test
    void overlappingSubmitWithSameIdempotencyKeyReturnsExistingOrderInsteadOfCartError() throws Exception {
        AppLoginSession session = appLogin("order-submit-race-user");
        long userId = session.userId();
        long skuId = createPublishedSku("ORDER-RACE-SKU", 3990L, 4990L, 10, "ENABLED");
        long cartItemId = cartItemId(addCartItem(session.token(), skuId, 2));
        long addressId = insertAddress(userId, "race");

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                TokenKind.APP,
                userId,
                "order-submit-race-user",
                List.of(),
                List.of()
        );
        AppOrderSubmitRequest request = new AppOrderSubmitRequest(
                null, List.of(cartItemId), null, null, addressId, null, "checkout-race-001");
        idempotencyRaceProbe.arm("order-submit-2");

        ExecutorService executor = Executors.newFixedThreadPool(2, new SubmitThreadFactory());
        try {
            CompletableFuture<OrderSubmitResponse> firstSubmit = CompletableFuture.supplyAsync(
                    () -> appOrderService.submit(principal, request),
                    executor
            );
            CompletableFuture<OrderSubmitResponse> secondSubmit = CompletableFuture.supplyAsync(
                    () -> appOrderService.submit(principal, request),
                    executor
            );

            OrderSubmitResponse created = firstSubmit.get(30, TimeUnit.SECONDS);
            idempotencyRaceProbe.markWinnerCommitted();
            OrderSubmitResponse repeated = secondSubmit.get(30, TimeUnit.SECONDS);

            assertThat(repeated.orderId()).isEqualTo(created.orderId());
            assertThat(repeated.orderNo()).isEqualTo(created.orderNo());
            assertThat(repeated.status()).isEqualTo(created.status());
            assertThat(repeated.payableAmountCent()).isEqualTo(created.payableAmountCent());
            assertThat(repeated.couponDiscountCent()).isEqualTo(created.couponDiscountCent());
            assertThat(jdbcClient.sql("""
                            select count(*)
                            from shop_order
                            where user_id = :userId
                              and idempotency_key = :idempotencyKey
                            """)
                    .param("userId", userId)
                    .param("idempotencyKey", "checkout-race-001")
                    .query(Integer.class)
                    .single()).isEqualTo(1);
            assertThat(jdbcClient.sql("select count(*) from order_item where order_id = :orderId")
                    .param("orderId", created.orderId())
                    .query(Integer.class)
                    .single()).isEqualTo(1);
            assertThat(jdbcClient.sql("select count(*) from stock_lock where order_id = :orderId")
                    .param("orderId", created.orderId())
                    .query(Integer.class)
                    .single()).isEqualTo(1);
            assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                    .param("skuId", skuId)
                    .query(Integer.class)
                    .single()).isEqualTo(8);
            assertThat(jdbcClient.sql("select count(*) from cart_item where user_id = :userId")
                    .param("userId", userId)
                    .query(Integer.class)
                    .single()).isEqualTo(0);
        } finally {
            idempotencyRaceProbe.reset();
            executor.shutdownNow();
        }
    }

    @Test
    void submitRejectsCartItemThatDoesNotBelongToCurrentUser() throws Exception {
        AppLoginSession owner = appLogin("order-owner-user");
        AppLoginSession other = appLogin("order-other-user");
        String ownerToken = owner.token();
        String otherToken = other.token();
        long otherAddressId = insertAddress(other.userId(), "ownership");
        long skuId = createPublishedSku("ORDER-OWNERSHIP-SKU", 3990L, 4990L, 10, "ENABLED");

        String cartResponse = addCartItem(ownerToken, skuId, 1);
        long cartItemId = objectMapper.readTree(cartResponse).path("data").path("id").asLong();

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"idempotencyKey":"checkout-ownership-001"}
                                """.formatted(cartItemId, otherAddressId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(250001));
    }

    @Test
    void submitRejectsDisabledSkuOffSaleCategoryAndStockShortage() throws Exception {
        AppLoginSession session = appLogin("order-unavailable-user");
        String appToken = session.token();
        long addressId = insertAddress(session.userId(), "unavailable");

        long disabledSkuId = createPublishedSku("ORDER-DISABLED-SKU", 3990L, 4990L, 10, "ENABLED");
        long disabledCartItemId = cartItemId(addCartItem(appToken, disabledSkuId, 1));
        jdbcClient.sql("""
                        update product_sku
                        set status = 'DISABLED', updated_at = current_timestamp
                        where id = :skuId
                        """)
                .param("skuId", disabledSkuId)
                .update();

        long offSaleSkuId = createPublishedSku("ORDER-OFFSALE-SKU", 3990L, 4990L, 10, "ENABLED");
        long offSaleCartItemId = cartItemId(addCartItem(appToken, offSaleSkuId, 1));
        Long offSaleSpuId = jdbcClient.sql("select spu_id from product_sku where id = :skuId")
                .param("skuId", offSaleSkuId)
                .query(Long.class)
                .single();
        adminProductService.unpublishSpu(offSaleSpuId);

        long categoryDisabledSkuId = createPublishedSku("ORDER-CATEGORY-DISABLED-SKU", 3990L, 4990L, 10, "ENABLED");
        long categoryDisabledCartItemId = cartItemId(addCartItem(appToken, categoryDisabledSkuId, 1));
        Long categoryId = jdbcClient.sql("""
                        select s.category_id
                        from product_spu s
                        join product_sku k on k.spu_id = s.id
                        where k.id = :skuId
                        """)
                .param("skuId", categoryDisabledSkuId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        update product_category
                        set status = 'DISABLED', updated_at = current_timestamp
                        where id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .update();

        long shortageSkuId = createPublishedSku("ORDER-SHORTAGE-SKU", 3990L, 4990L, 10, "ENABLED");
        long shortageCartItemId = cartItemId(addCartItem(appToken, shortageSkuId, 2));
        jdbcClient.sql("""
                        update product_sku
                        set stock_available = 1, updated_at = current_timestamp
                        where id = :skuId
                        """)
                .param("skuId", shortageSkuId)
                .update();

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"idempotencyKey":"checkout-disabled-001"}
                                """.formatted(disabledCartItemId, addressId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200002));

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"idempotencyKey":"checkout-offsale-001"}
                                """.formatted(offSaleCartItemId, addressId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"idempotencyKey":"checkout-category-disabled-001"}
                                """.formatted(categoryDisabledCartItemId, addressId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"idempotencyKey":"checkout-shortage-001"}
                                """.formatted(shortageCartItemId, addressId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200100));
    }

    @Test
    void submitRejectsSelectedCouponThatIsNotApplicable() throws Exception {
        AppLoginSession session = appLogin("order-coupon-user");
        String appToken = session.token();
        long userId = session.userId();
        long skuId = createPublishedSku("ORDER-COUPON-SKU", 3990L, 4990L, 10, "ENABLED");
        long templateId = seedTemplate("Threshold Too High", "ENABLED", 10, 0, 1, 7000L, 1000L);
        long userCouponId = seedUserCoupon(userId, templateId, "Threshold Too High", "CLAIMED", "2026-07-07 09:00:00");

        long cartItemId = cartItemId(addCartItem(appToken, skuId, 1));
        long addressId = insertAddress(userId, "coupon");

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"addressId":%d,"userCouponId":%d,"idempotencyKey":"checkout-coupon-001"}
                                """.formatted(cartItemId, addressId, userCouponId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(300001));
    }

    @Test
    void appOrderListAndDetailReturnOnlyCurrentUsersOrders() throws Exception {
        AppLoginSession session = appLogin("order-list-user");
        String appToken = session.token();
        long userId = session.userId();
        long skuId = createPublishedSku("ORDER-LIST-SKU", 3990L, 4990L, 10, "ENABLED");
        long otherUserId = appLogin("order-list-other-user").userId();

        insertOrderSnapshot(9101L, "ORD-LIST-USER", userId, skuId, 9901L, "List User Item");
        insertOrderSnapshot(9102L, "ORD-LIST-OTHER", otherUserId, skuId, 9902L, "Other User Item");
        String receiverAddress = longestValidReceiverAddress();
        jdbcClient.sql("update shop_order set receiver_address = :receiverAddress where id = 9101")
                .param("receiverAddress", receiverAddress)
                .update();

        mockMvc.perform(get("/app/orders?current=1&size=10")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].orderId").value(9101))
                .andExpect(jsonPath("$.data.records[0].orderNo").value("ORD-LIST-USER"))
                .andExpect(jsonPath("$.data.records[0].productTitle").value("List User Item"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10));

        mockMvc.perform(get("/app/orders/{orderId}", 9101L)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(9101))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-LIST-USER"))
                .andExpect(jsonPath("$.data.receiverAddress").value(receiverAddress))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].productTitle").value("List User Item"));
    }

    @Test
    void orderCenterHttpSupportsStatusGroupsRejectsAmbiguityAndConfirmsOwnedReceiptIdempotently() throws Exception {
        AppLoginSession owner = appLogin("order-center-http-owner");
        AppLoginSession other = appLogin("order-center-http-other");
        long skuId = createPublishedSku("ORDER-CENTER-HTTP", 3990L, 4990L, 10, "ENABLED");
        insertOrderSnapshot(9201L, "ORD-CENTER-CREATED", owner.userId(), skuId, 9921L, "Created Item");
        insertOrderSnapshot(9202L, "ORD-CENTER-PAYING", owner.userId(), skuId, 9922L, "Paying Item");
        insertOrderSnapshot(9203L, "ORD-CENTER-SHIPPED", owner.userId(), skuId, 9923L, "Shipped Item");
        jdbcClient.sql("update shop_order set status = 'PAYING' where id = 9202").update();
        jdbcClient.sql("""
                        update shop_order
                        set status = 'SHIPPED', shipped_at = timestamp '2026-07-08 14:00:00'
                        where id = 9203
                        """).update();

        mockMvc.perform(get("/app/orders")
                        .param("current", "1")
                        .param("size", "10")
                        .param("statusGroup", "UNPAID")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(get("/app/orders")
                        .param("status", "SHIPPED")
                        .param("statusGroup", "TO_RECEIVE")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(get("/app/orders")
                        .param("statusGroup", "NOT_A_GROUP")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest());

        String first = mockMvc.perform(post("/app/orders/{orderId}/confirm-receipt", 9203L)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(9203L))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String firstCompletedAt = objectMapper.readTree(first).path("data").path("completedAt").asText();

        mockMvc.perform(post("/app/orders/{orderId}/confirm-receipt", 9203L)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedAt").value(firstCompletedAt));

        mockMvc.perform(post("/app/orders/{orderId}/confirm-receipt", 9203L)
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(post("/app/orders/{orderId}/confirm-receipt", 9201L)
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    private String longestValidReceiverAddress() {
        return "省".repeat(64)
                + " " + "市".repeat(64)
                + " " + "区".repeat(64)
                + " " + "路".repeat(255);
    }

    private long cartItemId(String cartResponse) throws Exception {
        return objectMapper.readTree(cartResponse).path("data").path("id").asLong();
    }

    private String addCartItem(String appToken, long skuId, int quantity) throws Exception {
        return mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":%d}
                                """.formatted(skuId, quantity)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private long insertAddress(long userId, String suffix) {
        jdbcClient.sql("""
                        insert into user_address
                            (user_id, receiver_name, receiver_phone, province, city, district, detail_address, is_default)
                        values (:userId, :receiverName, '13800138000', '北京市', '', '朝阳区', :detailAddress, true)
                        """)
                .param("userId", userId)
                .param("receiverName", "收货人-" + suffix)
                .param("detailAddress", "火锅路-" + suffix + "号")
                .update();
        return jdbcClient.sql("select max(id) from user_address where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private void insertOrderSnapshot(long orderId, String orderNo, long userId, long skuId, long orderItemId, String productTitle) {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, 'CREATED', 'CART', :idempotencyKey,
                             9980, 7980, 500, 0, 7480, 0,
                             timestamp '2026-07-07 12:30:00', timestamp '2026-07-07 12:30:00')
                        """)
                .param("orderId", orderId)
                .param("orderNo", orderNo)
                .param("userId", userId)
                .param("idempotencyKey", "seed-" + orderId)
                .update();
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, product_subtitle, main_image,
                             sku_image, display_image, sku_code, spec_text, original_price_cent,
                             unit_price_cent, quantity, line_original_amount_cent, line_amount_cent, created_at)
                        values
                            (:orderItemId, :orderId, :skuId,
                             (select spu_id from product_sku where id = :skuId),
                             :productTitle, 'Seed subtitle', 'https://example.test/order-main.jpg',
                             'https://example.test/order-sku.jpg', 'https://example.test/order-sku.jpg',
                             (select sku_code from product_sku where id = :skuId), '300g',
                             4990, 3990, 2, 9980, 7980, timestamp '2026-07-07 12:30:00')
                        """)
                .param("orderItemId", orderItemId)
                .param("orderId", orderId)
                .param("skuId", skuId)
                .param("productTitle", productTitle)
                .update();
    }

    private String appLoginAndExtractToken(String code) throws Exception {
        return appLogin(code).token();
    }

    private AppLoginSession appLogin(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return new AppLoginSession(
                json.path("data").path("token").asText(),
                json.path("data").path("user").path("userId").asLong()
        );
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long createPublishedSku(String skuCode, long priceCent, long originalPriceCent, int stock, String skuStatus) {
        return createPublishedSkuWithFiles(skuCode, priceCent, originalPriceCent, stock, skuStatus, null, null).skuId();
    }

    private ProductFixture createPublishedSkuWithFiles(
            String skuCode,
            long priceCent,
            long originalPriceCent,
            int stock,
            String skuStatus,
            StoredFile mainFile,
            StoredFile skuFile
    ) {
        String mainImageUrl = mainFile == null ? "https://example.test/order-main.jpg" : mainFile.publicUrl();
        Long mainImageFileId = mainFile == null ? null : mainFile.id();
        String skuImageUrl = skuFile == null ? "https://example.test/order-sku.jpg" : skuFile.publicUrl();
        Long skuImageFileId = skuFile == null ? null : skuFile.id();
        return createPublishedSkuWithCustomImages(
                skuCode,
                priceCent,
                originalPriceCent,
                stock,
                skuStatus,
                mainImageUrl,
                mainImageFileId,
                skuImageUrl,
                skuImageFileId
        );
    }

    private ProductFixture createPublishedSkuWithCustomImages(
            String skuCode,
            long priceCent,
            long originalPriceCent,
            int stock,
            String skuStatus,
            String mainImageUrl,
            Long mainImageFileId,
            String skuImageUrl,
            Long skuImageFileId
    ) {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Order Category " + skuCode, "", null, 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Order SPU " + skuCode,
                "Order subtitle",
                mainImageUrl,
                mainImageFileId,
                "麻辣,鲜香,浓郁",
                "<p>Order detail</p>",
                1,
                List.of(new AdminProductImageUpsertRequest("https://example.test/order-gallery.jpg", null)),
                List.of(new AdminSkuUpsertRequest(null, skuCode, "{\"规格\":\"300g\"}", "300g", priceCent, originalPriceCent, stock, 300, skuImageUrl, skuImageFileId, skuStatus, 1))
        ));
        adminProductService.publishSpu(spuId);
        Long skuId = jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();
        return new ProductFixture(spuId, skuId);
    }

    private int protectedSnapshotUsageCount(long fileId, long orderItemId, String snapshotUrl) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_file_usage
                        where file_id = :fileId
                          and usage_type = 'ORDER_ITEM_SNAPSHOT'
                          and owner_type = 'ORDER_ITEM'
                          and owner_id = :orderItemId
                          and snapshot_url = :snapshotUrl
                          and protected = true
                          and status = 'ACTIVE'
                        """)
                .param("fileId", fileId)
                .param("orderItemId", orderItemId)
                .param("snapshotUrl", snapshotUrl)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private StoredFile insertStorageFile(String originalFilename) {
        String objectKey = "public/test/order/" + System.nanoTime() + "-" + originalFilename;
        String publicUrl = "http://localhost:8080/files/public/test/" + originalFilename;
        jdbcClient.sql("""
                        insert into storage_file
                            (purpose, asset_category_id, visibility, provider, bucket, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('PRODUCT_IMAGE', 1, 'PUBLIC', 'LOCAL', '', :objectKey, :originalFilename,
                             'image/png', 'png', 68, :sha256, 1, 1, '', null,
                             :publicUrl, 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("objectKey", objectKey)
                .param("originalFilename", originalFilename)
                .param("sha256", "sha-" + objectKey)
                .param("publicUrl", publicUrl)
                .update();
        Long fileId = jdbcClient.sql("""
                        select id
                        from storage_file
                        where object_key = :objectKey
                        """)
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        assertThat(fileId).isNotNull();
        return new StoredFile(fileId, publicUrl);
    }

    private long seedTemplate(String name, String status, int totalStock, int claimedCount, int perUserLimit, long thresholdCent, long discountCent) {
        String couponType = thresholdCent == 0L ? "NO_THRESHOLD" : "MIN_SPEND";
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (:name, 'seed', :couponType, 'AMOUNT_OFF', :thresholdCent, :discountCent,
                             'ALL', '', 'coupon.amount-off.v1', :totalStock, :claimedCount, :perUserLimit,
                             timestamp '2026-07-01 00:00:00', timestamp '2026-08-01 23:59:59', :status, 1)
                        """)
                .param("name", name)
                .param("couponType", couponType)
                .param("thresholdCent", thresholdCent)
                .param("discountCent", discountCent)
                .param("totalStock", totalStock)
                .param("claimedCount", claimedCount)
                .param("perUserLimit", perUserLimit)
                .param("status", status)
                .update();
        return jdbcClient.sql("select id from coupon_template where name = :name order by id desc limit 1")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long seedUserCoupon(long userId, long templateId, String templateName, String status, String claimedAt) {
        return seedUserCoupon(
                userId,
                templateId,
                templateName,
                status,
                claimedAt,
                "2026-07-01 00:00:00",
                "2026-08-01 23:59:59"
        );
    }

    private long seedUserCoupon(
            long userId,
            long templateId,
            String templateName,
            String status,
            String claimedAt,
            String validStartAt,
            String validEndAt
    ) {
        String couponType = jdbcClient.sql("select coupon_type from coupon_template where id = :templateId")
                .param("templateId", templateId)
                .query(String.class)
                .single();
        Long thresholdCent = jdbcClient.sql("select threshold_cent from coupon_template where id = :templateId")
                .param("templateId", templateId)
                .query(Long.class)
                .single();
        Long discountCent = jdbcClient.sql("select discount_cent from coupon_template where id = :templateId")
                .param("templateId", templateId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into user_coupon
                            (user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value, valid_start_at,
                             valid_end_at, status, claimed_at)
                        values
                            (:userId, :templateId, :templateName, :couponType, 'AMOUNT_OFF',
                             :thresholdCent, :discountCent, 'ALL', '', :validStartAt,
                             :validEndAt, :status, :claimedAt)
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .param("templateName", templateName)
                .param("couponType", couponType)
                .param("thresholdCent", thresholdCent)
                .param("discountCent", discountCent)
                .param("validStartAt", LocalDateTime.parse(validStartAt.replace(" ", "T")))
                .param("validEndAt", LocalDateTime.parse(validEndAt.replace(" ", "T")))
                .param("status", status)
                .param("claimedAt", LocalDateTime.parse(claimedAt.replace(" ", "T")))
                .update();
        return jdbcClient.sql("""
                        select id
                        from user_coupon
                        where user_id = :userId and template_id = :templateId and template_name = :templateName
                        order by id desc
                        limit 1
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .param("templateName", templateName)
                .query(Long.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IdempotencyRaceConfiguration {

        @Bean
        IdempotencyRaceProbe idempotencyRaceProbe() {
            return new IdempotencyRaceProbe();
        }

        @Bean
        BeanPostProcessor idempotencyRaceDataSourcePostProcessor(IdempotencyRaceProbe idempotencyRaceProbe) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && !(bean instanceof ProbeDataSource)) {
                        return new ProbeDataSource(dataSource, idempotencyRaceProbe);
                    }
                    return bean;
                }
            };
        }
    }

    static class SubmitThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, "order-submit-" + counter.incrementAndGet());
        }
    }

    static class IdempotencyRaceProbe {

        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final AtomicInteger existingOrderReadCount = new AtomicInteger();
        private final AtomicBoolean losingSubmitPaused = new AtomicBoolean(false);
        private volatile CyclicBarrier existingOrderBarrier = new CyclicBarrier(2);
        private volatile CountDownLatch winnerCommitted = new CountDownLatch(0);
        private volatile String losingThreadName;

        void arm(String losingThreadName) {
            this.losingThreadName = losingThreadName;
            this.existingOrderReadCount.set(0);
            this.losingSubmitPaused.set(false);
            this.existingOrderBarrier = new CyclicBarrier(2);
            this.winnerCommitted = new CountDownLatch(1);
            this.armed.set(true);
        }

        void markWinnerCommitted() {
            this.winnerCommitted.countDown();
        }

        void reset() {
            this.armed.set(false);
            this.winnerCommitted.countDown();
        }

        void beforeStatement(String sql) {
            if (!armed.get()) {
                return;
            }
            String normalized = normalizeSql(sql);
            if (isExistingOrderLookup(normalized)) {
                int currentCount = existingOrderReadCount.incrementAndGet();
                if (currentCount <= 2) {
                    awaitBarrier(existingOrderBarrier);
                }
                return;
            }
            if ((isCartSelection(normalized) || isOrderOwnershipInsert(normalized))
                    && losingThreadName != null
                    && losingThreadName.equals(Thread.currentThread().getName())
                    && losingSubmitPaused.compareAndSet(false, true)) {
                awaitLatch(winnerCommitted);
            }
        }

        private boolean isExistingOrderLookup(String sql) {
            return sql.contains("select id as order_id")
                    && sql.contains("from shop_order")
                    && sql.contains("idempotency_key");
        }

        private boolean isCartSelection(String sql) {
            return sql.contains("select id as cart_item_id")
                    && sql.contains("from cart_item");
        }

        private boolean isOrderOwnershipInsert(String sql) {
            return sql.contains("insert into shop_order");
        }

        private String normalizeSql(String sql) {
            return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        }

        private void awaitBarrier(CyclicBarrier barrier) {
            try {
                barrier.await(30, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to synchronize idempotency lookup race", ex);
            }
        }

        private void awaitLatch(CountDownLatch latch) {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Timed out waiting for winning submit to commit");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for idempotency race latch", ex);
            } catch (TimeoutException ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
        }
    }

    static class ProbeDataSource extends AbstractDataSource {

        private final DataSource delegate;
        private final IdempotencyRaceProbe idempotencyRaceProbe;

        ProbeDataSource(DataSource delegate, IdempotencyRaceProbe idempotencyRaceProbe) {
            this.delegate = delegate;
            this.idempotencyRaceProbe = idempotencyRaceProbe;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            InvocationHandler handler = (proxy, method, args) -> {
                try {
                    if ("prepareStatement".equals(method.getName())
                            && args != null
                            && args.length > 0
                            && args[0] instanceof String sql) {
                        PreparedStatement preparedStatement = (PreparedStatement) method.invoke(connection, args);
                        return wrapPreparedStatement(preparedStatement, sql);
                    }
                    return method.invoke(connection, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getTargetException();
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    handler
            );
        }

        private PreparedStatement wrapPreparedStatement(PreparedStatement preparedStatement, String sql) {
            InvocationHandler handler = (proxy, method, args) -> {
                try {
                    if (method.getName().startsWith("execute")) {
                        idempotencyRaceProbe.beforeStatement(sql);
                    }
                    return method.invoke(preparedStatement, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getTargetException();
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    handler
            );
        }
    }

    private record ProductFixture(long spuId, long skuId) {
    }

    private record StoredFile(Long id, String publicUrl) {
    }

    private record AppLoginSession(String token, long userId) {
    }
}
