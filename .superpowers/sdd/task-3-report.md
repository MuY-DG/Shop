# Task 3 Report: Product And Order Usage Integration

## Scope

Implemented Task 3 in `backend/shop-server` to connect product/category/order data with storage usage tracking while preserving old URL-only behavior.

## RED Evidence

First RED run used:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,AppOrderControllerTest,OrderSchemaTest,StorageControllerTest test
```

Observed failure before implementation:

- `testCompile` failed because `AdminProductImageUpsertRequest` did not exist yet.
- The first compiler errors were:
  - `AppOrderControllerTest`: `cannot find symbol class AdminProductImageUpsertRequest`
  - `AppProductControllerTest`: `cannot find symbol class AdminProductImageUpsertRequest`

This confirmed the new gallery/file-id contract was not yet implemented.

## What Changed

### Product/category request and response contract

- Added nullable request fields:
  - `iconFileId`
  - `mainImageFileId`
  - gallery item `{ url, fileId }`
  - SKU `imageFileId`
- Added matching response fields on:
  - admin/app category responses
  - admin/app product detail responses
  - gallery item responses
  - admin/app SKU responses

### Backward compatibility

- Admin gallery input now accepts both:
  - legacy string arrays like `["https://.../a.jpg"]`
  - object arrays like `[{ "url": "https://.../a.jpg", "fileId": 10001 }]`
- Response shape is object-based.
- Old URL-only product/category/SKU/order data still works when file ids are null.

### Product/category storage usage sync

- Category save/update now maintains `PRODUCT_CATEGORY_ICON`.
- SPU save/update now maintains:
  - `PRODUCT_SPU_MAIN`
  - `PRODUCT_SPU_GALLERY`
  - `PRODUCT_DETAIL_HTML`
- SKU save/update now maintains `PRODUCT_SKU_IMAGE`.
- Replacements mark previous owner usages `REMOVED` and recreate current `ACTIVE` usages through `StorageUsageService`.

### Detail HTML resolution

- `detailHtml` now resolves embedded storage files by matching `storage_file.public_url`.
- Matching files create `PRODUCT_DETAIL_HTML` usages under owner type `PRODUCT_SPU`.

### Order snapshot integration

- Checkout/order item snapshot flow now carries:
  - `mainImageFileId`
  - `skuImageFileId`
  - `displayImageFileId`
- `order_item` inserts now persist these snapshot file ids when present.
- Each non-null snapshot file id creates `ORDER_ITEM_SNAPSHOT` usage with:
  - `protected = true`
  - `snapshot_url` set to the stored URL snapshot

## Test Coverage Added/Updated

- `AdminProductCategoryControllerTest`
  - category icon file-id save/update/remove usage coverage
- `AdminProductSpuControllerTest`
  - SPU main/gallery/SKU/detailHtml file usage coverage
  - legacy string gallery compatibility coverage
- `AppProductControllerTest`
  - app category/detail file-id response coverage
- `AppOrderControllerTest`
  - preview/detail/order snapshot file-id and protected usage coverage
- `OrderSchemaTest`
  - order item snapshot file-id persistence coverage

Also updated existing test helpers that construct product/category requests so the whole backend test source still compiles against the expanded DTOs.

## GREEN Verification

Final GREEN run:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,AppOrderControllerTest,OrderSchemaTest,StorageControllerTest test
```

Result:

- `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

## Notes

- I intentionally kept URL snapshots as the source-of-truth response fields for compatibility, with file ids added as nullable companions.
- I added lightweight storage-table cleanup in the touched integration tests because the in-memory DB state was persisting uploaded/seeded files across context resets and made `StorageControllerTest` flaky.

## Task 3 Review Fixes (2026-07-08)

### Review scope

Addressed two follow-up review findings:

1. Legacy admin update payloads that omit nullable file-id fields must preserve existing category/SPU/gallery/SKU file ids when the URL snapshot is unchanged.
2. `ORDER_ITEM_SNAPSHOT` protected usages must not be inserted when the snapshot URL is blank, even if the file id snapshot is still stored on `order_item`.

### RED evidence

First regression run used:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppOrderControllerTest,StorageControllerTest test
```

Initial failures after adding tests:

- `AdminProductCategoryControllerTest.legacyCategoryUpdatePreservesIconFileIdWhenUrlIsUnchanged`
  - legacy category update cleared `icon_file_id`
- `AdminProductSpuControllerTest.legacySpuUpdatePreservesMainGalleryAndSkuFileIdsWhenUrlsAreUnchanged`
  - legacy SPU update dropped `mainImageFileId` from detail response
