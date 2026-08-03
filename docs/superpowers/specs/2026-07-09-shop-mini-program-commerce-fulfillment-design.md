# Shop Mini Program Commerce And Fulfillment Design

Date: 2026-07-09

Status: Approved design, pending implementation planning

## 1. Goal

Complete the existing Shop commerce loop without reopening the already delivered foundation, authentication/RBAC, product, cart, coupon, order/stock-lock, storage, WeChat Pay, base shipment, after-sale, or refund phases.

This phase adds:

- Durable mini program app sessions and a consistent app-user profile.
- A direct-buy checkout source that shares the existing order calculation and locking path without touching the cart.
- A minimal address book and order receiver snapshots: captured at creation, replaceable only while the order is CREATED or PAYING, and immutable after payment.
- A reachable, paged mini program order center with receipt confirmation and after-sale navigation.
- All four WeChat logistics types under unified delivery.
- Shipping capability discovery, official carrier-code selection, safe retry, and explicit unfiled/unavailable behavior.
- A real scheduler for expired payment close processing.

The delivery target is the main checkout at:

    /Users/muybaby/Project/Production/Shop

If an isolated worktree is used during execution, every accepted commit must be safely converged back to main and verified again on main.

## 2. Scope Boundary

### 2.1 Included

- POST /app/auth/refresh.
- POST /app/auth/logout.
- GET /app/users/me.
- Consistent AppUserProfile mapping for login, refresh, me, and phone authorization.
- Versioned mini program auth state and migration from the legacy token keys.
- Single-flight session restore, refresh, and silent login.
- One 401 recovery cycle and one original-request retry.
- CART and DIRECT checkout sources.
- Product quantity selection and direct-buy navigation.
- User address CRUD, default address, address selection, and ownership checks.
- Order receiver snapshots.
- Mini program profile order/address/after-sale entries.
- Order status groups, paging, reach-bottom loading, retry, and pull-to-refresh.
- Order detail payment, address, shipment, after-sale, and key-time presentation.
- Current-user after-sale list and detail.
- Local SHIPPED to COMPLETED receipt confirmation.
- logistics_type 1, 2, 3, and 4.
- delivery_mode 1 only.
- Exactly one shipping_list item.
- WeChat shipping capability query and carrier list synchronization.
- Admin logistics-type form and dynamic validation.
- Safe shipping upload retry and clear unfiled/unavailable states.
- Scheduled timeout order close.
- Automated exact-payload tests and separately reported real WeChat smoke.

### 2.2 Excluded

- Split delivery or multiple packages.
- Real same-city dispatch integration.
- Virtual entitlement fulfillment.
- Pickup verification or pickup-code redemption.
- Third-party real-time tracking.
- Multi-warehouse inventory.
- Member points or new promotion types.
- Rewriting WeChat Pay or refund.
- Silent phone-number collection.
- Silent nickname/avatar collection.
- wx.getUserProfile calls.
- Persisting temporary avatar file paths.

Nickname and avatar are intentionally absent from this phase contract. A future explicit user-submission phase may extend AppUserProfile.

## 3. Verified Baseline

The live repository was inspected before this design:

- Branch: main.
- HEAD: cb468d16 chore: use tunnel domain for miniprogram api.
- Tracked worktree: clean.
- Remote: none configured.
- Latest Flyway migration: V9__payment_runtime_setting.sql.
- Baseline backend suite: 235 tests, 0 failures, 0 errors.
- Baseline admin typecheck: passed.
- Baseline mini program typecheck: passed.

Confirmed gaps:

- App auth exposes login and phone only.
- Opaque refresh tokens are issued and stored, but cannot be consumed, rotated, or revoked.
- The mini program stores the refresh token but never reads it.
- The request layer has no 401 refresh/retry behavior.
- Profile onShow always calls silentLogin.
- The app profile response cannot restore the masked phone.
- Order preview and submit accept cartItemIds only.
- AppOrderService always stores source CART and always deletes selected cart rows.
- No address table or address API exists.
- Order receiver columns remain empty during submit.
- Product direct buy is disabled.
- The order list has no tabs or reach-bottom paging.
- App OrderDetail omits backend payment and shipment fields.
- An after-sale query failure currently clears the whole order detail.
- No SHIPPED to COMPLETED transition exists.
- PaymentTimeoutCloseService has a callable method but no scheduler.
- The current shipping provider hard-codes logistics_type and delivery_mode to 1.
- Admin shipping requires free-text company and tracking number for every shipment.
- shipmentNote is incorrectly sent as item_desc.
- The current retry endpoint can call WeChat again after UPLOADED.
- isTradeManaged and get_delivery_list are not integrated.

