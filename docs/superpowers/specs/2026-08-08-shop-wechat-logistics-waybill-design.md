# Shop WeChat Logistics Query And Electronic Waybill Design

**Date:** 2026-08-08

**Status:** Approved for implementation

## 1. Goal

Complete the physical-order fulfillment loop so that an administrator can either enter an existing carrier and tracking number or generate a WeChat electronic waybill, preview and print the label, and then explicitly confirm shipment. After shipment, the mini program must always show the saved carrier and tracking number and, when WeChat accepts the real order and waybill relationship, open the official logistics query plugin and support WeChat logistics messages.

The first usable environment is WeChat's official express sandbox. The implementation must not require a monthly-settlement account, customer code, or password to run the sandbox flow. Later production activation must be a configuration change after the merchant binds a real express account in the WeChat Mini Program console, not a code rewrite.

## 2. Scope

### 2.1 Included

- One physical package per Shop order.
- Preserve the current manual shipment form and local `PAID -> SHIPPED` behavior.
- Add explicit electronic-waybill lifecycle management before local shipment.
- WeChat express sandbox with server-enforced `TEST / test_biz_id / 1 / test_service_name` values.
- Production-ready configuration fields for a later bound `delivery_id`, `biz_id`, and service type.
- One structured sender profile and default parcel dimensions/weight.
- Structured receiver snapshots on orders; new orders and address reselection populate them.
- Electronic waybill create, refresh/recovery, cancel, print-data retrieval, sandbox status simulation, and confirm-shipment operations.
- Browser label preview and print through an isolated iframe.
- WeChat logistics query registration through `trace_waybill`.
- WeChat logistics message registration through `follow_waybill` for manually entered shipments when enabled.
- Official mini-program logistics query plugin.
- A static mini-program logistics card that is independent of WeChat trace-registration success.
- Copy tracking number and graceful plugin fallback.
- Safe error states, idempotency, ambiguous-upstream recovery, RBAC, and order-archive integration.
- Automated backend/admin/mini-program tests and a separately reported preview + real-device smoke.

### 2.2 Excluded

- Multiple packages or split delivery.
- Batch label generation and batch printing.
- Thermal-printer SDK, cloud print agent, or silent printing. V1 uses the browser print dialog.
- Purchasing postage outside WeChat express APIs.
- Saving a monthly-account password.
- Automatically binding a real express account. The merchant binds it in the WeChat console first.
- Guessing province/city/district by parsing historical concatenated addresses.
- Claiming that a random tracking number has official trajectory data.
- Treating WeChat Developer Tools as proof that the logistics plugin works; the official plugin requires preview and a real device.
- Replacing the existing `upload_shipping_info` or WeChat receipt-reconciliation capability.

## 3. Verified Baseline

The live repository was inspected before this design:

- Branch: `main`.
- HEAD: `76b4fb48 fix(miniprogram): align order number display`.
- Tracked worktree: clean.
- Remote: none configured.
- Latest production Flyway migration: `V82__product_review_images.sql`; the next production migration is `V83`.
- No backend process was listening on port 8080 during the audit.
- Focused backend baseline: 85 tests, 0 failures, 0 errors.
- Mini-program baseline: `pnpm check`, 161 tests passed.
- Admin baseline: `pnpm typecheck` passed.

Confirmed current behavior:

- `POST /admin/orders/{orderId}/ship` creates the only `order_shipment` row and changes `PAID` to `SHIPPED` in one local transaction.
- The existing WeChat shipping provider covers trade-managed shipping upload, receipt query, capability query, and the message/query carrier directory. It does not cover express-business electronic waybills or trace registration.
- App order detail already returns a backend shipment DTO, but the mini program types it as `unknown` and does not render it.
- `shop_order` stores only a concatenated receiver address. `user_address` has the structured address that electronic-waybill APIs require.
- The existing carrier directory is not the same contract as the express-business account/service directory.
- Local ignored development/production environment files currently enable the existing `upload_shipping_info` flow; that remains independent from this phase.

## 4. Official Capability Boundaries

Four WeChat capabilities remain separate facts:

1. `upload_shipping_info` reports shipment for a WeChat payment order. This capability already exists in Shop.
2. `trace_waybill` registers a real payment order and waybill for the logistics query component and returns `waybill_token`.
3. `follow_waybill` registers a real payment order and waybill for logistics messages and also returns `waybill_token`.
4. `/cgi-bin/express/business/order/*` creates, queries, cancels, and tests electronic waybills.

