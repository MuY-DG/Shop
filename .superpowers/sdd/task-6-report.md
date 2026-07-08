# Task 6 Report: Coupon Smoke Documentation And Focused Verification

## Status

DONE_WITH_CONCERNS

## Docs Changed

### `docs/dev-setup.md`

- Added a focused coupon backend test command under `## Backend Checks`.
- Added the exact `## Coupon Checks` section from the brief.
- Included the required automated verification commands:
  - `./mvnw -Dtest=CouponSchemaTest,AdminCouponTemplateControllerTest,AppCouponControllerTest,CouponDiscountCalculatorTest test`
  - `pnpm typecheck`
  - `pnpm build`
- Added the pointer to `docs/smoke-checks.md#coupon-smoke-checks`.

### `docs/smoke-checks.md`

- Added `## Coupon Smoke Checks` immediately after `## Cart Smoke Checks`.
- Included the required real-local-smoke distinction text:
  - test-profile WeChat login is mocked
  - product/cart/coupon/promotion requests go through real local backend APIs
  - no product/cart/coupon mocks are involved in those requests
- Added the required coupon smoke steps and commands for:
  1. starting backend with the existing test-profile command
  2. admin login
  3. mini program login
  4. creating and enabling a no-threshold coupon template
  5. verifying claimable list
  6. claiming coupon
  7. verifying my coupons
  8. creating category/SPU/SKU and publishing
  9. adding SKU to cart
  10. querying `/app/coupons/available`
- Added the exact expected final output block from the brief:

```text
success
新人无门槛券
CLAIMED
购物车优惠券测试锅底 1 3990
500
```

## Focused Verification Results

Per the user instruction for this subagent, only the focused verification commands were run.

### Backend focused coupon tests

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponSchemaTest,AdminCouponTemplateControllerTest,AppCouponControllerTest,CouponDiscountCalculatorTest test
```

Result:

- Passed
- Summary observed: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`
- Final Maven result: `BUILD SUCCESS`

### Mini program typecheck

Command:

```bash
cd miniprogram
pnpm typecheck
```

Result:

- Passed
- Command completed with exit code 0

### Admin build

Command:

```bash
cd admin
pnpm build
```

Result:

- Passed
- Vite production build completed successfully
- Final observed line: `built in 18.64s`

## Commit

Created commit:

- `c50c459 docs: add coupon smoke checks`

## Files Changed

- `docs/dev-setup.md`
- `docs/smoke-checks.md`

## Self-Review

- Scope stayed within the brief: only the two requested docs were edited.
- The coupon smoke docs now clearly distinguish real local smoke from automated tests/builds.
- The smoke text explicitly states that test-profile WeChat login is mocked, while product/cart/coupon/promotion requests are real local backend API calls.
- No long-lived backend smoke server was started in this subagent.
- No unrelated tracked or ignored files were cleaned or reverted.
- The branch remained `codex/shop-coupon`.

## Concerns

- I followed the user’s explicit instruction to run only the focused verification commands, so I did not run the heavier final verification block from the brief:

```bash
cd backend/shop-server && ./mvnw test
cd miniprogram && pnpm typecheck
cd admin && pnpm build
git status --short --ignored
```

- I still collected `git status --short --ignored` after the commit for visibility. It showed only ignored local noise entries and no remaining tracked modifications at that moment.
- The repository emitted an existing Git housekeeping warning during commit about `.git/gc.log` and unreachable loose objects. This did not block the commit and was not modified here.

---

## Task 6 Review Fixes

### Changes made

- Updated `docs/smoke-checks.md` so coupon smoke commands no longer hard-code `claimEndAt` to `2026-12-31T23:59:59`.
- Added runtime-generated `VALID_START_AT` and `VALID_END_AT` shell variables, and reused them in both create and update coupon-template requests.
- Updated the claimable-list verification snippet to find the target coupon by `TEMPLATE_ID` or the smoke template name `新人无门槛券` instead of assuming `records[0]`.

### Diff check

Command:

```bash
git diff --check -- docs/smoke-checks.md docs/dev-setup.md
```

Result:

- Passed
- `git diff --check -- docs/smoke-checks.md docs/dev-setup.md` produced no output.

### Remaining Task 6 doc review follow-up

