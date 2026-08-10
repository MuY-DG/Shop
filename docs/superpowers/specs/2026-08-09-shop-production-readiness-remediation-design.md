# Shop Production Readiness Remediation Design

**Date:** 2026-08-09

**Status:** Approved for implementation

## 1. Goal

Close the verified launch-blocking gaps without changing the settled modular-monolith architecture or broadening the work into a visual redesign:

1. expire abandoned `CREATED` orders and release their stock/coupons;
2. restock paid-but-unshipped orders after a confirmed full refund;
3. make WeChat shipment upload recoverable and give non-uploaded shipments a truthful local receipt path;
4. separate Mini Program runtime environments, repair the customer-service order link, and move login behind explicit privacy consent;
5. add versioned merchant/legal publication, food disclosure, and account-rights foundations that accept real merchant data later without fabricating it.

The implementation must continue to separate order, payment, after-sale, inventory, and shipment states. Network calls stay outside database transactions. Existing payment, refund, waybill, tracking, storage, and customer-service recovery mechanisms are preserved.

## 2. Non-Negotiable Product Constraint

The current profile member card is intentionally retained because the merchant wants the visual treatment.

The implementation must not remove, rename, hide, or restyle:

- the V avatar frame;
- the crown;
- the visible `金牌会员` text;
- the current logged-in display condition;
- the member-card layout and assets.

Account-rights and compliance entry points belong in the account profile/settings area or public footer, never inside that member card. Automated source-contract tests must protect this constraint.

## 3. Verified Baseline

- Branch: `main`.
- HEAD: `2993a9be feat(logistics): add WeChat shipment tracking views`.
- Tracked worktree: clean before design work.
- Remote/upstream: none configured.
- Latest Flyway migration: `V84__shipment_tracking_data.sql`.
- Mini Program: `pnpm check`, 173/173 tests passed.
- Admin: typecheck and build passed; lint had 7 errors; 137/138 manually discovered tests passed, with one timezone-dependent expectation.
- Backend: 1061 tests started; 1052 passed and 9 Testcontainers tests errored because Docker was unavailable. No business assertion failed, but the full gate was not green.
- No local backend, MySQL, Redis, or Docker runtime was available during the audit.
- The repository contains only the development API hostname `pay-dev.muybaby6.icu`; no confirmed production API/admin hostname is available.
- No confirmed legal-entity, business-license, food-license/filing, or product-label data exists in the repository.

## 4. Scope

### 4.1 Included

- order-level payment deadline fixed at submit time;
- leased, idempotent timeout closure for `CREATED` orders;
- fixed SKU lock ordering on release/restock paths;
- refund-time inventory disposition snapshot and idempotent restock;
- persistent WeChat upload claims, scheduled delivery, bounded retry, and `UNKNOWN` reconciliation;
- truthful local receipt fallback for shipments not successfully uploaded to WeChat;
- `develop/trial/release` Mini Program configuration resolution with release fail-closed behavior;
- customer-service order-card route repair;
- explicit privacy policy version and consent evidence before login/upsert;
- immutable merchant-publication and legal-document revisions;
- food disclosure and publish gate for products;
- asynchronous app-user rights requests, cancellation blocking rules, token invalidation, and minimal anonymization;
- focused backend/admin/Mini Program tests and documentation.

### 4.2 Excluded

- removing or redesigning the `金牌会员` presentation;
- partial refunds, exchange, return logistics, or returned-goods inspection;
- multi-package/split shipment, batch shipment, or warehouse/ERP implementation;
- general message queue or a repository-wide outbox framework;
- microservices conversion;
- inventing a production hostname, legal entity, license, policy text, or food-label fact;
- claiming production WeChat, printing, COS, WebSocket, map, or true-device verification from automated tests.

## 5. Order Payment Deadline

Migration `V85__created_order_payment_deadline.sql` adds nullable rollout-compatible fields to `shop_order`:

