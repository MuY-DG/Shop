# Task 5 Report: Mini Program Coupon Pages And Cart Coupon Summary

## Implementation notes

- Added coupon DTO types to `miniprogram/types/api.ts` for claimable coupons, user coupons, and cart-available coupon summaries.
- Created `miniprogram/services/coupon.ts` with the required exports:
  - `getClaimableCoupons`
  - `claimCoupon`
  - `getMyCoupons`
  - `getAvailableCoupons`
- Registered coupon pages in `miniprogram/app.json`:
  - `pages/coupon/list/list`
  - `pages/coupon/mine/mine`
- Implemented the claimable coupon page under `miniprogram/pages/coupon/list/`:
  - calls `ensureAppLogin()`
  - loads `/app/coupons/claimable`
  - supports claim action with success toast and reload
  - includes navigation to `/pages/coupon/mine/mine`
- Implemented the my coupon page under `miniprogram/pages/coupon/mine/`:
  - calls `ensureAppLogin()`
  - loads `/app/coupons/mine`
  - groups coupons visually by status
  - shows validity window and threshold text
- Updated `miniprogram/pages/profile/` to add action rows for:
  - `领券中心 -> /pages/coupon/list/list`
  - `我的优惠券 -> /pages/coupon/mine/mine`
  - kept existing phone authorization flow intact
- Updated `miniprogram/pages/cart/` to request `/app/coupons/available` after cart load using available cart item ids and render settlement-bar coupon summary text while keeping checkout disabled.

## Typecheck result

- Command: `cd miniprogram && pnpm typecheck`
- Result: PASS (`tsc --noEmit`)

## Files changed

- `miniprogram/types/api.ts`
- `miniprogram/services/coupon.ts`
- `miniprogram/app.json`
- `miniprogram/pages/profile/profile.ts`
- `miniprogram/pages/profile/profile.wxml`
- `miniprogram/pages/profile/profile.wxss`
- `miniprogram/pages/cart/cart.ts`
- `miniprogram/pages/cart/cart.wxml`
- `miniprogram/pages/cart/cart.wxss`
- `miniprogram/pages/coupon/list/list.ts`
- `miniprogram/pages/coupon/list/list.wxml`
- `miniprogram/pages/coupon/list/list.wxss`
- `miniprogram/pages/coupon/list/list.json`
- `miniprogram/pages/coupon/mine/mine.ts`
- `miniprogram/pages/coupon/mine/mine.wxml`
- `miniprogram/pages/coupon/mine/mine.wxss`
- `miniprogram/pages/coupon/mine/mine.json`

## Self-review

- Scoped changes to `miniprogram` only; did not touch backend, admin, docs, or unrelated local files.
- Followed existing mini program service/page patterns from cart, product, and profile modules.
- Cart coupon summary degrades safely when coupon data is unavailable, without enabling checkout or blocking cart rendering.
- Did not add new test tooling; verification stays within the repo-supported `pnpm typecheck` flow from the brief.

## Concerns

- No real-device or DevTools smoke run was performed in this task, so page rendering and live API behavior still need manual verification in the mini program runtime.
- The cart summary currently shows `优惠券暂不可用` if the coupon summary API request fails; that keeps the cart usable, but the exact fallback copy may need product confirmation if a stricter UX is desired.

## Review fix: POST body for available coupons

- Updated `miniprogram/services/coupon.ts` so `getAvailableCoupons(cartItemIds?: number[])` now calls `POST /app/coupons/available` with JSON body `{ cartItemIds: cartItemIds ?? [] }`.
- Preserved the existing request helper defaults, so authenticated requests still use the standard auth behavior.
- Rechecked the cart call site in `miniprogram/pages/cart/cart.ts`: it still passes `response.items.filter((item) => item.available).map((item) => item.id)`, which matches the Task 5 plan for available cart item ids.
- Verified the service contract with a temporary local stub test before and after the change:
  - before fix: failed because the service emitted `GET /app/coupons/available?cartItemIds=...`
  - after fix: passed when asserting `POST /app/coupons/available` plus JSON body

