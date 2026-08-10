# Shop Production Readiness Remediation Implementation Plan

**Date:** 2026-08-09

**Goal:** Execute the approved launch-readiness remediation in narrow, test-backed slices while preserving the current modular monolith and the visible `金牌会员` member card.

**Design:** `docs/superpowers/specs/2026-08-09-shop-production-readiness-remediation-design.md`

## Global Constraints

- Work from the live `main` checkout and preserve unrelated changes.
- Do not remove, rename, hide, or restyle the profile V frame, crown, `金牌会员` text, member-card assets, or logged-in display condition.
- Do not fabricate a production hostname, merchant identity, license, policy, or food fact.
- Use migrations `V85` through `V90` in the order defined by the design.
- Keep order, payment, after-sale, inventory, and shipment states independent.
- Keep provider calls outside database transactions.
- Use fixed lock order, leases/claim tokens, CAS transitions, and idempotent side effects.
- Write or extend tests with each behavior slice; run focused tests, review the diff, fix findings, and re-run before broader gates.
- Do not commit unless the user explicitly asks.
- Report automated/mock validation separately from Docker/MySQL, production, Developer Tools, and true-device smoke.

## Task 1: Add Order-Level Payment Deadline

### Files

- Create `backend/shop-server/src/main/resources/db/migration/V85__created_order_payment_deadline.sql`
- Add order-deadline properties/policy, timeout service, and scheduler
- Modify `AppOrderService`, `PaymentInitiationService`, and `OrderCloseService`
- Extend schema, service, scheduler, payment, and concurrency tests
- Update example config and operational docs

### Steps

1. Add failing schema tests for deadline/claim fields and index.
2. Add the rollout-compatible V85 migration without bulk-closing old orders.
3. Write one fixed deadline during order submission.
4. Make payment initiation inherit and validate that deadline.
5. Implement claimed `CREATED` timeout scanning and reuse `OrderCloseService`.
6. Sort all release-side SKU locks by `sku_id,id`.
7. Cover duplicate scans, stale claims, payment/close races, coupon release, and stock conservation.
8. Document production reconciliation/backfill and change the recommended default to 15 minutes while retaining an explicit environment override.

### Focused Verification

```bash
cd backend/shop-server
./mvnw -Dtest='OrderSchemaTest,CreatedOrderTimeoutCloseServiceTest,CreatedOrderTimeoutCloseSchedulerTest,PaymentInitiationServiceTest,OrderCloseServiceTest' test
```

## Task 2: Restock Paid-Unshipped Refunds

### Files

- Create `backend/shop-server/src/main/resources/db/migration/V86__refund_inventory_restock.sql`
- Add `RefundInventoryRestockService`
- Modify stock enums, `AdminAfterSaleService`, and `RefundFinalizationService`
- Extend refund callback/recovery/schema/concurrency tests

### Steps

1. Add failing schema and refund-success tests.
2. Persist an immutable restock disposition when the refund is prepared.
3. Preserve it when a failed/closed refund is retried with a new refund order.
4. Verify complete confirmed-lock mappings and lock SKUs in ascending order.
5. Restock, transition locks, write unique stock logs, and finalize refund states in one transaction.
6. Prove shipped/completed refunds do not restock and duplicate success paths cannot add stock twice.
7. Add a read-only historical reconciliation query/runbook; never auto-restock historical successes.

### Focused Verification

```bash
cd backend/shop-server
./mvnw -Dtest='AfterSaleSchemaTest,RefundCallbackServiceTest,RefundRecoveryServiceTest,RefundFinalizationServiceTest' test
```

## Task 3: Make WeChat Shipment Upload Recoverable

### Files

- Create `backend/shop-server/src/main/resources/db/migration/V87__wechat_shipping_reliable_delivery.sql`
- Modify shipment status/state store/coordinator/provider result parsing
- Add delivery/reconciliation properties, service, and scheduler
- Modify local shipment creation and admin retry/reconcile behavior
- Extend receipt, provider, scheduler, workflow, and concurrency tests

### Steps

1. Add failing schema/state-machine tests for PENDING, claim tokens, scheduling, and reconciliation counters.
2. Write PENDING at local shipment commit when upload is enabled.
3. Claim due work using order-then-shipment locking and token-fenced terminal writes.
4. Keep immediate delivery as an optimization and add scheduled correctness recovery.
5. Add bounded UNAVAILABLE retry; deterministic FAILED remains operator-owned.
6. Expand `get_order` parsing to retain a safe shipping summary and make existing receipt logic consume it.
7. Reconcile UNKNOWN without blind re-upload; require matching identity/facts or spaced definitive not-uploaded observations.
8. Preserve admin manual retry and add an explicitly named reconcile action.
9. Add provider HTTP connect/read/response-size bounds and sanitized logging tests.

### Focused Verification

```bash
cd backend/shop-server
./mvnw -Dtest='ShipmentSchemaTest,WechatShippingUploadCoordinatorTest,WechatShippingUploadRecoveryTest,WechatReceiptReconciliationServiceTest,RealWechatShippingProviderTest' test
```

## Task 4: Add Truthful Local Receipt Fallback

### Files

- Modify `AppOrderService` receipt confirmation policy
- Reuse shipment upload status and provider mode
- Extend app order and MySQL workflow tests

