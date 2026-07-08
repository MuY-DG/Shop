# Hotpot Shop WeChat Mini Program Design

Date: 2026-07-06

## 1. Goal

Build a standard commerce loop for a WeChat mini program that sells hotpot base products. The system includes:

- Spring Boot backend.
- Art Design Pro admin console.
- Native WeChat mini program client.

The first version must support real purchase flow: product browsing, SKU selection, cart, checkout, coupon usage, WeChat Pay JSAPI payment, payment callback, timeout order close, admin shipment, WeChat shipping information upload, user after-sale request, admin refund audit, and WeChat refund.

## 2. Key Decisions

- Architecture: modular monolith backend.
- Mini program: native WeChat mini program with TypeScript.
- Admin: Art Design Pro with backend permission mode.
- Backend security: Spring Security.
- Product model: SPU + SKU.
- Delivery method: express shipping.
- Coupon scope: coupon only in V1, with promotion extension points.
- User login: WeChat silent login plus optional phone authorization.
- Payment: WeChat Pay JSAPI.
- Refund: call WeChat refund API after admin approval.
- Inventory strategy: lock stock on order submit, release on timeout/cancel, confirm deduction on payment success.
- Shipment: admin shipment must upload shipping information to WeChat order shipping service before the order is treated as fully shipped.

## 3. Architecture

Use one Spring Boot service for both admin and mini program APIs. Split internal packages by business modules so each module has clear ownership and can be extracted later if needed.

Recommended repository layout:

```text
Shop/
  backend/
    shop-server/
  admin/
  miniprogram/
  docs/
```

Backend API namespaces:

```text
/admin/**  Art Design Pro admin APIs
/app/**    WeChat mini program APIs
/wxpay/**  WeChat payment and refund callbacks
/wechat/** WeChat platform event callbacks and platform-service utilities
```

Core backend modules:

- `common`: response envelope, error codes, exceptions, pagination, idempotency, audit helpers.
- `security`: Spring Security filters, token handling, admin/app authentication separation.
- `auth`: admin login, app login, token issue/refresh.
- `admin`: Art Design Pro menu, role, permission, admin user.
- `user`: mini program user, optional phone authorization, address book.
- `product`: category, SPU, SKU, images, specs.
- `inventory`: SKU stock, stock lock, stock logs.
- `cart`: cart items.
- `coupon`: coupon templates, user coupons, coupon lock/use/release.
- `promotion`: discount calculation pipeline, initially only coupon.
- `order`: order, order item, amount snapshot, order state machine.
- `payment`: WeChat Pay JSAPI payment, payment callback, refund API, refund callback.
- `logistics`: local shipment, carrier list, WeChat shipping upload.
- `aftersale`: refund-only and return-refund applications, audit and state logs.

## 4. Art Design Pro Compatibility

Art Design Pro uses a base response shape:

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

The backend should use this envelope for all JSON APIs.

Admin paged list APIs should return page data inside `data`:

```json
{
  "records": [],
  "total": 100,
  "current": 1,
  "size": 10
}
```

Art Design Pro should run with:

```env
VITE_ACCESS_MODE=backend
```

In backend mode, Spring Boot returns the menu tree and button permissions. Menu items should follow the Art Design Pro route shape:

```json
{
  "id": 1,
  "name": "Product",
  "path": "/product",
  "component": "/index/index",
  "meta": {
    "title": "商品管理",
    "icon": "ri:shopping-bag-3-line",
    "keepAlive": false,
    "authList": [
      { "title": "新增商品", "authMark": "product:spu:create" }
    ]
  },
  "children": []
}
```

Recommended admin menu groups:

- Dashboard.
- Product: category, SPU, SKU, stock.
- Marketing: coupon.
- Trade: order, shipment, after-sale.
- System: admin user, role, menu, permission.

References:

- Art Design Pro must-read: https://www.artd.pro/docs/zh/guide/must-read.html
- Art Design Pro permission: https://www.artd.pro/docs/zh/guide/in-depth/permission.html
- Art Design Pro route and menu: https://www.artd.pro/docs/zh/guide/essentials/route.html

## 5. Mini Program Scope

Use native WeChat mini program with TypeScript and TDesign MiniProgram.

V1 pages:

- Home: banners, featured products, category entry.
- Category/list: product list and filters.
- Product detail: SPU info, images, SKU picker, coupon entry, add to cart, buy now.
- Cart: SKU quantity, stock check, checkout entry.
- Checkout: address, coupon, freight, amount confirmation.
- Payment result: success, pending, failed.
- Orders: tabs for unpaid, to ship, shipped, completed, after-sale.
- Order detail: order items, amount, shipment, payment, after-sale actions.
- After-sale application: refund-only and return-refund.
- Address book: receiver, phone, region, detail.
- Profile: silent login, optional phone authorization.

V1 does not include member levels, points, group buying, flash sale, distribution, live commerce, content community, multi-warehouse inventory, or real-time third-party logistics tracking.

## 6. Product And Inventory

Product model:

- SPU stores shared product information: title, subtitle, category, images, detail content, status, sort, selling points.
- SKU stores sellable variants: spec combination, price, original price, stock, weight, SKU code, status, image.
- Order items save snapshots of SKU title, spec text, image, price, quantity, and amount.

Inventory behavior:

1. User submits order.
2. Backend verifies SKU is on sale and stock is enough.
3. Backend creates stock lock records.
4. Unpaid order timeout releases stock locks.
5. Payment success confirms stock deduction.
6. Order close/cancel releases unused locks and locked coupon.

Stock state changes must be recorded in `stock_log`.

## 7. Coupon And Promotion

V1 supports coupon only.

Coupon template capabilities:

- No-threshold coupon.
- Minimum-spend coupon.
- Validity period.
- Optional product/category scope.
- Total issue limit.
- Per-user claim/use limit.
- Enabled/disabled status.

Order creation should not hard-code coupon logic in the order service. Use a promotion calculation layer:

```text
CheckoutContext -> PromotionCalculator -> DiscountResult -> OrderAmountSnapshot
```

V1 has one calculator: `CouponDiscountCalculator`. Future calculators can add full reduction, member price, flash sale, or bundle discount without rewriting the order state machine.

Coupon lifecycle:

```text
CLAIMED -> LOCKED -> USED
                 \-> CLAIMED
```

Unpaid order timeout releases the locked coupon back to `CLAIMED`. Payment success consumes it.

## 8. Order State Machine

Order states:

```text
CREATED -> PAYING -> PAID -> TO_SHIP -> SHIPPED -> COMPLETED
                 \-> CLOSED
```

State meanings:

- `CREATED`: order created, stock and coupon locked.
- `PAYING`: WeChat prepay created.
- `PAID`: payment callback confirmed.
- `TO_SHIP`: paid order waiting for admin shipment.
- `SHIPPED`: local shipment saved and WeChat shipping upload succeeded.
- `COMPLETED`: user confirms receipt or system marks completed after configured period.
- `CLOSED`: unpaid timeout, user cancel before payment, or payment close confirmed.

Payment callback is the source of truth for paid state. Callback handling must be idempotent.

Order must store snapshots:

- Product title, SKU spec, product image.
- Unit price, quantity, line amount.
- Coupon amount, freight, payable amount, paid amount.
- Receiver name, receiver phone, address.
- Payment transaction id and merchant trade number.

## 9. WeChat Pay And Refund

Payment:

- Use WeChat Pay JSAPI.
- Backend creates payment order and returns JSAPI payment parameters to the mini program.
- Payment notify endpoint verifies signature, decrypts resource, checks amount, writes callback log, and updates local payment/order state idempotently.

Refund:

- User creates after-sale request.
- Admin reviews request.
- If approved, backend creates refund order and calls WeChat refund API.
- Refund callback verifies signature, writes callback log, and updates refund/after-sale/order state idempotently.

After-sale states:

```text
APPLIED -> REVIEWING -> APPROVED -> REFUNDING -> REFUNDED
                         \-> REJECTED
```

V1 supports:

- Refund-only.
- Return-refund.

Return logistics fields should be present in the model so the return-refund flow can be expanded, but V1 can keep return logistics simple.

## 10. WeChat Shipping Information Management

Admin shipment must integrate with WeChat Mini Program Order Shipping.

Official service:

- WeChat mini program shipping information management service.
- Relevant official page: https://developers.weixin.qq.com/miniprogram/dev/platform-capabilities/business-capabilities/order-shipping/order-shipping.html

V1 shipment flow:

```text
Admin enters carrier and tracking number
-> Backend validates order is TO_SHIP
-> Backend saves local shipment draft
-> Backend builds WeChat upload_shipping_info payload
-> Backend calls WeChat API
-> If WeChat succeeds, mark shipment uploaded and order SHIPPED
-> If WeChat fails, keep order TO_SHIP and expose retry/error in admin
```

Primary API:

```text
POST https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info?access_token=ACCESS_TOKEN
```

Official reference: https://developers.weixin.qq.com/miniprogram/dev/server/API/order_shipping/api_uploadshippinginfo.html

V1 default values:

```text
logistics_type = 1
delivery_mode = 1
```

Meaning:

- `logistics_type = 1`: express delivery.
- `delivery_mode = 1`: unified delivery.

Required payload concepts for express delivery:

- `order_key`: identify the payment order by `transaction_id`, or by `mchid + out_trade_no`.
- `shipping_list`: list of shipment packages.
- `tracking_no`: express tracking number.
- `express_company`: WeChat delivery company code.
- `item_desc`: product description, limited by WeChat to 120 Chinese characters.
- `upload_time`: RFC 3339 timestamp.
- `payer.openid`: mini program openid of the payer.

Carrier code source:

- `express_company` must use WeChat delivery id, not a free-text Chinese carrier name.
- Sync carriers from:

```text
POST https://api.weixin.qq.com/cgi-bin/express/delivery/open_msg/get_delivery_list?access_token=ACCESS_TOKEN
```

Official reference: https://developers.weixin.qq.com/miniprogram/dev/server/API/weixin-express/express-msg/api_get_delivery_list.html

Supporting APIs:

- Check whether shipping service is enabled:
  https://developers.weixin.qq.com/miniprogram/dev/server/API/order_shipping/api_istrademanaged.html
- Query WeChat shipping state:
  https://developers.weixin.qq.com/miniprogram/dev/server/API/order_shipping/api_getorder.html
- Query WeChat shipping order list:
  https://developers.weixin.qq.com/miniprogram/dev/server/API/order_shipping/api_getorderlist.html
- Set shipping message jump path:
  https://developers.weixin.qq.com/miniprogram/dev/server/API/order_shipping/api_setmsgjumppath.html
- Confirm receipt reminder:
  https://developers.weixin.qq.com/miniprogram/dev/server/API/order_shipping/api_notifyconfirmreceive.html

V1 should include these local tables or equivalent entities:

- `carrier`: WeChat `delivery_id`, display name, enabled flag.
- `shipment`: local shipment record, carrier, tracking number, order id, status.
- `wechat_shipping_upload`: order id, transaction id, request payload, response errcode/errmsg, upload status, retry count, last attempt time.
- `wechat_trade_event`: WeChat trade-management reminder and settlement event logs.

Important constraints:

- Unified delivery requires exactly one shipping item.
- Split delivery is supported by the model but not exposed by default in V1.
- WeChat supports up to 15 packages for one payment order in split delivery.
- Split delivery only supports express delivery.
- If shipping info is modified after completion, WeChat treats it as re-shipment; each payment order has only one re-shipment chance.
- `upload_time` must be newer than the previous upload when updating shipping info.

## 11. Authentication And Authorization

Use Spring Security.

Admin authentication:

- Username/password login.
- Password hashed with strong one-way hashing.
- Admin token is separate from app token.
- Roles and permissions control admin APIs and Art Design Pro menus/buttons.

Mini program authentication:

- `wx.login` gets code.
- Backend exchanges code for `openid` and `session_key`.
- Backend creates or updates app user.
- Backend issues app token.
- Phone authorization is optional and stored as user profile data.

Token separation:

- Admin tokens cannot call `/app/**`.
- App tokens cannot call `/admin/**`.
- WeChat callbacks do not use user token authentication; they use signature verification and callback-specific validation.

## 12. Idempotency And Consistency

Required idempotent operations:

- Submit order: client idempotency key.
- Create payment: one active payment order per order.
- Payment callback: dedupe by WeChat transaction id and merchant trade number.
- Timeout close: release stock and coupon once.
- Admin shipment: only one successful WeChat shipping upload for the same unified shipment unless explicitly treated as allowed update/re-shipment.
- Refund application: prevent duplicate open after-sale requests for the same order item scope.
- Refund approval: call WeChat refund once per refund order.
- Refund callback: dedupe by refund id and out refund number.
- Coupon lock/use/release: state transition only once.