## 4. Architecture Decisions

### 4.1 Extend The Existing Modular Monolith

Keep the current Spring Boot modular monolith and native mini program. Add focused services and DTOs around the existing order, auth, user, logistics, payment, and after-sale modules.

Do not introduce a second order pipeline. Both checkout sources produce the same internal CheckoutSelection and then reuse:

- Product/SKU availability validation.
- Price snapshots.
- Promotion and coupon calculation.
- Stock locking.
- Coupon locking.
- Idempotency.
- Order and item snapshots.

### 4.2 Keep Existing Order Status Values

The authoritative order statuses remain:

    CREATED
    PAYING
    PAID
    SHIPPED
    COMPLETED
    CLOSED
    REFUNDING
    REFUNDED

Do not add TO_SHIP. In user-facing status groups:

- CREATED and PAYING mean 待付款.
- PAID means 待发货.
- SHIPPED means 待收货.
- COMPLETED means 已完成.
- REFUNDING and REFUNDED are shown through order detail and after-sale surfaces.

Backend, admin, and mini program types must use the same enum values.

### 4.3 Keep Local Fulfillment Independent From Platform Capability

Saving a valid local shipment changes a PAID order to SHIPPED even when the mini program is unfiled, the shipping service is unavailable, or the WeChat upload fails.

The local shipment result and the WeChat upload result are separate facts:

    local shipment: SHIPPED
    WeChat upload: SKIPPED | UPLOADING | UPLOADED | FAILED | UNAVAILABLE | UNKNOWN

No mock, disabled capability, or failed real request may be recorded as UPLOADED.

## 5. App Session And Profile Design

### 5.1 Canonical Profile

Use one backend DTO:

    AppUserProfile {
      userId: number
      openidMasked: string
      phoneAuthorized: boolean
      phoneNumberMasked: string | null
    }

The profile mapper reads the current app_user row. The full openid and full authorized phone remain server-side. Auth state persists only the masked profile.

Login, refresh, me, and phone authorization must all use this mapper. Phone authorization returns the full AppUserProfile, not a smaller response with different fields.

Nickname and avatar fields are not added to the phase contract. A future explicit user-submission phase can extend AppUserProfile without changing these four fields.

### 5.2 API Contracts

Login:

    POST /app/auth/login
    request:
      { code: string }

    data:
      {
        token: string,
        refreshToken: string,
        expiresIn: number,
        user: AppUserProfile
      }

Refresh:

    POST /app/auth/refresh
    request:
      { refreshToken: string }

    data:
      {
        token: string,
        refreshToken: string,
        expiresIn: number,
        user: AppUserProfile
      }

Current user:

    GET /app/users/me
    Authorization: Bearer app_access_token

    data: AppUserProfile

Phone authorization:

    POST /app/auth/phone
    Authorization: Bearer app_access_token
    request:
      { code: string }

    data: AppUserProfile

Logout:

    POST /app/auth/logout
    Authorization: Bearer app_access_token

    data: null

/app/auth/login and /app/auth/refresh are public at the access-token filter layer. Refresh validates its apr_ token inside the auth service. Logout and phone require an APP access token.

### 5.3 Session Family And Refresh Rotation

Each login creates one stable session family identified by `TokenSession.sessionId`. Every access/refresh pair in that family has its own `TokenSession.generationId`; refresh keeps the same `sessionId` and creates a new `generationId`. A production generation ID is canonical 36-character UUID text in `8-4-4-4-12` hexadecimal shape; lowercase and uppercase hex are both accepted and preserved. Java and Redis apply the same shape check without imposing UUID version or variant bits. A legacy serialized generation value that is missing, JSON null, non-string, ASCII or Unicode whitespace, or otherwise not canonical falls back to `sessionId`.

Token storage must support these atomic operations:

- Save an access/refresh generation and its stable family index only when no family-revoked marker exists. The marker check and writes are one synchronized/Lua compare-and-set operation.
- Lookup an access token and reject it when either its stable family marker or generation marker exists.
- Atomically consume one refresh token, write a marker for only the consumed generation, and delete the indexed old generation.
- Logout by writing the stable family marker and deleting every currently indexed token key in that family.

Refresh algorithm:

1. Validate apr_ prefix.
2. Atomically consume the refresh token. The decoded `sessionId` must be a nonblank string; otherwise delete only the presented refresh key and return absent. Normalize every missing, JSON-null, non-string, whitespace, or noncanonical legacy generation value to `sessionId` without applying string operations to non-string JSON values.
3. Reject absent, expired, already-consumed, or wrong-kind tokens.
4. Write the old generation marker and revoke its old access and remaining indexed keys.
5. Re-read the enabled app user and map the current profile.
6. Issue a fresh access/refresh pair with a new `generationId` in the same stable `sessionId` family.
7. Atomically save the new generation only if logout has not marked the family revoked.

Concurrent use of one refresh token produces one successful rotation at most.

Refresh and logout are linearizable across the consume/save boundary. If logout writes the family marker before the refreshed generation is saved, save is rejected with authentication required and no token is resurrected. If refreshed generation save wins first, logout deletes that indexed generation. Replaying an already-consumed legacy refresh deletes only the presented stale token and cannot delete a newer generation's stable family index.

Logout resolves the access token session, writes the stable family marker, and revokes the whole current session family. Generation and family markers live for at least `max(accessTtl, refreshTtl)`, so an unindexed legacy token cannot become usable after a shorter sibling TTL expires. It does not log either raw token.

In-memory and Redis implementations must have equivalent session-family, canonical-generation, and legacy-fallback semantics. Redis uses Lua for family save, refresh consumption, and logout; missing, set, and corrupt index types have defined fail-closed behavior, and only hashed token keys are stored or passed to Lua.

### 5.4 Versioned Client Auth State

The canonical storage entry is:

    shop_app_auth_state_v1

Shape:

    {
      version: 1,
      accessToken: string,
      refreshToken: string,
      accessExpiresAt: number,
      profile: AppUserProfile | null
    }

On startup:

1. Parse and validate the versioned entry.
2. If it is absent, read legacy shop_app_token and shop_app_refresh_token.
3. Create a version-1 state without throwing when either old value is missing or malformed.
4. Remove legacy keys only after the new state is persisted.
5. Never persist an unmasked profile phone.

The in-memory global state mirrors the versioned state.

### 5.5 Single-Flight And 401 Recovery

Maintain module-level promises for:

- ensureSession.
- silentLogin.
- refreshSession.

Concurrent callers await the same promise. The promise is cleared in finally.

Request recovery is iterative, not recursive:

1. Send the original request.
2. If it is not HTTP 401, handle the normal envelope.
3. If it is 401 and the request is eligible, attempt refresh once.
4. If refresh succeeds, retry the original request once.
5. If refresh fails, clear auth state and run silentLogin once.
6. After silent login, use the same single permitted original-request retry.
7. A second 401 is returned to the caller and clears invalid state; no further refresh or login occurs.

Login, refresh, logout, and explicitly unauthenticated requests opt out of automatic recovery. wx.uploadFile paths use the same recovery coordinator instead of inventing a separate loop.

Profile onShow performs:

    ensureSession -> GET /app/users/me -> update page

It never calls silentLogin unconditionally.

### 5.6 Phone Authorization Rules

The getPhoneNumber button remains the only trigger. There is no automatic request.

The WeChat code is:

- Valid for five minutes.
- Consumable once.
- Different from the wx.login code.

Missing code, user cancellation, unverified subject, quota failure, or unavailable account capability produces a clear non-blocking message. The client does not fabricate success.

## 6. Address Book Design

### 6.1 Schema

V10 creates user_address:

    id BIGINT primary key
    user_id BIGINT not null
    receiver_name VARCHAR(64) not null
    receiver_phone VARCHAR(32) not null
    province VARCHAR(64) not null
    city VARCHAR(64) not null
    district VARCHAR(64) not null
    detail_address VARCHAR(255) not null
    is_default BOOLEAN not null default false
    created_at TIMESTAMP not null
    updated_at TIMESTAMP not null

Indexes support user listing and default lookup.

Default invariants are enforced transactionally:

- The first address becomes default.
- Setting an address default clears the previous default for that user.
- Deleting the default promotes the oldest remaining address.
- No operation can read, update, delete, select, or default another user's address.

Address receiver phones are returned only to authenticated address pages and are not copied into auth storage. The auth profile continues to store only phoneNumberMasked.

### 6.2 Address APIs

    GET    /app/addresses
    GET    /app/addresses/{addressId}
    POST   /app/addresses
    PUT    /app/addresses/{addressId}
    DELETE /app/addresses/{addressId}
    POST   /app/addresses/{addressId}/default