### Steps

1. Add a complete status matrix test.
2. Keep `REAL + UPLOADED` under WeChat receipt authority.
3. Block UPLOADING/UNKNOWN while the provider result is ambiguous.
4. Permit audited local confirmation for PENDING/SKIPPED/FAILED/UNAVAILABLE and non-real modes.
5. Use the same order-then-shipment lock order as the delivery worker.
6. Preserve active-after-sale blocking and add a distinct local-fallback status log event.

## Task 5: Fix Mini Program Runtime, Route, And Consent Ordering

### Files

- Modify `miniprogram/miniprogram/config/app-config.ts` and app initialization
- Modify customer-service chat navigation
- Modify login/session request flow and backend login DTO/service
- Add environment, navigation, login, and member-card regression tests

### Steps

1. Add pure tests for develop/trial/release config resolution and release rejection rules.
2. Keep develop on the current dev endpoint; leave release explicitly unconfigured until a real hostname is supplied.
3. Reuse `buildOrderDetailUrl` for the customer-service order card.
4. Remove automatic login preparation from page load.
5. Load the current privacy document anonymously; require checkbox and click before `wx.login` or backend login.
6. Send the accepted document version/env with login and persist consent atomically with user upsert.
7. Add source-contract assertions that the existing `金牌会员` UI remains unchanged.

### Focused Verification

```bash
cd miniprogram
pnpm check
```

## Task 6: Add Merchant Publication And Legal Documents

### Files

- Create `backend/shop-server/src/main/resources/db/migration/V88__merchant_compliance_and_legal_documents.sql`
- Add backend `compliance` controllers/services/DTOs and RBAC
- Add admin API/types/pages for merchant qualification and legal-document revisions
- Add public Mini Program qualification/legal pages and footer/settings links
- Add schema, RBAC, versioning, validation, rendering, and privacy-consent tests

### Steps

1. Add failing schema/version immutability tests.
2. Implement drafts, validation, preview, publish, and immutable history.
3. Seed no fake publication and reject placeholder/incomplete/expired facts.
4. Expose only current published facts through explicit anonymous GET routes.
5. Reuse managed storage assets for qualification images.
6. Add admin publish confirmation and missing-field display.
7. Add public Mini Program entry points outside the member card.
8. Prove login rejects missing/stale policy versions and records current consent atomically.

## Task 7: Add Food Product Disclosure And Publish Gate

### Files

- Create `backend/shop-server/src/main/resources/db/migration/V89__product_food_disclosure.sql`
- Extend backend product admin/app DTOs and publish validation
- Add admin product compliance tab
- Add Mini Program food-label section
- Extend storage usage and product schema/service/UI tests

### Steps

1. Add failing schema and publish-gate tests.
2. Migrate historical products to UNCLASSIFIED.
3. Implement structured food facts, managed label assets, and SKU net content.
4. Require a valid published merchant food qualification for FOOD publication.
5. Return and render a dedicated food disclosure before free-form detail content.
6. Preserve truthful variable batch/date wording and reject fabricated fixed values.

## Task 8: Add Account Rights And Cancellation

### Files

- Create `backend/shop-server/src/main/resources/db/migration/V90__app_user_rights_requests.sql`
- Add app/admin rights-request controllers/services/DTOs and RBAC
- Extend app-user token session/auth-version checks
- Add admin request management page
- Add Mini Program account-rights page under account profile/settings
- Add schema/state/auth/anonymization/UI tests

### Steps

1. Add failing schema, active-request uniqueness, and state-machine tests.
2. Require a fresh WeChat code for cancellation identity verification.
3. Let users submit, inspect, and withdraw pending requests.
4. Let authorized admins review with mandatory reasons and retention explanation.
5. Block completion while active commerce obligations exist.
6. On completion, invalidate sessions/auth version and minimally anonymize optional identity data while retaining required records.
7. Ensure cancelled/disabled app users cannot use old access or refresh tokens.
8. Add the Mini Program entry outside the protected member-card surface.

## Task 9: Quality Gates, Review, And Documentation

### Steps

1. Run focused backend tests after each migration/slice.
2. Run Mini Program `pnpm check`.
3. Fix current admin lint and timezone-test failures, add `test` and `check` scripts, then run check/build.
4. Run `./mvnw test`; if Docker is unavailable, report the exact Testcontainers errors and rerun the non-container suite separately.
5. When Docker is available, run the new MySQL concurrency tests for timeout close, refund restock, shipment delivery, document publish, and rights-request uniqueness.
6. Run `git diff --check`, inspect tracked/untracked scope, and perform cross-surface review/fix/re-review.
7. Update `docs/dev-setup.md`, `docs/smoke-checks.md`, release checklist, and Mini Program README.
8. Record the still-missing real hostname, merchant qualification, policies, product labels, WeChat console configuration, and true-device smoke as external release prerequisites.

## Final Gates

```bash
cd backend/shop-server && ./mvnw test
cd admin && pnpm check && pnpm build
cd miniprogram && pnpm check
git diff --check
git status --short --branch
```

No automated result may be reported as production WeChat, official logistics plugin, physical printing, COS, WebSocket, map, package-upload, or true-device proof.
