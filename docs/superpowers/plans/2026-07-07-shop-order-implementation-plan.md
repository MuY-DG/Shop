# Shop Order And Stock Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the order creation phase for the hotpot shop: cart-based checkout preview, order creation, amount snapshots, stock lock, coupon lock, close-time release, app order APIs, admin order list/detail, mini program checkout flow, and real local smoke checks.

**Architecture:** Add a focused `order` backend module to the existing Spring Boot modular monolith. Order creation reads owned cart rows and current SKU data inside a transaction, snapshots prices and item text into `shop_order` and `order_item`, decrements `product_sku.stock_available`, inserts `stock_lock` rows, writes `stock_log`, and locks an eligible `user_coupon` through the existing promotion calculation path. Frontends consume the new contracts without adding WeChat Pay, payment callbacks, shipment, refund, or after-sale behavior in this phase.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security, MyBatis-Plus 3.5.16, Flyway, MySQL 8/H2 test profile, JdbcClient, Art Design Pro, Vue 3, TypeScript, Element Plus, native WeChat mini program TypeScript, TDesign MiniProgram, pnpm.

## Global Constraints

- API response envelope remains `{ "code": 200, "msg": "success", "data": {} }`.
- Admin paged APIs return `data.records`, `data.total`, `data.current`, and `data.size`.
- App order APIs require an `APP` token and live under `/app/orders/**`.
- Admin order APIs require an `ADMIN` token and live under `/admin/orders/**`.
- Admin tokens must not authorize `/app/orders/**`; app tokens must not authorize `/admin/orders/**`.
- Money is stored and returned as integer cents.
- Order item rows store immutable snapshots: product title, SKU code, spec text, image, original price, unit price, quantity, original line amount, and line amount.
- Order amount snapshots include `productOriginalAmountCent`, `productAmountCent`, `couponDiscountCent`, `freightCent`, `payableAmountCent`, and `paidAmountCent`.
- Order creation uses existing promotion calculation classes: `CheckoutContext`, `CheckoutItem`, `CouponDiscountCalculator`, and `DiscountResult`.
- Order creation validates SKU status `ENABLED`, SPU status `ON_SALE`, category status `ENABLED`, cart row ownership, and `product_sku.stock_available >= cart_item.quantity`.
- Order creation locks stock by decrementing `product_sku.stock_available`, inserting `stock_lock` rows with status `LOCKED`, and writing `stock_log` rows with change type `ORDER_LOCK`.
- Closing a `CREATED` order releases stock by changing lock rows to `RELEASED`, incrementing `product_sku.stock_available`, writing `stock_log` rows with change type `ORDER_RELEASE`, and releasing any locked coupon.
- This phase implements close/release as a reusable transaction and admin-triggerable endpoint; automatic timeout scanning is left for the payment/timeout phase.
- This phase does not implement WeChat Pay JSAPI, payment callback, paid-state transition, shipment, WeChat shipping upload, refund, or after-sale.
- Backend tests run on the existing `test` profile with H2 in MySQL mode.
- Admin verification includes `pnpm build`.
- Mini program verification includes `pnpm typecheck`.
- Real local Order Smoke uses the local backend and local database path. In the `test` profile, only WeChat login remains backed by the mock WeChat mini program client; product, cart, coupon, promotion, order, and stock requests go through real local backend APIs.
- Do not log secrets, WeChat tokens, login codes, phone codes, stable tokens, payment credentials, or production credentials.

---

## Scope Boundary

Included:

- `shop_order`, `order_item`, and `stock_lock` Flyway migration.
- Admin order menu and permissions.
- Backend enums and DTOs for order state, preview, submit, list, detail, item snapshots, and admin query.
- App checkout preview API.
- App submit-order-from-cart API with idempotency key.
- App order list/detail APIs.
- Admin order list/detail APIs.
- Admin close-created-order endpoint to exercise the release transaction before the scheduled timeout phase.
- Stock lock and release stock logs.
- Coupon lock through `user_coupon.status`, `locked_order_id`, and `locked_at`; close-time release returns the coupon to `CLAIMED`, clears `locked_order_id`/`locked_at`, and records `released_at`.
- Mini program order preview page, order list page, updated order detail page, order service wrapper, and cart checkout navigation.
- Admin order page and API wrapper.
- Order smoke checks in `docs/smoke-checks.md`.