Create/update request:

    {
      receiverName: string,
      receiverPhone: string,
      province: string,
      city: string,
      district: string,
      detailAddress: string,
      isDefault: boolean
    }

Response additionally contains id, formattedAddress, createdAt, and updatedAt.

Names, phones, and each address part are trimmed and validated. Blank province/city/district/detail values are rejected.

## 7. Checkout Selection And Order Snapshot Design

### 7.1 Public Selection Contract

Checkout source:

    CART
    DIRECT

Preview request:

    {
      source?: "CART" | "DIRECT",
      cartItemIds?: number[],
      skuId?: number,
      quantity?: number,
      addressId?: number | null,
      userCouponId?: number | null
    }

Submit request:

    {
      source?: "CART" | "DIRECT",
      cartItemIds?: number[],
      skuId?: number,
      quantity?: number,
      addressId: number,
      userCouponId?: number | null,
      idempotencyKey: string
    }

Missing source is normalized to CART only for compatibility with the current client and smoke scripts. The updated mini program always sends it explicitly.

### 7.2 Conditional Validation

For CART:

- cartItemIds must be nonempty after normalization.
- skuId and quantity must be absent.
- Every cart item must belong to the current user.
- Existing cart availability rules remain in force.

For DIRECT:

- Exactly one skuId is required.
- quantity is required and between 1 and 999.
- cartItemIds must be absent.
- SKU, SPU, category, and stock validations match CART.
- No cart row is created, merged, updated, deleted, or cleared.

For both:

- Preview may omit addressId so a user with no address can still see items and price before selecting an address.
- Submit requires an owned addressId.
- A supplied userCouponId must belong to the user and be applicable.
- An omitted userCouponId keeps the current best-coupon behavior.

### 7.3 Internal Selection

Add a focused checkout-selection component that returns:

    CheckoutSelection {
      source
      previewItems
      checkoutItems
      selectedCartItemIds
      productOriginalAmountCent
      productAmountCent
      checkoutContext
    }

CART and DIRECT use different loaders but the same snapshot and pricing builder. AppOrderService owns the transaction and consumes the normalized selection.

OrderPreviewItem.cartItemId becomes nullable for DIRECT.

### 7.4 Submit Transaction

Submit performs:

1. Normalize and validate selection.
2. Lock relevant cart rows for CART.
3. Lock SKU rows for both sources.
4. Lock and validate the owned address.
5. Calculate coupon and amounts.
6. Create the order with the real source.
7. Copy receiver name, phone, and the full formatted address into shop_order.
8. Create immutable order-item snapshots.
9. Lock stock and coupon using the existing mechanisms.
10. Delete selected cart rows only when source is CART.
11. Return the current OrderSubmitResponse.

Address edits after submit never change the order snapshot.

### 7.5 Idempotency Digest

V10 adds checkout_request_digest to shop_order.

The digest is SHA-256 over a canonical, non-secret representation of:

- Source.
- Sorted requested cart item ids, or direct sku id and quantity.
- Address id.
- Requested coupon id, including an explicit null marker when automatic selection is requested.

The digest is computable from the replayed request after CART submit has deleted the original cart rows.

Do not include raw receiver phone or full address in logs.

When the same user and idempotency key already exist:

- Matching digest returns the existing order.
- Different digest returns ORDER_STATE_CONFLICT.

Legacy rows with an empty digest retain the previous lookup behavior only for pre-migration compatibility.

## 8. Mini Program Product And Checkout Experience

Product detail adds:

- Selected quantity, default 1.
- Minus and plus controls.
- Upper bound of min(999, selected SKU stock).
- Reset/clamp when the SKU changes.
- Disabled direct buy when no valid enabled/in-stock SKU exists.
- Clear toast for disabled or sold-out SKU selection.

Add to cart uses the selected quantity and keeps the existing cart behavior.

Direct buy navigates to the existing preview page with:

    source=DIRECT
    sku_id=<id>
    quantity=<quantity>

Cart checkout navigates with source=CART and cart_item_ids.

Preview:

- Restores the selected/default address.
- Shows a no-address call to action when none exists.
- Allows address switching through the address list in selection mode.
- Reloads price and coupon when source, quantity, coupon, or address changes.
- Disables submit until a valid address is selected.
- Keeps one stable idempotency key for the page instance.

## 9. Mini Program Order Center

### 9.1 Profile Entries