Use both DB unique keys and Redis idempotency keys where appropriate. Critical payment, refund, stock, coupon, and shipment state changes must be protected by database transactions and state checks.

## 13. Error Handling

All APIs return the standard response envelope. Business error codes should be stable and documented.

Initial business error categories:

- Authentication required.
- Permission denied.
- Product unavailable.
- SKU unavailable.
- Stock shortage.
- Coupon unavailable.
- Coupon already used.
- Order state conflict.
- Payment pending.
- Payment amount mismatch.
- Payment callback verification failed.
- Shipment carrier invalid.
- WeChat shipping upload failed.
- Refund state conflict.
- Refund rejected.
- WeChat refund failed.

Admin should show actionable errors, especially for WeChat shipping upload failures and refund failures.

## 14. Technology Stack

Backend:

- Java 21.
- Spring Boot 3.
- Spring Security.
- MyBatis-Plus.
- MySQL 8.
- Redis.
- Flyway.
- OpenAPI/Knife4j.
- WeChat Pay API v3 Java SDK.

Admin:

- Art Design Pro.
- Vue 3.
- TypeScript.
- Vite.
- Element Plus.

Mini program:

- Native WeChat mini program.
- TypeScript.
- TDesign MiniProgram.

Infrastructure:

- Environment profiles: dev, test, prod.
- Structured logs with request id.
- Admin operation audit logs.
- Payment callback logs.
- Refund callback logs.
- WeChat shipping upload logs.
- File storage adapter interface; local storage in dev, OSS/COS adapter later.

## 15. Development Milestones

Milestone 0: project setup and infrastructure.

- Create backend/admin/miniprogram structure.
- Configure Spring Boot, MySQL, Redis, Flyway.
- Add standard response, error handling, logging, OpenAPI.
- Add Art Design Pro base project.
- Add native mini program base with request layer.

Milestone 1: authentication and RBAC.

- Admin login with Spring Security.
- Admin user, role, menu, permission.
- Art Design Pro backend menu mode.
- Mini program silent login.
- Token separation between admin and app.

Milestone 2: product catalog.

- Category, SPU, SKU, images, stock.
- Admin product pages.
- Mini program home/list/detail pages.

Milestone 3: cart, checkout, coupon.

- Cart.
- Address book.
- Coupon claim/list/use.
- Checkout amount calculation.
- Stock and coupon pre-check.

Milestone 4: order and payment.

- Order creation.
- Stock lock and coupon lock.
- WeChat Pay JSAPI prepay.
- Payment callback.
- Timeout close and release.

Milestone 5: shipment and WeChat shipping upload.

- Carrier sync from WeChat delivery list.
- Admin shipment page.
- Local shipment record.
- WeChat `upload_shipping_info`.
- Shipment retry and error display.
- WeChat shipping state query.
- Optional message jump path setting.

Milestone 6: after-sale and refund.

- Refund-only and return-refund application.
- Admin after-sale audit.
- WeChat refund call.
- Refund callback.
- Order/after-sale/refund state logs.

## 16. Acceptance Criteria

V1 is acceptable when:

- Admin can log in and receive backend-generated Art Design Pro menus.
- Admin can create category, SPU, SKU, price, stock, and publish product.
- Mini program user can silently log in, browse product, select SKU, add to cart, and submit order.
- User can claim/use coupon in checkout.
- Order submit locks stock and coupon.
- Unpaid timeout releases stock and coupon.
- User can complete WeChat JSAPI payment.
- Payment callback updates payment and order exactly once even when repeated.
- Admin can ship an order.
- Shipment uploads to WeChat `upload_shipping_info` successfully before order becomes `SHIPPED`.
- Failed WeChat shipping upload is visible in admin and can be retried safely.
- User can view shipment information in mini program.
- User can apply for refund-only or return-refund.
- Admin can approve or reject after-sale request.
- Approved refund calls WeChat refund once.
- Refund callback updates refund and after-sale state exactly once even when repeated.

## 17. Notes For Implementation Planning

Implement by vertical slices. Each slice should include:

- Database migration.
- Backend API.
- Admin page if relevant.
- Mini program page if relevant.
- Focused tests for state transitions and idempotency.

Do not implement future marketing features in V1. Keep extension points in promotion, logistics, storage, and after-sale modules, but keep the user-facing V1 focused on the stable purchase loop.