- Updated the `docs/smoke-checks.md` claimable-list lookup to prefer the newly created template by ID first, then fall back to the smoke template name only if no ID match exists.
- Used the documented/backend response shape for claimable items: `body.data.records[*].templateId` with `item.id` kept as a compatibility fallback in the snippet.

### Verification

Command:

```bash
git diff --check -- docs/smoke-checks.md
```

Result:

- Passed
- `git diff --check -- docs/smoke-checks.md` produced no output.

---

## 2026-07-07 Task 6 Second Review Fix

### Changes

- Updated `miniprogram/services/storage.ts` so `uploadEvidenceFile` accepts valid PRIVATE app upload metadata without requiring `url` or `publicUrl`, while still rejecting malformed success payloads that miss required numeric or string fields.
- Updated `miniprogram/pages/home/home.ts` so `APP_PATH` tab destinations with a query string use `wx.reLaunch({ url: jumpPath })` and preserve the configured query, while plain tab paths still use `wx.switchTab`.

### Verification

- `cd miniprogram && pnpm typecheck` passed.
- `git diff --check -- miniprogram/services/storage.ts miniprogram/pages/home/home.ts .superpowers/sdd/task-6-report.md` passed.


---

## 2026-07-07 Task 6 Mini Program Banner And Upload Helper

### Scope

- Added `HomeBanner`, `StorageFileUploadResponse`, and `EvidenceUploadPurpose` in `miniprogram/types/api.ts`.
- Created `miniprogram/services/home.ts` for `GET /app/home/banners`.
- Created `miniprogram/services/storage.ts` for authenticated `wx.uploadFile` evidence uploads.
- Updated `miniprogram/pages/home/home.ts`, `home.wxml`, and `home.wxss` to load banners in parallel with product preview, render a swiper when banners exist, preserve the existing hero fallback when banners are absent, and route banner taps by jump type.

### RED Evidence

Command:

```bash
cd miniprogram && pnpm typecheck
```

Result:

- Failed as expected before implementation was complete.
- Observed TypeScript error:

```text
services/storage.ts(4,7): error TS2322: Type '"HOME_BANNER"' is not assignable to type 'EvidenceUploadPurpose'.
```

### GREEN Evidence

Command:

```bash
cd miniprogram && pnpm typecheck
```

Result:

- Passed
- Observed output:

```text
$ tsc --noEmit
```

### Notes

- Banner fetch failure now falls back to the existing static hero without blocking category/product loading.
- `APP_PATH` banner routes switch to tab pages with `wx.switchTab`; non-tab paths use `wx.navigateTo`.
- No real local smoke was run in this task; only the required mini program typecheck was executed.

---

## 2026-07-07 Task 6 Review Fix

### Changes

- Hardened `miniprogram/services/storage.ts` so `uploadEvidenceFile` rejects malformed success payloads unless the upload metadata includes a finite numeric `id`, a non-empty string `purpose`, a non-empty string `visibility`, and a usable image URL in `url` or `publicUrl`.
- Tightened the same guard again so app upload responses only pass when `visibility` is `PRIVATE` and `uploadedByType` is `APP`, while still allowing `url` and `publicUrl` to be absent for private files.
- Added clamp rules to `miniprogram/pages/home/home.wxss` for `.banner-title` and `.banner-subtitle` so long admin-managed text stays inside the fixed swiper frame.

### Verification

Command:

```bash
cd miniprogram && pnpm typecheck
```

Result:

- Passed
- Command completed with exit code 0

Command:

```bash
git diff --check -- miniprogram/services/storage.ts miniprogram/pages/home/home.wxss .superpowers/sdd/task-6-report.md
```

Result:

- Passed
- No diff check warnings were reported.

---

## 2026-07-07 Task 6 Fourth Review Fix

- Tightened `miniprogram/services/storage.ts` upload success validation so PRIVATE responses still allow missing or null `url` and `publicUrl`, while requiring positive numeric `id` and `sizeBytes`, matching purpose/visibility/status/provider metadata, and optional positive numeric metadata when present.
- Split `miniprogram/pages/home/home.ts` into independent banner, category, and product loaders so banner latency or failure no longer blocks category or product rendering.
- Verification run: `cd miniprogram && pnpm typecheck` and `git diff --check -- miniprogram/services/storage.ts miniprogram/pages/home/home.ts .superpowers/sdd/task-6-report.md`.
