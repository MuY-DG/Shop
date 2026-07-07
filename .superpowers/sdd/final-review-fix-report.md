# Final Review Fix Report

Status: DONE

Base HEAD at start: `d8e9e88 test: align rbac expectations with product catalog`

## Fix Summary

- App product list count/list/detail now join `product_category` and require the category to remain `ENABLED`, so products in disabled categories are hidden from public app APIs after publish.
- SPU update now carries authenticated admin operator context from `AdminProductSpuController` into `AdminProductService`.
- SPU update captures existing SKU stock before replacing rows and writes stock audit entries:
  - `ADJUST` for existing SKU IDs when `stockAvailable` changes.
  - `INITIAL` for newly added SKUs.
- SKU stock adjustment now reads the SKU row with `SELECT ... FOR UPDATE` inside the transaction before calculating and writing the stock delta.
- Admin SPU list price fields are nullable in TS types and render `暂无价格` when either aggregate price is absent or non-finite.

## Red Evidence

Command:

```bash
cd backend/shop-server
./mvnw -Dtest='AppProductControllerTest,AdminProductServiceTest,AdminProductSpuControllerTest' test
```

Exit status: 1

Key output:

```text
COMPILATION ERROR
AdminProductServiceTest.java:[157,28] 方法 updateSpu 应用到给定类型失败
需要: Long, AdminSpuUpsertRequest
找到: Long, AdminSpuUpsertRequest, long
```

This was the expected red failure for the desired authenticated `updateSpu` stock-audit path before production code existed.

## Verification

### Focused Backend Tests

Command:

```bash
cd backend/shop-server
./mvnw -Dtest='AppProductControllerTest,AdminProductServiceTest,AdminProductSpuControllerTest' test
```

Exit status: 0

Key output:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Full Backend Tests

Command:

```bash
cd backend/shop-server
./mvnw test
```

Exit status: 0

Key output:

```text
Tests run: 81, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Admin Build

Command:

```bash
cd admin
pnpm build
```

Exit status: 0

Key output:

```text
$ vue-tsc --noEmit && vite build
✓ 3240 modules transformed.
✓ built in 14.56s
```

### Diff Check

Command:

```bash
git diff --check
```

Exit status: 0

Key output: no output.

## Git Notes

- `.pnpm-store/` was present as untracked local noise and was not staged.
- No miniprogram files changed, so miniprogram typecheck was not rerun.
