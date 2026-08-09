# Shop WeChat Logistics Query And Electronic Waybill Implementation Plan

**Date:** 2026-08-08

**Goal:** Implement the approved single-package manual/electronic shipment flow, WeChat sandbox label printing, logistics registration/messages, and mini-program logistics query without weakening the existing local shipment and WeChat trade-shipping behavior.

**Design:** `docs/superpowers/specs/2026-08-08-shop-wechat-logistics-waybill-design.md`

## Global Constraints

- Work from the verified `main` worktree and preserve unrelated user changes if they appear.
- Write tests before or with each behavior slice; run the narrow tests, review the diff, fix findings, re-review, then run the broader suite.
- Do not commit unless the user explicitly asks.
- New production schema starts at `V83` and remains compatible with H2 tests and MySQL 8.4.10.
- Keep one package per order and one final `order_shipment` per order.
- Creating an electronic waybill never changes the order from `PAID`.
- Only explicit confirm-shipment creates `order_shipment` and changes `PAID -> SHIPPED`.
- WeChat network calls remain outside database transactions.
- Do not log or expose access tokens, waybill tokens, OpenIDs, phones, addresses, transaction ids, raw provider bodies, or print HTML.
- Do not store customer-account passwords or label HTML.
- Preserve all existing `upload_shipping_info`, receipt reconciliation, manual fulfillment types, after-sale gates, and response-envelope contracts.
- Random tracking numbers may verify only local display/copy; they are not expected to pass WeChat trace/message registration.
- Real-device plugin smoke is user-owned and must be reported separately from automated checks.

## Task 1: Add Schema, RBAC, And Structured Receiver Snapshots

### Files

- Create `backend/shop-server/src/main/resources/db/migration/V83__wechat_logistics_waybill.sql`
- Modify `backend/shop-server/src/main/java/org/muybaby/shopserver/user/address/service/OwnedAddress.java`
- Modify `backend/shop-server/src/main/java/org/muybaby/shopserver/user/address/service/AppAddressService.java`
- Modify `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java`
- Modify shipment schema/status DTOs as needed
- Test `backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/WechatWaybillSchemaTest.java`
- Extend existing checkout/address/shipment schema tests

### Steps

1. Add failing Flyway/H2 tests for structured order fields, setting/waybill/registration tables, constraints, indexes, shipment source/link, menu 502, and six permissions.
2. Add V83 with the exact design schema and a singleton `DISABLED` setting row.
3. Extend `OwnedAddress` and its query projection with province/city/district/detail/location/doorplate.
4. Write structured snapshots during checkout and the existing receiver-reselection flow.
5. Add tests that new/reselected addresses populate all fields and historical empty fields are not parsed.
6. Add `MANUAL` default mapping for existing shipments.
7. Run focused schema, order, and address tests.

### Verification

```bash
cd backend/shop-server
./mvnw -Dtest='WechatWaybillSchemaTest,OrderSchemaTest,ShipmentSchemaTest,AppOrderControllerTest,AppAddressControllerTest' test
```

## Task 2: Implement WeChat Express Configuration

### Files

- Create configuration DTOs and service under `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/waybill/`
- Create `AdminWechatExpressConfigController.java`
- Add safe errors to `common/error/ErrorCode.java`
- Test `AdminWechatExpressConfigControllerTest.java`

### Steps

1. Write tests for read/write permissions, validation, optimistic revision conflicts, sandbox forced values, production required fields, and password absence.
2. Implement mode and effective-config types.
3. Implement a singleton setting store with `SELECT ... FOR UPDATE` and revision compare-and-set.
4. Validate structured sender data and parcel defaults.
5. Return both stored production identifiers and server-derived effective sandbox values without exposing secret material.
6. Run focused controller/service tests and review response privacy.

## Task 3: Implement Electronic-Waybill Provider Ports

### Files

- Create `WechatElectronicWaybillProvider` and request/result records
- Create real and mock/test provider implementations
- Reuse `WechatAccessTokenProvider`
- Test `WechatElectronicWaybillProviderTest.java`

### Steps

1. Write exact-payload tests for add/get/cancel/testUpdate.
2. Cover sender/receiver, cargo, shop detail, insured=off, service, path, OpenID, remark, and optional expected pickup time.
3. Force official TEST values in sandbox at the service boundary and again validate them in the provider request.
4. Strictly parse `errcode`, provider order id, delivery id, waybill id, order status, `print_html`, and identity mismatches.
5. Map deterministic errors to `REJECTED`; network/malformed/mismatch outcomes to `UNKNOWN`; local prerequisites to `UNAVAILABLE`.
6. Add log-capture tests proving PII, tokens, transaction ids, and raw bodies are absent.
7. Add response and Base64 size limits for print data.

## Task 4: Implement Electronic-Waybill Lifecycle And Admin APIs

### Files