- `AppOrderControllerTest.submitSkipsProtectedSnapshotUsageWhenSkuImageUrlIsBlank`
  - protected `ORDER_ITEM_SNAPSHOT` usage was still created for a blank SKU image snapshot URL

### Fix implementation

- `AdminProductService`
  - normalize legacy category updates before persistence so unchanged icon URLs retain the existing `icon_file_id`
  - normalize SPU updates so unchanged `main_image`, gallery URLs, and existing SKU image URLs retain their prior file ids when the request omits them
  - preserve gallery file ids by matching incoming legacy URLs against existing `product_spu_image` rows before replace/insert
- `AppOrderService`
  - skip `addProtectedUsage(...)` for `ORDER_ITEM_SNAPSHOT` when `snapshotUrl` is blank, while still persisting the file-id snapshot columns on `order_item`

### GREEN verification

Focused review-fix slice:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppOrderControllerTest,StorageControllerTest test
```

Result:

- `Tests run: 24, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

Original Task 3 focused slice:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,AppOrderControllerTest,OrderSchemaTest,StorageControllerTest test
```

Result:

- `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

## Task 3 Review Fixes (2026-07-07, omitted vs explicit-null file ids)

### Review scope

Addressed the backward-compatibility bug where update payloads treated omitted file-id fields the same as explicit JSON `null`.

Required behavior now covered:

1. Omitted `iconFileId`, `mainImageFileId`, gallery `fileId`, and SKU `imageFileId` preserve the existing file id only when the URL snapshot is unchanged.
2. Explicit JSON `null` clears the stored file id and removes active usages even when the URL snapshot is unchanged.
3. Legacy gallery string payloads still behave as omitted-fileId input; object payloads can now distinguish `fileId` omitted vs `fileId: null`.

### RED evidence

First regression run used:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppOrderControllerTest,StorageControllerTest test
```

Observed failures before the fix:

- `AdminProductCategoryControllerTest.explicitNullCategoryUpdateClearsIconFileIdWhenUrlIsUnchanged`
  - expected `icon_file_id = null`
  - actual `icon_file_id = 5`
- `AdminProductSpuControllerTest.explicitNullSpuUpdateClearsMainGalleryAndSkuFileIdsWhenUrlsAreUnchanged`
  - expected cleared file ids on unchanged URLs
  - actual `main_image_file_id = 10` and legacy-preserve logic kept the prior ids

### Fix implementation

- Converted these DTOs to presence-aware Jackson classes while keeping constructor-style usage and record-style accessors:
  - `AdminCategoryRequest`
  - `AdminSpuUpsertRequest`
  - `AdminProductImageUpsertRequest`
  - `AdminSkuUpsertRequest`
- Added presence accessors:
  - `iconFileIdSpecified()`
  - `mainImageFileIdSpecified()`
  - `fileIdSpecified()`
  - `imageFileIdSpecified()`
- `AdminProductImageUpsertRequest` still accepts both legacy strings and object payloads through a delegating `JsonNode` creator:
  - strings => `fileIdSpecified = false`
  - objects with omitted `fileId` => `fileIdSpecified = false`
  - objects with `fileId: null` => `fileIdSpecified = true`
- Updated `AdminProductService` normalization so existing file ids are preserved only when the request omitted the file-id field and the URL snapshot is unchanged.
- Added regression coverage proving explicit `null` clears:
  - category icon file id
  - SPU main image file id
  - gallery file id
  - SKU image file id

### GREEN verification

Focused regression slice:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppOrderControllerTest,StorageControllerTest test
```

Result:

- `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

Original Task 3 slice:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,AppOrderControllerTest,OrderSchemaTest,StorageControllerTest test
```

Result:

- `Tests run: 30, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

## Task 3 Review Fixes (2026-07-07, protected blank snapshot usage)

### Review scope

Adjusted `AppOrderService` so `ORDER_ITEM_SNAPSHOT` usage is created for every non-null order item snapshot file id, even when the snapshot URL is blank. The usage row still stores `defaultString(snapshotUrl)` for compatibility.

### Regression update

- Renamed the blank-URL order regression to `submitCreatesProtectedSnapshotUsageWhenSkuImageUrlIsBlank`
- Updated the assertion to expect a protected `storage_file_usage` row for the blank snapshot case instead of absence

### Verification

Focused run:

```bash
cd backend/shop-server && ./mvnw -Dtest=AppOrderControllerTest,StorageControllerTest test
```

Expanded run:

```bash
cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,AppOrderControllerTest,OrderSchemaTest,StorageControllerTest test
```

Both completed with `BUILD SUCCESS`.
