# Shop Coupon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the V1 coupon phase for the hotpot shop: coupon templates, user coupon claim/list/status, all-site no-threshold and minimum-spend amount-off calculation, admin coupon management, mini program coupon pages, cart-based available coupon query, smoke documentation, and final automated verification.

**Architecture:** Add focused `coupon` and `promotion` backend modules to the existing Spring Boot modular monolith. Coupon template management owns issuing rules; claimed `user_coupon` rows snapshot discount and scope values so future template edits do not mutate already claimed coupons. Promotion calculation stays independent from order creation through `CheckoutContext -> PromotionCalculator -> DiscountResult`, so later checkout/order creation can reuse the same calculation path without coupling coupon logic into the order service.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security, MyBatis-Plus 3.5.16, Flyway, MySQL 8/H2 test profile, JdbcClient, Art Design Pro, Vue 3, TypeScript, Element Plus, native WeChat mini program TypeScript, TDesign MiniProgram, pnpm.

## Global Constraints

- API response envelope remains `{ "code": 200, "msg": "success", "data": {} }`.
- Admin paged APIs return `data.records`, `data.total`, `data.current`, and `data.size`.
- Admin coupon APIs require an `ADMIN` token and live under `/admin/marketing/coupons/**`.
- Mini program coupon APIs require an `APP` token and live under `/app/coupons/**`.
- Admin tokens must not authorize `/app/coupons/**`; app tokens must not authorize `/admin/marketing/coupons/**`.
- Money is stored and returned as integer cents: `thresholdCent`, `discountCent`, `cartAmountCent`, `discountAmountCent`, `payableAmountCent`.
- V1 supports amount-off coupons only: no-threshold coupons use `thresholdCent = 0`; minimum-spend coupons use `thresholdCent > 0`.
- V1 user-facing scope is all-site coupons only: `scopeType = ALL` and `scopeValue = ""`; schema and strategy fields must allow future `PRODUCT`, `CATEGORY`, percent discount, and activity coupons.
- Coupon template edits affect future claims only; claimed user coupons use snapshot columns on `user_coupon`.
- Claiming a coupon must be transactional and stock-safe: lock template row, check enabled/current validity/stock/per-user limit, increment `claimed_count`, insert `user_coupon`, insert `coupon_claim_record`.
- Cart-based available coupon calculation reads the current user's cart rows and current SKU prices; it must not create orders, lock stock, lock coupons, call payment, or write order data.
- This phase does not implement complete checkout, address book, order creation, stock lock, WeChat Pay, payment callback, timeout close, shipment, WeChat shipping upload, after-sale, or refund.
- Backend tests run on the existing `test` profile with H2 in MySQL mode.
- Admin verification includes `pnpm build`.
- Mini program verification includes `pnpm typecheck`.
- Real local Coupon Smoke uses the local backend and local database path. In the `test` profile, only WeChat login remains backed by the mock WeChat mini program client; product, cart, coupon, and promotion requests go through real local backend APIs.
- Do not log secrets, WeChat tokens, login codes, phone codes, stable tokens, payment credentials, or production credentials.

---

## Scope Boundary

Included:

- `coupon_template`, `user_coupon`, and `coupon_claim_record` Flyway migration.
- Marketing coupon admin menu and permissions.
- Coupon template list/create/update/enable/disable backend APIs.
- Coupon template stock, validity, and per-user claim limit.
- App claimable coupon list, claim coupon, my coupons list.
- Cart-based available coupon query for current user cart rows.
- Promotion calculation service with `CheckoutContext`, `PromotionCalculator`, `CouponDiscountCalculator`, and `DiscountResult`.
- Admin coupon management API wrapper, type definitions, and coupon template page.
- Mini program coupon service, claimable page, my coupon page, profile entry, and cart available-coupon summary.
- Coupon smoke checks in `docs/smoke-checks.md` and focused command pointer in `docs/dev-setup.md`.

Excluded:

- Coupon lock/use/release endpoints wired to real orders.
- Checkout page, address book, order creation, stock lock/release/deduction.
- WeChat Pay JSAPI, payment callback, timeout close.
- Shipment, WeChat shipping upload, refund, and after-sale.
- Product-specific, category-specific, percent-off, member, flash-sale, bundle, or activity coupon user-facing behavior.
- Background expiration job. Read APIs may display `EXPIRED` when a claimed coupon is past `validEndAt`, but this phase does not require scheduled status mutation.

## References

