# Task 2 Report: App Order Preview And Submit Backend

## Status

DONE

## Scope

Implemented the Task 2 backend app order flow in `backend/shop-server` only:

- `POST /app/orders/preview`
- `POST /app/orders`
- `GET /app/orders`
- `GET /app/orders/{orderId}`

Covered the required current-user cart resolution, coupon selection/validation, order snapshot creation, stock lock, coupon lock, cart cleanup, and idempotent submit behavior.

## TDD Evidence

### RED

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=AppOrderControllerTest test
```

Observed failure before implementation:

- `AppOrderControllerTest.previewUsesCurrentUserCartRowsAndSelectsBestCouponWhenCouponOmitted`
  - expected `200`, got `404` on `/app/orders/preview`
- `AppOrderControllerTest.submitCreatesOrderLocksStockAndCouponDeletesCartRowsAndIsIdempotent`
  - expected `200`, got `404` on `/app/orders`
- `AppOrderControllerTest.submitRejectsCartItemThatDoesNotBelongToCurrentUser`
  - expected `400`, got `404` on `/app/orders`
- `AppOrderControllerTest.submitRejectsDisabledSkuOffSaleCategoryAndStockShortage`
  - expected `400`, got `404` on `/app/orders`
- `AppOrderControllerTest.submitRejectsSelectedCouponThatIsNotApplicable`
  - expected `400`, got `404` on `/app/orders`
- `AppOrderControllerTest.appOrderListAndDetailReturnOnlyCurrentUsersOrders`
  - expected `200`, got `404` on `/app/orders`

Failure cause matched expectation: the app order controller/service routes did not exist yet.

### GREEN

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=AppOrderControllerTest test
```

Result:

- `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

## Files Changed

- `backend/shop-server/src/main/java/org/muybaby/shopserver/order/AppOrderController.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java`

## Implementation Notes

### Controller

- Added `AppOrderController` following the existing app cart/coupon controller style.
- Kept preview request body optional so null or empty `cartItemIds` resolves to all current-user cart rows.
- Exposed app list/detail endpoints on the same controller to satisfy the Task 2 contract.

### Service

- Added `AppOrderService` with a shared checkout loader that:
  - resolves owned cart rows for the current app user
  - treats null or empty `cartItemIds` as all current-user cart rows
  - returns `250001` when explicit cart ids do not resolve for the current user
  - validates SKU/SPU/category availability and stock before preview/submit
- Reused `CheckoutContext`, `CheckoutItem`, `CouponCandidate`, `CouponDiscountCalculator`, and `DiscountResult` for coupon evaluation.
- When `userCouponId` is omitted, selects the best current `CLAIMED` coupon for the computed checkout context; when supplied, enforces ownership, claimed status, validity window, and calculator applicability.
- Submit transaction:
  - checks `(user_id, idempotency_key)` first and returns the existing order when present
  - inserts `shop_order` and `order_item` snapshots
  - decrements `product_sku.stock_available`
  - inserts `stock_lock`
  - writes `stock_log` with `ORDER_LOCK`
  - locks the chosen coupon in `user_coupon`
  - deletes the submitted cart rows
- Added duplicate-key fallback on order insert so a race on `(user_id, idempotency_key)` still resolves back to the already-created order.

### Tests

- Added `AppOrderControllerTest` first and drove the implementation from it.
- Covered:
  - app token boundary on `/app/orders/preview`
  - preview pricing/snapshots plus auto-best coupon selection
  - submit side effects and repeated-submit idempotency
  - foreign cart-row rejection
  - disabled SKU / off-sale SPU / disabled category / stock shortage business errors
  - selected inapplicable coupon rejection
  - current-user-only list/detail reads

## Self-Review

- Stayed inside Task 2 backend scope; did not touch admin or mini program files.
- Followed the repo’s existing app service/controller patterns instead of introducing a new abstraction layer.
- Kept all stock/coupon/cart mutations in the submit transaction.
- Verified null/empty cart selection behavior, explicit cart ownership enforcement, and auto-best coupon selection with focused controller tests.
- Hardened idempotency beyond the sequential test case by handling duplicate-key insert races.

## Concerns

- The brief defines no dedicated app order not-found error code. `GET /app/orders/{orderId}` currently throws `100400 / VALIDATION_FAILED` for a missing or foreign order id because Task 2 was constrained to the existing error-code set.

## Commit

- `44f71cb` — `feat: add app order creation`

---

## Follow-up: Critical Review Fix For App Order Idempotency

### Status

DONE

### Review Finding Addressed

Fixed the Task 2 critical race where overlapping submits with the same `idempotencyKey` could both miss `findExistingOrder()`, then race through cart loading and fail with a cart validation error before the duplicate-key fallback on `shop_order` insert had any chance to recover the second request.

### TDD Evidence

#### RED

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=AppOrderControllerTest#overlappingSubmitWithSameIdempotencyKeyReturnsExistingOrderInsteadOfCartError test
```

Observed failure before the fix:

- `AppOrderControllerTest.overlappingSubmitWithSameIdempotencyKeyReturnsExistingOrderInsteadOfCartError`
  - second overlapping submit failed with `BusinessException: Validation failed`
  - stack reached `AppOrderService.loadCheckoutSelection(...)`
  - this matched the review finding: the loser request was still on the cart path after missing the first idempotency lookup

#### GREEN

Focused race regression:

```bash
cd backend/shop-server
./mvnw -Dtest=AppOrderControllerTest#overlappingSubmitWithSameIdempotencyKeyReturnsExistingOrderInsteadOfCartError test
```

Result:

- `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

Full Task 2 controller suite:

```bash
cd backend/shop-server
./mvnw -Dtest=AppOrderControllerTest test
```

Result:

- `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

### Files Changed

- `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java`

### Implementation Notes

- Moved idempotency ownership to the start of `AppOrderService.submit()` by inserting the `shop_order` row before cart-row locking/loading.
- The early insert now reserves the unique `(user_id, idempotency_key)` slot inside the submit transaction with a placeholder header that uses schema defaults and zero monetary fields.
- After cart validation and coupon resolution complete, the same transaction updates that reserved order row with the final checkout snapshots and amounts before writing `order_item`, `stock_lock`, stock logs, coupon lock, and cart deletion.
- Kept the existing fast path for sequential replay via `findExistingOrder(...)` and retained the duplicate-key fallback for concurrent callers that lose the early ownership race.

### Regression Coverage Added

- Added a deterministic overlapping-submit regression in `AppOrderControllerTest`.
- The test forces both submits past the initial `findExistingOrder(...)` read, then holds the designated loser until the winner commits, proving the second request now returns the same created order rather than failing on cart state.
- Assertions also confirm there is still only one `shop_order`, one `order_item`, one `stock_lock`, a single stock decrement, and cart cleanup only once.
