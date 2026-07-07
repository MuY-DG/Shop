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
class AppOrderControllerTest {

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
                                {"cartItemIds":[]}
                                """))
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

        addCartItem(appToken, skuId, 2);

        String submitPayload = """
                {"idempotencyKey":"checkout-20260707-001"}
                """;
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
    void submitRejectsCartItemThatDoesNotBelongToCurrentUser() throws Exception {
        String ownerToken = appLoginAndExtractToken("order-owner-user");
        String otherToken = appLoginAndExtractToken("order-other-user");
        long skuId = createPublishedSku("ORDER-OWNERSHIP-SKU", 3990L, 4990L, 10, "ENABLED");

        String cartResponse = addCartItem(ownerToken, skuId, 1);
        long cartItemId = objectMapper.readTree(cartResponse).path("data").path("id").asLong();

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"idempotencyKey":"checkout-ownership-001"}
                                """.formatted(cartItemId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(250001));
    }

    @Test
    void submitRejectsDisabledSkuOffSaleCategoryAndStockShortage() throws Exception {
        String appToken = appLoginAndExtractToken("order-unavailable-user");

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
                                {"cartItemIds":[%d],"idempotencyKey":"checkout-disabled-001"}
                                """.formatted(disabledCartItemId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200002));

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"idempotencyKey":"checkout-offsale-001"}
                                """.formatted(offSaleCartItemId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"idempotencyKey":"checkout-category-disabled-001"}
                                """.formatted(categoryDisabledCartItemId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"idempotencyKey":"checkout-shortage-001"}
                                """.formatted(shortageCartItemId)))
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

        mockMvc.perform(post("/app/orders")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d],"userCouponId":%d,"idempotencyKey":"checkout-coupon-001"}
                                """.formatted(cartItemId, userCouponId)))
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
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].productTitle").value("List User Item"));
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
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Order Category " + skuCode, "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Order SPU " + skuCode,
                "Order subtitle",
                "https://example.test/order-main.jpg",
                "麻辣,鲜香,浓郁",
                "<p>Order detail</p>",
                1,
                List.of("https://example.test/order-gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, skuCode, "{\"规格\":\"300g\"}", "300g", priceCent, originalPriceCent, stock, 300, "https://example.test/order-sku.jpg", skuStatus, 1))
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

    private record AppLoginSession(String token, long userId) {
    }
}
