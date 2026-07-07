package org.muybaby.shopserver.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AdminProductService adminProductService;

    @BeforeEach
    void clearOrderState() {
        jdbcClient.sql("delete from stock_lock").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from stock_log").update();
        jdbcClient.sql("delete from cart_item").update();
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_template").update();
    }

    @Test
    void adminOrderApisRequireAdminToken() throws Exception {
        String appToken = appLogin("admin-order-auth-user").token();

        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/orders")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));
    }

    @Test
    void adminCanListOrdersAndFilterByStatusAndOrderNo() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long userId = appLogin("admin-order-list-user").userId();
        long skuId = createPublishedSku("ADMIN-LIST-SKU", 3990L, 4990L, 12, "ENABLED");

        insertOrderSnapshot(9101L, "ADM-ORDER-ALPHA", OrderStatus.CREATED.name(), userId, skuId, 9201L, "Admin List Alpha");
        insertOrderSnapshot(9102L, "ADM-ORDER-BETA", OrderStatus.CLOSED.name(), userId, skuId, 9202L, "Admin List Beta");

        mockMvc.perform(get("/admin/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("current", "1")
                        .param("size", "10")
                        .param("status", "CREATED")
                        .param("orderNo", "ALPHA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].orderId").value(9101))
                .andExpect(jsonPath("$.data.records[0].orderNo").value("ADM-ORDER-ALPHA"))
                .andExpect(jsonPath("$.data.records[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data.records[0].productTitle").value("Admin List Alpha"))
                .andExpect(jsonPath("$.data.records[0].itemCount").value(2))
                .andExpect(jsonPath("$.data.records[0].productAmountCent").value(7980))
                .andExpect(jsonPath("$.data.records[0].payableAmountCent").value(7480));
    }

    @Test
    void adminCanReadOrderDetailSnapshots() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long userId = appLogin("admin-order-detail-user").userId();
        long skuId = createPublishedSku("ADMIN-DETAIL-SKU", 3990L, 4990L, 12, "ENABLED");

        insertOrderSnapshot(9301L, "ADM-DETAIL-ORDER", OrderStatus.CREATED.name(), userId, skuId, 9401L, "Admin Detail Item");

        mockMvc.perform(get("/admin/orders/{orderId}", 9301L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(9301))
                .andExpect(jsonPath("$.data.orderNo").value("ADM-DETAIL-ORDER"))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.productOriginalAmountCent").value(9980))
                .andExpect(jsonPath("$.data.productAmountCent").value(7980))
                .andExpect(jsonPath("$.data.couponDiscountCent").value(500))
                .andExpect(jsonPath("$.data.payableAmountCent").value(7480))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(9401))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].productTitle").value("Admin Detail Item"))
                .andExpect(jsonPath("$.data.items[0].skuCode").value("ADMIN-DETAIL-SKU"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].lineOriginalAmountCent").value(9980))
                .andExpect(jsonPath("$.data.items[0].lineAmountCent").value(7980));
    }

    @Test
    void closeCreatedOrderReleasesStockLocksAndCoupon() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        CreatedOrderSeed seed = createCreatedOrder("admin-order-close-user", "ADMIN-CLOSE-SKU", true);

        mockMvc.perform(post("/admin/orders/{orderId}/close", seed.orderId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isMap());

        assertThat(jdbcClient.sql("""
                        select status
                        from shop_order
                        where id = :orderId
                        """)
                .param("orderId", seed.orderId())
                .query(String.class)
                .single()).isEqualTo(OrderStatus.CLOSED.name());
        assertThat(jdbcClient.sql("""
                        select close_reason
                        from shop_order
                        where id = :orderId
                        """)
                .param("orderId", seed.orderId())
                .query(String.class)
                .single()).isEqualTo("ADMIN_CLOSE");
        assertThat(jdbcClient.sql("""
                        select closed_at
                        from shop_order
                        where id = :orderId
                        """)
                .param("orderId", seed.orderId())
                .query(LocalDateTime.class)
                .single()).isNotNull();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from stock_lock
                        where order_id = :orderId
                          and status = 'RELEASED'
                          and released_at is not null
                        """)
                .param("orderId", seed.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select stock_available
                        from product_sku
                        where id = :skuId
                        """)
                .param("skuId", seed.skuId())
                .query(Integer.class)
                .single()).isEqualTo(10);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from stock_log
                        where sku_id = :skuId
                          and change_type = 'ORDER_RELEASE'
                        """)
                .param("skuId", seed.skuId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select status
                        from user_coupon
                        where id = :userCouponId
                        """)
                .param("userCouponId", seed.userCouponId())
                .query(String.class)
                .single()).isEqualTo("RELEASED");
        assertThat(jdbcClient.sql("""
                        select locked_order_id
                        from user_coupon
                        where id = :userCouponId
                        """)
                .param("userCouponId", seed.userCouponId())
                .query(Long.class)
                .single()).isEqualTo(seed.orderId());
        assertThat(jdbcClient.sql("""
                        select released_at
                        from user_coupon
                        where id = :userCouponId
                        """)
                .param("userCouponId", seed.userCouponId())
                .query(LocalDateTime.class)
                .single()).isNotNull();
    }

    @Test
    void closeReturnsOrderStateConflictAndRollsBackWhenLockedCouponCannotBeReleased() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        CreatedOrderSeed seed = createCreatedOrder("admin-order-bad-coupon-user", "ADMIN-BAD-COUPON-SKU", true);

        jdbcClient.sql("""
                        update user_coupon
                        set status = 'CLAIMED',
                            updated_at = timestamp '2026-07-07 13:00:00'
                        where id = :userCouponId
                        """)
                .param("userCouponId", seed.userCouponId())
                .update();

        mockMvc.perform(post("/admin/orders/{orderId}/close", seed.orderId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("""
                        select status
                        from shop_order
                        where id = :orderId
                        """)
                .param("orderId", seed.orderId())
                .query(String.class)
                .single()).isEqualTo(OrderStatus.CREATED.name());
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where id = :orderId
                          and closed_at is null
                        """)
                .param("orderId", seed.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select status
                        from stock_lock
                        where order_id = :orderId
                        """)
                .param("orderId", seed.orderId())
                .query(String.class)
                .single()).isEqualTo(StockLockStatus.LOCKED.name());
        assertThat(jdbcClient.sql("""
                        select stock_available
                        from product_sku
                        where id = :skuId
                        """)
                .param("skuId", seed.skuId())
                .query(Integer.class)
                .single()).isEqualTo(8);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from stock_log
                        where sku_id = :skuId
                          and change_type = 'ORDER_RELEASE'
                        """)
                .param("skuId", seed.skuId())
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void closingOrderTwiceReturnsOrderStateConflictWithoutDuplicateReleaseEffects() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        CreatedOrderSeed seed = createCreatedOrder("admin-order-double-close-user", "ADMIN-DOUBLE-CLOSE-SKU", true);

        mockMvc.perform(post("/admin/orders/{orderId}/close", seed.orderId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/orders/{orderId}/close", seed.orderId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from stock_log
                        where sku_id = :skuId
                          and change_type = 'ORDER_RELEASE'
                        """)
                .param("skuId", seed.skuId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select stock_available
                        from product_sku
                        where id = :skuId
                        """)
                .param("skuId", seed.skuId())
                .query(Integer.class)
                .single()).isEqualTo(10);
    }

    private void insertOrderSnapshot(
            long orderId,
            String orderNo,
            String status,
            long userId,
            long skuId,
            long orderItemId,
            String productTitle
    ) {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key, coupon_name,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, :status, 'CART', :idempotencyKey, 'Seed Coupon',
                             9980, 7980, 500, 0, 7480, 0,
                             timestamp '2026-07-07 12:30:00', timestamp '2026-07-07 12:30:00')
                        """)
                .param("orderId", orderId)
                .param("orderNo", orderNo)
                .param("userId", userId)
                .param("status", status)
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

    private CreatedOrderSeed createCreatedOrder(String userCode, String skuCode, boolean withCoupon) throws Exception {
        AppLoginSession session = appLogin(userCode);
        long skuId = createPublishedSku(skuCode, 3990L, 4990L, 10, "ENABLED");
        Long userCouponId = null;
        if (withCoupon) {
            long templateId = seedTemplate("Admin Close Coupon " + skuCode, "ENABLED", 10, 0, 1, 0L, 500L);
            userCouponId = seedUserCoupon(session.userId(), templateId, "Admin Close Coupon " + skuCode, "CLAIMED", "2026-07-07 08:00:00");
        }
        addCartItem(session.token(), skuId, 2);

        String payload = userCouponId == null
                ? """
                {"idempotencyKey":"seed-%s"}
                """.formatted(skuCode)
                : """
                {"userCouponId":%d,"idempotencyKey":"seed-%s"}
                """.formatted(userCouponId, skuCode);
        String response = mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        return new CreatedOrderSeed(
                data.path("orderId").asLong(),
                data.path("orderNo").asText(),
                session.userId(),
                skuId,
                userCouponId
        );
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
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Admin Order Category " + skuCode, "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Admin Order SPU " + skuCode,
                "Admin order subtitle",
                "https://example.test/admin-order-main.jpg",
                "麻辣,鲜香,浓郁",
                "<p>Admin order detail</p>",
                1,
                List.of("https://example.test/admin-order-gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, skuCode, "{\"规格\":\"300g\"}", "300g", priceCent, originalPriceCent, stock, 300, "https://example.test/admin-order-sku.jpg", skuStatus, 1))
        ));
        adminProductService.publishSpu(spuId);
        return jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();
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
                .param("validStartAt", LocalDateTime.parse("2026-07-01T00:00:00"))
                .param("validEndAt", LocalDateTime.parse("2026-08-01T23:59:59"))
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

    private record AppLoginSession(String token, long userId) {
    }

    private record CreatedOrderSeed(Long orderId, String orderNo, Long userId, Long skuId, Long userCouponId) {
    }
}
