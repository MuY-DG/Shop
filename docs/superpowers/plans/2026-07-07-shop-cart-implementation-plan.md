# Shop Cart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the mini program shopping cart phase for app users: authenticated cart items, add/list/update/delete/clear APIs, current SKU price display, product availability checks, mini program cart UI, backend tests, mini program typecheck, and real local smoke checks.

**Architecture:** Add a focused `cart` backend module to the existing Spring Boot modular monolith. Cart rows store only ownership, SKU, quantity, and timestamps; all read models join current product tables so the cart always displays the current SKU price and current availability, without order snapshots. The native WeChat mini program consumes `/app/cart/**` with the app token already issued by silent login.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security, MyBatis-Plus 3.5.16, Flyway, MySQL 8/H2 test profile, JdbcClient, native WeChat mini program TypeScript, TDesign MiniProgram, pnpm.

## Global Constraints

- API response envelope remains `{ "code": 200, "msg": "success", "data": {} }`.
- Mini program cart APIs live under `/app/cart/**` and require an APP token.
- Admin tokens must not authorize `/app/cart/**`.
- Use `AuthenticatedPrincipal.subjectId()` as the app user id for every cart operation.
- Money is stored and returned as integer cents: `priceCent`, `originalPriceCent`, `lineAmountCent`, `totalAmountCent`.
- `cart_item` stores `user_id`, `sku_id`, `quantity`, `created_at`, and `updated_at`; it does not store price, product title, product image, SKU spec, or line amount snapshots.
- Cart list displays current SKU price from `product_sku.price_cent`; line amount is calculated from current price times quantity.
- Add and quantity update validate SKU status `ENABLED`, SPU status `ON_SALE`, category status `ENABLED`, and `product_sku.stock_available >= quantity`.
- Cart list must not hide existing user rows when SKU/SPU/category/stock changes; it marks unavailable rows and reports the reason.
- Cart list also keeps rows visible when a referenced SKU row is missing; those rows use zero price/stock and `SKU_UNAVAILABLE`.
- This phase does not implement checkout, coupon, order creation, stock lock, payment, shipment, after-sale, or refund.
- Backend tests run on the existing `test` profile with H2 in MySQL mode.
- Mini program verification includes `pnpm typecheck`.
- Real local cart smoke uses the local backend and local database path; product and cart requests are not mocked. In the `test` profile, only WeChat login remains backed by the mock WeChat mini program client.
- Do not log secrets, WeChat tokens, login codes, phone codes, or production credentials.

---

## Scope Boundary

Included:

- `cart_item` table and Flyway migration.
- App-token-protected mini program cart APIs.
- Add to cart with same-user same-SKU quantity merge.
- Cart list with current price, current stock, and availability reason.
- Quantity update.
- Delete one cart item.
- Clear current user's cart.
- SKU status, SPU on-sale status, category enabled status, and stock validation.
- Product detail "add to cart" entry.
- Mini program cart tab/page.
- Backend schema, service, controller, and security tests.
- Mini program typecheck.
- Cart smoke checks in `docs/smoke-checks.md` and focused command in `docs/dev-setup.md`.

Excluded:

- Checkout item selection persistence.
- Buy now flow.
- Coupon/promotion calculation.
- Order item snapshots.
- Stock lock/release/deduction.
- Payment, shipment, after-sale, refund.
- Admin cart management.

## References

- Product design: `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`
- Product catalog plan: `docs/superpowers/plans/2026-07-06-shop-product-catalog-implementation-plan.md`
- Local setup and auth smoke truth: `docs/dev-setup.md`
- Existing product schema: `backend/shop-server/src/main/resources/db/migration/V3__product_catalog.sql`
- Existing app auth flow: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/AppAuthController.java`
- Existing mini program request helper: `miniprogram/utils/request.ts`

## API Contracts

Mini program cart list:

```http
GET /app/cart/items
Authorization: Bearer app_access_token
```

```json
{
  "items": [
    {
      "id": 9001,
      "skuId": 1000,
      "spuId": 100,
      "productTitle": "重庆牛油火锅底料",
      "productSubtitle": "厚重牛油香",
      "mainImage": "https://example.test/hotpot-main.jpg",
      "skuImage": "https://example.test/hotpot-sku-300.jpg",
      "displayImage": "https://example.test/hotpot-sku-300.jpg",
      "specText": "牛油 / 300g",
      "priceCent": 3990,
      "originalPriceCent": 4990,
      "quantity": 2,
      "lineAmountCent": 7980,
      "stockAvailable": 100,
      "skuStatus": "ENABLED",
      "spuStatus": "ON_SALE",
      "available": true,
      "unavailableReason": null,
      "createdAt": "2026-07-07T12:00:00",
      "updatedAt": "2026-07-07T12:00:00"
    }
  ],
  "totalQuantity": 2,
  "totalAmountCent": 7980,
  "unavailableCount": 0
}
```

Add SKU to cart:

```http
POST /app/cart/items
Authorization: Bearer app_access_token
Content-Type: application/json

{
  "skuId": 1000,
  "quantity": 2
}
```

If the current user already has the SKU in cart, the backend updates the row to `existing.quantity + request.quantity` after stock validation.

Update quantity:

```http
PUT /app/cart/items/9001/quantity
Authorization: Bearer app_access_token
Content-Type: application/json