- Product design: `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`
- Product catalog plan: `docs/superpowers/plans/2026-07-06-shop-product-catalog-implementation-plan.md`
- Cart plan: `docs/superpowers/plans/2026-07-07-shop-cart-implementation-plan.md`
- Existing product schema: `backend/shop-server/src/main/resources/db/migration/V3__product_catalog.sql`
- Existing cart schema: `backend/shop-server/src/main/resources/db/migration/V4__cart.sql`
- Existing cart backend API: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/AppCartController.java`
- Existing admin API style: `admin/src/api/product.ts`
- Existing mini program request helper: `miniprogram/utils/request.ts`

## API Contracts

Admin template page:

```http
GET /admin/marketing/coupons/templates?current=1&size=20&name=新人&status=ENABLED
Authorization: Bearer adm_access_token
```

```json
{
  "records": [
    {
      "id": 5001,
      "name": "新人无门槛券",
      "description": "全场商品可用",
      "couponType": "NO_THRESHOLD",
      "discountType": "AMOUNT_OFF",
      "thresholdCent": 0,
      "discountCent": 500,
      "scopeType": "ALL",
      "scopeValue": "",
      "strategyKey": "coupon.amount-off.v1",
      "totalStock": 100,
      "claimedCount": 12,
      "stockRemaining": 88,
      "perUserLimit": 1,
      "validStartAt": "2026-07-07T00:00:00",
      "validEndAt": "2026-08-07T23:59:59",
      "status": "ENABLED",
      "sortOrder": 10,
      "createdAt": "2026-07-07T12:00:00",
      "updatedAt": "2026-07-07T12:00:00"
    }
  ],
  "total": 1,
  "current": 1,
  "size": 20
}
```

Admin create/update template:

```http
POST /admin/marketing/coupons/templates
Authorization: Bearer adm_access_token
Content-Type: application/json

{
  "name": "满100减20",
  "description": "全场商品满100元可用",
  "couponType": "MIN_SPEND",
  "discountType": "AMOUNT_OFF",
  "thresholdCent": 10000,
  "discountCent": 2000,
  "scopeType": "ALL",
  "scopeValue": "",
  "strategyKey": "coupon.amount-off.v1",
  "totalStock": 200,
  "perUserLimit": 1,
  "validStartAt": "2026-07-07T00:00:00",
  "validEndAt": "2026-08-07T23:59:59",
  "status": "DISABLED",
  "sortOrder": 20
}
```

Enable/disable:

```http
POST /admin/marketing/coupons/templates/5001/enable
POST /admin/marketing/coupons/templates/5001/disable
```

App claimable list:

```http
GET /app/coupons/claimable
Authorization: Bearer app_access_token
```

```json
[
  {
    "templateId": 5001,
    "name": "新人无门槛券",
    "description": "全场商品可用",
    "couponType": "NO_THRESHOLD",
    "thresholdCent": 0,
    "discountCent": 500,
    "validStartAt": "2026-07-07T00:00:00",
    "validEndAt": "2026-08-07T23:59:59",
    "claimedCount": 0,
    "perUserLimit": 1,
    "claimable": true,
    "unavailableReason": null
  }
]
```

Claim template:

```http
POST /app/coupons/templates/5001/claim
Authorization: Bearer app_access_token
```

My coupons:

```http
GET /app/coupons/mine?status=CLAIMED
Authorization: Bearer app_access_token
```

Available coupons for current cart:

```http
POST /app/coupons/available
Authorization: Bearer app_access_token
Content-Type: application/json

{
  "cartItemIds": [9001, 9002]
}
```

If `cartItemIds` is omitted or empty, calculate against all available current-user cart rows.

```json
{
  "cartAmountCent": 11970,
  "bestUserCouponId": 7001,
  "bestDiscountCent": 2000,
  "payableAmountCent": 9970,
  "coupons": [
    {
      "userCouponId": 7001,
      "templateId": 5001,
      "name": "满100减20",
      "couponType": "MIN_SPEND",
      "thresholdCent": 10000,
      "discountCent": 2000,
      "discountAmountCent": 2000,
      "available": true,
      "unavailableReason": null,
      "validEndAt": "2026-08-07T23:59:59"
    }
  ]
}
```

Business error behavior:

```text
401       missing token or wrong token kind for namespace
100400    validation failed for missing/invalid fields
300001    coupon template or user coupon is unavailable
300002    claim limit reached for this coupon template
300003    coupon has already been used
```

## File Structure

- Create: `backend/shop-server/src/main/resources/db/migration/V5__coupon.sql`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/CouponTemplateStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/CouponType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/DiscountType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/CouponScopeType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/UserCouponStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/dto/AdminCouponTemplateRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/dto/AdminCouponTemplateQueryRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/dto/AdminCouponTemplateResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/dto/AppClaimableCouponResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/dto/AppUserCouponResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/dto/AvailableCouponRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/dto/AvailableCouponResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/dto/AvailableCouponItemResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AdminCouponService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AppCouponService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/CouponReadMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/AdminCouponTemplateController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/AppCouponController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/CheckoutContext.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/CheckoutItem.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/DiscountResult.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/PromotionCalculator.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/CouponDiscountCalculator.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/CouponSchemaTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AdminCouponTemplateControllerTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AppCouponControllerTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/promotion/CouponDiscountCalculatorTest.java`
- Create: `admin/src/api/coupon.ts`
- Modify: `admin/src/types/api/api.d.ts`
- Create: `admin/src/views/marketing/coupon/index.vue`
- Create: `admin/src/views/marketing/coupon/modules/coupon-template-dialog.vue`
- Modify: `miniprogram/types/api.ts`
- Create: `miniprogram/services/coupon.ts`
- Modify: `miniprogram/app.json`
- Modify: `miniprogram/pages/profile/profile.ts`
- Modify: `miniprogram/pages/profile/profile.wxml`
- Modify: `miniprogram/pages/profile/profile.wxss`
- Modify: `miniprogram/pages/cart/cart.ts`
- Modify: `miniprogram/pages/cart/cart.wxml`
- Modify: `miniprogram/pages/cart/cart.wxss`
- Create: `miniprogram/pages/coupon/list/list.json`
- Create: `miniprogram/pages/coupon/list/list.ts`
- Create: `miniprogram/pages/coupon/list/list.wxml`
- Create: `miniprogram/pages/coupon/list/list.wxss`
- Create: `miniprogram/pages/coupon/mine/mine.json`
- Create: `miniprogram/pages/coupon/mine/mine.ts`
- Create: `miniprogram/pages/coupon/mine/mine.wxml`
- Create: `miniprogram/pages/coupon/mine/mine.wxss`
- Modify: `docs/dev-setup.md`
- Modify: `docs/smoke-checks.md`

