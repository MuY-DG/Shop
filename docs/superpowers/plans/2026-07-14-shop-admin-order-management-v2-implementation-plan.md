# Shop Admin Order Management V2 Implementation Plan

Date: 2026-07-14

## Goal

Turn the existing admin order list into an operations-oriented workspace with counted status tabs, richer search and list fields, durable order-status history, and a reference-informed detail drawer. Keep the mini program unchanged and do not display a payment-method field.

## Source of Truth

- Approved conversation decisions from 2026-07-14.
- Existing order status contract in `backend/shop-server/src/main/java/org/muybaby/shopserver/order/OrderStatus.java`.
- Existing admin order implementation in `backend/shop-server/src/main/java/org/muybaby/shopserver/order` and `admin/src/views/order/list/index.vue`.
- Existing order, payment, fulfillment, and after-sale migrations V6, V8, and V10.
- Reference screenshots supplied by the user for information hierarchy only; fields not backed by the current system stay omitted.

## Product Decisions

- Admin status tabs are `ALL`, `UNPAID`, `TO_SHIP`, `TO_RECEIVE`, `COMPLETED`, `CLOSED`, `REFUNDING`, and `REFUNDED`.
- `UNPAID` contains both `CREATED` and `PAYING`; the list does not expose them as separate primary tabs.
- Every tab shows a count computed after all non-status filters have been applied.
- The status select is removed from the search form.
- User search initially supports user id and authorized phone only. The current `app_user` table has no display-name or nickname field, so user-name search is omitted.
- List columns are order number, receiver, first product snapshot, actual paid amount, display status, created time, and operations.
- The list operation area contains `详情` and `更多`; `更多` initially contains only `订单记录`.
- Existing shipment, close, and WeChat-upload-retry actions remain available in the detail footer under the existing permissions.
- The detail drawer has `订单信息` and `商品信息` tabs. It uses only fields already stored by the system or values directly derived from those fields.
- Payment method, buyer message, merchant note, promoter, user name, and invented order-type labels are not displayed.
- Phones are masked in list/read-only presentation; backend search still accepts the normalized phone value.
- Status history is durable data. It is not synthesized only in the browser.

## Global Constraints

- Do not modify `miniprogram/`.
- Do not edit existing migrations V1 through V19; add V20 only.
- Preserve existing exact-status admin API compatibility while adding the admin status-group contract.
- Keep order, payment, shipment, receipt, close, and refund transitions transactional with their status-log insert.
- Avoid one request per status tab and avoid client-side counts from the current page.
- Existing permissions continue to guard read, close, shipment, and retry actions.
- Follow test-first development and run focused tests before broad verification.

## Task 1: Add Status History Schema and Backend Contracts

Files:

- Create `backend/shop-server/src/main/resources/db/migration/V20__admin_order_management_v2.sql`.
- Create admin order status-group and status-log DTO/domain files.
- Extend `AdminOrderQueryRequest`, `OrderSummaryResponse`, and `OrderDetailResponse`.
- Extend schema/controller tests.

Steps:

1. Add failing tests for the V20 table, indexes, and legacy-order backfill.
2. Add failing contract tests for the new query fields, status counts, richer list rows, enriched detail, and status-log endpoint.
3. Add `order_status_log` with order id, from/to status, event type, operator type/id, description, and event time.
4. Backfill deterministic milestones from current order, payment, shipment, and refund timestamps.
5. Add indexes for admin status/time, receiver lookup, tracking number, and status-log reads.

## Task 2: Implement Admin Search, Counts, List, and Detail Reads

Files:

- Modify `AdminOrderController` and `AdminOrderService`.
- Add focused service/controller tests.

Steps:

1. Implement shared non-status filters for order number, user id/phone, receiver name/phone, creation range, and tracking number.
2. Keep the legacy exact `status` filter and add the admin `statusGroup` filter.
3. Add one grouped count query that ignores the selected status tab but honors every other active filter.
4. Return receiver data and the first order-item snapshot in each list row without N+1 queries.
5. Enrich detail with user id/authorized phone, lifecycle timestamps, item count, and successful refunded amount.
6. Add a status-log read endpoint ordered chronologically.

## Task 3: Record Every Order Status Transition

Files:

- Create an order status-log writer/service.
- Modify order creation, payment start/success, close, shipment, receipt, refund start/success, and refund restoration paths.
- Add focused transition tests.

Steps:

1. Insert the status event in the same transaction as each `shop_order.status` mutation.
2. Record user, admin, system, or WeChat as the source and include an operator id when one exists.
3. Verify duplicate callbacks and idempotent operations do not create duplicate status transitions.

## Task 4: Rebuild the Admin Order Workspace

Files:

- Modify `admin/src/api/order.ts`.
- Modify `admin/src/types/api/api.d.ts`.
- Refactor `admin/src/views/order/list/index.vue` and add focused helpers/tests when useful.

Steps:

1. Replace the status select with counted, scrollable tabs.
2. Add compact and expandable search fields with the composite user-search selector.
3. Replace the list with the approved columns and masked receiver phone.
4. Keep status visible under `全部`; map `PAID` to `待发货` and `SHIPPED` to `待收货`.
5. Change operations to `详情` plus a `更多` dropdown containing `订单记录`.
6. Add a timeline drawer for durable order status records.
7. Rebuild the detail drawer header and its `订单信息` / `商品信息` tabs.
8. Keep business actions in the detail footer and collapse advanced WeChat shipment diagnostics.

## Task 5: Verification and Review

Focused backend gate:

```bash
cd backend/shop-server
./mvnw -Dtest='AdminOrderControllerTest,*Payment*Test,*Shipment*Test,*AfterSale*Test,*OrderServiceTest' test
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

Review checklist:

- Counts respect non-status filters and do not count only the current page.
- `CREATED` and `PAYING` both appear under `待付款`.
- User-name search and payment method are absent.
- Phone presentation is masked and raw phone values are not logged.
- Historical and new orders both have meaningful status records.
- Status-log writes are transactional and idempotent.
- Existing shipment/close/retry permissions and actions still work.
- No mini-program file changed.
