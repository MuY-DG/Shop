# Task 3 Report: App Coupon APIs And Promotion Calculation Service

## TDD RED

- Added failing tests first:
  - `backend/shop-server/src/test/java/org/muybaby/shopserver/promotion/CouponDiscountCalculatorTest.java`
  - `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AppCouponControllerTest.java`
- RED command:
  - `cd backend/shop-server && ./mvnw -Dtest=CouponDiscountCalculatorTest,AppCouponControllerTest test`
- RED result:
  - FAIL as expected during test compile.
  - First failure was missing promotion type:
    - `CouponDiscountCalculatorTest.java:[55,13] cannot find symbol`
    - `symbol: class CouponCandidate`

## GREEN

- Implemented promotion types and calculator:
  - `CheckoutItem`
  - `CheckoutContext`
  - `DiscountResult`
  - `PromotionCalculator`
  - `CouponCandidate`
  - `CouponDiscountCalculator`
- Implemented app coupon backend:
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AppCouponService.java`
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/AppCouponController.java`
- GREEN command:
  - `cd backend/shop-server && ./mvnw -Dtest=CouponDiscountCalculatorTest,AppCouponControllerTest test`
- GREEN result:
  - PASS
  - `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`

## What changed

- Added `/app/coupons/claimable`, `/app/coupons/templates/{templateId}/claim`, `/app/coupons/mine`, and `/app/coupons/available`.
- Added transactional coupon claim flow with:
  - app-user auth enforcement
  - template row locking via `FOR UPDATE`
  - active template validation
  - per-user claim limit check
  - `user_coupon` snapshot insert
  - `coupon_claim_record` insert
  - `coupon_template.claimed_count` increment
- Added app-side coupon listing and mine queries using existing DTOs from Tasks 1-2.
- Added reusable promotion calculation types for future checkout/order reuse.
- Added coupon availability calculation against current valid cart rows with best-coupon selection.

## Files changed

- `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/AppCouponController.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AppCouponService.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/CheckoutItem.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/CheckoutContext.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/DiscountResult.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/PromotionCalculator.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/CouponCandidate.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/promotion/CouponDiscountCalculator.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AppCouponControllerTest.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/promotion/CouponDiscountCalculatorTest.java`

## Test results

- Focused promotion tests: PASS
- Focused app coupon controller tests: PASS
- Verification command:
  - `cd backend/shop-server && ./mvnw -Dtest=CouponDiscountCalculatorTest,AppCouponControllerTest test`
- Additional hygiene check:
  - `git diff --check -- backend/shop-server/src/main/java/org/muybaby/shopserver/coupon backend/shop-server/src/main/java/org/muybaby/shopserver/promotion backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AppCouponControllerTest.java backend/shop-server/src/test/java/org/muybaby/shopserver/promotion/CouponDiscountCalculatorTest.java`
  - PASS

## Self-review

- Followed the task boundary: backend coupon app API and promotion calculation only; no admin, miniprogram, docs, or unrelated cleanup changes.
- Kept endpoint paths, controller shape, service method names, DTO usage, and commit message aligned with the task brief.
- Reused existing backend patterns from cart/admin coupon tests and shared `BusinessException` / `ErrorCode` handling.
- Kept the promotion abstraction small and focused so checkout/order can reuse it later without coupling coupon logic into order service.
- Ensured available-coupon calculation only uses current user cart rows that remain sellable under the same status/stock constraints described in the brief.

## Concerns

- `claimable` currently reports `CLAIM_LIMIT_REACHED` when the user has already reached `per_user_limit`; this matches the test contract added here, but the brief does not prescribe a public `unavailableReason` enum for this endpoint.
- Verification in this task is focused to the required promotion/app coupon suite. Broader regression coverage across the full backend test set was not run in this task.

---

## Review Fix: stock-aware claimable mapping

### TDD RED

- Adjusted `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AppCouponControllerTest.java` so `/app/coupons/claimable` covers two distinct unavailable cases:
  - `Exhausted Coupon`: `total_stock = 1`, `claimed_count = 1`, current user has never claimed it.
  - `Limit Reached Coupon`: user already has one `user_coupon` row and hits `per_user_limit`.
- RED command:
  - `cd backend/shop-server && ./mvnw -Dtest=CouponDiscountCalculatorTest,AppCouponControllerTest test`
- RED result:
  - FAIL as expected.
  - Relevant failure:
    - `AppCouponControllerTest.claimableListReturnsEnabledCurrentTemplatesAndClaimableFlag`
    - `Expecting value to be false but was true`
- RED response evidence showed the root cause directly:
  - `Exhausted Coupon` returned `"claimedCount":1` and `"claimable":true`, so stock exhaustion was not part of the claimable mapping.

### GREEN

- Updated `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AppCouponService.java`:
  - included `total_stock` in the claimable query
  - computed `OUT_OF_STOCK` from `claimed_count >= total_stock`
  - kept `CLAIM_LIMIT_REACHED` for the per-user-limit path
  - made stock exhaustion win before per-user-limit when mapping `claimable` / `unavailableReason`
- GREEN command:
  - `cd backend/shop-server && ./mvnw -Dtest=CouponDiscountCalculatorTest,AppCouponControllerTest test`
- GREEN result:
  - PASS
  - `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`

### Files changed for the review fix

- `backend/shop-server/src/test/java/org/muybaby/shopserver/coupon/AppCouponControllerTest.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/coupon/service/AppCouponService.java`
