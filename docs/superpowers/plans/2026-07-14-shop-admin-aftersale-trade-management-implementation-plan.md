# Shop Admin After-Sale and Trade Management Implementation Plan

Date: 2026-07-14

## Goal

Rebuild the admin after-sale workspace with the same operational design language as the order workspace, while keeping after-sale-specific fields and actions. Add a new `交易管理` navigation parent containing the order and after-sale lists, preserve existing permissions, and keep the mini program unchanged.

## Source of Truth

- Approved conversation decisions from 2026-07-14.
- Existing after-sale lifecycle in `AfterSaleStatus`, `AdminAfterSaleService`, and `RefundCallbackService`.
- Existing admin after-sale page in `admin/src/views/aftersale/list/index.vue`.
- Existing order workspace patterns in `admin/src/views/order/list/index.vue`.
- Existing RBAC-backed menu rows from migrations V6 and V8.
- Existing authenticated private evidence endpoint and blob-preview lifecycle.

## Product Decisions

- Primary status tabs are `全部`, `待审核`, `退款中`, `已退款`, `已拒绝`, and `退款失败`.
- The `退款中` business group contains both legacy `APPROVED` and active `REFUNDING`; `APPROVED` is not exposed as a separate primary tab.
- Every tab shows a count after all non-status filters are applied.
- Search fields are after-sale id, order number, user id/authorized phone, after-sale type, application time, and merchant/WeChat refund number.
- User-name search is omitted because `app_user` has no display-name field.
- List columns are after-sale id, order number, after-sale information, requested amount, display status, application time, and operations.
- Approved amount, reviewer data, evidence, and refund diagnostics remain in detail instead of the list.
- Operations use `详情` plus `更多`. `更多` contains `查看关联订单` for every row and `审核通过并退款` / `审核拒绝` only for `REQUESTED` rows with `aftersale:audit`.
- No retry-refund action is added because the backend has no safe manual retry contract.
- No synthetic after-sale timeline is added because the system does not yet have complete after-sale lifecycle logs.
- Detail uses `售后信息`, `凭证信息`, and `退款信息` tabs and only displays persisted fields.
- Private evidence continues to load through the authenticated blob endpoint and object URLs must still be revoked.

## Menu Decisions

- Add a new RBAC menu parent named `交易管理` with route path `/trade`.
- Move the existing order-list and after-sale-list menu rows under the new parent.
- Child routes become `/trade/orders` and `/trade/after-sales`; their component paths remain `/order/list` and `/aftersale/list`.
- Preserve all existing `order:*` and `aftersale:*` menu-permission mappings.
- Grant the new parent to every role that currently has an order or after-sale menu grant.
- Disable the redundant old order and after-sale parent rows after child migration.
- Add compatibility redirects from `/order/list` and `/aftersale/list` to the new routes.

## Global Constraints

- Do not modify `miniprogram/`.
- Do not edit migrations V1 through V20; add V21 only.
- Preserve exact-status query compatibility while adding the grouped status-tab contract.
- Keep evidence preview authorization and resource cleanup intact.
- Keep approve/reject permission enforcement and backend validation intact.
- Avoid N+1 evidence/refund queries on the list endpoint by returning a lightweight summary DTO.
- Follow test-first development and run focused tests before broad verification.

## Task 1: Add Failing Backend and Migration Tests

Files:

- Modify `AdminAfterSaleControllerTest`.
- Create an after-sale/trade-management schema test.
- Update affected legacy schema assertions.

Steps:

1. Add contract tests for grouped status filtering, non-status filters, status counts, and lightweight list rows.
2. Add migration assertions for the `交易管理` parent, both child routes, role grants, disabled old parents, and unchanged permission ownership.
3. Add assertions that list rows do not eagerly include evidence files or refund-order detail.

## Task 2: Implement Lightweight After-Sale Admin Reads

Files:

- Extend `AdminAfterSaleQueryRequest`.
- Add after-sale status-group, summary, and status-count DTOs.
- Modify `AdminAfterSaleController` and `AdminAfterSaleService`.

Steps:

1. Normalize shared filters for after-sale id, order number, user id/phone, after-sale type, application range, and refund number.
2. Keep legacy exact `status` and add a grouped `statusGroup` filter.
3. Add a grouped count query that ignores only the selected status group.
4. Return summary rows from one list query without loading evidence or refund detail per record.
5. Keep the detail endpoint and authenticated evidence endpoint behavior unchanged.

## Task 3: Add the Trade Management Menu Migration

Files:

- Create `backend/shop-server/src/main/resources/db/migration/V21__admin_trade_management.sql`.
- Modify migration/schema tests that enumerate current menu versions or menu structure.
- Add frontend compatibility redirects.

Steps:

1. Insert the new parent menu with a stable id and trade-oriented icon.
2. Copy relevant role grants to the new parent without duplicates.
3. Reparent and rename the existing child paths while preserving component and permission mappings.
4. Disable old parent rows and remove obsolete role-menu grants to them.
5. Verify roles with either child still receive a valid parent-child route tree.

## Task 4: Rebuild the Admin After-Sale Workspace

Files:

- Modify `admin/src/api/aftersale.ts`.
- Modify `admin/src/types/api/api.d.ts`.
- Refactor `admin/src/views/aftersale/list/index.vue`.

Steps:

1. Add counted status tabs and remove the status select.
2. Add the approved two-row, three-column search form with aligned labels.
3. Use the shared `ArtTable` workflow and the approved lightweight columns.
4. Change operations to `详情` plus `更多`, retaining permission guards.
5. Make the approval action explicitly say that it starts a refund.
6. Rebuild detail into a summary header and three content tabs.
7. Preserve private evidence previews and cleanup behavior.

## Task 5: Verification

Focused backend gate:

```bash
cd backend/shop-server
./mvnw -Dtest='AdminAfterSaleControllerTest,AfterSaleSchemaTest,*AfterSale*Test,*Refund*Test,AdminMenuControllerTest,AdminMenuRouteServiceTest' test
```

Broad gates:

```bash
cd backend/shop-server
./mvnw test

cd admin
pnpm typecheck
pnpm build

git diff --check
git status --short --ignored
```

Browser checklist:

- New trade menu contains both pages and the old standalone parents are absent.
- Status tabs show correct counts and preserve non-status filters.
- Search fields align in two rows and user search exposes only id/phone.
- List shows no eager evidence/refund detail and operations match row status and permissions.
- Approval wording clearly communicates that a refund will be initiated.
- Detail tabs show only real system fields and private evidence still previews correctly.
- Old order and after-sale URLs redirect to the new routes.
- No mini-program file changes.