- Create lifecycle state store/service under the waybill package
- Create context/create/summary/history/sandbox DTOs
- Create `AdminElectronicWaybillController.java`
- Extend admin order detail with electronic-waybill summary
- Test `AdminElectronicWaybillControllerTest.java`
- Test lifecycle concurrency/service behavior

### Steps

1. Write failing tests for context blockers: disabled/incomplete setting, non-PAID order, active after-sale, missing structured receiver, missing payer OpenID, missing transaction, invalid/non-HTTPS product image, existing shipment, and active attempt.
2. Implement trusted request assembly from setting, order snapshot, payment snapshot, and immutable order items.
3. Implement create idempotency: same key/same digest replay, same key/different digest conflict, and double-click single upstream call.
4. Persist `CREATING` before the network call; finalize to `CREATED`, `FAILED`, or `UNKNOWN` with CAS.
5. Implement stale `CREATING` and `UNKNOWN` recovery through get, never blind add.
6. Implement cancel claim and recovery state machine.
7. Implement no-store print endpoint against the same provider order and update only print-request metadata.
8. Implement sandbox event whitelist and enforce mode + TEST identities + permission server-side.
9. Return pre-shipment electronic-waybill summary in admin order detail.
10. Run focused tests, diff review, fixes, and re-review.

## Task 5: Converge Electronic And Manual Shipment Safely

### Files

- Modify `LocalShipmentService.java`
- Modify `AdminShipmentService.java`
- Add electronic confirm service/controller behavior
- Extend `OrderShipmentResponse` and `AppOrderShipmentResponse`
- Extend `AdminShipmentControllerTest.java`
- Add confirm concurrency tests

### Steps

1. Add failing tests showing create label keeps `PAID` and creates no shipment.
2. Add failing tests showing active label blocks manual shipment and address reselection.
3. Refactor local shipment creation into one trusted transaction helper while preserving the existing manual DTO path.
4. Manual path writes `shipment_source=MANUAL`.
5. Electronic confirm reads carrier/tracking only from the locked `CREATED` record, writes `WECHAT_WAYBILL`, links the record, and marks it `CONFIRMED`.
6. Preserve current active-after-sale and one-shipment guards.
7. Prove concurrent/double confirm creates exactly one shipment and one status transition.
8. After commit, invoke existing shipping upload and new registration coordinators independently; failures do not roll back local shipment.

## Task 6: Implement Trace And Message Registration

### Files

- Create `WechatWaybillRegistrationProvider` and real/mock implementations
- Create registration state store/coordinator
- Create admin retry endpoint
- Create app token endpoint/DTO
- Extend shipment DTO safe metadata
- Test provider, coordinator, retry, ownership, and no-store behavior

### Steps

1. Write exact-payload tests for `trace_waybill` and `follow_waybill` using payment OpenID/transaction, receiver phone, carrier id, waybill, goods, and `pages/order/detail/detail?order_id={id}`.
2. Prove identity is reconstructed from `payment_order`, including after the app-user row is absent.
3. Implement source policy: electronic -> TRACE; manual + messages -> FOLLOW; manual without messages -> TRACE.
4. Implement one-row-per-shipment claim/CAS state machine and safe retry.
5. Persist successful token server-side only.
6. Implement owner-only `POST /app/orders/{id}/logistics/waybill-token` with no-store response.
7. Add tests that foreign users, non-express shipments, missing shipment, and incomplete identifiers cannot obtain a token.
8. Prove trace failure never removes static shipment fields or changes `SHIPPED`.
9. Run existing upload/receipt tests together with new registration tests.

## Task 7: Integrate Archive, Cleanup, And Documentation

### Files

- Modify `OrderAggregateCleanupService.java`
- Extend its tests
- Modify `docs/dev-setup.md`
- Modify `docs/smoke-checks.md`

### Steps

1. Add failing archive tests for electronic attempts and registration rows.
2. Include new tables in eligibility, archive sections/counts, and child-first delete order.
3. Ensure a failed archive/purge remains retryable and cannot leave an orphan.
4. Document config modes, sandbox quota/OpenID rule, production switch, sender values, and message switch.
5. Document static-card-only fake-number expectations and real token prerequisites.
6. Document backend/admin/mini automated checks separately from preview + real-device smoke.

## Task 8: Add Admin API, Types, And Configuration Page

### Files

- Create `admin/src/api/waybill.ts`
- Create `admin/src/types/api/waybill.d.ts`
- Extend `admin/src/types/api/api.d.ts`
- Create `admin/src/views/order/logistics-config/index.vue`
- Add focused pure helpers/tests for validation and display mapping

### Steps

1. Define mode/status/context/attempt/config/create/sandbox types without any password or token fields.
2. Add envelope APIs and a raw Blob print API.
3. Build the dynamic-menu configuration page with mode help, sender, production identifiers, parcel defaults, and revision conflicts.
4. Apply both `v-auth` and local permission checks; backend remains authoritative.
5. Test sandbox forced display, production validation, revision handling, and permission gating.
6. Run focused tests and admin typecheck.