Profile adds:

- 我的订单.
- 待付款.
- 待发货.
- 待收货.
- 待评价.
- 我的售后.
- 收货地址.

Navigation carries the initial status group rather than creating duplicate order pages.

### 9.2 Order List Contract

Extend the current endpoint:

    GET /app/orders?current=1&size=10&statusGroup=UNPAID

statusGroup values:

    ALL
    UNPAID
    TO_SHIP
    TO_RECEIVE
    TO_REVIEW
    COMPLETED
    CANCELLED

Mapping:

- ALL: no status predicate.
- UNPAID: CREATED or PAYING.
- TO_SHIP: PAID.
- TO_RECEIVE: SHIPPED.
- TO_REVIEW: COMPLETED、`completed_at` 非空，且至少一个未评价订单项关联的商品尚未永久清理、当前仍可评价。
- COMPLETED: 所有 COMPLETED 订单；“已完成”始终表示已收货，与是否评价无关。
- CANCELLED: CLOSED.

The existing exact status query remains supported for compatibility.

Each order summary includes its persisted order-item snapshots (title, specification,
display/SKU/main images, unit price, quantity, and review state) plus the pending-review
item count. The list never joins current catalog presentation data, so historical orders
remain stable after a product is edited or taken off sale.

The page provides:

- Status tabs.
- current/size paging.
- onReachBottom append.
- Pull-to-refresh reset.
- Separate initial-loading and next-page-loading states.
- Empty state.
- Error state with retry.
- No duplicate records when onShow and reach-bottom overlap.

### 9.3 Order Detail

App OrderDetailResponse must match backend reality and include:

- Receiver snapshot.
- Item and amount snapshots.
- paymentStatus, outTradeNo, transactionId, paidAt.
- Logistics type, delivery mode, item description, carrier code/name, tracking number, local shipment status, WeChat upload status, safe user-facing message, shippedAt, and uploadedAt.
- Latest after-sale summary.
- createdAt, closedAt, paidAt, shippedAt, completedAt, refundingAt, and refundedAt when present.

The app detail service must query the latest payment row using the same fallback semantics as admin instead of returning null fields unconditionally.

Page loading is split:

1. Load and display order detail.
2. Load after-sale data independently.

An after-sale failure cannot clear a successfully loaded order.

onShow always refreshes. The page JSON enables pull-to-refresh, and onPullDownRefresh always stops the indicator in finally.

### 9.4 Receipt Confirmation

Before receipt confirmation, unpaid orders may replace their receiver snapshot:

    PUT /app/orders/{orderId}/receiver

Rules:

- APP token required.
- Order must belong to the current user.
- Request selects an address owned by the current user.
- Only CREATED or PAYING orders may replace the receiver snapshot; paid and later states remain immutable.
- The operation changes only receiver fields and does not recalculate items, stock, discounts, freight, or payable amount.

    POST /app/orders/{orderId}/confirm-receipt

Rules:

- APP token required.
- Order must belong to the current user.
- SHIPPED transitions atomically to COMPLETED and sets completed_at.
- Repeating on COMPLETED returns the current completed result.
- Other statuses return ORDER_STATE_CONFLICT.
- WeChat receipt-confirmation component capability never blocks this local transition.

Completed orders remain eligible for the existing after-sale application policy so local receipt confirmation does not remove customer protection.

### 9.5 Current-User After-Sales

Add:

    GET /app/after-sales?current=1&size=10&status=<optional>
    GET /app/after-sales/{afterSaleId}

Both enforce current-user ownership. The mini program adds one after-sale list and one detail page, reusing existing response fields and the current application page.

## 10. WeChat Logistics Design

### 10.1 Strong Types

Backend LogisticsType uses the official numeric values:

    EXPRESS(1)
    LOCAL_DELIVERY(2)
    VIRTUAL(3)
    PICKUP(4)

DeliveryMode contains:

    UNIFIED(1)

JSON serialization and parsing use the numeric values. Admin and mini program expose the same numeric-to-label mapping through typed constants.

### 10.2 V10 Shipment Migration

V10 alters order_shipment additively and does not edit V8.

Required persisted fields:

- logistics_type.
- delivery_mode.
- item_desc.
- express_company_code.
- express_company_name.
- consignor_contact.
- receiver_contact.
- upload_time.
- last_attempt_at.

V10 renames the existing express_company column to express_company_name and adds express_company_code. The rename preserves every historical value without pretending that free text is an official carrier id. Existing tracking_no and shipment_note values remain intact.