Electronic-waybill sandbox support does not imply that a fake tracking number is accepted by `trace_waybill`, `follow_waybill`, or `upload_shipping_info`. A random number is useful only for verifying the local static card and copy action. Official tracking requires a real recognized waybill tied to the actual buyer and WeChat transaction.

The official sandbox has these constraints:

- `delivery_id=TEST`
- `biz_id=test_biz_id`
- `service_type=1`
- `service_name=test_service_name`
- no more than 10 create calls per day
- the order OpenID must belong to a mini-program administrator, operator, or developer

Official source inputs:

- [Express business introduction](https://developers.weixin.qq.com/miniprogram/dev/platform-capabilities/industry/express/business/introduction.html)
- [Logistics query plugin](https://developers.weixin.qq.com/miniprogram/dev/platform-capabilities/industry/express/business/express_search.html)
- [Logistics messages](https://developers.weixin.qq.com/miniprogram/dev/platform-capabilities/industry/express/business/express_open_msg.html)
- [Express sandbox](https://developers.weixin.qq.com/miniprogram/dev/platform-capabilities/industry/express/business/express_sanbox.html)
- [Create electronic waybill](https://developers.weixin.qq.com/miniprogram/dev/server/API/express/express-by-business/api_addorder)
- [Get electronic waybill](https://developers.weixin.qq.com/miniprogram/dev/server/API/express/express-by-business/api_getorder)
- [Cancel electronic waybill](https://developers.weixin.qq.com/miniprogram/dev/server/API/express/express-by-business/api_cancelorder)
- [Simulate sandbox status](https://developers.weixin.qq.com/miniprogram/dev/server/API/express/express-by-business/api_testupdateorder)
- [Register logistics query](https://developers.weixin.qq.com/miniprogram/dev/server/API/weixin-express/express-search/api_trace_waybill)
- [Register logistics messages](https://developers.weixin.qq.com/miniprogram/dev/server/API/weixin-express/express-msg/api_follow_waybill)

## 5. Core Architecture Decisions

### 5.1 Electronic Waybill Is A Pre-Shipment Resource

An electronic waybill is not a local shipment. Creating one must leave the Shop order in `PAID` and must not create `order_shipment`.

The two paths converge only at explicit shipment confirmation:

```text
manual carrier + tracking ────────────────┐
                                          ├─> order_shipment -> SHIPPED
electronic waybill -> preview -> print ──┘
```

The manual path continues to use `/admin/orders/{orderId}/ship`. The electronic path uses a trusted server-side waybill record and never accepts a carrier or tracking number again at confirm time.

### 5.2 Keep WeChat Providers Focused

Do not add express-business and trace methods to the current `WechatShippingProvider`.

Add two ports:

```text
WechatElectronicWaybillProvider
  add(request)
  get(request)
  cancel(request)
  testUpdate(request)

WechatWaybillRegistrationProvider
  trace(request)
  follow(request)
```

Both real implementations reuse `WechatAccessTokenProvider`. Test-profile implementations are deterministic and never call WeChat. `SANDBOX` is a real upstream mode with forced test values; it is not the same as the current mini-program mock mode.

Provider results are typed as `SUCCESS`, `REJECTED`, `UNKNOWN`, or `UNAVAILABLE`.

- `REJECTED`: WeChat returned a structurally valid, definitive rejection.
- `UNKNOWN`: timeout, transport failure, malformed response, missing identity fields, or response/request identity mismatch.
- `UNAVAILABLE`: local configuration or access-token capability is unavailable before a meaningful upstream operation.

No ambiguous create/cancel request may be blindly retried.

### 5.3 Upstream Calls Stay Outside Database Transactions

Transactions only lock rows, capture immutable snapshots, claim work, and perform compare-and-set transitions. Network calls occur after transaction commit. Final results are saved in a second short transaction.

This follows the existing `WechatShippingUploadStateStore` pattern and avoids holding database locks while WeChat or a carrier is slow.

### 5.4 Static Logistics Is Local Truth

Once local shipment succeeds, carrier, tracking number, and shipped time are visible in the mini program even if:

- WeChat shipping upload fails,
- trace/message registration fails,
- the token is unavailable,
- the official plugin cannot load, or
- the carrier has not produced its first trajectory node.

The official plugin is an enhancement, not the source of local shipment truth.

## 6. Data Model

### 6.1 Structured Receiver Snapshot

Migration `V83__wechat_logistics_waybill.sql` adds these non-null, empty-default columns to `shop_order`:

- `receiver_province`
- `receiver_city`
- `receiver_district`
- `receiver_detail_address`
- `receiver_location_name`
- `receiver_doorplate`

`OwnedAddress` carries both the existing formatted value and every structured field. Order checkout and the existing address-reselection path write all fields together.

Historical orders retain empty structured fields. They may still use manual shipment. Electronic-waybill context returns a blocker until the buyer reselects an address while the order is still editable. No string parsing or geographical guessing is allowed.

An active waybill attempt prevents address reselection, because the recipient snapshot has already been sent upstream.

### 6.2 WeChat Express Setting

Create singleton table `wechat_express_setting` with:

- `id=1`
- `mode`: `DISABLED | SANDBOX | PRODUCTION`
- `message_enabled` for manual-shipment `follow_waybill`
- sender name, mobile, company, province, city, district, and detailed address
- production delivery id/name, biz id, service type/name
- default parcel weight, length, width, and height
- optimistic-lock `revision`
- `updated_by`, `created_at`, `updated_at`

The row is seeded as `DISABLED`. An administrator explicitly switches to `SANDBOX` after completing the sender fields. In sandbox mode the backend ignores stored production account values and forces the four official test values.

The setting stores no customer password. After a real account is bound in the WeChat console, the administrator selects `PRODUCTION` and saves its non-secret account/service identifiers.

### 6.3 Electronic Waybill Attempts

Create `order_electronic_waybill` with one row per create attempt:

- local `id`, `order_id`, `attempt_no`
- `idempotency_key`, `request_digest`
- globally unique `provider_order_id`
- `mode`
- delivery id/name, biz id, service type/name snapshots
- `status`: `CREATING | CREATED | CANCELING | CANCELED | UNKNOWN | FAILED | CONFIRMED`
- `pending_operation`: `NONE | CREATE | CANCEL | REFRESH`
- `waybill_id`
- parcel count, weight, length, width, and height
- custom remark and expected pickup Unix timestamp
- complete sender snapshot
- complete receiver snapshot
- `payment_order_id` and payer OpenID snapshot
- safe last error code/message
- upstream attempt count and last attempt time
- print request count and last print-request time
- `created_by`, `confirmed_by`
- created, updated, canceled, and confirmed timestamps

Indexes and constraints include:

- unique `provider_order_id`
- unique `(order_id, attempt_no)`
- unique `(order_id, idempotency_key)`
- status and order lookup indexes

The label HTML is never stored. Raw `waybill_data`, OpenID, address, and upstream responses are never logged.

Only one active attempt may exist for an order. The service enforces this while holding the `shop_order` row lock. `FAILED` and `CANCELED` attempts remain as audit history and permit a new attempt with a new idempotency key.

### 6.4 Shipment Link

Add to `order_shipment`:

- `shipment_source`: `MANUAL | WECHAT_WAYBILL`, defaulting historical rows to `MANUAL`
- nullable, unique `electronic_waybill_id`

The existing one-shipment-per-order constraint remains.

### 6.5 Waybill Registration

Create `shipment_waybill_registration`, one row per shipment:

- `shipment_id`, unique
- `registration_kind`: `TRACE | FOLLOW`
- `status`: `PENDING | REGISTERING | REGISTERED | FAILED | UNKNOWN | UNAVAILABLE | SKIPPED`
- server-side `waybill_token`
- safe error code/message
- claim token and claim time
- attempt count, last attempt time, registered time, created time, updated time

The token is returned only from an owner-authenticated, no-store app endpoint. It is not placed in ordinary order detail, page data, client storage, logs, analytics, or toast messages.

Registration policy:

- `WECHAT_WAYBILL` shipment: use query-only `trace_waybill`; `addOrder(add_source=0)` already owns the electronic-waybill notification behavior.
- `MANUAL` shipment with `message_enabled=true`: use `follow_waybill`, which enables official logistics messages and yields the plugin token.
- `MANUAL` shipment with messages disabled: use `trace_waybill`.

`payment_order.payer_openid` and `payment_order.transaction_id` are the historical identity source. Registration must not depend on a mutable or deleted `app_user` row.

## 7. State And Concurrency

### 7.1 Create

```text
none/FAILED/CANCELED
  -> CREATING
  -> CREATED | FAILED | UNKNOWN
```

- The request includes a UUID idempotency key.
- Same key + same request digest returns the existing attempt.
- Same key + different digest returns an idempotency conflict.
- Double-click/concurrent create claims only one upstream add call.
- `CREATING` or `UNKNOWN` is recovered with `getOrder(provider_order_id)` before any new add.
- A successful response must match the requested provider order id and delivery id and contain a non-empty waybill id.

### 7.2 Cancel

```text
CREATED -> CANCELING -> CANCELED | CREATED | UNKNOWN
```

- A definitive rejection restores `CREATED` and saves a safe error.
- An ambiguous result becomes `UNKNOWN` with `pending_operation=CANCEL`.
- Refresh queries the existing provider order; it never creates a replacement.
- `CONFIRMED` cannot be canceled from Shop because it already represents a local shipment.

### 7.3 Confirm Shipment

Confirmation locks rows in a fixed `shop_order -> order_electronic_waybill` order and verifies:

- the order is still `PAID`,
- no shipment exists,
- no active after-sale blocks shipment,
- the waybill belongs to the order and is `CREATED`,
- the carrier and waybill id are complete.

The same transaction creates `order_shipment`, links the waybill, changes the waybill to `CONFIRMED`, changes the order to `SHIPPED`, and records the existing order-status log.

After commit, the existing `upload_shipping_info` coordinator and the new registration coordinator run independently. Either can fail without rolling back the local shipment.

### 7.4 Conflicting Operations

An attempt in `CREATING`, `CREATED`, `CANCELING`, or `UNKNOWN` blocks:

- manual shipment,
- creation of a second active waybill, and
- receiver-address reselection.

The administrator must cancel or recover the attempt first. This prevents orphaned labels and recipient mismatch.

## 8. Backend API

All JSON endpoints keep the existing `{ code, msg, data }` envelope.

### 8.1 Configuration

- `GET /admin/logistics/wechat-express/config`
- `PUT /admin/logistics/wechat-express/config`

The update request contains `revision`; stale updates fail instead of silently overwriting another administrator's changes. Responses show sandbox effective values and mask the production biz id where it is used only for display.

### 8.2 Electronic Waybills

- `GET /admin/orders/{orderId}/waybills/context`
- `GET /admin/orders/{orderId}/waybills`
- `POST /admin/orders/{orderId}/waybills`
- `POST /admin/orders/{orderId}/waybills/{waybillRecordId}/refresh`
- `POST /admin/orders/{orderId}/waybills/{waybillRecordId}/cancel`
- `GET /admin/orders/{orderId}/waybills/{waybillRecordId}/print?printType=0|1`
- `POST /admin/orders/{orderId}/waybills/{waybillRecordId}/sandbox-events`
- `POST /admin/orders/{orderId}/waybills/{waybillRecordId}/confirm-shipment`

Create input contains only:

- `idempotencyKey`
- parcel count/weight/length/width/height
- optional remark
- optional expected pickup Unix timestamp

Account identifiers, sender, receiver, OpenID, order items, images, WeChat transaction, and mini-program path are assembled server-side from trusted snapshots/configuration.

The context endpoint returns effective mode, blockers, sender/receiver display data, default parcel values, the current active/latest attempt, and server-provided sandbox actions.

### 8.3 Registration Retry And App Token

- `POST /admin/orders/{orderId}/shipping/retry-waybill-registration`
- `POST /app/orders/{orderId}/logistics/waybill-token`

The app endpoint verifies current-user ownership, verifies an entity-express shipment, returns an existing token if registered, or performs one safe registration/retry. Its response is `{ waybillToken }` with `Cache-Control: no-store`.

The order detail returns only safe registration status/support metadata alongside the existing shipment fields; it never returns the token.

## 9. Printing Contract

Official `getOrder` returns Base64 `print_html`. The print endpoint:

- validates record ownership and print permission,
- queries the same provider order and never calls add,
- verifies provider-order, delivery, and waybill identity,
- applies encoded and decoded size limits,
- strictly validates Base64,
- returns `text/html; charset=UTF-8`,
- sets `Cache-Control: no-store`,
- increments only a print-request counter after a valid response,
- never stores or logs the HTML.

The admin client creates a short-lived Blob URL and loads it in an iframe sandboxed with same-origin/modals only; scripts, forms, popups, and top navigation are not allowed. Preview and print reuse the same label. The Blob URL is revoked on order switch, dialog close, and component unmount.

The UI reports “已调起打印”, never “物理打印成功”.

## 10. Admin Experience

### 10.1 Configuration Page

Add `订单管理 / 电子面单配置` with:

- mode selector: disabled, WeChat sandbox, production
- explanation of sandbox OpenID and daily quota limits
- message-enable switch for manual shipments
- structured sender form
- production delivery/biz/service form, disabled while sandbox is selected
- default parcel form
- optimistic-lock save feedback

The page never asks for or stores the express-account password.

### 10.2 Shipment Dialog

Keep the current “发货” entry and add two modes:

- 手动填写运单
- 生成电子面单

The manual mode preserves current fields and validation.

The electronic mode is a child component, not additional inline logic in the existing large order-list page. Its states are:

```text
CLOSED -> OPENING
OPENING -> EDITING | READY
EDITING -> CREATING -> READY | EDITING
READY -> PREVIEWING | PRINTING | CANCELING | CONFIRMING
```

`READY` shows environment, carrier, service, waybill number, creation time, print request count, and actions for preview, print/reprint, cancel, confirm shipment, refresh, and sandbox simulation.

An active label locks switching to manual mode until cancellation. Order detail shows a pre-shipment label summary even before `shipment` exists.

### 10.3 Sandbox Actions

The backend returns the allowed actions and validates them again:

- `100001` 揽件成功
- `200001` 更新运输轨迹
- `300002` 开始派送
- `300003` 签收成功

The endpoint works only for a sandbox record with effective `TEST / test_biz_id` values and the dedicated test permission.

## 11. Mini-Program Experience

Declare the official plugin in `app.json`:

```json
{
  "plugins": {
    "logisticsPlugin": {
      "version": "2.1.5",
      "provider": "wx9ad912bf20548d92"
    }
  }
}
```

Order detail renders a logistics card after the receiver card when a shipment exists. For entity express it shows:

- carrier name
- full tracking number with copy action
- shipped time
- “查看物流” action when carrier code and tracking number are complete

The view-model builder trims and normalizes shipment data once. Long numbers may ellipsize visually, but copy uses the complete value.

Clicking “查看物流”:

1. sets a dedicated anti-double-click loading state,
2. requests the token endpoint,
3. keeps the token in a function-local variable only,
4. loads `requirePlugin('logisticsPlugin')` behind a typed wrapper,
5. calls `openWaybillTracking({ waybillToken })`,
6. clears loading in all outcomes.

Missing token, request failure, missing plugin/method, or synchronous plugin exception shows:

> 当前物流轨迹暂不可用，请稍后再试

The static logistics card and copy action remain available. Successfully invoking the plugin is not described as proof that trajectory data loaded.

## 12. Authorization

Add permissions under order management:

- `logistics:express:config:read`
- `logistics:express:config:write`
- `order:waybill:manage`
- `order:waybill:print`
- `order:waybill:test`
- `order:shipping:registration:retry`

Confirm-shipment requires both `order:waybill:manage` and existing `order:ship`. Existing manual shipment keeps `order:ship`. The backend enforces every permission; frontend `v-auth` is presentation only.

## 13. Failure, Privacy, And Observability

- Never log access tokens, waybill tokens, OpenIDs, phones, sender/receiver addresses, transaction ids, print HTML, or raw upstream bodies.
- Reuse `WechatShippingErrorSanitizer` for safe numeric/string codes and bounded messages.
- Record provider mode, operation, local record id, safe outcome, safe error code, latency, and attempt count.
- `UNKNOWN` is visible and recoverable; it is never silently converted to success or failure.
- No mock/disabled result is reported as a successful real WeChat operation.
- Official quota errors remain safe upstream rejections; the UI explains the sandbox daily limit.
- App token and print responses use `no-store`.
- Goods images sent to trace/follow must be non-empty public HTTPS URLs; otherwise registration fails safely while static shipment remains visible.

## 14. Archive And Retention

`OrderAggregateCleanupService` must include the two new order-scoped tables in:

- cleanup eligibility checks,
- private archive sections and counts,
- deletion order,
- failure/retry tests.

Waybill attempts and registration states are fulfillment audit evidence. They must not be orphaned or silently omitted when an old order aggregate is archived and purged.

## 15. Acceptance Criteria

1. A sandbox-configured admin can create a TEST label without a real monthly account; order remains `PAID`.
2. Preview/reprint retrieves the same upstream order and never creates another label.
3. Confirm shipment creates exactly one shipment and changes the order to `SHIPPED`.
4. Existing manual shipment remains available when no active label exists.
5. An active label blocks manual shipment and receiver changes until resolved/canceled.
6. The mini program shows saved logistics information even if every WeChat trace/message call fails.
7. A valid token opens the official plugin through the typed wrapper; all failures degrade to a toast.
8. Manual shipment can register messages through `follow_waybill`; electronic shipment uses query-only trace registration to avoid duplicate message registration.
9. Fake/random tracking numbers are explicitly treated as static-card-only test data.
10. Switching to production later requires saved identifiers plus prior console binding, but no source change or stored password.
11. Automated checks pass; preview + real-device results are reported separately and never fabricated.