## Task 9: Add Admin Waybill Workflow And Print Preview

### Files

- Modify `admin/src/views/order/list/index.vue`
- Create `admin/src/views/order/list/modules/electronic-waybill-panel.vue`
- Create `admin/src/views/order/list/modules/waybill-preview-dialog.vue`
- Create `admin/src/views/order/list/waybill-workflow.ts`
- Create `admin/src/views/order/list/waybill-workflow.test.ts`

### Steps

1. Write pure-state tests for opening, stale-response protection, double-click guards, active-label manual lock, create, refresh, cancel, confirm, and sandbox visibility.
2. Preserve the existing manual form and add a two-mode entry.
3. Render electronic UI in the child panel; do not further centralize it in the 2500-line order page.
4. Add order-detail priority: shipment, then pre-shipment waybill, then empty state.
5. Implement preview/reprint from one Blob response without calling create.
6. Sandbox iframe scripts/forms/popups/top navigation; revoke Blob URLs on every lifecycle exit.
7. Report print-dialog invocation accurately.
8. Test no-permission behavior and old-order async response isolation.
9. Run focused tests, typecheck, and production build.

### Verification

```bash
cd admin
pnpm exec tsx --test src/views/order/list/shipping-form.test.ts src/views/order/list/waybill-workflow.test.ts
pnpm typecheck
pnpm build
```

## Task 10: Add Mini-Program Static Logistics Card And Plugin

### Files

- Modify `miniprogram/miniprogram/app.json`
- Modify `miniprogram/types/order.ts`
- Modify `miniprogram/constants/api-endpoints.ts`
- Modify `miniprogram/services/order.ts`
- Modify `miniprogram/features/order-center.ts`
- Add typed plugin wrapper feature
- Modify order-detail TS/WXML/LESS
- Extend navigation/config, view-model, clipboard, and feature tests

### Steps

1. Add failing config test for alias `logisticsPlugin`, version `2.1.5`, provider `wx9ad912bf20548d92`.
2. Replace `shipment?: unknown` with the exact backend shipment type and safe registration metadata.
3. Build a normalized shipment view model; static rendering must not depend on token state.
4. Add the logistics card after receiver information with carrier, full tracking copy, shipped time, and accessible loading/disabled view action.
5. Add token service call and typed runtime-injected plugin wrapper.
6. Keep the token function-local and cover empty token, API reject, `requirePlugin` throw, missing method, and plugin throw.
7. Preserve page content on logistics failure and show the agreed fallback toast.
8. Run `pnpm check` and diff review.

### Verification

```bash
cd miniprogram
pnpm check
```

## Task 11: Cross-Stack Verification And Review

1. Run `git diff --check` and inspect every changed path for unrelated edits.
2. Run backend focused suites for schema, address, waybill provider/lifecycle, shipment, registration, cleanup, existing shipping upload, and receipt reconciliation.
3. Run the full backend Maven test suite.
4. Run admin focused tests, typecheck, and build.
5. Run mini-program `pnpm check`.
6. If Docker is available, migrate a disposable MySQL 8.4.10 database through all Flyway migrations and run concurrency-sensitive integration tests.
7. Request an independent code review across backend, admin, mini program, privacy, and official payload contracts.
8. Fix all material findings, rerun affected tests, request re-review, and repeat until clean.
9. Hand off exact real smoke steps without claiming them passed:
   - set complete sender and SANDBOX mode,
   - use a real paid order whose OpenID is an admin/operator/developer,
   - create one TEST label (mind the daily limit),
   - preview and invoke print,
   - confirm shipment,
   - simulate pickup/transport/delivery/signature,
   - build through WeChat preview,
   - open order detail on a real device,
   - verify static carrier/tracking and copy,
   - verify official plugin or capture safe upstream error code,
   - later repeat with a real bound carrier/account and real waybill.

### Final Commands

```bash
cd backend/shop-server
./mvnw test

cd ../../../admin
pnpm typecheck
pnpm build

cd ../miniprogram
pnpm check

cd ..
git diff --check
git status --short
```

## Review Checklist

- [ ] Electronic create never changes order status.
- [ ] Confirm uses only the server-side waybill record.
- [ ] Manual flow remains unchanged without an active label.
- [ ] Upstream calls are outside transactions.
- [ ] Create/cancel/confirm/registration are idempotent under double clicks and concurrency.
- [ ] Ambiguous results remain recoverable and are never blindly retried.
- [ ] Sandbox values are forced server-side.
- [ ] Password and print HTML are never stored.
- [ ] Tokens/PII/transaction ids/raw responses are absent from logs and normal DTOs.
- [ ] Static mini-program logistics survives every platform failure.
- [ ] Token endpoint is owner-only and no-store.
- [ ] Print endpoint is no-store and permission-gated.
- [ ] New tables are archived and purged with the order aggregate.
- [ ] Existing upload and receipt reconciliation remain green.
- [ ] Automated and real-device verification are reported separately.