express-company and tracking columns become nullable for non-express modes.

Legacy rows are normalized as:

- logistics_type = 1.
- delivery_mode = 1.
- Existing company text preserved as the name snapshot.
- Existing tracking number preserved.
- Existing shipment note preserved only as the local note.
- item_desc is backfilled from order-item descriptions when safely possible, otherwise a neutral legacy description is used.
- A free-text historical company is not pretended to be a valid WeChat delivery id.

### 10.3 Carrier Directory

V10 creates wechat_delivery_company:

    delivery_id VARCHAR(128) primary key
    delivery_name VARCHAR(128) not null
    enabled BOOLEAN not null
    synced_at TIMESTAMP not null

The official get_delivery_list response is synchronized into this table. A carrier absent from a later sync is disabled rather than deleted so old shipment snapshots still resolve.

Admin endpoints:

    GET  /admin/wechat-shipping/carriers
    POST /admin/wechat-shipping/carriers/sync

Shipping an express order requires an enabled delivery id. The backend resolves the display name and stores both code and name snapshots. Operators cannot submit a display name as the code.

### 10.4 Capability Query

    GET /admin/wechat-shipping/capability

Response:

    {
      uploadEnabled: boolean,
      state: "AVAILABLE" | "UNAVAILABLE" | "UNKNOWN",
      tradeManaged: boolean | null,
      errorCode: string | null,
      errorMessage: string | null,
      checkedAt: string
    }

The provider calls is_trade_managed with an internal access token. Access tokens and authorization headers are never returned or logged.

An unfiled or unapproved account returns UNAVAILABLE with a safe code/message. Transport ambiguity returns UNKNOWN.

### 10.5 Admin Ship Contract

    POST /admin/orders/{orderId}/ship

Request:

    {
      logisticsType: 1 | 2 | 3 | 4,
      itemDesc: string,
      expressCompanyCode?: string,
      trackingNo?: string,
      consignorContact?: string,
      shipmentNote?: string
    }

itemDesc is prefilled from order items:

    product title + spec + quantity

The automatic value is code-point limited to 120 characters. Admin may edit it, but the backend rejects blank or over-limit values.

For SF, the backend first derives receiver_contact from the immutable order receiver phone and masks it while keeping the final four digits visible. An optional consignorContact is normalized and masked server-side.

The response separates:

    localShipmentStatus
    wechatUploadStatus
    wechatErrorCode
    wechatErrorMessage
    retryCount
    shippedAt
    wechatUploadedAt

Admin shows a warning rather than a generic success toast when local shipment succeeds but WeChat upload does not.

### 10.6 Conditional Validation

EXPRESS:

- expressCompanyCode required and must resolve to an enabled carrier.
- trackingNo required.
- itemDesc required, at most 120 Unicode code points.
- SF requires at least one masked consignor or receiver contact.

LOCAL_DELIVERY:

- itemDesc required.
- No company, tracking number, or contact is required.

VIRTUAL:

- itemDesc required.
- No company, tracking number, or contact is required.

PICKUP:

- itemDesc required.
- No company, tracking number, or contact is required.
- Pickup location and pickup code are not added in this phase.

All modes:

- Order must be PAID for first local shipment.
- delivery_mode is always 1.
- shipping_list contains exactly one item.

### 10.7 Exact WeChat Payload

Common payload:

    {
      order_key,
      logistics_type,
      delivery_mode: 1,
      shipping_list: [ one item ],
      upload_time,
      payer: { openid }
    }

EXPRESS shipping item:

    {
      tracking_no,
      express_company,
      item_desc,
      contact?: {
        consignor_contact?: masked string,
        receiver_contact?: masked string
      }
    }

LOCAL_DELIVERY, VIRTUAL, and PICKUP shipping item:

    {
      item_desc
    }

Non-express serialization omits tracking_no, express_company, and contact. It does not send those keys with empty-string values.

order_key uses the payment transaction_id branch. Missing transaction id does not block local shipment; it records a safe FAILED result for the platform upload.

upload_time is RFC 3339 and persisted. Every permitted retry uses a newer value.

### 10.8 Local Save And WeChat Attempt

The local transaction:

1. Lock and validate the PAID order.
2. Validate the mode-specific request.
3. Insert the complete original local shipment facts.
4. Set order SHIPPED and shipped_at.
5. Commit.

Only after that commit does the upload coordinator:

1. Atomically claim the shipment as UPLOADING.
2. Rebuild the payload exclusively from persisted shipment/order/payment data.
3. Check configured/capability state.
4. Call WeChat when eligible.
5. Persist UPLOADED, FAILED, UNAVAILABLE, or UNKNOWN.

A real provider exception cannot roll back the local shipment.

### 10.9 Retry Rules

    POST /admin/orders/{orderId}/shipping/retry-wechat-upload

Ordinary retry is allowed only from:

- FAILED.
- UNAVAILABLE.
- SKIPPED after upload becomes enabled.

It is rejected from:

- UPLOADING.
- UPLOADED.
- UNKNOWN.

UNKNOWN represents an ambiguous transport outcome that might already have reached WeChat. It requires later reconciliation or an explicit future re-shipment workflow and cannot consume the one re-shipment chance through ordinary retry.

Concurrent retry claims result in one provider call at most.

retryCount counts operator retry attempts, not the initial upload.

## 11. Admin And Mini Program Presentation

### 11.1 Admin

The existing order detail drawer is extended rather than replaced.

Shipment form order:

1. Select logistics type.
2. Review/edit item description.
3. For EXPRESS, select carrier code and enter tracking number.
4. For SF, show which masked contact will be sent.
5. Optionally enter a local shipment note.

Capability state and carrier sync state are visible before submit.

Order detail renders mode-specific labels:

- 实体快递: carrier name/code and tracking number.
- 同城配送: 同城配送，无快递单号.
- 虚拟商品: 虚拟商品交付.
- 用户自提: 用户自提.

### 11.2 Mini Program

Order detail uses the same labels but does not expose internal WeChat error details that are only actionable to administrators.

It shows:

- Local fulfillment mode.
- Carrier/tracking for express.
- Non-express explanation.
- WeChat upload state in user-safe wording.
- Shipment and upload times.

## 12. Payment Timeout Scheduler

PaymentTimeoutCloseService remains the business operation but is hardened before scheduling:

- Scan a bounded batch.
- Process each payment independently.
- Do not hold one transaction and all row locks across all remote close calls.
- Isolate one provider failure so remaining expired payments can continue.
- Reuse OrderCloseService so stock and coupon release remain idempotent.

Add a scheduler enabled by configuration:

    shop.pay.timeout-scan-enabled
    shop.pay.timeout-scan-delay
    shop.pay.timeout-scan-batch-size

The test profile disables automatic scheduling by default and tests the coordinator directly with deterministic time.

## 13. Security And Privacy

Never log or persist in unrestricted request logs:

- WeChat access tokens.
- Full openid.
- Full authorized profile phone.
- Raw phone authorization code.
- wx.login code.
- Certificates or private keys.
- Authorization headers.
- Payment notification plaintext.

Shipping logs contain only:

- Local order/shipment identifiers.
- Safe status.
- Sanitized errcode/errmsg.
- Exception class when necessary.

The provider payload is tested through mock HTTP capture but not printed in production logs.

## 14. Testing Strategy

Every implementation task follows RED, GREEN, refactor:

1. Add one or more failing tests.
2. Run them and confirm the expected failure.
3. Implement the smallest correct behavior.
4. Run focused verification.
5. Run spec review and code-quality review.
6. Fix Critical and Important findings.
7. Re-run covering tests and re-review.
8. Commit that task only.

### 14.1 Backend Required Coverage

- login, refresh, me, and phone use the same profile mapper.
- Phone remains masked after later login and me.
- Refresh rotates exactly once under concurrency.
- Expired/consumed refresh tokens fail.
- Logout revokes current access and refresh tokens.
- Address ownership, default invariants, and delete promotion.
- CART and DIRECT conditional request validation.
- DIRECT never inserts, updates, merges, or deletes cart rows.
- Correct order source and receiver snapshot.
- Idempotency digest match and mismatch behavior.
- Status-group paging.
- SHIPPED to COMPLETED transition and idempotent repeat.
- Completed-order after-sale eligibility.
- Scheduled timeout close releases stock and coupon.
- All four exact upload JSON payloads.
- Non-express omitted fields.
- item_desc blank and length validation.
- Carrier-code and SF-contact validation.
- capability false/unavailable behavior.
- Missing transaction id.
- All four persistence, query, and retry paths.
- UPLOADED ordinary retry rejection.
- Concurrent retry single-call behavior.
- Empty/malformed/nonzero WeChat response safety.
- No sensitive log output.

### 14.2 Frontend Required Coverage