{
  "quantity": 3
}
```

Delete one cart item:

```http
DELETE /app/cart/items/9001
Authorization: Bearer app_access_token
```

Clear cart:

```http
DELETE /app/cart/items
Authorization: Bearer app_access_token
```

Business error behavior:

```text
401       missing token or admin token used on /app/cart/**
100400    validation failed for missing skuId or quantity outside 1..999
200001    SPU is not ON_SALE or category is not ENABLED
200002    SKU does not exist or SKU status is not ENABLED
200100    requested quantity exceeds current stock
250001    cart item does not exist for the current user
```

## File Structure

- Create: `backend/shop-server/src/main/resources/db/migration/V4__cart.sql`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/entity/CartItem.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/mapper/CartItemMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/AddCartItemRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/UpdateCartQuantityRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/CartItemResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/CartListResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/service/AppCartService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/AppCartController.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/cart/CartSchemaTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/cart/AppCartControllerTest.java`
- Modify: `miniprogram/types/api.ts`
- Modify: `miniprogram/services/auth.ts`
- Create: `miniprogram/services/cart.ts`
- Modify: `miniprogram/pages/product/detail/detail.ts`
- Modify: `miniprogram/pages/product/detail/detail.wxml`
- Modify: `miniprogram/pages/product/detail/detail.wxss`
- Modify: `miniprogram/app.json`
- Create: `miniprogram/pages/cart/cart.json`
- Create: `miniprogram/pages/cart/cart.ts`
- Create: `miniprogram/pages/cart/cart.wxml`
- Create: `miniprogram/pages/cart/cart.wxss`
- Modify: `docs/dev-setup.md`
- Modify: `docs/smoke-checks.md`

---

### Task 1: Backend Cart Schema And DTO Contracts

**Files:**
- Create: `backend/shop-server/src/main/resources/db/migration/V4__cart.sql`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/entity/CartItem.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/mapper/CartItemMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/AddCartItemRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/UpdateCartQuantityRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/CartItemResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/CartListResponse.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/cart/CartSchemaTest.java`

**Interfaces:**
- Consumes: Existing `app_user`, `product_spu`, `product_sku`, and `product_category` tables from migrations V2 and V3.
- Produces: `cart_item` table; `ErrorCode.CART_ITEM_NOT_FOUND`; request DTOs `AddCartItemRequest(Long skuId, Integer quantity)` and `UpdateCartQuantityRequest(Integer quantity)`; response DTOs `CartItemResponse` and `CartListResponse`.

- [ ] **Step 1: Write the failing schema test**

Create `backend/shop-server/src/test/java/org/muybaby/shopserver/cart/CartSchemaTest.java`:

```java
package org.muybaby.shopserver.cart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CartSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void cartItemTableAcceptsOneSkuPerUserAndKeepsQuantity() {
        jdbcClient.sql("""
                        insert into app_user (id, openid, phone_authorized, status)
                        values (9911, 'cart-schema-openid', false, 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, icon, sort_order, status)
                        values (9912, 0, 'Cart Schema Category', '', 1, 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu (id, category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values (9913, 9912, 'Cart Schema SPU', 'subtitle', 'https://example.test/main.jpg', 'A,B', '<p>detail</p>', 1, 'ON_SALE')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_sku (id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent, stock_available, weight_gram, image, status, sort_order)
                        values (9914, 9913, 'CART-SCHEMA-SKU', '{"规格":"300g"}', '300g', 3990, 4990, 10, 300, 'https://example.test/sku.jpg', 'ENABLED', 1)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into cart_item (id, user_id, sku_id, quantity)
                        values (9915, 9911, 9914, 2)
                        """)
                .update();

        Integer quantity = jdbcClient.sql("select quantity from cart_item where user_id = 9911 and sku_id = 9914")
                .query(Integer.class)
                .single();

        assertThat(quantity).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run the schema test to verify it fails**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=CartSchemaTest test
```

Expected: FAIL because table `cart_item` does not exist.

- [ ] **Step 3: Add the Flyway migration**

Create `backend/shop-server/src/main/resources/db/migration/V4__cart.sql`:

```sql
CREATE TABLE cart_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cart_item_user_sku UNIQUE (user_id, sku_id)
);

CREATE INDEX idx_cart_item_user_updated ON cart_item(user_id, updated_at);
CREATE INDEX idx_cart_item_sku ON cart_item(sku_id);
```

- [ ] **Step 4: Add cart entity, mapper, DTOs, and error code**

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/entity/CartItem.java`:

```java
package org.muybaby.shopserver.cart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("cart_item")
public record CartItem(
        @TableId(type = IdType.AUTO) Long id,
        Long userId,
        Long skuId,
        Integer quantity,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/mapper/CartItemMapper.java`:

```java
package org.muybaby.shopserver.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.cart.entity.CartItem;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/AddCartItemRequest.java`:

```java
package org.muybaby.shopserver.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(999) Integer quantity
) {
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/UpdateCartQuantityRequest.java`:

```java
package org.muybaby.shopserver.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateCartQuantityRequest(
        @NotNull @Min(1) @Max(999) Integer quantity
) {
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/CartItemResponse.java`:

```java
package org.muybaby.shopserver.cart.dto;

import java.time.LocalDateTime;

public record CartItemResponse(
        Long id,
        Long skuId,
        Long spuId,
        String productTitle,
        String productSubtitle,
        String mainImage,
        String skuImage,
        String displayImage,
        String specText,
        Long priceCent,
        Long originalPriceCent,
        Integer quantity,
        Long lineAmountCent,
        Integer stockAvailable,
        String skuStatus,
        String spuStatus,
        Boolean available,
        String unavailableReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/dto/CartListResponse.java`:

```java
package org.muybaby.shopserver.cart.dto;

import java.util.List;

public record CartListResponse(
        List<CartItemResponse> items,
        Integer totalQuantity,
        Long totalAmountCent,
        Integer unavailableCount
) {
}
```

Modify `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java` by adding the cart error between `STOCK_SHORTAGE` and `COUPON_UNAVAILABLE`:

```java
CART_ITEM_NOT_FOUND(250001, "Cart item not found"),
```

- [ ] **Step 5: Run the schema test to verify it passes**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=CartSchemaTest test
```

Expected: PASS with `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add backend/shop-server/src/main/resources/db/migration/V4__cart.sql \
  backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java \
  backend/shop-server/src/main/java/org/muybaby/shopserver/cart \
  backend/shop-server/src/test/java/org/muybaby/shopserver/cart/CartSchemaTest.java
git commit -m "feat: add cart schema contracts"
```

---

### Task 2: App Cart Backend API

**Files:**
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/service/AppCartService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/AppCartController.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/cart/AppCartControllerTest.java`

**Interfaces:**
- Consumes: `CartListResponse`, `CartItemResponse`, `AddCartItemRequest`, `UpdateCartQuantityRequest`, `AuthenticatedPrincipal`, `TokenKind.APP`, product catalog tables.
- Produces: app cart endpoints `GET /app/cart/items`, `POST /app/cart/items`, `PUT /app/cart/items/{cartItemId}/quantity`, `DELETE /app/cart/items/{cartItemId}`, `DELETE /app/cart/items`.

- [ ] **Step 1: Write the failing controller/security tests**

Create `backend/shop-server/src/test/java/org/muybaby/shopserver/cart/AppCartControllerTest.java`:

```java
package org.muybaby.shopserver.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppCartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void cartApisRequireAppToken() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(get("/app/cart/items"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addListUpdateDeleteAndClearCartItemsForCurrentUser() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long skuId = createPublishedSku("CART-API-SKU-1", 3990L, 4990L, 10, "ENABLED");

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":2}
                                """.formatted(skuId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(skuId))
                .andExpect(jsonPath("$.data.quantity").value(2))
                .andExpect(jsonPath("$.data.priceCent").value(3990))
                .andExpect(jsonPath("$.data.lineAmountCent").value(7980))
                .andExpect(jsonPath("$.data.available").value(true));

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":3}
                                """.formatted(skuId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andExpect(jsonPath("$.data.lineAmountCent").value(19950));

        String listResponse = mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.totalQuantity").value(5))
                .andExpect(jsonPath("$.data.totalAmountCent").value(19950))
                .andExpect(jsonPath("$.data.unavailableCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long cartItemId = objectMapper.readTree(listResponse).path("data").path("items").get(0).path("id").asLong();

        mockMvc.perform(put("/app/cart/items/{cartItemId}/quantity", cartItemId)
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(4))
                .andExpect(jsonPath("$.data.lineAmountCent").value(15960));

        mockMvc.perform(delete("/app/cart/items/{cartItemId}", cartItemId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void addAndUpdateValidateProductStatusAndStock() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long skuId = createPublishedSku("CART-STOCK-SKU-1", 2990L, 3990L, 2, "ENABLED");

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":3}
                                """.formatted(skuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200100));

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":2}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        String listResponse = mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long cartItemId = objectMapper.readTree(listResponse).path("data").path("items").get(0).path("id").asLong();

        jdbcClient.sql("""
                        update product_sku
                        set stock_available = 1, updated_at = current_timestamp
                        where id = :skuId
                        """)
                .param("skuId", skuId)
                .update();

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].available").value(false))
                .andExpect(jsonPath("$.data.items[0].unavailableReason").value("STOCK_SHORTAGE"))
                .andExpect(jsonPath("$.data.unavailableCount").value(1));

        mockMvc.perform(put("/app/cart/items/{cartItemId}/quantity", cartItemId)
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200100));
    }

    @Test
    void unavailableSkuAndUnpublishedSpuReturnBusinessErrors() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long disabledSkuId = createPublishedSku("CART-DISABLED-SKU", 2990L, 3990L, 5, "ENABLED");
        long enabledSkuId = createPublishedSku("CART-OFFSALE-SKU", 3990L, 4990L, 5, "ENABLED");
        long spuId = jdbcClient.sql("select spu_id from product_sku where id = :skuId")
                .param("skuId", enabledSkuId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        update product_sku
                        set status = 'DISABLED', updated_at = current_timestamp
                        where id = :skuId
                        """)
                .param("skuId", disabledSkuId)
                .update();

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(disabledSkuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200002));

        adminProductService.unpublishSpu(spuId);

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(enabledSkuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));
    }

    @Test
    void cartRowsAreIsolatedByAppUser() throws Exception {
        String firstUserToken = appLoginAndExtractToken("test-login-code");
        String secondUserToken = appLoginAndExtractToken("second-login-code");
        long skuId = createPublishedSku("CART-ISOLATED-SKU", 5990L, 6990L, 10, "ENABLED");

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + firstUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + secondUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    private String appLoginAndExtractToken(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long createPublishedSku(String skuCode, long priceCent, long originalPriceCent, int stock, String skuStatus) {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Cart Category " + skuCode, "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Cart SPU " + skuCode,
                "Cart subtitle",
                "https://example.test/cart-main.jpg",
                "牛油浓香,手工炒制",
                "<p>Cart detail</p>",
                1,
                List.of("https://example.test/cart-gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, skuCode, "{\"规格\":\"300g\"}", "300g", priceCent, originalPriceCent, stock, 300, "https://example.test/cart-sku.jpg", skuStatus, 1))
        ));
        adminProductService.publishSpu(spuId);
        return jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();
    }
}
```

- [ ] **Step 2: Run the controller test to verify it fails**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AppCartControllerTest test
```

Expected: FAIL because `AppCartController` does not exist.

- [ ] **Step 3: Implement the cart service and controller**

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/service/AppCartService.java` with these public methods and SQL behavior:

```java
package org.muybaby.shopserver.cart.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.cart.dto.AddCartItemRequest;
import org.muybaby.shopserver.cart.dto.CartItemResponse;
import org.muybaby.shopserver.cart.dto.CartListResponse;
import org.muybaby.shopserver.cart.dto.UpdateCartQuantityRequest;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AppCartService {

    private static final int MAX_QUANTITY = 999;
    private static final String CATEGORY_ENABLED = "ENABLED";

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public AppCartService(JdbcClient jdbcClient, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    public CartListResponse list(AuthenticatedPrincipal principal) {
        Long userId = requireAppUser(principal);
        List<CartItemResponse> items = findCartItems(userId);
        int totalQuantity = items.stream()
                .mapToInt(CartItemResponse::quantity)
                .sum();
        long totalAmountCent = items.stream()
                .filter(CartItemResponse::available)
                .mapToLong(CartItemResponse::lineAmountCent)
                .sum();
        int unavailableCount = (int) items.stream()
                .filter(item -> !item.available())
                .count();
        return new CartListResponse(items, totalQuantity, totalAmountCent, unavailableCount);
    }

    @Transactional
    public CartItemResponse add(AuthenticatedPrincipal principal, AddCartItemRequest request) {
        Long userId = requireAppUser(principal);
        int requestQuantity = requireQuantity(request.quantity());
        SellableSkuRow sku = findSellableSku(request.skuId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_UNAVAILABLE));
        Optional<CartQuantityRow> existingItem = findCartItemBySkuForUpdate(userId, request.skuId());
        int targetQuantity = existingItem
                .map(item -> item.quantity() + requestQuantity)
                .orElse(requestQuantity);
        requireSellable(sku, targetQuantity);

        Long cartItemId = existingItem
                .map(item -> {
                    updateQuantityById(item.id(), targetQuantity);
                    return item.id();
                })
                .orElseGet(() -> insertCartItem(userId, request.skuId(), requestQuantity));
        return findCartItem(userId, cartItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
    }

    @Transactional
    public CartItemResponse updateQuantity(AuthenticatedPrincipal principal, Long cartItemId, UpdateCartQuantityRequest request) {
        Long userId = requireAppUser(principal);
        int targetQuantity = requireQuantity(request.quantity());
        CartQuantityRow item = findCartItemByIdForUpdate(userId, cartItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
        SellableSkuRow sku = findSellableSku(item.skuId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_UNAVAILABLE));
        requireSellable(sku, targetQuantity);
        updateQuantityById(cartItemId, targetQuantity);
        return findCartItem(userId, cartItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, Long cartItemId) {
        Long userId = requireAppUser(principal);
        int deletedRows = jdbcClient.sql("""
                        DELETE FROM cart_item
                        WHERE id = :cartItemId
                          AND user_id = :userId
                        """)
                .param("cartItemId", cartItemId)
                .param("userId", userId)
                .update();
        if (deletedRows != 1) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    @Transactional
    public void clear(AuthenticatedPrincipal principal) {
        Long userId = requireAppUser(principal);
        jdbcClient.sql("""
                        DELETE FROM cart_item
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .update();
    }

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private int requireQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return quantity;
    }

    private void requireSellable(SellableSkuRow sku, int quantity) {
        if (!SkuStatus.ENABLED.name().equals(sku.skuStatus())) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
        if (!ProductStatus.ON_SALE.name().equals(sku.spuStatus()) || !CATEGORY_ENABLED.equals(sku.categoryStatus())) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        if (sku.stockAvailable() < quantity) {
            throw new BusinessException(ErrorCode.STOCK_SHORTAGE);
        }
    }

    private Optional<SellableSkuRow> findSellableSku(Long skuId) {
        return jdbcClient.sql("""
                        SELECT k.id AS sku_id,
                               k.stock_available,
                               k.status AS sku_status,
                               s.status AS spu_status,
                               c.status AS category_status
                        FROM product_sku k
                        JOIN product_spu s ON s.id = k.spu_id
                        JOIN product_category c ON c.id = s.category_id
                        WHERE k.id = :skuId
                        """)
                .param("skuId", skuId)
                .query(this::mapSellableSku)
                .optional();
    }

    private Optional<CartQuantityRow> findCartItemBySkuForUpdate(Long userId, Long skuId) {
        return jdbcClient.sql("""
                        SELECT id, sku_id, quantity
                        FROM cart_item
                        WHERE user_id = :userId
                          AND sku_id = :skuId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .param("skuId", skuId)
                .query(this::mapCartQuantity)
                .optional();
    }

    private Optional<CartQuantityRow> findCartItemByIdForUpdate(Long userId, Long cartItemId) {
        return jdbcClient.sql("""
                        SELECT id, sku_id, quantity
                        FROM cart_item
                        WHERE user_id = :userId
                          AND id = :cartItemId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .param("cartItemId", cartItemId)
                .query(this::mapCartQuantity)
                .optional();
    }

    private Long insertCartItem(Long userId, Long skuId, Integer quantity) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO cart_item (user_id, sku_id, quantity)
                        VALUES (:userId, :skuId, :quantity)
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("skuId", skuId)
                        .addValue("quantity", quantity),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to generate cart item id");
        }
        return key.longValue();
    }

    private void updateQuantityById(Long cartItemId, Integer quantity) {
        int updatedRows = jdbcClient.sql("""
                        UPDATE cart_item
                        SET quantity = :quantity,
                            updated_at = :updatedAt
                        WHERE id = :cartItemId
                        """)
                .param("quantity", quantity)
                .param("updatedAt", LocalDateTime.now())
                .param("cartItemId", cartItemId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    private List<CartItemResponse> findCartItems(Long userId) {
        return jdbcClient.sql(cartItemSelectSql() + """
                        WHERE ci.user_id = :userId
                        ORDER BY ci.updated_at DESC, ci.id DESC
                        """)
                .param("userId", userId)
                .query(this::mapCartItem)
                .list();
    }

    private Optional<CartItemResponse> findCartItem(Long userId, Long cartItemId) {
        return jdbcClient.sql(cartItemSelectSql() + """
                        WHERE ci.user_id = :userId
                          AND ci.id = :cartItemId
                        """)
                .param("userId", userId)
                .param("cartItemId", cartItemId)
                .query(this::mapCartItem)
                .optional();
    }

    private String cartItemSelectSql() {
        return """
                SELECT ci.id AS cart_item_id,
                       ci.sku_id,
                       ci.quantity,
                       ci.created_at,
                       ci.updated_at,
                       k.spu_id,
                       k.spec_text,
                       k.price_cent,
                       k.original_price_cent,
                       k.stock_available,
                       k.image AS sku_image,
                       k.status AS sku_status,
                       s.title AS product_title,
                       s.subtitle AS product_subtitle,
                       s.main_image,
                       s.status AS spu_status,
                       c.status AS category_status
                FROM cart_item ci
                LEFT JOIN product_sku k ON k.id = ci.sku_id
                LEFT JOIN product_spu s ON s.id = k.spu_id
                LEFT JOIN product_category c ON c.id = s.category_id
                """;
    }

    private CartItemResponse mapCartItem(ResultSet rs, int rowNum) throws SQLException {
        int quantity = rs.getInt("quantity");
        Long spuId = valueOrZero(rs.getObject("spu_id", Long.class));
        Long priceCent = valueOrZero(rs.getObject("price_cent", Long.class));
        Long originalPriceCent = valueOrZero(rs.getObject("original_price_cent", Long.class));
        Integer stockAvailable = valueOrZero(rs.getObject("stock_available", Integer.class));
        String skuStatus = rs.getString("sku_status");
        String spuStatus = rs.getString("spu_status");
        String categoryStatus = rs.getString("category_status");
        String unavailableReason = unavailableReason(skuStatus, spuStatus, categoryStatus, stockAvailable, quantity);
        String skuImage = rs.getString("sku_image");
        String mainImage = defaultString(rs.getString("main_image"));
        return new CartItemResponse(
                rs.getLong("cart_item_id"),
                rs.getLong("sku_id"),
                spuId,
                defaultString(rs.getString("product_title")),
                defaultString(rs.getString("product_subtitle")),
                mainImage,
                defaultString(skuImage),
                StringUtils.hasText(skuImage) ? skuImage : mainImage,
                defaultString(rs.getString("spec_text")),
                priceCent,
                originalPriceCent,
                quantity,
                priceCent * quantity,
                stockAvailable,
                skuStatus,
                spuStatus,
                unavailableReason == null,
                unavailableReason,
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private String unavailableReason(String skuStatus, String spuStatus, String categoryStatus, int stockAvailable, int quantity) {
        if (!StringUtils.hasText(skuStatus)) {
            return "SKU_UNAVAILABLE";
        }
        if (!SkuStatus.ENABLED.name().equals(skuStatus)) {
            return "SKU_UNAVAILABLE";
        }
        if (!ProductStatus.ON_SALE.name().equals(spuStatus) || !CATEGORY_ENABLED.equals(categoryStatus)) {
            return "PRODUCT_UNAVAILABLE";
        }
        if (stockAvailable < quantity) {
            return "STOCK_SHORTAGE";
        }
        return null;
    }

    private Long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private Integer valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private SellableSkuRow mapSellableSku(ResultSet rs, int rowNum) throws SQLException {
        return new SellableSkuRow(
                rs.getLong("sku_id"),
                rs.getInt("stock_available"),
                rs.getString("sku_status"),
                rs.getString("spu_status"),
                rs.getString("category_status")
        );
    }

    private CartQuantityRow mapCartQuantity(ResultSet rs, int rowNum) throws SQLException {
        return new CartQuantityRow(
                rs.getLong("id"),
                rs.getLong("sku_id"),
                rs.getInt("quantity")
        );
    }

    private record SellableSkuRow(
            Long skuId,
            Integer stockAvailable,
            String skuStatus,
            String spuStatus,
            String categoryStatus
    ) {
    }

    private record CartQuantityRow(
            Long id,
            Long skuId,
            Integer quantity
    ) {
    }
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/AppCartController.java`:

```java
package org.muybaby.shopserver.cart;

import jakarta.validation.Valid;
import org.muybaby.shopserver.cart.dto.AddCartItemRequest;
import org.muybaby.shopserver.cart.dto.CartItemResponse;
import org.muybaby.shopserver.cart.dto.CartListResponse;
import org.muybaby.shopserver.cart.dto.UpdateCartQuantityRequest;
import org.muybaby.shopserver.cart.service.AppCartService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/cart")
public class AppCartController {

    private final AppCartService appCartService;

    public AppCartController(AppCartService appCartService) {
        this.appCartService = appCartService;
    }

    @GetMapping("/items")
    public ApiResponse<CartListResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success(appCartService.list(principal));
    }

    @PostMapping("/items")
    public ApiResponse<CartItemResponse> add(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        return ApiResponse.success(appCartService.add(principal, request));
    }

    @PutMapping("/items/{cartItemId}/quantity")
    public ApiResponse<CartItemResponse> updateQuantity(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartQuantityRequest request
    ) {
        return ApiResponse.success(appCartService.updateQuantity(principal, cartItemId, request));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long cartItemId
    ) {
        appCartService.delete(principal, cartItemId);
        return ApiResponse.success();
    }

    @DeleteMapping("/items")
    public ApiResponse<Void> clear(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        appCartService.clear(principal);
        return ApiResponse.success();
    }
}
```

- [ ] **Step 4: Run the cart backend tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='CartSchemaTest,AppCartControllerTest' test
```

Expected: PASS with `BUILD SUCCESS`.

- [ ] **Step 5: Run the security regression test**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='AppAuthControllerTest,SecurityConfigTest,PathTokenKindResolverTest,AppCartControllerTest' test
```

Expected: PASS with `BUILD SUCCESS`; app cart APIs reject missing tokens and admin tokens.

- [ ] **Step 6: Commit**

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/cart \
  backend/shop-server/src/test/java/org/muybaby/shopserver/cart
git commit -m "feat: add app cart APIs"
```

---

### Task 3: Mini Program Cart Service And Product Detail Entry

**Files:**
- Modify: `miniprogram/types/api.ts`
- Modify: `miniprogram/services/auth.ts`
- Create: `miniprogram/services/cart.ts`
- Modify: `miniprogram/pages/product/detail/detail.ts`
- Modify: `miniprogram/pages/product/detail/detail.wxml`
- Modify: `miniprogram/pages/product/detail/detail.wxss`

**Interfaces:**
- Consumes: Backend `/app/cart/**` contracts from Task 2 and existing `request()` auth header behavior.
- Produces: TypeScript cart types, cart API client functions, `ensureAppLogin()`, and product-detail add-to-cart behavior.

- [ ] **Step 1: Add cart API types**

Append these interfaces to `miniprogram/types/api.ts`:

```ts
export interface CartItem {
  id: number;
  skuId: number;
  spuId: number;
  productTitle: string;
  productSubtitle: string;
  mainImage: string;
  skuImage: string;
  displayImage: string;
  specText: string;
  priceCent: number;
  originalPriceCent: number;
  quantity: number;
  lineAmountCent: number;
  stockAvailable: number;
  skuStatus: "ENABLED" | "DISABLED";
  spuStatus: "DRAFT" | "ON_SALE" | "OFF_SALE";
  available: boolean;
  unavailableReason: "SKU_UNAVAILABLE" | "PRODUCT_UNAVAILABLE" | "STOCK_SHORTAGE" | null;
  createdAt: string;
  updatedAt: string;
}

export interface CartListResponse {
  items: CartItem[];
  totalQuantity: number;
  totalAmountCent: number;
  unavailableCount: number;
}
```

- [ ] **Step 2: Add login helper for token-dependent pages**

Modify `miniprogram/services/auth.ts` by adding:

```ts
export async function ensureAppLogin(): Promise<void> {
  const app = getAppTokenState();
  if (app.globalData.token) {
    return;
  }

  await silentLogin();
}
```

- [ ] **Step 3: Create the cart service**

Create `miniprogram/services/cart.ts`:

```ts
import type { CartItem, CartListResponse } from "../types/api";
import { request } from "../utils/request";

export interface AddCartItemPayload {
  skuId: number;
  quantity: number;
}

export interface UpdateCartQuantityPayload {
  quantity: number;
}

export function getCartItems(): Promise<CartListResponse> {
  return request<CartListResponse>({
    url: "/app/cart/items"
  });
}

export function addCartItem(payload: AddCartItemPayload): Promise<CartItem> {
  return request<CartItem>({
    url: "/app/cart/items",
    method: "POST",
    data: payload
  });
}

export function updateCartItemQuantity(
  cartItemId: number,
  payload: UpdateCartQuantityPayload
): Promise<CartItem> {
  return request<CartItem>({
    url: `/app/cart/items/${cartItemId}/quantity`,
    method: "PUT",
    data: payload
  });
}

export function deleteCartItem(cartItemId: number): Promise<void> {
  return request<void>({
    url: `/app/cart/items/${cartItemId}`,
    method: "DELETE"
  });
}

export function clearCart(): Promise<void> {
  return request<void>({
    url: "/app/cart/items",
    method: "DELETE"
  });
}
```

- [ ] **Step 4: Connect product detail add-to-cart behavior**

Modify `miniprogram/pages/product/detail/detail.ts`:

```ts
import type { ProductDetail, ProductSku } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import { addCartItem } from "../../../services/cart";
import { formatPrice, getProductDetail } from "../../../services/product";
```

Add `addingCart: false` to `data`.

Add this method inside `Page({ ... })`:

```ts
async onAddCartTap() {
  if (!this.data.selectedSkuId || this.data.addingCart) {
    return;
  }

  this.setData({
    addingCart: true
  });

  try {
    await ensureAppLogin();
    await addCartItem({
      skuId: this.data.selectedSkuId,
      quantity: 1
    });
    wx.showToast({
      title: "已加入购物车",
      icon: "success"
    });
  } catch (error) {
    wx.showToast({
      title: error instanceof Error ? error.message : "加入失败",
      icon: "none"
    });
  } finally {
    this.setData({
      addingCart: false
    });
  }
}
```

Modify `miniprogram/pages/product/detail/detail.wxml` action bar:

```xml
<view class="action-bar">
  <button
    class="action-button cart-button"
    disabled="{{!selectedSkuId || addingCart}}"
    loading="{{addingCart}}"
    bindtap="onAddCartTap"
  >
    加入购物车
  </button>
  <button class="action-button buy-button" disabled="true">立即购买</button>
</view>
```

Modify `miniprogram/pages/product/detail/detail.wxss` only if the loading state needs spacing; keep existing button colors and fixed bar layout:

```css
.action-button[disabled] {
  opacity: 0.55;
}
```

- [ ] **Step 5: Run mini program typecheck**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected: PASS with no TypeScript diagnostics.

- [ ] **Step 6: Commit**

```bash
git add miniprogram/types/api.ts \
  miniprogram/services/auth.ts \
  miniprogram/services/cart.ts \
  miniprogram/pages/product/detail/detail.ts \
  miniprogram/pages/product/detail/detail.wxml \
  miniprogram/pages/product/detail/detail.wxss
git commit -m "feat: add mini program cart service"
```

---

### Task 4: Mini Program Cart Page

**Files:**
- Modify: `miniprogram/app.json`
- Create: `miniprogram/pages/cart/cart.json`
- Create: `miniprogram/pages/cart/cart.ts`
- Create: `miniprogram/pages/cart/cart.wxml`
- Create: `miniprogram/pages/cart/cart.wxss`

**Interfaces:**
- Consumes: `ensureAppLogin()`, `getCartItems()`, `updateCartItemQuantity()`, `deleteCartItem()`, `clearCart()`, and `formatPrice()`.
- Produces: Cart tab page with list, current price display, quantity controls, delete, clear, unavailable item states, and disabled checkout button.

- [ ] **Step 1: Register the cart page and tab**

Modify `miniprogram/app.json`:

```json
{
  "pages": [
    "pages/home/home",
    "pages/product/list/list",
    "pages/product/detail/detail",
    "pages/cart/cart",
    "pages/profile/profile",
    "pages/order/detail/detail"
  ],
  "tabBar": {
    "list": [
      {
        "pagePath": "pages/home/home",
        "text": "首页"
      },
      {
        "pagePath": "pages/product/list/list",
        "text": "分类"
      },
      {
        "pagePath": "pages/cart/cart",
        "text": "购物车"
      },
      {
        "pagePath": "pages/profile/profile",
        "text": "我的"
      }
    ]
  }
}
```

Keep the existing `window`, `color`, `selectedColor`, `backgroundColor`, and `usingComponents` fields unchanged.

- [ ] **Step 2: Create page config**

Create `miniprogram/pages/cart/cart.json`:

```json
{
  "navigationBarTitleText": "购物车",
  "enablePullDownRefresh": true
}
```

- [ ] **Step 3: Create cart page logic**

Create `miniprogram/pages/cart/cart.ts`:

```ts
import type { CartItem } from "../../types/api";
import { ensureAppLogin } from "../../services/auth";
import {
  clearCart,
  deleteCartItem,
  getCartItems,
  updateCartItemQuantity
} from "../../services/cart";
import { formatPrice } from "../../services/product";

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface CartItemView extends CartItem {
  priceText: string;
  lineAmountText: string;
  stockText: string;
  unavailableText: string;
}

function unavailableText(item: CartItem): string {
  if (!item.unavailableReason) {
    return "";
  }
  if (item.unavailableReason === "SKU_UNAVAILABLE") {
    return "规格已下架";
  }
  if (item.unavailableReason === "PRODUCT_UNAVAILABLE") {
    return "商品已下架";
  }
  return "库存不足";
}

function toCartItemView(item: CartItem): CartItemView {
  return {
    ...item,
    priceText: formatPrice(item.priceCent),
    lineAmountText: formatPrice(item.lineAmountCent),
    stockText: `库存 ${item.stockAvailable}`,
    unavailableText: unavailableText(item)
  };
}

Page({
  data: {
    loading: false,
    clearing: false,
    errorText: "",
    items: [] as CartItemView[],
    totalQuantity: 0,
    totalAmountText: formatPrice(0),
    unavailableCount: 0
  },
  async onShow() {
    await this.loadCart();
  },
  async onPullDownRefresh() {
    await this.loadCart();
    wx.stopPullDownRefresh();
  },
  async loadCart() {
    this.setData({
      loading: true,
      errorText: ""
    });

    try {
      await ensureAppLogin();
      const response = await getCartItems();
      this.setData({
        items: response.items.map(toCartItemView),
        totalQuantity: response.totalQuantity,
        totalAmountText: formatPrice(response.totalAmountCent),
        unavailableCount: response.unavailableCount
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "购物车加载失败"
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  },
  async onQuantityMinus(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item || item.quantity <= 1) {
      return;
    }
    await this.updateQuantity(item.id, item.quantity - 1);
  },
  async onQuantityPlus(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item) {
      return;
    }
    await this.updateQuantity(item.id, item.quantity + 1);
  },
  async updateQuantity(cartItemId: number, quantity: number) {
    try {
      await updateCartItemQuantity(cartItemId, { quantity });
      await this.loadCart();
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "数量修改失败",
        icon: "none"
      });
    }
  },
  async onDeleteTap(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item) {
      return;
    }

    try {
      await deleteCartItem(item.id);
      await this.loadCart();
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "删除失败",
        icon: "none"
      });
    }
  },
  async onClearTap() {
    if (this.data.items.length === 0 || this.data.clearing) {
      return;
    }

    this.setData({
      clearing: true
    });

    try {
      await clearCart();
      await this.loadCart();
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "清空失败",
        icon: "none"
      });
    } finally {
      this.setData({
        clearing: false
      });
    }
  },
  onProductTap(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item || item.spuId <= 0) {
      return;
    }
    wx.navigateTo({
      url: `/pages/product/detail/detail?id=${item.spuId}`
    });
  },
  findItem(event: DatasetEvent): CartItemView | undefined {
    const cartItemId = Number(event.currentTarget.dataset.id);
    if (!Number.isFinite(cartItemId) || cartItemId <= 0) {
      return undefined;
    }
    return this.data.items.find((item) => item.id === cartItemId);
  }
});
```

- [ ] **Step 4: Create cart page markup**

Create `miniprogram/pages/cart/cart.wxml`:

```xml
<view class="page cart-page">
  <view class="cart-header">
    <view>
      <view class="cart-title">购物车</view>
      <view class="cart-subtitle">{{totalQuantity}} 件商品</view>
    </view>
    <button
      class="clear-button"
      size="mini"
      loading="{{clearing}}"
      disabled="{{items.length === 0 || clearing}}"
      bindtap="onClearTap"
    >
      清空
    </button>
  </view>

  <view wx:if="{{loading}}" class="empty-state">购物车加载中...</view>
  <view wx:elif="{{errorText}}" class="empty-state">{{errorText}}</view>
  <view wx:elif="{{items.length === 0}}" class="empty-state">购物车是空的</view>
  <view wx:else class="cart-list">
    <view
      wx:for="{{items}}"
      wx:key="id"
      class="cart-item {{item.available ? '' : 'unavailable'}}"
    >
      <image
        class="item-image"
        src="{{item.displayImage || item.mainImage}}"
        mode="aspectFill"
        data-id="{{item.id}}"
        bindtap="onProductTap"
      />
      <view class="item-body">
        <view class="item-title" data-id="{{item.id}}" bindtap="onProductTap">{{item.productTitle}}</view>
        <view class="item-spec">{{item.specText}}</view>
        <view wx:if="{{!item.available}}" class="unavailable-text">{{item.unavailableText}}</view>
        <view class="item-meta">
          <view>
            <view class="price">{{item.priceText}}</view>
            <view class="stock">{{item.stockText}}</view>
          </view>
          <view class="quantity-row">
            <button class="quantity-button" data-id="{{item.id}}" disabled="{{item.quantity <= 1}}" bindtap="onQuantityMinus">-</button>
            <view class="quantity-value">{{item.quantity}}</view>
            <button class="quantity-button" data-id="{{item.id}}" bindtap="onQuantityPlus">+</button>
          </view>
        </view>
        <view class="item-footer">
          <view class="line-amount">{{item.lineAmountText}}</view>
          <button class="delete-button" size="mini" data-id="{{item.id}}" bindtap="onDeleteTap">删除</button>
        </view>
      </view>
    </view>
  </view>

  <view class="settlement-bar">
    <view class="amount-block">
      <view class="amount-label">合计</view>
      <view class="amount-value">{{totalAmountText}}</view>
    </view>
    <button class="checkout-button" disabled="true">去结算</button>
  </view>
</view>
```

- [ ] **Step 5: Create cart page styles**

Create `miniprogram/pages/cart/cart.wxss`:

```css
.cart-page {
  min-height: 100vh;
  padding: 24rpx 24rpx 150rpx;
  background: #f7f3ee;
  box-sizing: border-box;
}

.cart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  margin-bottom: 24rpx;
}

.cart-title {
  color: #241f1c;
  font-size: 40rpx;
  font-weight: 800;
  line-height: 52rpx;
}

.cart-subtitle {
  margin-top: 4rpx;
  color: #7a6f68;
  font-size: 24rpx;
}

.clear-button,
.delete-button {
  color: #8a6f61;
}

.empty-state {
  padding: 160rpx 32rpx;
  color: #7a6f68;
  font-size: 28rpx;
  text-align: center;
}

.cart-list {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.cart-item {
  display: grid;
  grid-template-columns: 168rpx minmax(0, 1fr);
  gap: 20rpx;
  padding: 22rpx;
  border-radius: 12rpx;
  background: #ffffff;
  box-shadow: 0 10rpx 28rpx rgba(110, 82, 67, 0.08);
  box-sizing: border-box;
}

.cart-item.unavailable {
  opacity: 0.72;
}

.item-image {
  width: 168rpx;
  height: 168rpx;
  border-radius: 10rpx;
  background: #ead9cf;
}

.item-body {
  min-width: 0;
}

.item-title {
  color: #241f1c;
  font-size: 28rpx;
  font-weight: 800;
  line-height: 38rpx;
}

.item-spec,
.stock {
  color: #7a6f68;
  font-size: 22rpx;
  line-height: 32rpx;
}

.item-spec {
  margin-top: 8rpx;
}

.unavailable-text {
  display: inline-flex;
  margin-top: 10rpx;
  padding: 4rpx 10rpx;
  border-radius: 6rpx;
  background: #f5dfd8;
  color: #b3261e;
  font-size: 22rpx;
}

.item-meta,
.item-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.item-meta {
  margin-top: 18rpx;
}

.price,
.line-amount {
  color: #b3261e;
  font-weight: 800;
}

.price {
  font-size: 30rpx;
}

.line-amount {
  font-size: 28rpx;
}

.quantity-row {
  display: grid;
  grid-template-columns: 56rpx 64rpx 56rpx;
  align-items: center;
  border: 1rpx solid #ead9cf;
  border-radius: 8rpx;
  overflow: hidden;
}

.quantity-button {
  width: 56rpx;
  height: 54rpx;
  padding: 0;
  border-radius: 0;
  background: #fff8f4;
  color: #8a6f61;
  font-size: 28rpx;
  line-height: 54rpx;
}

.quantity-value {
  height: 54rpx;
  color: #241f1c;
  font-size: 24rpx;
  font-weight: 700;
  line-height: 54rpx;
  text-align: center;
}

.item-footer {
  margin-top: 16rpx;
}

.settlement-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240rpx;
  gap: 20rpx;
  align-items: center;
  padding: 18rpx 24rpx calc(18rpx + env(safe-area-inset-bottom));
  background: #ffffff;
  box-shadow: 0 -10rpx 30rpx rgba(68, 44, 32, 0.08);
  box-sizing: border-box;
}

.amount-label {
  color: #7a6f68;
  font-size: 22rpx;
}

.amount-value {
  margin-top: 4rpx;
  color: #b3261e;
  font-size: 36rpx;
  font-weight: 800;
}

.checkout-button {
  width: 240rpx;
  height: 84rpx;
  border-radius: 12rpx;
  background: #b3261e;
  color: #ffffff;
  font-size: 28rpx;
  font-weight: 800;
  line-height: 84rpx;
}
```

- [ ] **Step 6: Run mini program typecheck**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected: PASS with no TypeScript diagnostics.

- [ ] **Step 7: Commit**

```bash
git add miniprogram/app.json \
  miniprogram/pages/cart
git commit -m "feat: add mini program cart page"
```

---

### Task 5: Verification Docs And Cart Smoke Checks

**Files:**
- Modify: `docs/dev-setup.md`
- Modify: `docs/smoke-checks.md`

**Interfaces:**
- Consumes: Backend cart tests from Task 2 and mini program typecheck from Tasks 3 and 4.
- Produces: Focused cart test command in dev setup and real local cart smoke instructions.

- [ ] **Step 1: Update focused backend checks**

Modify `docs/dev-setup.md` by adding this section after the product catalog focused tests:

````markdown
Focused cart tests:

```bash
cd backend/shop-server
./mvnw -Dtest='CartSchemaTest,AppCartControllerTest,AppAuthControllerTest,SecurityConfigTest,PathTokenKindResolverTest' test
```
````

- [ ] **Step 2: Add cart smoke documentation**

Modify `docs/smoke-checks.md` by adding this section after Product Catalog Smoke Checks:

````markdown
## Cart Smoke Checks

This is a real local smoke check for the cart phase. It uses the local backend and local database path. In the `test` profile, WeChat login is still backed by the mock WeChat mini program client described in `docs/dev-setup.md`; product catalog and cart requests go through the real local backend APIs, not product or cart mocks.

Start backend:

```bash
cd backend/shop-server
./mvnw -Dspring-boot.run.profiles=test \
  -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.arguments=--spring.config.additional-location=file:src/test/resources/ \
  spring-boot:run
```

Admin login:

```bash
ADMIN_TOKEN=$(
  curl -s -X POST http://localhost:8080/admin/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"userName":"Super","password":"123456"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.token));'
)
```

Mini program login:

```bash
APP_TOKEN=$(
  curl -s -X POST http://localhost:8080/app/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"code":"test-login-code"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.token));'
)
```

Create category:

```bash
CATEGORY_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/categories \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"parentId":0,"name":"购物车牛油锅底","icon":"","sortOrder":20,"status":"ENABLED"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Create SPU and SKU:

```bash
SPU_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/spus \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"categoryId\":${CATEGORY_ID},\"title\":\"购物车重庆牛油火锅底料\",\"subtitle\":\"厚重牛油香\",\"mainImage\":\"https://example.test/cart-main.jpg\",\"sellingPoints\":\"牛油浓香,手工炒制\",\"detailHtml\":\"<p>适合3-5人火锅。</p>\",\"sortOrder\":20,\"images\":[\"https://example.test/cart-gallery-1.jpg\"],\"skus\":[{\"skuCode\":\"CART-HY-NY-300G\",\"specJson\":\"{\\\"口味\\\":\\\"牛油\\\",\\\"重量\\\":\\\"300g\\\"}\",\"specText\":\"牛油 / 300g\",\"priceCent\":3990,\"originalPriceCent\":4990,\"stockAvailable\":100,\"weightGram\":300,\"image\":\"https://example.test/cart-sku-300.jpg\",\"status\":\"ENABLED\",\"sortOrder\":1}]}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Publish SPU:

```bash
curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/publish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Read SKU id from app detail:

```bash
SKU_ID=$(
  curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.skus[0].id));'
)
```

Add to cart:

```bash
CART_ITEM_ID=$(
  curl -s -X POST http://localhost:8080/app/cart/items \
    -H "Authorization: Bearer ${APP_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"skuId\":${SKU_ID},\"quantity\":2}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.quantity !== 2) process.exit(1); console.log(body.data.id); });'
)
```

List cart and verify current SKU price:

```bash
curl -s http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const item = body.data.items[0]; if (item.priceCent !== 3990 || item.lineAmountCent !== 7980 || !item.available) process.exit(1); console.log(`${item.productTitle} ${item.quantity} ${item.lineAmountCent}`); });'
```

Update quantity:

```bash
curl -s -X PUT "http://localhost:8080/app/cart/items/${CART_ITEM_ID}/quantity" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"quantity":3}' \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.quantity !== 3 || body.data.lineAmountCent !== 11970) process.exit(1); console.log(body.data.quantity); });'
```

Delete item:

```bash
curl -s -X DELETE "http://localhost:8080/app/cart/items/${CART_ITEM_ID}" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Add again and clear cart:

```bash
curl -s -X POST http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"skuId\":${SKU_ID},\"quantity\":1}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.quantity !== 1) process.exit(1); console.log(body.data.quantity); });'

curl -s -X DELETE http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'

curl -s http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.items.length !== 0) process.exit(1); console.log(body.data.items.length); });'
```

Expected result:

```text
success
购物车重庆牛油火锅底料 2 7980
3
success
1
success
0
```
````

- [ ] **Step 3: Run focused backend tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='CartSchemaTest,AppCartControllerTest,AppAuthControllerTest,SecurityConfigTest,PathTokenKindResolverTest' test
```

Expected: PASS with `BUILD SUCCESS`.

- [ ] **Step 4: Run full backend tests**

Run:

```bash
cd backend/shop-server
./mvnw test
```

Expected: PASS with `BUILD SUCCESS`.

- [ ] **Step 5: Run mini program typecheck**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected: PASS with no TypeScript diagnostics.

- [ ] **Step 6: Run cart smoke checks**

Run the new `docs/smoke-checks.md#cart-smoke-checks` commands against the local backend test profile.

Expected: The smoke output includes:

```text
success
购物车重庆牛油火锅底料 2 7980
3
success
1
success
0
```

- [ ] **Step 7: Commit**

```bash
git add docs/dev-setup.md docs/smoke-checks.md
git commit -m "docs: add cart smoke checks"
```

---

## Final Verification Matrix

Run these before marking the cart phase complete:

```bash
cd backend/shop-server
./mvnw -Dtest='CartSchemaTest,AppCartControllerTest,AppAuthControllerTest,SecurityConfigTest,PathTokenKindResolverTest' test
./mvnw test
```

```bash
cd miniprogram
pnpm typecheck
```

Then run `docs/smoke-checks.md#cart-smoke-checks` against the local backend test profile.

Expected:

- Focused backend tests pass.
- Full backend tests pass.
- Mini program typecheck passes.
- Cart smoke proves app login, admin-created published product, add cart, list current SKU price, update quantity, delete item, and clear cart through local HTTP APIs.

## Execution Order

1. Task 1 creates the database and DTO contracts.
2. Task 2 implements protected backend cart behavior and tests.
3. Task 3 connects product detail to add cart through the app token.
4. Task 4 creates the cart tab/page.
5. Task 5 documents and runs the verification path.