Excluded:

- WeChat Pay JSAPI prepay and payment parameter return.
- Payment callback, payment transaction id, merchant trade number population, and paid-state transition.
- Scheduled timeout scanner.
- Shipment, WeChat shipping upload, after-sale, refund, and refund callback.
- Address book and receiver address management. V1 order rows use empty receiver snapshot fields in this phase.

## References

- Product design: `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`
- Product catalog plan: `docs/superpowers/plans/2026-07-06-shop-product-catalog-implementation-plan.md`
- Cart plan: `docs/superpowers/plans/2026-07-07-shop-cart-implementation-plan.md`
- Coupon plan: `docs/superpowers/plans/2026-07-07-shop-coupon-implementation-plan.md`
- Smoke documentation: `docs/smoke-checks.md`
- Existing product schema: `backend/shop-server/src/main/resources/db/migration/V3__product_catalog.sql`
- Existing cart schema: `backend/shop-server/src/main/resources/db/migration/V4__cart.sql`
- Existing coupon schema: `backend/shop-server/src/main/resources/db/migration/V5__coupon.sql`
- Existing cart backend service: `backend/shop-server/src/main/java/org/muybaby/shopserver/cart/service/AppCartService.java`
- Existing coupon backend service: `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AppCouponService.java`
- Existing promotion calculator: `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/CouponDiscountCalculator.java`
- Existing admin API style: `admin/src/api/coupon.ts`
- Existing mini program request helper: `miniprogram/utils/request.ts`

## API Contracts

App order preview:

```http
POST /app/orders/preview
Authorization: Bearer app_access_token
Content-Type: application/json

{
  "cartItemIds": [9001],
  "userCouponId": 7001
}
```

If `cartItemIds` is omitted or empty, preview all available current-user cart rows. If `userCouponId` is omitted, choose the best available coupon from current user coupons. If `userCouponId` is supplied and unavailable, return `300001`.

```json
{
  "items": [
    {
      "cartItemId": 9001,
      "skuId": 1000,
      "spuId": 100,
      "productTitle": "重庆牛油火锅底料",
      "productSubtitle": "厚重牛油香",
      "mainImage": "https://example.test/main.jpg",
      "skuImage": "https://example.test/sku.jpg",
      "displayImage": "https://example.test/sku.jpg",
      "skuCode": "HY-NY-300G",
      "specText": "牛油 / 300g",
      "originalPriceCent": 4990,
      "unitPriceCent": 3990,
      "quantity": 2,
      "lineOriginalAmountCent": 9980,
      "lineAmountCent": 7980
    }
  ],
  "productOriginalAmountCent": 9980,
  "productAmountCent": 7980,
  "userCouponId": 7001,
  "couponName": "新人无门槛券",
  "couponDiscountCent": 500,
  "freightCent": 0,
  "payableAmountCent": 7480
}
```

Submit order:

```http
POST /app/orders
Authorization: Bearer app_access_token
Content-Type: application/json

{
  "cartItemIds": [9001],
  "userCouponId": 7001,
  "idempotencyKey": "checkout-20260707-001"
}
```

```json
{
  "orderId": 8001,
  "orderNo": "ORD202607071230001234",
  "status": "CREATED",
  "payableAmountCent": 7480,
  "couponDiscountCent": 500,
  "createdAt": "2026-07-07T12:30:00"
}
```

App list/detail:

```http
GET /app/orders?current=1&size=10&status=CREATED
GET /app/orders/8001
```

Admin list/detail/close:

```http
GET /admin/orders?current=1&size=20&orderNo=ORD&status=CREATED
GET /admin/orders/8001
POST /admin/orders/8001/close
```

Business error behavior:

```text
401       missing token or wrong token kind for namespace
100400    validation failed for missing/invalid fields or empty checkout cart
200001    SPU is not ON_SALE or category is not ENABLED
200002    SKU does not exist or SKU status is not ENABLED
200100    requested quantity exceeds current stock
250001    cart item does not exist for the current user
300001    selected coupon is unavailable or not applicable
400001    order state conflict
```