Add small pure TypeScript helpers so behavior can be tested without a full component runtime:

- Auth state migration and malformed storage.
- ensureSession/silentLogin single-flight.
- One refresh and one original retry.
- No repeated login across repeated profile onShow.
- Product quantity bounds and direct-buy query.
- Address selection.
- Order status-group mapping and page append dedupe.
- Shipment labels.
- Admin four-mode field visibility and validation.

Admin uses its existing tsx runtime for node:test-style pure-module tests. The mini program adds a lightweight tsx test runtime as a development-only dependency. Typecheck and production build remain mandatory gates.

### 14.3 Full Automated Verification

    cd backend/shop-server
    ./mvnw test

    cd admin
    pnpm typecheck
    CI=true pnpm build

    cd miniprogram
    pnpm typecheck

    cd /Users/muybaby/Project/Production/Shop
    git diff --check
    git status --short --ignored

### 14.4 Real Smoke Versus Mock

Mock/provider automation proves:

- Exact four-mode payload shape.
- Validation.
- Persistence.
- Retry state.
- Failure handling.

It does not prove that WeChat accepted a real upload.

Real local smoke is reported separately:

- Real mini program session restore.
- Real authorized masked phone restore when account capability allows it.
- Direct buy without cart mutation.
- CART and DIRECT order creation.
- Address snapshot.
- Order center navigation and actions.
- Four admin logistics submissions.
- Real upload response if the account is capable.

If filing, certification, quota, or shipping-management capability is unavailable:

- The failure is recorded and shown.
- Local commerce remains usable.
- No UPLOADED status is fabricated.
- Final reporting names the external limitation.

## 15. Logical Implementation Slices

The implementation plan will decompose the work into sequential, separately reviewed commits:

1. V10 schema and shared contracts.
2. Backend session rotation, profile, me, and logout.
3. Mini program versioned session and profile recovery.
4. Backend address book.
5. Backend shared CART/DIRECT checkout and address snapshots.
6. Mini program quantity, direct buy, address, and checkout.
7. Backend order-center paging, detail, after-sale list, and receipt confirmation.
8. Mini program order center and after-sale pages.
9. WeChat capability, carrier directory, and exact payload gateway.
10. Four-mode shipment persistence and retry state machine.
11. Admin four-mode shipment UI.
12. Payment timeout scheduler.
13. Documentation, automated verification, and real-smoke handoff.

Tasks that touch V10, shared API types, AppOrderService, the request layer, or the same page run sequentially. Implementation subagents are never dispatched in parallel against those files.

## 16. Acceptance Criteria

This phase is accepted when:

- Reopening Profile repeatedly does not repeatedly call /app/auth/login.
- Expired access tokens recover without a persistent 401 loop.
- Bound users restore phoneNumberMasked after restart.
- Phone collection occurs only after a user getPhoneNumber click.
- Direct buy uses the selected SKU/quantity and leaves cart rows unchanged.
- CART and DIRECT both use the same pricing, coupon, inventory, and snapshot path.
- Submit requires an owned address and stores a receiver snapshot; only CREATED/PAYING may replace it, and payment makes it immutable.
- Profile reaches order list, status groups, after-sales, and addresses.
- List paging, loading, empty, error, retry, and pull-to-refresh work.
- Detail remains visible if after-sale loading fails.
- Detail shows payment, address, shipment, after-sale, and key times.
- Users can confirm SHIPPED orders locally.
- Admin can submit all four logistics types with correct dynamic validation.
- Express companies use WeChat delivery ids.
- All four mock HTTP payload tests match the official JSON shape.
- Non-express payloads omit express-only fields.
- UPLOADED records cannot use ordinary retry.
- Unfiled/unavailable WeChat results remain explicit and retryable only when safe.
- Payment timeout close is actually scheduled and remains idempotent.
- Backend, admin, and mini program verification commands pass on main.
- Mock results and real WeChat outcomes are reported separately.

## 17. Official References

- Phone number component:
  https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/getPhoneNumber.html
- Upload shipping information:
  https://developers.weixin.qq.com/miniprogram/dev/server/API/order_shipping/api_uploadshippinginfo.html
- Query shipping-management capability:
  https://developers.weixin.qq.com/miniprogram/dev/server/API/order_shipping/api_istrademanaged.html
- Get delivery company ids:
  https://developers.weixin.qq.com/miniprogram/dev/server/API/weixin-express/express-msg/api_get_delivery_list.html
