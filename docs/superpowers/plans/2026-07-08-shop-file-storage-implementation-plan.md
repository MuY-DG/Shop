# Shop File Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the local-provider file upload, asset library, usage tracking, and home banner foundation that product, mini program, after-sale, and later payment configuration can reuse.

**Architecture:** Add a backend `storage` module with purpose-driven validation, local object persistence, metadata, asset categories, and usage relations. Business modules keep URL snapshot compatibility but add nullable `file_id` references and call a narrow `StorageUsageService` so file details can show where an asset is used and delete can protect active/protected usage. Admin gets a file library plus reusable asset picker; the mini program reads dynamic home banners and keeps a graceful fallback.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Security, Flyway, MyBatis-Plus/JdbcClient, MySQL/H2, Vue 3, TypeScript, Element Plus, Art Design Pro, native WeChat mini program TypeScript.

## Global Constraints

- Current repo state must be checked first; preserve existing changes in `docs/superpowers/specs/2026-07-08-shop-file-storage-design.md`, `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`, and `docs/dev-setup.md`.
- Do not implement WeChat Pay, payment configuration screens, payment callbacks, refund calls, or any concrete OSS/COS/S3 provider in this phase.
- Payment configuration later must reuse this phase's `PRIVATE` file capability.
- Implement only a local storage provider, while keeping an extension interface for OSS/COS/S3.
- Support `PUBLIC` and `PRIVATE`; `PUBLIC` is served only through controlled `GET /files/public/**`; `PRIVATE` has no public URL.
- Supported purposes must include `PRODUCT_IMAGE`, `PRODUCT_SKU_IMAGE`, `CATEGORY_ICON`, `HOME_BANNER`, `MARKETING_IMAGE`, `APP_ICON`, `RICH_TEXT_IMAGE`, `PAYMENT_CERTIFICATE`, `AFTER_SALE_IMAGE`, and `REFUND_EVIDENCE`.
- Seed asset categories: 商品图片, 首页轮播, 分类图标, 小程序图标, 富文本图片, 运营活动, 售后凭证, 支付证书, 通用素材.
- Record usage for product category icon, SPU main image, SPU gallery, SKU image, rich text, home banner, order item snapshot, after-sale evidence, and payment configuration certificate.
- Files with active usage cannot be physically deleted; protected usage such as order snapshots and payment configuration files must block deletion.
- Order items keep URL snapshots and add nullable `main_image_file_id`, `sku_image_file_id`, and `display_image_file_id`; old URL-only data remains valid.
- Home banners use `home_banner.image_file_id` plus `image_url` snapshot and support sorting, enable/disable, jump type, and effective time.
- API envelope remains `{ code, msg, data }`; admin page results remain `{ records, total, current, size }`.
- Do not allow public anonymous upload.
- Do not log file contents, certificates, private keys, APIv3 keys, tokens, login codes, phone codes, or client-supplied storage paths.
- Do not let clients control real storage paths or object keys.
- Automated acceptance commands: `cd backend/shop-server && ./mvnw test`, `cd admin && pnpm typecheck`, `cd admin && CI=true pnpm build`, `cd miniprogram && pnpm typecheck`, `git status --short --ignored`.
- Real local smoke must be reported separately from automated tests/builds and must include the smoke checklist in the final task.

---

## File Structure

