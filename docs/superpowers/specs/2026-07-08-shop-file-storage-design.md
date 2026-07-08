# Shop File Upload And Storage Design

## Status

This spec defines the next foundation phase before WeChat Pay configuration and payment implementation.

Current state:

- Product catalog currently accepts image URLs entered by admin users.
- Art Design Pro already has upload UI examples and FormData-compatible request handling.
- The Spring Boot backend does not yet provide a file upload API, file metadata table, or storage provider abstraction.
- Payment configuration will need certificate/private-key file upload later, but that should consume a general storage foundation instead of creating a one-off payment-only uploader.

## Goal

Build a reusable file upload and storage capability that supports most Shop upload needs:

- Product main images, gallery images, SKU images, category icons, and rich text images.
- Home carousel/banner covers and future marketing images.
- Icon-like assets used by category entries, operation shortcuts, and later mini program content blocks.
- Payment certificate/public key/private key files for later payment configuration.
- After-sale/refund evidence images for later user service flows.
- Admin-only document-style files if needed later.
- A provider abstraction that starts with local storage and can be extended to OSS/COS/S3 without changing business modules.
- A lightweight asset library so admins can classify, search, preview, reuse, and understand where an uploaded file is used.

## Scope

Included in the first upload phase:

- Backend `storage` module with upload policy, metadata persistence, and provider abstraction.
- Local storage provider as the only concrete provider.
- Public and private file visibility.
- Admin upload APIs.
- Authenticated mini program upload API for future after-sale images, even if no after-sale page consumes it yet.
- Admin file category APIs, file list/detail/delete APIs, usage visibility, and asset picker support.
- Admin frontend upload components wired to product image fields and a basic file management page.
- WangEditor-compatible image upload endpoint or adapter for the existing admin editor component.
- Basic home banner management and mini program home carousel display, because the mini program home page already has a banner requirement but no dynamic banner model.
- Smoke documentation for admin image upload, private certificate upload, and mini program authenticated image upload.

Excluded from the first upload phase:

- A concrete OSS/COS/S3 implementation.
- CDN configuration.
- Image compression, resizing, watermarking, or cropping.
- Virus scanning service integration.
- Public anonymous upload.
- Direct browser upload to OSS.
- Full CMS/page-builder functionality.
- Image AI tagging or automatic semantic classification.
- Advanced DAM functions such as renditions, approval workflow, and copyright/license tracking.
- Payment configuration screens and actual WeChat Pay calls.

## Storage Model

The backend should persist a `storage_file` metadata table. Suggested fields:

- `id`: internal file id.
- `purpose`: business purpose, such as `PRODUCT_IMAGE`, `PRODUCT_SKU_IMAGE`, `CATEGORY_ICON`, `HOME_BANNER`, `MARKETING_IMAGE`, `APP_ICON`, `RICH_TEXT_IMAGE`, `PAYMENT_CERTIFICATE`, `AFTER_SALE_IMAGE`, `REFUND_EVIDENCE`, or `GENERIC_PRIVATE`.
- `asset_category_id`: optional admin-managed category/folder id for browsing assets.
- `visibility`: `PUBLIC` or `PRIVATE`.
- `provider`: initially `LOCAL`.
- `bucket`: logical bucket or namespace, optional for local storage but useful for OSS later.
- `object_key`: generated storage key, never supplied directly by clients.
- `original_filename`: sanitized original file name for admin display.
- `content_type`: detected content type.
- `extension`: normalized extension.
- `size_bytes`: file size.
- `sha256`: content digest for audit and duplicate diagnostics.
- `width`: image width when the file is an image.
- `height`: image height when the file is an image.
- `alt_text`: optional display/accessibility text for public images.
- `tags_json`: optional JSON array of short tags for admin search.
- `public_url`: present only for public files.
- `status`: `ACTIVE` or `DELETED`.
- `uploaded_by_type`: `ADMIN` or `APP`.
- `uploaded_by_id`: admin user id or app user id.
- `created_at`, `updated_at`, `deleted_at`.