### Typecheck output

- Command: `cd miniprogram && pnpm typecheck`
- Output:

```text
$ tsc --noEmit
```

- Exit code: `0`

---

## Task 5: Admin Asset Library, Picker, Product Wiring, And Banner Management

### Scope

- Scoped writes to `admin/**` plus this report file.
- Preserved unrelated user docs and non-admin changes already present in the worktree.

### RED evidence

1. Initial command from the brief:
   - Command: `cd admin && pnpm typecheck`
   - Result: RED because `admin/package.json` did not expose a `typecheck` script yet.
   - Output:

   ```text
   [ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL] Command "typecheck" not found
   ```

2. After adding the script and upgrading frontend API/types contracts, the expected product-editor RED appeared before the UI work was completed:
   - Command: `cd admin && pnpm typecheck`
   - Result: RED
   - Representative errors:

   ```text
   src/views/product/spu/modules/spu-editor.vue(246,14): error TS2322: Type 'string' is not assignable to type 'ProductImageForm'.
   src/views/product/spu/modules/spu-editor.vue(388,48): error TS2339: Property 'trim' does not exist on type '{ url: string; fileId?: number | null | undefined; }'.
   ```

### Implemented

- Added `admin/src/api/storage.ts` for upload, list/detail/usages, move, delete, and file-category CRUD wrappers.
- Added `admin/src/api/content.ts` for home-banner list/create/update/enable/disable wrappers.
- Expanded `admin/src/types/api/api.d.ts` with:
  - `Api.Storage`
  - `Api.Content`
  - product file-id compatibility fields for category/SPU/gallery/SKU
  - shared asset value shape `{ fileId, url }`
- Created reusable `admin/src/components/business/asset-picker/index.vue`:
  - existing asset browsing
  - purpose/category filtering
  - inline upload
  - PRIVATE asset metadata-only presentation
  - emits `{ fileId, url }`
- Created `admin/src/views/storage/files/index.vue`:
  - category sidebar
  - purpose/visibility/status filters
  - grid/list toggle
  - upload dialog
  - detail drawer with usages
  - move-category action
  - delete action with backend error surfacing
- Created `admin/src/views/content/banner/index.vue`:
  - banner table
  - create/edit drawer
  - asset-backed banner image selection
  - enable/disable actions
- Updated product category dialog for `iconFileId` + editable icon URL.
- Updated SPU editor for:
  - `mainImageFileId`
  - gallery `{ url, fileId }`
  - SKU `imageFileId`
  - rich text editor usage instead of plain textarea
- Updated WangEditor upload to use `/admin/files/upload` with `RICH_TEXT_IMAGE` by default and insert returned URL.
- Added `admin/package.json` script:
  - `"typecheck": "vue-tsc --noEmit"`

### GREEN evidence

1. Typecheck:
   - Command: `cd admin && pnpm typecheck`
   - Result: PASS
   - Output:

   ```text
   $ vue-tsc --noEmit
   ```

2. Build:
   - Command: `cd admin && CI=true pnpm build`
   - Result: PASS
   - Output summary:

   ```text
   $ vue-tsc --noEmit && vite build
   ✓ built in 14.90s
   ```

### Files changed

- `admin/package.json`
- `admin/src/api/storage.ts`
- `admin/src/api/content.ts`
- `admin/src/types/api/api.d.ts`
- `admin/src/components/business/asset-picker/index.vue`
- `admin/src/views/storage/files/index.vue`
- `admin/src/views/content/banner/index.vue`
- `admin/src/views/product/category/modules/category-dialog.vue`
- `admin/src/views/product/spu/modules/spu-editor.vue`
- `admin/src/components/core/forms/art-wang-editor/index.vue`

### Concerns

- No real browser smoke or backend-connected admin manual verification was run in this task; verification stayed within `pnpm typecheck` and `CI=true pnpm build`.
- The new storage/banner routes rely on backend-seeded dynamic menus and live APIs already being available; this task did not alter backend/menu data.