- Create `backend/shop-server/src/main/resources/db/migration/V7__storage.sql`: storage tables, product/order nullable file-id columns, home banner table, RBAC menu/permission seeds.
- Create `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/**`: storage enums, properties, provider interface, local provider, upload policy, DTOs, controllers, service, usage service.
- Create `backend/shop-server/src/main/java/org/muybaby/shopserver/content/**`: home banner DTOs, service, admin/app controllers.
- Modify `backend/shop-server/src/main/resources/application.yaml` and `backend/shop-server/src/test/resources/application-test.yaml`: add `shop.storage` local defaults and test temp root.
- Modify `backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java`: permit `GET /files/public/**` and keep upload endpoints authenticated.
- Modify product DTOs/services/controllers under `backend/shop-server/src/main/java/org/muybaby/shopserver/product/**`: add nullable file-id fields and storage usage writes.
- Modify order DTOs/service under `backend/shop-server/src/main/java/org/muybaby/shopserver/order/**`: snapshot file ids and protected `ORDER_ITEM_SNAPSHOT` usages.
- Create backend tests under `backend/shop-server/src/test/java/org/muybaby/shopserver/storage/**` and `backend/shop-server/src/test/java/org/muybaby/shopserver/content/**`; update affected product/order/security/RBAC tests.
- Create `admin/src/api/storage.ts`, `admin/src/api/content.ts`, and add `Api.Storage`/`Api.Content` types in `admin/src/types/api/api.d.ts`.
- Create `admin/src/components/business/asset-picker/index.vue`.
- Create `admin/src/views/storage/files/index.vue` and `admin/src/views/content/banner/index.vue`.
- Modify `admin/src/views/product/category/modules/category-dialog.vue`, `admin/src/views/product/spu/modules/spu-editor.vue`, and `admin/src/components/core/forms/art-wang-editor/index.vue` to use storage upload/picker.
- Create `miniprogram/services/home.ts` and `miniprogram/services/storage.ts`; modify `miniprogram/types/api.ts` and `miniprogram/pages/home/home.*`.
- Modify `docs/dev-setup.md` and `docs/smoke-checks.md` with local storage config and real local smoke commands.

---

### Task 1: Backend Storage Schema, Provider, And Upload Policy

**Files:**
- Create: `backend/shop-server/src/main/resources/db/migration/V7__storage.sql`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StoragePurpose.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/FileVisibility.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageProviderKind.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageFileStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageUsageStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageFileUsageType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageUsageOwnerType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/UploadedByType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageProperties.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/provider/StorageProvider.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/provider/StoredObject.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/provider/LocalStorageProvider.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/service/UploadPolicy.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/service/StorageObjectKeyGenerator.java`
- Modify: `backend/shop-server/src/main/resources/application.yaml`
- Modify: `backend/shop-server/src/test/resources/application-test.yaml`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/storage/StorageSchemaTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/storage/UploadPolicyTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/storage/LocalStorageProviderTest.java`

**Interfaces:**
- Produces: `StorageProvider.put(String objectKey, String contentType, InputStream inputStream, long sizeBytes)`, `StorageProvider.open(String objectKey)`, `StorageProvider.delete(String objectKey)`.
- Produces: `UploadPolicy.requireAllowed(StoragePurpose purpose, String originalFilename, String contentType, long sizeBytes, boolean imageReadable)`.
- Produces: `StorageObjectKeyGenerator.nextKey(StoragePurpose purpose, String extension, LocalDate date)`.
- Produces DB tables/columns later tasks rely on: `storage_file`, `storage_asset_category`, `storage_file_usage`, `home_banner`, product file-id columns, order item file-id columns.

- [ ] **Step 1: Write failing schema and policy tests**

Add tests that assert all new tables/columns exist, category seeds exist, enum policies match the spec, private purposes force `PRIVATE`, invalid extension/empty/oversized inputs fail, and generated keys never contain the original filename.

```java
@Test
void storageMigrationCreatesTablesAndSeedsCategories() {
    assertThat(jdbcClient.sql("select count(*) from storage_asset_category")
            .query(Integer.class)
            .single()).isGreaterThanOrEqualTo(9);
    assertThat(jdbcClient.sql("select count(*) from information_schema.columns where table_name = 'order_item' and column_name = 'display_image_file_id'")
            .query(Integer.class)
            .single()).isEqualTo(1);
}
```

Run: `cd backend/shop-server && ./mvnw -Dtest=StorageSchemaTest,UploadPolicyTest,LocalStorageProviderTest test`

Expected: fail because classes and `V7__storage.sql` do not exist.

- [ ] **Step 2: Add schema and configuration**

Implement `V7__storage.sql` with:

```sql
CREATE TABLE storage_file (...);
CREATE TABLE storage_asset_category (...);
CREATE TABLE storage_file_usage (...);
CREATE TABLE home_banner (...);
ALTER TABLE product_category ADD COLUMN icon_file_id BIGINT NULL;
ALTER TABLE product_spu ADD COLUMN main_image_file_id BIGINT NULL;
ALTER TABLE product_spu_image ADD COLUMN file_id BIGINT NULL;
ALTER TABLE product_sku ADD COLUMN image_file_id BIGINT NULL;
ALTER TABLE order_item ADD COLUMN main_image_file_id BIGINT NULL;
ALTER TABLE order_item ADD COLUMN sku_image_file_id BIGINT NULL;
ALTER TABLE order_item ADD COLUMN display_image_file_id BIGINT NULL;
```

Seed RBAC permissions `file:upload`, `file:read`, `file:delete`, `file:category`, `content:banner:read`, `content:banner:create`, `content:banner:update`, and `content:banner:publish`; seed menu routes `/storage/files` and `/content/banner`.

Add `shop.storage` defaults to application config:

```yaml
shop:
  storage:
    provider: local
    public-base-url: ${SHOP_STORAGE_PUBLIC_BASE_URL:http://localhost:8080}
    local:
      root: ${SHOP_STORAGE_LOCAL_ROOT:var/uploads}
    limits:
      image-max-size: ${SHOP_STORAGE_IMAGE_MAX_SIZE:5MB}
      private-file-max-size: ${SHOP_STORAGE_PRIVATE_FILE_MAX_SIZE:1MB}
```

- [ ] **Step 3: Implement provider and upload policy**

Implement local provider using `Path.normalize()` and an explicit root-prefix check before reads/writes/deletes. Implement policy so image purposes allow `jpg`, `jpeg`, `png`, `webp`, and `gif`; certificate purposes allow `pem`, `crt`, `cer`, and `txt`; empty files are rejected; `PAYMENT_CERTIFICATE` is always private.

- [ ] **Step 4: Run focused backend tests**

Run: `cd backend/shop-server && ./mvnw -Dtest=StorageSchemaTest,UploadPolicyTest,LocalStorageProviderTest test`

Expected: pass.

- [ ] **Step 5: Review and commit task**

Review diff for schema drift, unsafe paths, and sensitive logging. Commit: `git add backend/shop-server/src/main/resources/db/migration/V7__storage.sql backend/shop-server/src/main/java/org/muybaby/shopserver/storage backend/shop-server/src/main/resources/application.yaml backend/shop-server/src/test/resources/application-test.yaml backend/shop-server/src/test/java/org/muybaby/shopserver/storage backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java && git commit -m "feat: add storage schema and local provider"`.

---

### Task 2: Backend Storage APIs, Public File Route, Categories, And Delete Protection