## File Structure

- Create: `backend/shop-server/src/main/resources/db/migration/V6__order.sql`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/StockChangeType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/OrderStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/StockLockStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/AppOrderPreviewRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/AppOrderSubmitRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderPreviewResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderPreviewItemResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderSubmitResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderSummaryResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderDetailResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderItemResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/AdminOrderQueryRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AdminOrderService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/AppOrderController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/AdminOrderController.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/order/OrderSchemaTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/order/AdminOrderControllerTest.java`
- Create: `admin/src/api/order.ts`
- Modify: `admin/src/types/api/api.d.ts`
- Create: `admin/src/views/order/list/index.vue`
- Modify: `miniprogram/types/api.ts`
- Create: `miniprogram/services/order.ts`
- Modify: `miniprogram/app.json`
- Modify: `miniprogram/pages/cart/cart.ts`
- Modify: `miniprogram/pages/cart/cart.wxml`
- Modify: `miniprogram/pages/cart/cart.wxss`
- Create: `miniprogram/pages/order/preview/preview.json`
- Create: `miniprogram/pages/order/preview/preview.ts`
- Create: `miniprogram/pages/order/preview/preview.wxml`
- Create: `miniprogram/pages/order/preview/preview.wxss`
- Create: `miniprogram/pages/order/list/list.json`
- Create: `miniprogram/pages/order/list/list.ts`
- Create: `miniprogram/pages/order/list/list.wxml`
- Create: `miniprogram/pages/order/list/list.wxss`
- Modify: `miniprogram/pages/order/detail/detail.json`
- Modify: `miniprogram/pages/order/detail/detail.ts`
- Modify: `miniprogram/pages/order/detail/detail.wxml`
- Modify: `miniprogram/pages/order/detail/detail.wxss`
- Modify: `docs/smoke-checks.md`

---

### Task 1: Order Schema, Enums, DTO Contracts, And Menu Seed

**Files:**
- Create: `backend/shop-server/src/main/resources/db/migration/V6__order.sql`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/StockChangeType.java`
- Create: backend order enum and DTO files listed in File Structure.
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/order/OrderSchemaTest.java`

**Interfaces:**
- Consumes: `product_sku`, `stock_log`, `cart_item`, `user_coupon`, `admin_menu`, `admin_permission`, `admin_role_menu`, `admin_role_permission`, and `admin_menu_permission`.
- Produces: `shop_order`, `order_item`, `stock_lock`; `OrderStatus`; `StockLockStatus`; `StockChangeType.ORDER_LOCK`; `StockChangeType.ORDER_RELEASE`; order DTO records consumed by Tasks 2-5.

- [ ] **Step 1: Write the failing schema test**

Create `OrderSchemaTest` that inserts one `shop_order`, one `order_item`, and one `stock_lock`, then asserts the admin menu `/order/list` and permissions `order:read` and `order:close` exist.

Run: `cd backend/shop-server && ./mvnw -Dtest=OrderSchemaTest test`

Expected: FAIL before migration and DTO files exist.

- [ ] **Step 2: Implement migration and DTO contracts**

Create `V6__order.sql` with:

```sql
CREATE TABLE shop_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'CART',
    idempotency_key VARCHAR(80) NOT NULL,
    product_original_amount_cent BIGINT NOT NULL DEFAULT 0,
    product_amount_cent BIGINT NOT NULL DEFAULT 0,
    user_coupon_id BIGINT NULL,
    coupon_name VARCHAR(80) NOT NULL DEFAULT '',
    coupon_discount_cent BIGINT NOT NULL DEFAULT 0,
    freight_cent BIGINT NOT NULL DEFAULT 0,
    payable_amount_cent BIGINT NOT NULL DEFAULT 0,
    paid_amount_cent BIGINT NOT NULL DEFAULT 0,
    receiver_name VARCHAR(64) NOT NULL DEFAULT '',
    receiver_phone VARCHAR(32) NOT NULL DEFAULT '',
    receiver_address VARCHAR(255) NOT NULL DEFAULT '',
    payment_transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    merchant_trade_no VARCHAR(64) NOT NULL DEFAULT '',
    close_reason VARCHAR(64) NOT NULL DEFAULT '',
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    product_title VARCHAR(128) NOT NULL,
    product_subtitle VARCHAR(255) NOT NULL DEFAULT '',
    main_image VARCHAR(500) NOT NULL DEFAULT '',
    sku_image VARCHAR(500) NOT NULL DEFAULT '',
    display_image VARCHAR(500) NOT NULL DEFAULT '',
    sku_code VARCHAR(64) NOT NULL,
    spec_text VARCHAR(255) NOT NULL DEFAULT '',
    original_price_cent BIGINT NOT NULL DEFAULT 0,
    unit_price_cent BIGINT NOT NULL DEFAULT 0,
    quantity INT NOT NULL,
    line_original_amount_cent BIGINT NOT NULL DEFAULT 0,
    line_amount_cent BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stock_lock (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    locked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_shop_order_user_idempotency ON shop_order(user_id, idempotency_key);
CREATE UNIQUE INDEX uk_shop_order_order_no ON shop_order(order_no);
CREATE INDEX idx_shop_order_user_status_created ON shop_order(user_id, status, created_at);
CREATE INDEX idx_order_item_order ON order_item(order_id);
CREATE INDEX idx_stock_lock_order ON stock_lock(order_id, status);
```

Seed admin menu id `500` title `订单管理`, child id `501` path `list` component `/order/list`, and permissions `4001/order:read`, `4002/order:close`.

Create enum and DTO records exactly named in File Structure. Request records use Jakarta validation: `idempotencyKey` is `@NotBlank` and max 80 chars; `cartItemIds` allows null/empty to mean all available cart rows; quantities come only from cart rows.

- [ ] **Step 3: Verify schema test passes**

Run: `cd backend/shop-server && ./mvnw -Dtest=OrderSchemaTest test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/shop-server/src/main/resources/db/migration/V6__order.sql backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java backend/shop-server/src/main/java/org/muybaby/shopserver/product/StockChangeType.java backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/order/OrderSchemaTest.java
git commit -m "feat: add order schema contracts"
```

### Task 2: App Order Preview And Submit Backend

**Files:**
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/AppOrderController.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java`

**Interfaces:**
- Consumes: Task 1 schema and DTOs; existing cart/product/coupon/promotion tables and classes.
- Produces: `POST /app/orders/preview`, `POST /app/orders`, `GET /app/orders`, and `GET /app/orders/{orderId}`.

- [ ] **Step 1: Write failing app order controller tests**

Cover these behaviors in `AppOrderControllerTest`:

- App token required; admin token on `/app/orders/preview` returns `401` and code `100001`.
- Preview with one owned cart row returns item snapshots, `productOriginalAmountCent`, `productAmountCent`, best coupon id, coupon discount, freight `0`, and payable.
- Submit creates `CREATED` order, deletes selected cart rows, decrements SKU stock, inserts `stock_lock`, writes `stock_log` with `ORDER_LOCK`, and updates `user_coupon` to `LOCKED`.
- Repeating submit with the same `idempotencyKey` returns the existing order and does not create extra stock locks or coupon locks.
- Submit rejects another user's cart item with `250001`.
- Submit rejects disabled SKU, off-sale SPU/category, stock shortage, and selected inapplicable coupon with the documented codes.
- App list/detail return only current user's orders.

Run: `cd backend/shop-server && ./mvnw -Dtest=AppOrderControllerTest test`

Expected: FAIL because app order service/controller do not exist.

- [ ] **Step 2: Implement preview and submit transactions**

`AppOrderService.preview()` builds checkout rows from current-user cart items. `AppOrderService.submit()` performs a single transaction:

1. Check `(user_id, idempotency_key)` and return existing order if present.
2. Lock owned cart rows and SKU rows.
3. Validate sellable SKU/SPU/category and stock.
4. Calculate coupon discount with `CouponDiscountCalculator`.
5. Insert `shop_order`, `order_item`, `stock_lock`.
6. Decrement `product_sku.stock_available`.
7. Insert `stock_log` rows with `ORDER_LOCK`.
8. Lock selected coupon with `status = LOCKED`, `locked_order_id`, `locked_at`, and `updated_at`.
9. Delete selected cart rows.
10. Return `OrderSubmitResponse`.

- [ ] **Step 3: Run app order tests**

Run: `cd backend/shop-server && ./mvnw -Dtest=AppOrderControllerTest test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java
git commit -m "feat: add app order creation"
```

### Task 3: Admin Order APIs And Close Release Transaction

**Files:**
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AdminOrderService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/AdminOrderController.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/order/AdminOrderControllerTest.java`

**Interfaces:**
- Consumes: Orders, items, stock locks, and locked coupons created by Task 2.
- Produces: `GET /admin/orders`, `GET /admin/orders/{orderId}`, and `POST /admin/orders/{orderId}/close`.

- [ ] **Step 1: Write failing admin order tests**

Cover:

- Admin token required; app token on `/admin/orders` returns `401`.
- Admin list returns `records/total/current/size` and can filter by `status` and `orderNo`.
- Admin detail returns item snapshots and amount snapshots.
- Closing a `CREATED` order changes order status to `CLOSED`, releases each `stock_lock`, increments SKU stock, writes `ORDER_RELEASE` stock logs, and returns a locked coupon to `CLAIMED` so it is available again.
- Closing the same order twice returns `400001` and does not duplicate release logs.

Run: `cd backend/shop-server && ./mvnw -Dtest=AdminOrderControllerTest test`

Expected: FAIL before admin service/controller exist.

- [ ] **Step 2: Implement admin list/detail/close**

`AdminOrderService.closeCreatedOrder()` must lock the order row, require status `CREATED`, lock all `LOCKED` stock rows, release stock and coupon in the same transaction, and set `closed_at`, `close_reason = 'ADMIN_CLOSE'`, and `updated_at`.

- [ ] **Step 3: Run admin order tests**

Run: `cd backend/shop-server && ./mvnw -Dtest=AdminOrderControllerTest test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/order/AdminOrderControllerTest.java
git commit -m "feat: add admin order management"
```

### Task 4: Admin Order Management Page

**Files:**
- Create: `admin/src/api/order.ts`
- Modify: `admin/src/types/api/api.d.ts`
- Create: `admin/src/views/order/list/index.vue`

**Interfaces:**
- Consumes: Admin order APIs from Task 3 and backend menu seed `/order/list`.
- Produces: Art Design Pro order list/detail drawer page.

- [ ] **Step 1: Add admin API types and wrappers**

Create wrappers `fetchOrders(params)`, `fetchOrderDetail(orderId)`, and `closeOrder(orderId)` using the same `request` helper style as `admin/src/api/coupon.ts`.

Run: `cd admin && pnpm build`

Expected: FAIL until the page and types are complete.

- [ ] **Step 2: Build order page**

Create a quiet operational page with `ArtSearchBar`, `ArtTableHeader`, `ArtTable`, an Element Plus detail drawer, status tags, money formatting, item snapshot table, and a close action guarded by `v-auth="'order:close'"` for `CREATED` rows.

- [ ] **Step 3: Verify admin build**

Run: `cd admin && CI=true pnpm build`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add admin/src/api/order.ts admin/src/types/api/api.d.ts admin/src/views/order/list/index.vue
git commit -m "feat: add admin order page"
```

### Task 5: Mini Program Checkout Preview, Submit, List, And Detail

**Files:**
- Modify: `miniprogram/types/api.ts`
- Create: `miniprogram/services/order.ts`
- Modify: `miniprogram/app.json`
- Modify: `miniprogram/pages/cart/cart.ts`
- Modify: `miniprogram/pages/cart/cart.wxml`
- Modify: `miniprogram/pages/cart/cart.wxss`
- Create: `miniprogram/pages/order/preview/preview.json`
- Create: `miniprogram/pages/order/preview/preview.ts`
- Create: `miniprogram/pages/order/preview/preview.wxml`
- Create: `miniprogram/pages/order/preview/preview.wxss`
- Create: `miniprogram/pages/order/list/list.json`
- Create: `miniprogram/pages/order/list/list.ts`
- Create: `miniprogram/pages/order/list/list.wxml`
- Create: `miniprogram/pages/order/list/list.wxss`
- Modify: `miniprogram/pages/order/detail/detail.json`
- Modify: `miniprogram/pages/order/detail/detail.ts`
- Modify: `miniprogram/pages/order/detail/detail.wxml`
- Modify: `miniprogram/pages/order/detail/detail.wxss`

**Interfaces:**
- Consumes: App order APIs from Task 2.
- Produces: cart checkout navigation, preview page, submit flow, order list, and detail page.

- [ ] **Step 1: Add mini program order types and service**

Define types matching API Contracts and service functions `previewOrder`, `submitOrder`, `getOrders`, and `getOrderDetail`.

Run: `cd miniprogram && pnpm typecheck`

Expected: FAIL until pages are wired.

- [ ] **Step 2: Wire cart checkout to preview**

Enable `去结算` when at least one available cart item exists. On tap, navigate to `/pages/order/preview/preview?cart_item_ids=1,2` using available item ids.

- [ ] **Step 3: Build order pages**

Preview page loads preview, shows snapshots and amount rows, submits with a stable per-page `idempotencyKey`, and redirects to detail on success. Order list loads `GET /app/orders`. Detail page loads `GET /app/orders/{id}`.

- [ ] **Step 4: Verify mini program typecheck**

Run: `cd miniprogram && pnpm typecheck`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add miniprogram/types/api.ts miniprogram/services/order.ts miniprogram/app.json miniprogram/pages/cart miniprogram/pages/order
git commit -m "feat: add mini program checkout flow"
```

### Task 6: Order Smoke Checks And Final Verification

**Files:**
- Modify: `docs/smoke-checks.md`

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: `Order Smoke Checks` section and final automated verification evidence.

- [ ] **Step 1: Add order smoke documentation**

Append `## Order Smoke Checks` after `## Coupon Smoke Checks`. The smoke must:

1. Start backend test profile.
2. Login admin and app user.
3. Create and enable a coupon template.
4. Claim coupon.
5. Create category/SPU/SKU and publish it.
6. Add SKU to cart.
7. Preview order and verify product amount, coupon discount, and payable amount.
8. Submit order and print order id/order no.
9. Verify cart row is gone.
10. Verify SKU stock decreased.
11. Verify user coupon is `LOCKED`.
12. Fetch app order detail.
13. Fetch admin order detail.
14. Close order through admin endpoint.
15. Verify SKU stock restored and user coupon is `CLAIMED` again.

- [ ] **Step 2: Run full automated checks**

Run:

```bash
cd backend/shop-server && ./mvnw test
cd miniprogram && pnpm typecheck
cd admin && CI=true pnpm build
git status --short --ignored
```

Expected: backend tests pass, mini program typecheck passes, admin build passes, and git status shows only intended tracked changes plus ignored local files.

- [ ] **Step 3: Commit**

```bash
git add docs/smoke-checks.md
git commit -m "docs: add order smoke checks"
```

## Final Verification Matrix

- Backend: `cd backend/shop-server && ./mvnw test`
- Mini program: `cd miniprogram && pnpm typecheck`
- Admin: `cd admin && CI=true pnpm build`
- Git: `git status --short --ignored`
- Real local smoke: run `docs/smoke-checks.md#order-smoke-checks` against the local backend test profile.

## Requirement Coverage Check

- Backend order table and item table: Task 1.
- Order state flow: Tasks 1-3 with `CREATED` and `CLOSED` active in this phase and future states reserved.
- Cart-based order creation: Task 2.
- Checkout preview API: Task 2.
- Amount snapshots: Tasks 1-2.
- Existing coupon calculation service integration: Task 2.
- SKU status, stock, and cart ownership validation: Task 2.
- Stock lock and stock logs: Tasks 1-3.
- Coupon lock and repeat-use prevention: Task 2.
- Close-time inventory and coupon release: Task 3.
- App order preview, submit, list, and detail APIs: Task 2.
- Admin order list/detail management page: Task 4.
- Mini program cart checkout to order preview/submit: Task 5.
- No WeChat Pay, payment callback, shipment, WeChat shipping upload, refund, or after-sale: Global Constraints and Scope Boundary.
- Order smoke checks: Task 6.
