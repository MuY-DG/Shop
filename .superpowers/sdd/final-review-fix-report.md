# Final Review Fix Report

## Scope

Fixed the final cart review items in the Shop repo without touching unrelated code:

- Hardened `AppCartService.add` against duplicate-key races on first add.
- Enforced merged cart quantity max `999`.
- Marked mini program cart status fields nullable for missing-SKU rows.
- Added disabled-category coverage for add/list behavior.

## TDD Record

### Red

Added failing coverage first, then ran:

```bash
cd backend/shop-server && ./mvnw -Dtest='AppCartControllerTest,AppCartServiceTest,CartSchemaTest' test
```

Observed expected failures before production changes:

- `AppCartServiceTest.addMergesExistingRowAfterDuplicateKeyRetry` surfaced raw `DuplicateKeyException`.
- `AppCartControllerTest.addRejectsMergedQuantityAboveMaxLimit` returned `200` instead of validation `400/100400`.

### Green

Implemented the minimal production fix in `AppCartService`, then reran:

```bash
cd backend/shop-server && ./mvnw -Dtest='AppCartControllerTest,AppCartServiceTest,CartSchemaTest' test
cd backend/shop-server && ./mvnw -Dtest='AppCartControllerTest,CartSchemaTest' test
cd miniprogram && pnpm typecheck
```

All passed.

## Changes

### Backend

- On add merge path, validate `targetQuantity` after combining existing quantity and request quantity.
- On insert race path, catch `DuplicateKeyException`, re-read the cart row with `FOR UPDATE`, re-apply merge validation and stock/product checks, then update quantity instead of bubbling a `500`.

### Tests

- Added a controlled duplicate-key retry test for `AppCartService`.
- Added merged-quantity overflow coverage.
- Added disabled-category add rejection coverage.
- Added disabled-category retained-row list coverage.
- For missing SKU rows, asserted deserialized `skuStatus` / `spuStatus` are `null`.

### Mini Program Contract

- Updated `miniprogram/types/api.ts` so `CartItem.skuStatus` and `CartItem.spuStatus` are nullable.

## Concerns

- None.