- `payment_expires_at`;
- `created_timeout_claim_token`;
- `created_timeout_claimed_at`;
- `created_timeout_attempts`;
- index `(status, payment_expires_at, id)`.

All newly submitted orders receive one fixed deadline from a shared `OrderPaymentDeadlinePolicy`. Payment initiation inherits that deadline instead of extending it from the time the user taps Pay.

The timeout worker uses a short claim transaction, then a second transaction that locks the order and revalidates status, deadline, token, and payment-order absence before calling the existing close service. Closing remains `CREATED -> CLOSED` and uses the existing stock/coupon release logic.

Global stock lock order is:

```text
shop_order
-> stock_lock ordered by sku_id,id
-> product_sku ordered by id
-> user_coupon
-> order_status_log
```

Payment initiation and timeout close both lock `shop_order`, so only one can win. Existing rows with `payment_expires_at IS NULL` are not blindly closed by Flyway. A documented, read-only reconciliation report must precede any production backfill.

## 6. Refund Inventory Restock

Migration `V86__refund_inventory_restock.sql` adds:

- `refund_order.restock_required` and `restocked_at`;
- `stock_lock.restock_refund_order_id` and `restocked_at`;
- `stock_log.refund_order_id`;
- the indexes/uniqueness needed to prevent a second log/restock for one refund and SKU.

Enums add `StockLockStatus.RESTOCKED` and `StockChangeType.REFUND_RESTOCK`.

At refund approval, `restock_required=true` only when the locked order was `PAID`, has no shipment, and has no `shipped_at`. A retry refund inherits the prior disposition; it does not recompute after the order has moved to `REFUNDING`.

On a verified provider `SUCCESS`, the existing `RefundFinalizationService` calls one inventory service inside the same transaction. It verifies the complete order-item/confirmed-lock mapping, aggregates by SKU, locks SKUs in ascending order, updates stock, transitions locks to `RESTOCKED`, writes logs, and sets `refund_order.restocked_at`.

Shipped/completed orders never auto-restock. Historical successful refunds are reported for manual reconciliation and are not automatically restocked because the merchant may already have adjusted inventory.

## 7. Reliable WeChat Shipment Delivery

Migration `V87__wechat_shipping_reliable_delivery.sql` extends `order_shipment` with:

- upload state `PENDING`;
- `wechat_upload_claim_token` and `wechat_upload_claimed_at`;
- `wechat_upload_next_action_at`;
- total attempt and not-uploaded observation counters;
- last reconciliation time;
- index `(wechat_upload_status, wechat_upload_next_action_at, id)`.

New shipments use `PENDING` when upload is enabled and `SKIPPED` only when upload is disabled/not applicable. The existing immediate call remains an optimization; scheduled delivery is the correctness mechanism.

All terminal writes require `shipment id + UPLOADING + claim token`. Deterministic provider rejection becomes `FAILED` and needs an operator. Temporary unavailability uses bounded backoff. `UNKNOWN` is never uploaded again blindly.

The existing WeChat `get_order` provider path is expanded to retain a safe shipping summary. `UNKNOWN` reconciliation can:

- mark `UPLOADED` only when provider order identity and shipping facts match local truth;
- remain `UNKNOWN` on ambiguity/mismatch;
- move to `PENDING` only after the configured number of spaced, definitive not-uploaded observations.

Receipt behavior becomes:

- `REAL + UPLOADED`: WeChat remains authoritative;
- `UPLOADING/UNKNOWN`: block local completion while the result is ambiguous;
- `PENDING/SKIPPED/FAILED/UNAVAILABLE` or mock/non-real: allow audited local confirmation without pretending it came from WeChat.

The scheduler and receipt transaction use the same `shop_order -> order_shipment` lock order.

## 8. Mini Program Runtime And Navigation

Runtime configuration resolves from `develop`, `trial`, or `release` explicitly.

- `develop` uses the existing development hostname.
- until a real staging hostname exists, `trial` explicitly reuses the development API while
  keeping a separate local-session namespace; it is not production proof.