**Files:**
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/AdminFileController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/AppFileController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/PublicFileController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/AdminFileCategoryController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/dto/*.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/service/StorageService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/service/StorageUsageService.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/storage/StorageControllerTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/admin/rbac/AdminRbacSchemaTest.java`

**Interfaces:**
- Consumes: Task 1 `UploadPolicy`, `StorageProvider`, schema, properties.
- Produces: `StorageFileResponse`, `StorageFileUsageResponse`, `StorageAssetCategoryResponse`.
- Produces: `StorageService.uploadAdmin(...)`, `StorageService.uploadApp(...)`, `StorageService.page(...)`, `StorageService.detail(...)`, `StorageService.usages(...)`, `StorageService.move(...)`, `StorageService.delete(...)`, `StorageService.publicResource(...)`.
- Produces: `StorageUsageService.replaceOwnerUsages(...)`, `StorageUsageService.addProtectedUsage(...)`, `StorageUsageService.removeOwnerUsages(...)` for later product/order/banner tasks.

- [ ] **Step 1: Write failing controller tests**

Cover admin upload, app upload with app token, app upload purpose restrictions, category tree CRUD, move file, list/detail/usages, public file serving, private file no public URL, delete blocked by active/protected usage, unsupported extension, oversize, empty file, corrupted image, and path traversal filename rejection.

```java
mockMvc.perform(multipart("/admin/files/upload")
        .file(new MockMultipartFile("file", "../hotpot.jpg", "image/jpeg", tinyJpegBytes()))
        .param("purpose", "PRODUCT_IMAGE")
        .header("Authorization", "Bearer " + adminToken))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
    .andExpect(jsonPath("$.data.url").value(startsWith("http://localhost:8080/files/public/")));
```

Run: `cd backend/shop-server && ./mvnw -Dtest=StorageControllerTest,SecurityConfigTest,AdminRbacSchemaTest test`

Expected: fail because controllers/services are missing.

- [ ] **Step 2: Implement upload and metadata APIs**

Implement:

```text
POST /admin/files/upload
GET /admin/files
GET /admin/files/{id}
GET /admin/files/{id}/usages
POST /admin/files/{id}/move
DELETE /admin/files/{id}
GET /admin/file-categories
POST /admin/file-categories
PUT /admin/file-categories/{id}
POST /app/files/upload
GET /files/public/**
```

Return no `url`/`publicUrl` for private files. App upload only accepts `AFTER_SALE_IMAGE` and `REFUND_EVIDENCE`. `GET /files/public/**` serves only active `PUBLIC` rows and uses provider `open()`.

- [ ] **Step 3: Implement delete protection and usage service**

Soft-delete metadata by setting `storage_file.status = 'DELETED'` and `deleted_at`. If any `storage_file_usage.status = 'ACTIVE'` row exists, reject delete with `STORAGE_FILE_IN_USE`. If usage is protected, include a safe owner label/type in the detail response but never physical-delete the object.

- [ ] **Step 4: Run focused tests**

Run: `cd backend/shop-server && ./mvnw -Dtest=StorageControllerTest,SecurityConfigTest,AdminRbacSchemaTest test`

Expected: pass.

- [ ] **Step 5: Review and commit task**

Review for path traversal, auth separation, private URL leakage, and logs. Commit: `git add backend/shop-server/src/main/java/org/muybaby/shopserver/storage backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java backend/shop-server/src/test/java/org/muybaby/shopserver/storage backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java backend/shop-server/src/test/java/org/muybaby/shopserver/admin/rbac/AdminRbacSchemaTest.java && git commit -m "feat: add storage upload and asset APIs"`.

---

### Task 3: Product And Order Usage Integration

**Files:**
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminCategoryRequest.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminCategoryResponse.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AppCategoryResponse.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminSpuUpsertRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminProductImageUpsertRequest.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/ProductImageResponse.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminSkuUpsertRequest.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminSkuResponse.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AppSkuResponse.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/service/AdminProductService.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/service/ProductReadMapper.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/service/AppProductService.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderPreviewItemResponse.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderItemResponse.java`
- Test: update `backend/shop-server/src/test/java/org/muybaby/shopserver/product/AdminProductCategoryControllerTest.java`
- Test: update `backend/shop-server/src/test/java/org/muybaby/shopserver/product/AdminProductSpuControllerTest.java`
- Test: update `backend/shop-server/src/test/java/org/muybaby/shopserver/product/AppProductControllerTest.java`
- Test: update `backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java`
- Test: update `backend/shop-server/src/test/java/org/muybaby/shopserver/order/OrderSchemaTest.java`

**Interfaces:**
- Consumes: Task 2 `StorageUsageService`.
- Produces request fields: `iconFileId`, `mainImageFileId`, gallery objects `{ url, fileId }`, and SKU `imageFileId`.
- Produces response fields: `iconFileId`, `mainImageFileId`, gallery `fileId`, SKU `imageFileId`, order item file-id snapshots.

- [ ] **Step 1: Write failing product/order tests**

Add tests that save a product category icon, SPU main image, gallery image, SKU image, and detail HTML image using file ids; assert `storage_file_usage` contains `PRODUCT_CATEGORY_ICON`, `PRODUCT_SPU_MAIN`, `PRODUCT_SPU_GALLERY`, `PRODUCT_SKU_IMAGE`, and `PRODUCT_DETAIL_HTML`. Submit an order from a product with file ids and assert `order_item.*_file_id` plus protected `ORDER_ITEM_SNAPSHOT` usages.

```java
assertThat(jdbcClient.sql("""
        select count(*)
        from storage_file_usage
        where file_id = :fileId
          and usage_type = 'PRODUCT_SPU_MAIN'
          and owner_type = 'PRODUCT_SPU'
          and owner_id = :spuId
          and status = 'ACTIVE'
        """)
    .param("fileId", mainImageFileId)
    .param("spuId", spuId)
    .query(Integer.class)
    .single()).isEqualTo(1);
```

Run: `cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,AppOrderControllerTest,OrderSchemaTest test`

Expected: fail because product/order DTOs and services do not handle file ids.

- [ ] **Step 2: Add nullable file-id request/response support**

Allow product/category requests to send both URL snapshots and nullable file ids. For gallery, move the admin request to an object list:

```json
"images": [
  { "url": "http://localhost:8080/files/public/product/2026/07/08/a.jpg", "fileId": 10001 }
]
```

Update existing tests and docs that used string-only gallery entries. App product responses can expose file ids but must keep URL fields unchanged for old clients.

- [ ] **Step 3: Maintain active usage relations in product transactions**

When category/product is saved, call `StorageUsageService.replaceOwnerUsages(...)` after DB writes. Mark old owner usages `REMOVED`, then add active rows for the current file ids. If `detailHtml` contains public storage URLs, resolve file ids by `public_url` and record `PRODUCT_DETAIL_HTML` with owner type `PRODUCT_SPU`.

- [ ] **Step 4: Snapshot order item file ids and protected usage**

Extend checkout query to select `product_spu.main_image_file_id` and `product_sku.image_file_id`. Insert `order_item.main_image_file_id`, `sku_image_file_id`, and `display_image_file_id`. For each non-null display/main/sku file id, add `ORDER_ITEM_SNAPSHOT` usage with `protected = true` and `snapshot_url` set to the URL snapshot.

- [ ] **Step 5: Run focused tests**

Run: `cd backend/shop-server && ./mvnw -Dtest=AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,AppOrderControllerTest,OrderSchemaTest,StorageControllerTest test`

Expected: pass.

- [ ] **Step 6: Review and commit task**

Review for old URL-only compatibility, duplicate usage rows, and transaction boundaries. Commit: `git add backend/shop-server/src/main/java/org/muybaby/shopserver/product backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/product backend/shop-server/src/test/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/storage && git commit -m "feat: track product and order file usage"`.

---

### Task 4: Backend Home Banner APIs And App Banner Feed

**Files:**
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/content/HomeBannerStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/content/HomeBannerJumpType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/content/AdminHomeBannerController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/content/AppHomeBannerController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/content/service/HomeBannerService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/content/dto/*.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/content/HomeBannerControllerTest.java`

**Interfaces:**
- Consumes: `home_banner` table and `StorageUsageService`.
- Produces: `GET /admin/home/banners`, `POST /admin/home/banners`, `PUT /admin/home/banners/{id}`, `POST /admin/home/banners/{id}/enable`, `POST /admin/home/banners/{id}/disable`, and `GET /app/home/banners`.

- [ ] **Step 1: Write failing banner tests**

Cover create/update/list, enable/disable, effective time filtering, sort order, app response only enabled/current banners, image URL snapshot retention, and `HOME_BANNER` usage updates.

```java
mockMvc.perform(get("/app/home/banners"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data[0].title").value("首页热卖"))
    .andExpect(jsonPath("$.data[0].imageUrl").value(publicUrl));
```

Run: `cd backend/shop-server && ./mvnw -Dtest=HomeBannerControllerTest test`

Expected: fail because content module is missing.

- [ ] **Step 2: Implement banner service/controllers**

Store `image_file_id` and `image_url` snapshot. Validate jump types: `NONE` needs no target, `PRODUCT`/`CATEGORY` use `jumpTargetId`, and `APP_PATH`/`URL` use `jumpPath`. Use `StorageUsageService.replaceOwnerUsages(HOME_BANNER, bannerId, ...)` on create/update and remove usage on disable only when image is replaced or banner deleted later.

- [ ] **Step 3: Run focused tests**

Run: `cd backend/shop-server && ./mvnw -Dtest=HomeBannerControllerTest,StorageControllerTest test`

Expected: pass.

- [ ] **Step 4: Review and commit task**

Review app/public exposure, time filtering, and usage owner labels. Commit: `git add backend/shop-server/src/main/java/org/muybaby/shopserver/content backend/shop-server/src/test/java/org/muybaby/shopserver/content && git commit -m "feat: add home banner APIs"`.

---

### Task 5: Admin File Library, Asset Picker, Product Wiring, And Banner Management

**Files:**
- Create: `admin/src/api/storage.ts`
- Create: `admin/src/api/content.ts`
- Modify: `admin/src/types/api/api.d.ts`
- Create: `admin/src/components/business/asset-picker/index.vue`
- Create: `admin/src/views/storage/files/index.vue`
- Create: `admin/src/views/content/banner/index.vue`
- Modify: `admin/src/views/product/category/modules/category-dialog.vue`
- Modify: `admin/src/views/product/spu/modules/spu-editor.vue`
- Modify: `admin/src/components/core/forms/art-wang-editor/index.vue`

**Interfaces:**
- Consumes: Task 2 storage APIs and Task 4 banner APIs.
- Produces: Reusable asset picker that returns `{ fileId, url }`.
- Produces: Admin pages reachable from backend menus `/storage/files` and `/content/banner`.

- [ ] **Step 1: Add typecheck-first frontend contracts**

Add TypeScript types and API modules first, then run:

Run: `cd admin && pnpm typecheck`

Expected: fail until the pages/components use the new contracts correctly.

- [ ] **Step 2: Implement storage API client and asset picker**

`asset-picker` must support browsing existing files, filtering by purpose/category, inline upload with `FormData`, preview, and emit:

```ts
interface AssetPickValue {
  fileId: number | null
  url: string
}
```

For private purposes, show metadata and file id but no image preview URL.

- [ ] **Step 3: Implement file library page**

Build a work-focused management page with category tree, filters, grid/list display, upload dialog, file detail drawer, usage list, move category action, and delete action that surfaces backend in-use errors.

- [ ] **Step 4: Wire product and editor forms**

Replace manual URL-only inputs with asset picker controls for category icon, SPU main image, gallery images, SKU image, and WangEditor image upload. Keep URL values visible/editable enough for old URL-only data, but newly picked/uploaded assets must submit file ids.

- [ ] **Step 5: Implement banner management page**

Add create/edit drawer with title, subtitle, image picker, jump type, target/path, sort order, enabled/disabled state, start/end time, and table actions for enable/disable.

- [ ] **Step 6: Run admin verification**

Run: `cd admin && pnpm typecheck`

Expected: pass.

Run: `cd admin && CI=true pnpm build`

Expected: pass.

- [ ] **Step 7: Review and commit task**

Review for FormData handling, PRIVATE URL display, text overflow, nested cards, and route component paths. Commit: `git add admin/src/api/storage.ts admin/src/api/content.ts admin/src/types/api/api.d.ts admin/src/components/business/asset-picker admin/src/views/storage/files admin/src/views/content/banner admin/src/views/product admin/src/components/core/forms/art-wang-editor && git commit -m "feat: add admin asset library and picker"`.

---

### Task 6: Mini Program Home Banner Display And Authenticated App Upload Helper

**Files:**
- Create: `miniprogram/services/home.ts`
- Create: `miniprogram/services/storage.ts`
- Modify: `miniprogram/types/api.ts`
- Modify: `miniprogram/pages/home/home.ts`
- Modify: `miniprogram/pages/home/home.wxml`
- Modify: `miniprogram/pages/home/home.wxss`

**Interfaces:**
- Consumes: `GET /app/home/banners` and `POST /app/files/upload`.
- Produces: Home `swiper` banner view and `uploadEvidenceFile(filePath, purpose)` helper for later after-sale pages.

- [ ] **Step 1: Add typecheck-first mini program service contracts**

Add `HomeBanner`, `StorageFileUploadResponse`, and service function signatures, then run:

Run: `cd miniprogram && pnpm typecheck`

Expected: fail until implementation is complete.

- [ ] **Step 2: Implement banner fetch and click routing**

Load banners in parallel with categories and products. Display `swiper` only when banners exist; otherwise keep the current hero fallback. Click rules:

```text
NONE -> no action
PRODUCT -> wx.navigateTo('/pages/product/detail/detail?id=...')
CATEGORY -> wx.reLaunch('/pages/product/list/list?categoryId=...')
APP_PATH -> wx.navigateTo(jumpPath) unless it is a tab path, then wx.switchTab
URL/COUPON -> no navigation in this phase
```

- [ ] **Step 3: Implement authenticated app upload helper**

Use `wx.uploadFile` with `Authorization: Bearer ${token}` and fields `purpose=AFTER_SALE_IMAGE` or `purpose=REFUND_EVIDENCE`. Do not expose path control to the caller beyond the local `filePath`.

- [ ] **Step 4: Run mini program typecheck**

Run: `cd miniprogram && pnpm typecheck`

Expected: pass.

- [ ] **Step 5: Review and commit task**

Review fallback behavior, URL handling, and token upload header. Commit: `git add miniprogram/services/home.ts miniprogram/services/storage.ts miniprogram/types/api.ts miniprogram/pages/home && git commit -m "feat: show home banners in mini program"`.

---

### Task 7: Smoke Documentation, Full Verification, And Final Review

**Files:**
- Modify: `docs/dev-setup.md`
- Modify: `docs/smoke-checks.md`
- Optionally modify: `docs/superpowers/specs/2026-07-08-shop-file-storage-design.md` only if implementation clarifies a contract without changing scope.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: concrete smoke checklist and final verification evidence.

- [ ] **Step 1: Update docs with storage smoke commands**

Add a `File Storage And Home Banner Smoke Checks` section that includes:

```text
Admin uploads product image and uses it in product editing.
Admin uploads and categorizes a home banner image; mini program home swiper displays it.
Admin uploads category icon; mini program home/category entry displays it.
Admin file detail shows product and banner usages.
Files referenced by product, banner, order snapshot, or payment config cannot be deleted.
Admin uploads private .pem and sees metadata but no public URL.
Mini program app-token upload succeeds for AFTER_SALE_IMAGE/REFUND_EVIDENCE.
Illegal extension, oversize, empty file, and path traversal filename are rejected.
```

- [ ] **Step 2: Run backend full suite**

Run: `cd backend/shop-server && ./mvnw test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run admin typecheck and build**

Run: `cd admin && pnpm typecheck`

Expected: pass.

Run: `cd admin && CI=true pnpm build`

Expected: pass.

- [ ] **Step 4: Run mini program typecheck**

Run: `cd miniprogram && pnpm typecheck`

Expected: pass.

- [ ] **Step 5: Check git status**

Run: `git status --short --ignored`

Expected: tracked changes are limited to this phase plus the pre-existing docs changes; ignored local artifacts may remain.

- [ ] **Step 6: Final code review**

Dispatch a final reviewer with the full branch diff. Critical and Important findings must be fixed and re-reviewed before claiming completion.

- [ ] **Step 7: Commit docs and final fixes**

Commit: `git add docs/dev-setup.md docs/smoke-checks.md && git commit -m "docs: add file storage smoke checks"` if docs are the only remaining tracked changes; otherwise include reviewed final fix files in a precise commit.

---

## Self-Review

- Spec coverage: The plan covers local provider, upload policy, public/private visibility, all required purposes, asset categories, usage tracking, protected delete, product and order file ids, home banners, admin asset library, asset picker, mini program banner display, app upload helper, RBAC seeds, docs, and verification.
- Scope exclusions: The plan explicitly excludes concrete OSS/COS/S3, CDN, image processing, watermark/crop, virus scanning, public anonymous upload, CMS/page builder, payment configuration UI, and WeChat Pay.
- Placeholder scan: No `TBD`, `TODO`, or "similar to" placeholders remain; task steps name concrete files, interfaces, commands, and expected outcomes.
- Type consistency: `fileId` is nullable in business DTOs, URL snapshots remain strings, usage statuses are `ACTIVE`/`REMOVED`, file statuses are `ACTIVE`/`DELETED`, and banner statuses are `ENABLED`/`DISABLED`.
- Execution note: The user explicitly requested Subagent-Driven execution immediately after plan creation, so do not ask for an execution-mode choice; begin `superpowers:subagent-driven-development` after this plan is saved.