## Review fix

- Aligned `Api.Content.BannerForm` with the existing `BannerItem` contract by adding `imageUrl` and sending the trimmed URL from the banner editor payload.
- Added a visible usage status tag in the storage file detail drawer so active and historical references are distinguishable alongside the existing protected flag.
- `pnpm build` recreated `admin/node_modules` and refreshed ignored build artifacts locally, but no ignored/generated files are intended for commit.

---

## Task 5 Review Fix: Backend Shipment And WeChat Shipping Upload

### Scope

- Scoped writes to Task 5 backend shipment/upload code, Task 5 backend tests, and this report file.
- Did not change unrelated frontend or documentation files.
- Used only synthetic test values; no real WeChat certificate, key, token, openid, APIv3 key, or user-provided sensitive value was added.

### RED evidence

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminShipmentControllerTest,WechatShippingProviderTest test
```

Result: RED, expected review-blocking failures reproduced.

Summary:

```text
Tests run: 11, Failures: 2, Errors: 1, Skipped: 0
WechatShippingProviderTest.realProviderUploadsShippingInfoWithStableTokenAndOfficialOrderKey:
  No value at JSON path "$.delivery_mode"
AdminShipmentControllerTest.providerBusinessExceptionWhenUploadEnabledKeepsShipmentAndRecordsFailedUpload:
  Status expected:<200> but was:<400>
AdminShipmentControllerTest.providerRuntimeExceptionWhenUploadEnabledKeepsShipmentAndRecordsSafeFailure:
  Request processing failed because the provider runtime exception escaped
```

### Implemented

- Added `delivery_mode=1` to the official WeChat shipping upload JSON payload.
- Added RFC3339 `upload_time` using UTC upload time in the official WeChat shipping upload JSON payload.
- Added provider payload assertions for `delivery_mode` and RFC3339 `upload_time`.
- Added controller regression tests for provider `BusinessException` and runtime exception failures.
- Wrapped `wechatShippingProvider.upload(...)` inside `AdminShipmentService.refreshWechatUpload()` so provider/access-token failures become a local `FAILED` upload record instead of rolling back local shipment.
- Recorded generic safe upload failure code/message for thrown provider exceptions:
  - `WECHAT_SHIPPING_UPLOAD_FAILED`
  - `WeChat shipping upload failed`
- Preserved existing retry-count semantics: first upload failure records retry count `1`, retry API continues incrementing.
- Logged only exception class names for provider exceptions, not exception messages, payloads, access tokens, openids, tracking numbers, keys, or certificates.

### GREEN evidence

1. Focused shipment/provider tests:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminShipmentControllerTest,WechatShippingProviderTest test
```

Result:

```text
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

2. Required expanded backend slice:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminShipmentControllerTest,WechatShippingProviderTest,AdminOrderControllerTest,AppOrderControllerTest,RestWechatMiniProgramClientTest test
```

Result:

```text
Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

3. Full backend test suite:

```bash
cd backend/shop-server
./mvnw test
```

Result:

```text
Tests run: 209, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

4. Diff whitespace check:

```bash
git diff --check
```

Result: exit code `0`, no output.

5. Status check before commit:

```bash
git status --short --ignored
```

Result summary:

```text
M backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/RealWechatShippingProvider.java
M backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/AdminShipmentService.java
M backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/AdminShipmentControllerTest.java
M backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/WechatShippingProviderTest.java
ignored: .superpowers/sdd review inputs, admin generated artifacts, backend/shop-server/target/, node_modules/
```

### Files changed

- `.superpowers/sdd/task-5-report.md`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/RealWechatShippingProvider.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/AdminShipmentService.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/AdminShipmentControllerTest.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/WechatShippingProviderTest.java`

### Concerns

- `git status --short --ignored` still shows pre-existing ignored `.superpowers/sdd` review inputs and generated build/dependency directories. They were not modified for this fix except this tracked report file.