Business tables should store `file_id` where possible and may keep URL snapshot fields for display compatibility. Product image fields can keep URL compatibility in the first migration while new admin UI uploads return both `fileId` and `url`.

### Asset Categories

File purpose is a strict business/validation concept. Asset category is an admin browsing concept. The first phase should support a simple category tree, for example:

- 商品图片
- 首页轮播
- 分类图标
- 小程序图标
- 富文本图片
- 运营活动
- 售后凭证
- 支付证书
- 通用素材

Suggested `storage_asset_category` fields:

- `id`, `parent_id`, `name`, `code`, `description`.
- `sort_order`, `status`.
- `created_at`, `updated_at`.

Admin users can choose a category when uploading or move a file between categories later. Purpose-specific validation still wins: a file uploaded as `PAYMENT_CERTIFICATE` must remain private even if an admin places it under a visible category.

### File Usage Relations

Add a `storage_file_usage` table so the system can show where a file is used and protect important historical assets.

Suggested fields:

- `id`.
- `file_id`.
- `usage_type`: `PRODUCT_CATEGORY_ICON`, `PRODUCT_SPU_MAIN`, `PRODUCT_SPU_GALLERY`, `PRODUCT_SKU_IMAGE`, `PRODUCT_DETAIL_HTML`, `HOME_BANNER`, `ORDER_ITEM_SNAPSHOT`, `AFTER_SALE_EVIDENCE`, `PAYMENT_CONFIG_CERT`, or `GENERIC_REFERENCE`.
- `owner_type`: logical owner such as `PRODUCT_CATEGORY`, `PRODUCT_SPU`, `PRODUCT_SKU`, `HOME_BANNER`, `ORDER_ITEM`, `AFTER_SALE`, or `PAYMENT_CONFIG`.
- `owner_id`: business record id.
- `owner_label`: denormalized label for admin display, such as product title or banner title.
- `snapshot_url`: URL snapshot used by the owner when relevant.
- `sort_order`.
- `protected`: true for order snapshots, payment configuration files, and other records that should not be physically removed by routine cleanup.
- `status`: `ACTIVE` or `REMOVED`.
- `created_at`, `updated_at`.

Usage behavior:

- Product image selection creates or updates an active usage relation.
- Replacing a product image removes the old active usage unless the file is still used elsewhere.
- Order creation keeps URL snapshots for display and should also record file usage when a source `fileId` exists.
- Files with active usage should not be hard deleted.
- Protected usages should prevent physical deletion even if the public URL snapshot remains available elsewhere.

### Order Image Snapshots

Orders must remain stable even when product images are later changed. The existing `order_item.main_image`, `order_item.sku_image`, and `order_item.display_image` URL snapshots should remain. The file upload phase should add file id snapshot columns where practical, such as:

- `main_image_file_id`
- `sku_image_file_id`
- `display_image_file_id`

If the source product image has no `fileId`, order creation continues to snapshot the URL only. If a `fileId` exists, order creation snapshots both the URL and the file id and writes `ORDER_ITEM_SNAPSHOT` usage relations.

### Home Banner Model

Home carousel images should not live only as loose files. Add a small banner/content model that references the asset library:

Suggested `home_banner` fields:

- `id`, `title`, `subtitle`.
- `image_file_id`, `image_url`.
- `jump_type`: `NONE`, `PRODUCT`, `CATEGORY`, `COUPON`, `APP_PATH`, or `URL`.
- `jump_target_id`: product/category/coupon id when applicable.
- `jump_path`: mini program path or URL when applicable.
- `status`: `ENABLED` or `DISABLED`.
- `sort_order`.
- `start_at`, `end_at`.
- `created_at`, `updated_at`.

Admin manages banners under a content/operation menu. The mini program home page fetches enabled banners and renders a `swiper` above categories and recommended products. Banner image usage should be recorded as `HOME_BANNER`.

## Provider Abstraction

Create a small storage interface with operations equivalent to:

- `put(objectKey, contentType, inputStream, sizeBytes)`.
- `delete(objectKey)`.
- `open(objectKey)` for private controlled downloads or internal reads.
- `publicUrl(objectKey)` for public files.

The local provider writes files under a configured root directory and never trusts client-supplied paths. Object keys should be generated by the backend, for example:

```text
public/product/2026/07/08/<uuid>.jpg
public/banner/2026/07/08/<uuid>.jpg
public/icon/2026/07/08/<uuid>.png
private/payment/2026/07/08/<uuid>.pem
```

Future OSS providers should implement the same interface and use the existing metadata table without changing product, payment, or after-sale services.

## Upload Policy

Upload validation must be purpose-driven:

- Product, rich text, category icon, home banner, marketing image, and app icon files: allow common web image formats only, with a small per-file limit.
- After-sale/refund evidence: allow common image formats, optionally PDF later.
- Payment certificates and keys: allow `.pem`, `.crt`, `.cer`, and `.txt` only if needed; mark as private.
- Generic private files: disabled until a concrete business use appears.

The backend must:

- Reject empty files.
- Enforce size limits before and during persistence where practical.
- Validate extension and content type.
- Read image dimensions for image files and reject corrupted images.
- Normalize file names and never use the original filename as a filesystem path.
- Prevent path traversal.
- Store private files outside any directly served public directory.
- Avoid logging file contents, APIv3 keys, private keys, access tokens, or certificate bodies.

## API Shape

Admin APIs live under `/admin/files/**` and require an admin token.

Recommended contracts:

```http
POST /admin/files/upload
Content-Type: multipart/form-data

purpose=PRODUCT_IMAGE
file=<binary>
```

Response:

```json
{
  "id": 10001,
  "purpose": "PRODUCT_IMAGE",
  "visibility": "PUBLIC",
  "originalFilename": "hotpot.jpg",
  "contentType": "image/jpeg",
  "sizeBytes": 123456,
  "sha256": "masked-or-full-digest",
  "url": "http://localhost:8080/files/public/product/2026/07/08/uuid.jpg",
  "createdAt": "2026-07-08T12:00:00"
}
```

Other APIs:

- `GET /admin/files`: paged list by purpose, asset category, visibility, status, tag, usage state, and date range.
- `GET /admin/files/{id}`: metadata detail.
- `GET /admin/files/{id}/usages`: active and historical usage locations.
- `DELETE /admin/files/{id}`: soft delete metadata and attempt provider delete.
- `GET /admin/file-categories`: category tree.
- `POST /admin/file-categories`: create category.
- `PUT /admin/file-categories/{id}`: update category.
- `POST /admin/files/{id}/move`: move a file to another asset category.
- `POST /app/files/upload`: authenticated app upload for future after-sale evidence, restricted to app-allowed purposes.
- `GET /admin/home/banners`: admin banner list.
- `POST /admin/home/banners`: create banner.
- `PUT /admin/home/banners/{id}`: update banner.
- `POST /admin/home/banners/{id}/enable`: enable banner.
- `POST /admin/home/banners/{id}/disable`: disable banner.
- `GET /app/home/banners`: mini program home carousel banners.

Public file serving may use:

- `GET /files/public/**`: serves only active public local files.

Private files should not have a public route. Business modules such as payment configuration should reference a private `fileId` and let backend services read it internally.

## Admin UX

Add a focused file management page under System or a new Storage menu:

- Upload test file by purpose.
- Browse files by asset category/folder.
- Filter by purpose, visibility, tag, uploader, provider, status, usage state, and created time.
- Preview image files in a grid/list switch.
- Show purpose, asset category, visibility, dimensions, size, uploader, provider, status, URL for public files, and created time.
- Show usage locations, such as product, SKU, banner, order snapshot, after-sale request, or payment configuration.
- Move files between categories.
- Soft delete active files.
- Block delete when a file has active protected usage, and explain which records still use it.

Add an asset picker component:

- Choose from existing files by category/purpose.
- Upload a new file inline.
- Return both `fileId` and `url` to business forms.
- Restrict selectable purposes according to the field, for example banner fields should choose `HOME_BANNER` or compatible marketing images.

Product admin pages should use the upload component for:

- Category icon.
- SPU main image.
- SPU gallery images.
- SKU image.
- Detail rich text images.

Payment configuration later should reuse private upload controls and store `fileId` references, not manual certificate paths.

Home banner management should provide:

- Banner title/subtitle.
- Banner image picker/upload.
- Jump type and target.
- Sort order.
- Enabled/disabled state.
- Optional start/end time.

The mini program home page should render banners with a `swiper`. If no enabled banners exist, it should keep a graceful static/empty fallback.

## Configuration

Initial local configuration:

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

Future OSS configuration should live under the same namespace, for example:

```yaml
shop:
  storage:
    provider: oss
    oss:
      endpoint: ${SHOP_STORAGE_OSS_ENDPOINT:}
      bucket: ${SHOP_STORAGE_OSS_BUCKET:}
      access-key-id: ${SHOP_STORAGE_OSS_ACCESS_KEY_ID:}
      access-key-secret: ${SHOP_STORAGE_OSS_ACCESS_KEY_SECRET:}
```

Do not implement the OSS provider in the first phase. Define only the extension seam and keep the configuration names stable enough for later use.

## Security And Permissions

- Admin upload/list/delete requires RBAC permissions such as `file:upload`, `file:read`, and `file:delete`.
- Asset category management requires `file:category`.
- Home banner management requires permissions such as `content:banner:read`, `content:banner:create`, `content:banner:update`, and `content:banner:publish`.
- App upload requires an app token and only allows app-safe purposes.
- Public files can be viewed without auth through the public file route.
- Private files are never directly downloadable by URL.
- Payment certificate/key files must be private.
- Delete is soft at the metadata layer and should best-effort delete the physical object only when no active/protected usage remains.
- Audit logs should record actor, purpose, file id, and action without sensitive contents.

## Testing And Smoke

Automated verification should cover:

- Schema migration for `storage_file`.
- Schema migration for `storage_asset_category`, `storage_file_usage`, and `home_banner`.
- Upload policy validation by purpose.
- Successful local public image upload.
- Successful private certificate upload.
- Asset category CRUD and file move.
- File usage relation creation when product images and banners are saved.
- Delete blocking for active/protected file usage.
- Order image file id snapshot and `ORDER_ITEM_SNAPSHOT` usage when source file ids exist.
- Mini program home banner API returns enabled banners in display order.
- Rejection of unsupported extensions, oversized files, empty files, and path traversal filenames.
- Admin RBAC enforcement.
- App upload authentication and purpose restrictions.
- Public file route serves active public files and rejects private or deleted files.
- Provider abstraction tests with a temporary local directory.

Real local smoke should cover:

- Admin uploads a product image and uses the returned URL in product editing.
- Admin classifies uploaded images into product, banner, icon, rich text, and payment certificate categories.
- Admin creates an enabled home banner with an uploaded image; mini program home renders the carousel.
- Admin opens a file detail and sees product/banner usage locations.
- Admin cannot delete a file that is still used by a product, banner, protected order snapshot, or payment configuration.
- Admin uploads a private `.pem` file and sees metadata but no public URL.
- Mini program authenticated upload succeeds for an after-sale evidence purpose.
- Deleted public files no longer serve successfully.

## Implementation Order

1. Backend storage schema, asset category schema, usage relation schema, config properties, upload policies, local provider, and tests.
2. Backend admin/app upload APIs, public file route, category APIs, usage APIs, RBAC seed data, and tests.
3. Backend home banner schema, admin banner APIs, app banner API, and tests.
4. Admin file API client, file management page, asset picker, reusable upload control, and banner management page.
5. Wire product image fields, rich text image upload, category icons, SKU images, and home banners to the new file API.
6. Add smoke documentation and run backend/admin/miniprogram verification.
