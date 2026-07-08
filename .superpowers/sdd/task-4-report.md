# Task 4 Report: Admin Order Management Page

## Scope Delivered

- Added admin order API wrapper file: `admin/src/api/order.ts`
- Extended admin API namespace with order list/detail types: `admin/src/types/api/api.d.ts`
- Added admin order management page: `admin/src/views/order/list/index.vue`

## Implementation Notes

- Built an Art Design Pro operational page with:
  - order number + status search bar
  - paginated order table via `useTable`
  - status tags and cent-to-yuan money formatting
  - detail drawer with order metadata, address/payment fields, amount summary, and item snapshot table
  - close action shown only for `CREATED` rows and guarded by `v-auth="'order:close'"`
- Kept changes scoped to admin frontend only.

## TDD / Build Discipline

- RED attempt: ran `cd admin && pnpm build` after adding initial API/types/page scaffold.
- Result: build passed immediately, so there was no practical frontend RED failure to capture from the partial scaffold in this repo state.
- GREEN: ran `cd admin && CI=true pnpm build`
- Result: passed.

## Verification Summary

- `cd admin && pnpm build` -> PASS
- `cd admin && CI=true pnpm build` -> PASS

## Fix Notes

- Reviewed follow-up correctness issue on order detail drawer stale state.
- Updated `openDetail()` in `admin/src/views/order/list/index.vue` to clear `currentDetail` before starting a new fetch.
- If detail fetch fails, `currentDetail` now remains `null`, so stale drawer content is not rendered and the footer close action cannot target a previous order id.

## Build Output Summary (Fix)

- `cd admin && CI=true pnpm build` -> PASS

## Git / Commit

- Planned commit message: `feat: add admin order page`
- Local workspace also contains unrelated generated noise from dependency recreation (`.pnpm-store/`), left untouched.

---

# Task 4 Report: Backend Home Banner APIs And App Banner Feed

## Scope Delivered

- Added backend content enums:
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/HomeBannerStatus.java`
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/HomeBannerJumpType.java`
- Added backend content DTOs/controllers/service:
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/dto/AdminHomeBannerQueryRequest.java`
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/dto/AdminHomeBannerRequest.java`
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/dto/AdminHomeBannerResponse.java`
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/dto/AppHomeBannerResponse.java`
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/AdminHomeBannerController.java`
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/AppHomeBannerController.java`
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/content/service/HomeBannerService.java`
- Added focused backend controller test:
  - `backend/shop-server/src/test/java/org/muybaby/shopserver/content/HomeBannerControllerTest.java`
- Applied the minimal shared backend tweak required for the public app feed:
  - `backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java`

## Implementation Notes

- Implemented admin banner APIs:
  - `GET /admin/home/banners`
  - `POST /admin/home/banners`
  - `PUT /admin/home/banners/{id}`
  - `POST /admin/home/banners/{id}/enable`
  - `POST /admin/home/banners/{id}/disable`
- Implemented public app banner feed:
  - `GET /app/home/banners`
- Used existing `home_banner` table from `V7__storage.sql`.
- Stored both `image_file_id` and `image_url` snapshot.
- Reused `StorageUsageService.replaceOwnerUsages(...)` on create/update with:
  - `ownerType = HOME_BANNER`
  - `usageType = HOME_BANNER`
  - `ownerLabel = banner title`
  - `snapshotUrl = banner image snapshot`
- Preserved active usage rows on disable; only create/update replaces owner usages.
- App feed returns only `ENABLED` banners inside the effective time window and sorts by `sort_order asc, id desc`.
- Update preserves the existing `image_url` snapshot when the same `imageFileId` remains attached, even if the source storage file URL changes later.
- Jump type handling implemented as:
  - `NONE` -> clears target/path
  - `PRODUCT` / `CATEGORY` / `COUPON` -> requires `jumpTargetId`, clears path
  - `APP_PATH` / `URL` -> requires nonblank `jumpPath`, clears target

## TDD Evidence

### RED

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=HomeBannerControllerTest test
```

Result:

- Failed as expected.
- Observed failures:
  - `POST /admin/home/banners` returned `404` because the content controller did not exist yet.
  - `GET /app/home/banners` returned `401` because the app banner feed had not been permitted in security config yet.

### GREEN

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=HomeBannerControllerTest test
```

Result:

- Passed
- Summary observed: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- Final Maven result: `BUILD SUCCESS`

## Focused Verification

### Home banner focused tests

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=HomeBannerControllerTest test
```

Result:

- Passed
- Summary observed: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
- Final Maven result: `BUILD SUCCESS`

### Home banner plus storage regression tests

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=HomeBannerControllerTest,StorageControllerTest test
```

Result:

- Passed
- Summary observed: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`
- Final Maven result: `BUILD SUCCESS`

## Self-Review

- Scope stayed in backend content/test files plus the one necessary security allowlist change for the public app feed.
- No frontend, mini program, docs, or unrelated backend feature code was modified.
- The implementation does not accept client-controlled storage paths or client-supplied banner URLs.
- Private storage files are not eligible banner sources because the service requires an active `PUBLIC` storage file with a nonblank `public_url`.

## Concerns

- Missing-banner updates/enables/disables currently return the shared `VALIDATION_FAILED` business error instead of a dedicated content-specific error code, because adding a new shared error enum was not required to satisfy the task contract.