---

### Task 1: Coupon Schema, Enums, DTO Contracts, And Menu Seed

**Files:**
- Create: `backend/shop-server/src/main/resources/db/migration/V5__coupon.sql`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Create: coupon enum and DTO files listed in File Structure.
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/CouponSchemaTest.java`

**Interfaces:**
- Consumes: Existing `admin_menu`, `admin_permission`, `admin_role_menu`, `admin_role_permission`, `admin_menu_permission`, and `app_user` tables from migrations V2-V4.
- Produces: `coupon_template`, `user_coupon`, `coupon_claim_record`; `ErrorCode.COUPON_CLAIM_LIMIT_REACHED`; `ErrorCode.COUPON_ALREADY_USED`; enum values listed below; DTO records consumed by Tasks 2 and 3.

- [ ] **Step 1: Write the failing schema test**

Create `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/CouponSchemaTest.java`:

```java
package org.muybaby.shopserver.coupon;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CouponSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void couponTablesAndMarketingMenuExist() {
        jdbcClient.sql("""
                        insert into coupon_template
                            (id, name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (5001, 'Schema coupon', 'schema test', 'NO_THRESHOLD', 'AMOUNT_OFF', 0, 500,
                             'ALL', '', 'coupon.amount-off.v1', 10, 0, 1,
                             current_timestamp, dateadd('DAY', 7, current_timestamp), 'DISABLED', 10)
                        """)
                .update();

        jdbcClient.sql("""
                        insert into user_coupon
                            (id, user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value, valid_start_at,
                             valid_end_at, status, claimed_at)
                        values
                            (7001, 1, 5001, 'Schema coupon', 'NO_THRESHOLD', 'AMOUNT_OFF',
                             0, 500, 'ALL', '', current_timestamp,
                             dateadd('DAY', 7, current_timestamp), 'CLAIMED', current_timestamp)
                        """)
                .update();

        jdbcClient.sql("""
                        insert into coupon_claim_record (id, template_id, user_id, user_coupon_id, claimed_at)
                        values (8001, 5001, 1, 7001, current_timestamp)
                        """)
                .update();

        Integer marketingMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id in (400, 401)
                          and path in ('/marketing', 'coupon')
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in (
                            'coupon:template:create',
                            'coupon:template:update',
                            'coupon:template:enable',
                            'coupon:template:disable'
                        )
                        """)
                .query(Integer.class)
                .single();

        assertThat(marketingMenuCount).isEqualTo(2);
        assertThat(permissionCount).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Run the schema test and verify it fails**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponSchemaTest test
```

Expected: FAIL because `coupon_template`, `user_coupon`, and `coupon_claim_record` do not exist.

- [ ] **Step 3: Add the Flyway migration**

Create `backend/shop-server/src/main/resources/db/migration/V5__coupon.sql` with:

```sql
CREATE TABLE coupon_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NOT NULL DEFAULT '',
    coupon_type VARCHAR(20) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    threshold_cent BIGINT NOT NULL DEFAULT 0,
    discount_cent BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'ALL',
    scope_value VARCHAR(255) NOT NULL DEFAULT '',
    strategy_key VARCHAR(80) NOT NULL DEFAULT 'coupon.amount-off.v1',
    total_stock INT NOT NULL,
    claimed_count INT NOT NULL DEFAULT 0,
    per_user_limit INT NOT NULL DEFAULT 1,
    valid_start_at TIMESTAMP NOT NULL,
    valid_end_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_name VARCHAR(80) NOT NULL,
    coupon_type VARCHAR(20) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    threshold_cent BIGINT NOT NULL DEFAULT 0,
    discount_cent BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'ALL',
    scope_value VARCHAR(255) NOT NULL DEFAULT '',
    valid_start_at TIMESTAMP NOT NULL,
    valid_end_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_order_id BIGINT NULL,
    locked_at TIMESTAMP NULL,
    used_order_id BIGINT NULL,
    used_at TIMESTAMP NULL,
    released_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE coupon_claim_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_coupon_id BIGINT NOT NULL,
    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_coupon_template_status_sort ON coupon_template(status, sort_order, id);
CREATE INDEX idx_coupon_template_validity ON coupon_template(valid_start_at, valid_end_at);
CREATE INDEX idx_user_coupon_user_status_valid ON user_coupon(user_id, status, valid_end_at);
CREATE INDEX idx_user_coupon_template_user ON user_coupon(template_id, user_id);
CREATE INDEX idx_coupon_claim_template_user ON coupon_claim_record(template_id, user_id, claimed_at);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (3001, 'coupon:template:create', 'Create coupon template'),
    (3002, 'coupon:template:update', 'Update coupon template'),
    (3003, 'coupon:template:enable', 'Enable coupon template'),
    (3004, 'coupon:template:disable', 'Disable coupon template');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (400, NULL, 'Marketing', '/marketing', '/index/index', '营销管理', 'ri:coupon-3-line', 40, FALSE, TRUE, TRUE),
    (401, 400, 'MarketingCoupon', 'coupon', '/marketing/coupon', '优惠券', 'ri:coupon-line', 41, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 400), (1, 401);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 3001), (1, 3002), (1, 3003), (1, 3004);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (401, 3001), (401, 3002), (401, 3003), (401, 3004);
```

- [ ] **Step 4: Add enum and DTO contracts**

Add these enums with the exact values:

```java
public enum CouponTemplateStatus { ENABLED, DISABLED }
public enum CouponType { NO_THRESHOLD, MIN_SPEND }
public enum DiscountType { AMOUNT_OFF, PERCENT_OFF }
public enum CouponScopeType { ALL, PRODUCT, CATEGORY }
public enum UserCouponStatus { CLAIMED, LOCKED, USED, RELEASED, EXPIRED }
```

Add DTO records with exact names:

```java
public record AdminCouponTemplateRequest(
        String name,
        String description,
        String couponType,
        String discountType,
        Long thresholdCent,
        Long discountCent,
        String scopeType,
        String scopeValue,
        String strategyKey,
        Integer totalStock,
        Integer perUserLimit,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        String status,
        Integer sortOrder
) {}

public record AdminCouponTemplateQueryRequest(
        Long current,
        Long size,
        String name,
        String status
) {
    public long pageCurrent() { return current == null || current < 1 ? 1 : current; }
    public long pageSize() { return size == null || size < 1 || size > 100 ? 20 : size; }
}

public record AdminCouponTemplateResponse(
        Long id,
        String name,
        String description,
        String couponType,
        String discountType,
        Long thresholdCent,
        Long discountCent,
        String scopeType,
        String scopeValue,
        String strategyKey,
        Integer totalStock,
        Integer claimedCount,
        Integer stockRemaining,
        Integer perUserLimit,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        String status,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

public record AppClaimableCouponResponse(
        Long templateId,
        String name,
        String description,
        String couponType,
        Long thresholdCent,
        Long discountCent,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        Integer claimedCount,
        Integer perUserLimit,
        Boolean claimable,
        String unavailableReason
) {}

public record AppUserCouponResponse(
        Long userCouponId,
        Long templateId,
        String name,
        String couponType,
        Long thresholdCent,
        Long discountCent,
        String scopeType,
        String status,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        LocalDateTime claimedAt
) {}

public record AvailableCouponRequest(List<Long> cartItemIds) {}

public record AvailableCouponResponse(
        Long cartAmountCent,
        Long bestUserCouponId,
        Long bestDiscountCent,
        Long payableAmountCent,
        List<AvailableCouponItemResponse> coupons
) {}

public record AvailableCouponItemResponse(
        Long userCouponId,
        Long templateId,
        String name,
        String couponType,
        Long thresholdCent,
        Long discountCent,
        Long discountAmountCent,
        Boolean available,
        String unavailableReason,
        LocalDateTime validEndAt
) {}
```

Add `COUPON_CLAIM_LIMIT_REACHED(300002, "Coupon claim limit reached")` and `COUPON_ALREADY_USED(300003, "Coupon already used")` to `ErrorCode`.

- [ ] **Step 5: Run the schema test and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponSchemaTest test
```

Expected: PASS.

Commit:

```bash
git add backend/shop-server/src/main/resources/db/migration/V5__coupon.sql backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java backend/shop-server/src/main/java/org/muybaby/shopserver/coupon backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/CouponSchemaTest.java
git commit -m "feat: add coupon schema contracts"
```

---

### Task 2: Admin Coupon Template Backend API

**Files:**
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AdminCouponService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/CouponReadMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/AdminCouponTemplateController.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AdminCouponTemplateControllerTest.java`

**Interfaces:**
- Consumes: Task 1 schema and DTOs.
- Produces: Admin coupon template page/detail/create/update/enable/disable API consumed by Task 4.

- [ ] **Step 1: Write failing admin API tests**

Create `AdminCouponTemplateControllerTest` with tests for token separation, create/list/update/enable/disable, stock edit validation, and amount validation. Include these assertions:

```java
mockMvc.perform(get("/admin/marketing/coupons/templates"))
        .andExpect(status().isUnauthorized());

mockMvc.perform(post("/admin/marketing/coupons/templates")
        .header("Authorization", "Bearer " + appToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(validTemplateJson()))
        .andExpect(status().isUnauthorized());

mockMvc.perform(post("/admin/marketing/coupons/templates")
        .header("Authorization", "Bearer " + adminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(validTemplateJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isNumber());

mockMvc.perform(get("/admin/marketing/coupons/templates?current=1&size=20&name=新人")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].stockRemaining").value(100));

mockMvc.perform(post("/admin/marketing/coupons/templates/{templateId}/enable", templateId)
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

mockMvc.perform(post("/admin/marketing/coupons/templates")
        .header("Authorization", "Bearer " + adminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {"name":"Bad","couponType":"MIN_SPEND","discountType":"AMOUNT_OFF",
                 "thresholdCent":1000,"discountCent":1000,"scopeType":"ALL","scopeValue":"",
                 "totalStock":10,"perUserLimit":1,"validStartAt":"2026-07-07T00:00:00",
                 "validEndAt":"2026-08-07T23:59:59","status":"DISABLED","sortOrder":1}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(100400));
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminCouponTemplateControllerTest test
```

Expected: FAIL because controller/service do not exist.

- [ ] **Step 3: Implement admin read mapper**

`CouponReadMapper#adminTemplatePage(AdminCouponTemplateQueryRequest query)` must:

- Normalize `current` and `size` through `pageCurrent()` and `pageSize()`.
- Filter by optional `name` with `LIKE`.
- Filter by optional `status`.
- Return `PageResult<AdminCouponTemplateResponse>`.
- Calculate `stockRemaining = max(total_stock - claimed_count, 0)`.
- Order by `sort_order asc, id desc`.

- [ ] **Step 4: Implement admin service validation and writes**

`AdminCouponService` must provide:

```java
public Long create(AdminCouponTemplateRequest request)
public void update(Long templateId, AdminCouponTemplateRequest request)
public void enable(Long templateId)
public void disable(Long templateId)
```

Validation rules:

- `name` is nonblank and at most 80 characters.
- `couponType` is `NO_THRESHOLD` or `MIN_SPEND`.
- `discountType` is `AMOUNT_OFF`; accept `PERCENT_OFF` only as stored future value if requested by admin validation tests are not using it.
- `scopeType` is `ALL`; reject `PRODUCT` and `CATEGORY` for V1 API with `VALIDATION_FAILED`.
- `NO_THRESHOLD` requires `thresholdCent = 0`.
- `MIN_SPEND` requires `thresholdCent > 0`.
- `discountCent > 0`.
- For `MIN_SPEND`, `discountCent < thresholdCent`.
- `totalStock > 0`; update cannot set `totalStock < claimed_count`.
- `perUserLimit > 0`.
- `validStartAt` is before `validEndAt`.
- `status` is `ENABLED` or `DISABLED`.
- Default blank `strategyKey` to `coupon.amount-off.v1`.

- [ ] **Step 5: Implement controller**

`AdminCouponTemplateController` maps:

```java
@RestController
@RequestMapping("/admin/marketing/coupons/templates")
class AdminCouponTemplateController {
    @GetMapping
    ApiResponse<PageResult<AdminCouponTemplateResponse>> page(AdminCouponTemplateQueryRequest query)

    @PostMapping
    ApiResponse<Long> create(@Valid @RequestBody AdminCouponTemplateRequest request)

    @PutMapping("/{templateId}")
    ApiResponse<Void> update(@PathVariable Long templateId, @Valid @RequestBody AdminCouponTemplateRequest request)

    @PostMapping("/{templateId}/enable")
    ApiResponse<Void> enable(@PathVariable Long templateId)

    @PostMapping("/{templateId}/disable")
    ApiResponse<Void> disable(@PathVariable Long templateId)
}
```

- [ ] **Step 6: Run tests and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminCouponTemplateControllerTest test
```

Expected: PASS.

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/coupon backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AdminCouponTemplateControllerTest.java
git commit -m "feat: add admin coupon template api"
```

---

### Task 3: App Coupon APIs And Promotion Calculation Service

**Files:**
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AppCouponService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/AppCouponController.java`
- Create: promotion files listed in File Structure.
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AppCouponControllerTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/promotion/CouponDiscountCalculatorTest.java`

**Interfaces:**
- Consumes: Task 1 schema and DTOs; cart rows from `cart_item`; current product price and availability rules from the cart phase.
- Produces: App coupon APIs and reusable promotion calculation types for future checkout/order.

- [ ] **Step 1: Write failing promotion calculator tests**

Create `CouponDiscountCalculatorTest`:

```java
package org.muybaby.shopserver.promotion;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CouponDiscountCalculatorTest {

    @Test
    void noThresholdAmountCouponDiscountsUpToCartAmount() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 3990L, 1)));
        DiscountResult result = calculator.calculate(context, coupon(7001L, 0L, 500L));
        assertThat(result.available()).isTrue();
        assertThat(result.discountAmountCent()).isEqualTo(500L);
    }

    @Test
    void minimumSpendCouponRequiresThreshold() {
        CouponDiscountCalculator calculator = new CouponDiscountCalculator();
        CheckoutContext context = new CheckoutContext(1L, List.of(new CheckoutItem(1000L, 100L, 3990L, 2)));
        DiscountResult result = calculator.calculate(context, coupon(7001L, 10000L, 2000L));
        assertThat(result.available()).isFalse();
        assertThat(result.unavailableReason()).isEqualTo("THRESHOLD_NOT_MET");
    }
}
```

The `coupon(...)` helper should build the coupon candidate type introduced in implementation.

- [ ] **Step 2: Write failing app API tests**

Create `AppCouponControllerTest` with these behaviors:

- `/app/coupons/**` requires APP token and rejects admin token.
- Claimable list returns enabled, currently valid templates and per-user `claimable`.
- Claim inserts one `user_coupon`, increments `coupon_template.claimed_count`, and inserts one `coupon_claim_record`.
- Claim rejects over per-user limit with `300002`.
- Claim rejects out-of-stock or disabled templates with `300001`.
- My coupons returns claimed user coupon snapshots.
- Available coupons use current cart amount and return best discount.

Use existing helpers from `AppCartControllerTest` as the style reference: log in with `/app/auth/login`, create product/category/SKU through `AdminProductService`, add cart rows through `/app/cart/items`, then call `/app/coupons/available`.

- [ ] **Step 3: Run app coupon tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponDiscountCalculatorTest,AppCouponControllerTest test
```

Expected: FAIL because services and controllers do not exist.

- [ ] **Step 4: Implement promotion types**

Create:

```java
public record CheckoutItem(Long skuId, Long spuId, Long lineAmountCent, Integer quantity) {}

public record CheckoutContext(Long userId, List<CheckoutItem> items) {
    public long totalAmountCent() {
        return items == null ? 0 : items.stream().mapToLong(CheckoutItem::lineAmountCent).sum();
    }
}

public record DiscountResult(
        Long userCouponId,
        Boolean available,
        Long discountAmountCent,
        String unavailableReason
) {}

public interface PromotionCalculator<T> {
    DiscountResult calculate(CheckoutContext context, T candidate);
}
```

`CouponDiscountCalculator` must support only `scopeType = ALL` and `discountType = AMOUNT_OFF` for V1, return `SCOPE_UNSUPPORTED` for other scopes, return `THRESHOLD_NOT_MET` when cart total is below threshold, and cap discount to cart total.

- [ ] **Step 5: Implement AppCouponService**

`AppCouponService` must provide:

```java
public List<AppClaimableCouponResponse> claimable(AuthenticatedPrincipal principal)
public AppUserCouponResponse claim(AuthenticatedPrincipal principal, Long templateId)
public List<AppUserCouponResponse> mine(AuthenticatedPrincipal principal, String status)
public AvailableCouponResponse available(AuthenticatedPrincipal principal, AvailableCouponRequest request)
```

Implementation rules:

- Use `AuthenticatedPrincipal.subjectId()` as app user id.
- `claim` runs in one transaction.
- Lock selected template row with `FOR UPDATE`.
- Active template condition: `status = ENABLED`, `valid_start_at <= now`, `valid_end_at >= now`, `claimed_count < total_stock`.
- Per-user count uses `user_coupon where user_id = :userId and template_id = :templateId`.
- Insert `user_coupon` snapshot fields from template.
- Insert `coupon_claim_record`.
- `mine` returns current user's coupons ordered by `claimed_at desc, id desc`; if status query is present, filter by stored status.
- `available` reads current user's selected cart rows. If request `cartItemIds` is empty, read all current-user cart rows.
- Only include cart rows with SKU `ENABLED`, SPU `ON_SALE`, category `ENABLED`, and enough stock for the cart quantity.
- Calculate against `product_sku.price_cent * cart_item.quantity`.
- Only claimed, unexpired, all-site coupons can be available.
- `bestUserCouponId` is the available coupon with the largest `discountAmountCent`; tie-breaker is earliest `validEndAt`, then lower `userCouponId`.

- [ ] **Step 6: Implement AppCouponController**

Map:

```java
@RestController
@RequestMapping("/app/coupons")
class AppCouponController {
    @GetMapping("/claimable")
    ApiResponse<List<AppClaimableCouponResponse>> claimable(@AuthenticationPrincipal AuthenticatedPrincipal principal)

    @PostMapping("/templates/{templateId}/claim")
    ApiResponse<AppUserCouponResponse> claim(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable Long templateId)

    @GetMapping("/mine")
    ApiResponse<List<AppUserCouponResponse>> mine(@AuthenticationPrincipal AuthenticatedPrincipal principal, String status)

    @PostMapping("/available")
    ApiResponse<AvailableCouponResponse> available(@AuthenticationPrincipal AuthenticatedPrincipal principal, @RequestBody(required = false) AvailableCouponRequest request)
}
```

- [ ] **Step 7: Run tests and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponDiscountCalculatorTest,AppCouponControllerTest test
```

Expected: PASS.

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/coupon backend/shop-server/src/main/java/org/muybaby/shopserver/promotion backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AppCouponControllerTest.java backend/shop-server/src/test/java/org/muybaby/shopserver/promotion/CouponDiscountCalculatorTest.java
git commit -m "feat: add app coupon and promotion APIs"
```

---

### Task 4: Admin Coupon Management UI

**Files:**
- Create: `admin/src/api/coupon.ts`
- Modify: `admin/src/types/api/api.d.ts`
- Create: `admin/src/views/marketing/coupon/index.vue`
- Create: `admin/src/views/marketing/coupon/modules/coupon-template-dialog.vue`

**Interfaces:**
- Consumes: Task 2 admin APIs and backend menu component path `/marketing/coupon`.
- Produces: Art Design Pro coupon template management page.

- [ ] **Step 1: Add API wrapper and types**

`admin/src/api/coupon.ts` must export:

```typescript
export function fetchCouponTemplates(params: Api.Marketing.CouponTemplateSearchParams)
export function createCouponTemplate(data: Api.Marketing.CouponTemplateForm)
export function updateCouponTemplate(templateId: number, data: Api.Marketing.CouponTemplateForm)
export function enableCouponTemplate(templateId: number)
export function disableCouponTemplate(templateId: number)
```

Add `Api.Marketing` types:

```typescript
namespace Marketing {
  type CouponTemplateStatus = 'ENABLED' | 'DISABLED'
  type CouponType = 'NO_THRESHOLD' | 'MIN_SPEND'
  type DiscountType = 'AMOUNT_OFF' | 'PERCENT_OFF'
  type CouponScopeType = 'ALL' | 'PRODUCT' | 'CATEGORY'
  type CouponTemplateList = Api.Common.PaginatedResponse<CouponTemplate>

  interface CouponTemplate { /* fields from AdminCouponTemplateResponse */ }
  interface CouponTemplateForm { /* fields from AdminCouponTemplateRequest */ }
  type CouponTemplateSearchParams = Partial<Api.Common.CommonSearchParams & { name: string; status: CouponTemplateStatus }>
}
```

- [ ] **Step 2: Implement coupon template page**

`admin/src/views/marketing/coupon/index.vue` must:

- Use `ArtSearchBar`, `ArtTableHeader`, `ArtTable`, and `useTable` like `admin/src/views/product/spu/index.vue`.
- Search by template name and status.
- Show columns: ID, name/description, type, threshold, discount, stock remaining/total, per-user limit, validity, status tag, sort order, operations.
- Provide "新增优惠券" button.
- Operations: edit, enable, disable.
- Show status tags: enabled = success, disabled = info.
- Format money as `¥${(cent / 100).toFixed(2)}`.
- Keep UI work-focused and consistent with product admin pages.

- [ ] **Step 3: Implement coupon template dialog**

`coupon-template-dialog.vue` must include:

- Name, description, coupon type segmented/select, threshold cent input in yuan display, discount cent input in yuan display.
- Scope type fixed to "全场券" for V1 while submitting `scopeType: 'ALL'` and `scopeValue: ''`.
- Total stock, per-user limit, validity datetime range, status, sort order.
- Convert yuan inputs to integer cents before submit.
- For `NO_THRESHOLD`, force threshold to 0.
- For `MIN_SPEND`, require discount less than threshold in client validation.

- [ ] **Step 4: Build and commit**

Run:

```bash
cd admin
pnpm build
```

Expected: PASS.

Commit:

```bash
git add admin/src/api/coupon.ts admin/src/types/api/api.d.ts admin/src/views/marketing/coupon
git commit -m "feat: add admin coupon management view"
```

---

### Task 5: Mini Program Coupon Pages And Cart Coupon Summary

**Files:**
- Modify: `miniprogram/types/api.ts`
- Create: `miniprogram/services/coupon.ts`
- Modify: `miniprogram/app.json`
- Modify: profile and cart files listed in File Structure.
- Create: coupon list and mine page files listed in File Structure.

**Interfaces:**
- Consumes: Task 3 app coupon APIs.
- Produces: Mini program claimable coupon list, my coupons, and cart available coupon summary.

- [ ] **Step 1: Add types and service**

Add mini program API types matching Task 3 response DTOs:

```typescript
export interface ClaimableCoupon { templateId: number; name: string; description: string; couponType: "NO_THRESHOLD" | "MIN_SPEND"; thresholdCent: number; discountCent: number; validStartAt: string; validEndAt: string; claimedCount: number; perUserLimit: number; claimable: boolean; unavailableReason: string | null; }
export interface UserCoupon { userCouponId: number; templateId: number; name: string; couponType: "NO_THRESHOLD" | "MIN_SPEND"; thresholdCent: number; discountCent: number; scopeType: "ALL" | "PRODUCT" | "CATEGORY"; status: "CLAIMED" | "LOCKED" | "USED" | "RELEASED" | "EXPIRED"; validStartAt: string; validEndAt: string; claimedAt: string; }
export interface AvailableCouponItem { userCouponId: number; templateId: number; name: string; couponType: "NO_THRESHOLD" | "MIN_SPEND"; thresholdCent: number; discountCent: number; discountAmountCent: number; available: boolean; unavailableReason: string | null; validEndAt: string; }
export interface AvailableCouponResponse { cartAmountCent: number; bestUserCouponId: number | null; bestDiscountCent: number; payableAmountCent: number; coupons: AvailableCouponItem[]; }
```

`miniprogram/services/coupon.ts` must export:

```typescript
export function getClaimableCoupons(): Promise<ClaimableCoupon[]>
export function claimCoupon(templateId: number): Promise<UserCoupon>
export function getMyCoupons(status?: UserCoupon["status"]): Promise<UserCoupon[]>
export function getAvailableCoupons(cartItemIds?: number[]): Promise<AvailableCouponResponse>
```

- [ ] **Step 2: Add coupon pages to `app.json`**

Add pages:

```json
"pages/coupon/list/list",
"pages/coupon/mine/mine"
```

Keep existing tabBar unchanged.

- [ ] **Step 3: Implement claimable coupon page**

`pages/coupon/list/list.ts` must:

- Call `ensureAppLogin()`.
- Load `getClaimableCoupons()`.
- Render coupon cards with discount amount, threshold text, validity, claim state.
- `onClaimTap` calls `claimCoupon(templateId)`, shows success toast, then reloads.
- Provide `onMineTap` navigation to `/pages/coupon/mine/mine`.

- [ ] **Step 4: Implement my coupon page**

`pages/coupon/mine/mine.ts` must:

- Call `ensureAppLogin()`.
- Load `getMyCoupons()`.
- Render user coupons grouped visually by status text.
- Show valid date and threshold.
- Use concise empty/error states.

- [ ] **Step 5: Add profile entries**

Update profile page to add two action rows:

```text
领券中心 -> /pages/coupon/list/list
我的优惠券 -> /pages/coupon/mine/mine
```

Keep phone authorization behavior intact.

- [ ] **Step 6: Add cart available coupon summary**

Update cart page after successful cart load:

- Call `getAvailableCoupons(response.items.filter(item => item.available).map(item => item.id))`.
- Display `couponSummaryText` in the settlement bar.
- If `bestDiscountCent > 0`, display `已优惠 ¥X.XX，券后 ¥Y.YY`.
- If cart has items but no available coupons, display `暂无可用优惠券`.
- Keep checkout button disabled because order creation is not in this phase.

- [ ] **Step 7: Typecheck and commit**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected: PASS.

Commit:

```bash
git add miniprogram/types/api.ts miniprogram/services/coupon.ts miniprogram/app.json miniprogram/pages/profile miniprogram/pages/cart miniprogram/pages/coupon
git commit -m "feat: add mini program coupon pages"
```

---

### Task 6: Coupon Smoke Documentation And Final Verification

**Files:**
- Modify: `docs/dev-setup.md`
- Modify: `docs/smoke-checks.md`

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: Coupon smoke checklist and final verification evidence.

- [ ] **Step 1: Update `docs/smoke-checks.md`**

Add `## Coupon Smoke Checks` after Cart Smoke Checks. It must explicitly state:

```text
This is a real local smoke check for the coupon phase. It uses the local backend and local database path. In the test profile, WeChat login is still backed by the mock WeChat mini program client; product, cart, coupon, and promotion requests go through real local backend APIs, not product/cart/coupon mocks.
```

Include commands to:

1. Start backend with the existing test-profile local command.
2. Admin login.
3. Mini program login.
4. Create and enable a no-threshold coupon template.
5. Verify claimable list.
6. Claim coupon.
7. Verify my coupons.
8. Create category/SPU/SKU and publish.
9. Add SKU to cart.
10. Query `/app/coupons/available`.

Expected final output must include:

```text
success
新人无门槛券
CLAIMED
购物车优惠券测试锅底 1 3990
500
```

- [ ] **Step 2: Update `docs/dev-setup.md`**

Add a short coupon verification pointer:

```markdown
## Coupon Checks

Run automated checks with:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponSchemaTest,AdminCouponTemplateControllerTest,AppCouponControllerTest,CouponDiscountCalculatorTest test
cd ../../miniprogram
pnpm typecheck
cd ../admin
pnpm build
```

For real local coupon smoke, follow `docs/smoke-checks.md#coupon-smoke-checks`.
```

- [ ] **Step 3: Run focused verification**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponSchemaTest,AdminCouponTemplateControllerTest,AppCouponControllerTest,CouponDiscountCalculatorTest test
cd ../../miniprogram
pnpm typecheck
cd ../admin
pnpm build
```

Expected: all pass.

- [ ] **Step 4: Commit docs**

Commit:

```bash
git add docs/dev-setup.md docs/smoke-checks.md
git commit -m "docs: add coupon smoke checks"
```

- [ ] **Step 5: Run final required verification**

Run the exact final commands:

```bash
cd backend/shop-server && ./mvnw test
cd miniprogram && pnpm typecheck
cd admin && pnpm build
git status --short --ignored
```

Expected:

- Backend tests pass.
- Mini program TypeScript completes without diagnostics.
- Admin build completes successfully.
- Git status shows only intended tracked changes if any remain plus ignored local noise such as `.env.local`, `node_modules`, `target`, `dist`, `.DS_Store`, `.superpowers/`.

---

## Execution Order

1. Task 1: Coupon schema and contracts.
2. Task 2: Admin coupon backend API.
3. Task 3: App coupon APIs and promotion calculation.
4. Task 4: Admin coupon management UI.
5. Task 5: Mini program coupon pages and cart coupon summary.
6. Task 6: Smoke docs and final verification.

Do not run Tasks 4-5 before Tasks 2-3 because frontend code depends on backend contracts. Do not implement checkout/order/payment in any task. Use one implementation subagent per task and review each task before starting the next.

## Plan Self-Review

- Spec coverage: template/user coupon/claim tables, full-site no-threshold/minimum-spend coupons, extension fields, admin management, mini program claim/list/mine/cart-available query, reusable promotion calculation, smoke docs, and real local smoke distinction are covered.
- Scope control: checkout, order creation, stock lock, payment, shipment, refund, product-specific coupons, category coupons, percent discounts, and activity coupons stay outside this phase.
- Type consistency: DTO names, endpoint paths, enum values, and field names are consistent across backend, admin, mini program, and smoke docs.
- Placeholder scan: no task uses incomplete-work marker language; each task has files, interfaces, test commands, expected results, and commit command.