- `release` requires its own explicit value.
- release rejects missing/placeholder values, non-HTTPS, localhost, loopback, and hostnames containing `pay-dev`.
- release never falls back to development.

Because no production hostname is known, the checked-in release value remains intentionally unconfigured and fails closed until the merchant supplies it.

Customer-service order cards reuse the existing `buildOrderDetailUrl`, ensuring the route contains `order_id`.

## 9. Privacy Consent And Legal Revisions

Migration `V88__merchant_compliance_and_legal_documents.sql` introduces:

- immutable merchant-publication revisions;
- immutable legal-document revisions for privacy policy, user agreement, and after-sale policy;
- app-user document-consent evidence;
- compliance RBAC/menu records.

No fake publication is seeded. Draft validation prevents publishing empty, placeholder, expired, or incomplete merchant qualifications.

Login order is:

```text
load current published privacy policy
-> user opens/reads as desired
-> user checks consent
-> user taps login
-> wx.login
-> backend verifies the current policy version
-> user upsert and consent evidence commit atomically
-> session is issued
```

The consent record stores the document revision/version, content digest, server acceptance time, channel, and Mini Program environment. It does not add unnecessary raw-IP collection.

Public endpoints expose only the current published merchant qualification and legal documents. Admin endpoints create drafts, validate, preview, publish, and list history. Published revisions are never edited in place.

## 10. Food Product Disclosure

Migration `V89__product_food_disclosure.sql` adds:

- `product_spu.compliance_type`: `UNCLASSIFIED | FOOD | NON_FOOD`;
- structured food disclosure facts;
- original label/nutrition assets;
- SKU-level net content text.

Historical products become `UNCLASSIFIED`, not silently `NON_FOOD`.

Publish/enable rules:

- `UNCLASSIFIED` cannot be newly published;
- `FOOD` requires the configured mandatory facts, at least one original label asset, net content on every enabled SKU, and a current published food qualification;
- production/batch dates that vary by physical package use reviewed truthful wording such as `见包装喷码`, not invented fixed dates.

The public product DTO and Mini Program detail page show food facts and label images as a dedicated section. Free-form rich text remains supplemental, not the compliance source of truth.

## 11. Account Rights

Migration `V90__app_user_rights_requests.sql` introduces versioned requests for:

- account cancellation;
- personal-information deletion;
- access/copy;
- correction.

Users can submit, inspect, and withdraw an active request. Admin users can review, reject, approve, and complete it with required reasons and retained-data explanation.

Account cancellation requires a fresh WeChat code bound to the current user. Completion is blocked while orders, payments, refunds, or after-sales remain active. Completion:

- changes the user to `CANCELLED`;
- increments `auth_version` and invalidates old access/refresh sessions;
- replaces OpenID with a non-reversible cancellation placeholder;
- clears optional phone, unionid, nickname, and avatar data;
- retains only records required for transaction, refund, audit, and statutory retention, with the categories recorded on the request.

Token authentication validates enabled status and auth version for app users; a database-only status change must not leave a seven-day usable token.

## 12. Real Data And External Release Boundary

The code can be implemented and tested without production merchant data, but release remains blocked until the merchant supplies and verifies:

- production Mini Program/API/admin/callback hostnames, HTTPS, gateway routes, and WeChat legal domains;
- legal entity, unified social credit code, address, business license, applicable food qualification/filing, validity and images;
- complaint/customer-service contact;
- reviewed privacy, user, and after-sale policies;
- every product/SKU's real packaging label and food facts;
- account-rights owner, handling SLA, retention rules, and unresolved-business policy.

Automated validation must report these as missing prerequisites, not silently substitute development or example data.

## 13. Verification Boundary

Each slice uses focused tests, diff review, fix/re-review, then broader gates. Docker/MySQL concurrency checks are required when Docker is available; inability to run them remains an explicit blocker, not a passing result.

Real WeChat login/payment/shipping, official plugin, electronic label printing, COS, WebSocket, map/address, package upload, and true-device behavior remain separate smoke checks.
