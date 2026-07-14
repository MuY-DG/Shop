# Shop Asset Management Redesign

Date: 2026-07-13

## 1. Goal

Replace the current purpose-plus-category file model with a clean asset model in which:

- Reusable public images and videos live in one admin asset library.
- Human-managed folders are the only manual organization mechanism.
- Product image, SKU image, banner, and similar roles are recorded only as actual usages.
- After-sale evidence remains a private business attachment owned by the after-sale workflow.
- Payment keys and certificates remain private secret files owned by payment configuration.
- Upload policy is selected by the server endpoint and is not persisted as the lifetime identity of a file.

Existing storage object-provider configuration, public URL behavior, Product V2 media fields, and usage protection remain supported.

## 2. Existing Problem

The current `StoragePurpose` value mixes six independent concerns:

- Business upload screen.
- Media kind.
- Public or private visibility.
- File-extension and size policy.
- Object-key path segment.
- Default asset category.

`storage_asset_category` then repeats many of the same business labels. A third vocabulary, `storage_file_usage.usage_type`, records where the file is actually used. This produces duplicated filters, contradictory combinations, and a picker that prevents reuse based on the file's original upload screen rather than its technical compatibility.

The redesign removes persisted purpose and separates the stable concepts.

## 3. Domain Model

### 3.1 Asset scope

`StorageAssetScope` contains:

- `LIBRARY`: reusable public admin media.
- `ATTACHMENT`: private business evidence or attachments.
- `SECRET`: private credentials, keys, and certificates.

Scope controls discoverability and lifecycle. Only `LIBRARY` assets are returned from the admin asset-library APIs.

### 3.2 Media kind

`StorageMediaKind` contains:

- `IMAGE`
- `VIDEO`
- `DOCUMENT`

Media kind controls compatible pickers and technical validation. It does not describe where an asset is used.

### 3.3 Folder

`storage_asset_folder` is a human-managed tree for `LIBRARY` assets only.

- Folders do not have business-purpose codes.
- A library asset may be ungrouped.
- Selecting a folder in the library includes descendants by default.
- Disabled folders cannot receive new uploads or moves.
- A non-empty folder cannot be deleted.
- API root folders use `parentId = 0`; the database stores their `parent_id` as `NULL`.
- The database enforces parent existence and prevents duplicate names under the same parent.
- Structural changes lock a singleton folder-tree guard before validating and writing, so self-parenting, cycles, and concurrent delete-check bypasses are rejected by the service.

### 3.4 Usage

`storage_asset_usage` is the source of truth for actual business use.

Examples include:

- `PRODUCT_SPU_MAIN`
- `PRODUCT_SPU_GALLERY`
- `PRODUCT_SPU_VIDEO`
- `PRODUCT_SKU_IMAGE`
- `PRODUCT_SPEC_VALUE_IMAGE`
- `PRODUCT_CATEGORY_ICON`
- `HOME_BANNER`
- `AFTER_SALE_EVIDENCE`
- `PAYMENT_CONFIG_CERT`

The same compatible library image may be referenced by multiple owners and roles. Upload history never limits reuse.

### 3.5 Upload profiles

`StorageUploadProfile` is a server-side transient policy and is not stored on the asset row.

Profiles are:

- `LIBRARY_IMAGE`: `LIBRARY + IMAGE + PUBLIC`.
- `LIBRARY_VIDEO`: `LIBRARY + VIDEO + PUBLIC`.
- `AFTER_SALE_EVIDENCE`: `ATTACHMENT + IMAGE + PRIVATE`.
- `PAYMENT_SECRET`: `SECRET + DOCUMENT + PRIVATE`.

The calling controller chooses the profile. Clients cannot submit an arbitrary profile, scope, or visibility.

## 4. Database Contract

### `storage_asset`

Required columns:

- `id`
- `scope`
- `media_kind`
- `folder_id`, nullable and allowed only for `LIBRARY`
- `visibility`
- `provider`
- `storage_container`, the normalized LOCAL root or Tencent COS bucket recorded at upload time
- `storage_region`, recorded for Tencent COS and empty for LOCAL
- `object_key`
- `original_filename`
- `content_type`
- `extension`
- `size_bytes`
- `sha256`
- `width`, `height`, and `duration_seconds` where applicable
- `alt_text` and `tags_json`
- `public_url`
- `status`
- `uploaded_by_type` and `uploaded_by_id`
- `upload_context_type` and `upload_context_id`, nullable and used only to validate temporary domain uploads
- `expires_at`, nullable; scheduled cleanup considers only expired `ATTACHMENT`/`SECRET` assets that have neither an active usage nor an active direct payment-config reference
- `cleanup_attempts`, `cleanup_next_retry_at`, and `cleanup_lease_token`, used for token-owned, backoff-based physical deletion retries
- timestamps

### `storage_asset_folder_guard`

This table contains exactly one row, `id = 1`. Folder create, move, disable, and delete flows lock that row before validating or changing the tree, giving every structural mutation the same database lock order.

### `storage_asset_folder`

Required columns:

- `id`
- `parent_id`, nullable in storage and exposed as `0` for roots through the API
- `parent_key`, a generated value used to enforce sibling-name uniqueness including root folders
- `name`
- `sort_order`
- `status`
- timestamps

### `storage_asset_usage`

The existing usage contract is retained with `asset_id` replacing `file_id`. Active usages protect assets from deletion. Historical snapshot usages remain protected.

## 5. API Contract

### 5.1 Asset library

```text
GET    /admin/assets
POST   /admin/assets/upload
GET    /admin/assets/{assetId}
DELETE /admin/assets/{assetId}
POST   /admin/assets/{assetId}/move

GET    /admin/asset-folders
POST   /admin/asset-folders
PUT    /admin/asset-folders/{folderId}
DELETE /admin/asset-folders/{folderId}
```

The asset list always enforces `scope = LIBRARY` and `status = ACTIVE` unless a dedicated recycle-bin contract is added later.

List filters are:

- Filename keyword.
- Media kind.
- Folder, including descendants.
- Referenced or unreferenced.
- Created date range.

Admin upload accepts only `file` and optional `folderId`. The backend detects whether the file is an image or video from the extension/content-type pair and applies the matching library profile.

### 5.2 After-sale evidence

The generic `/app/files/upload` endpoint is removed.

The current UI uploads evidence before submitting the after-sale application. This phase uses an order-scoped temporary evidence endpoint:

```text
POST /app/orders/{orderId}/after-sale-evidence
```

The endpoint verifies APP authentication and order ownership, stores a private attachment, and returns its asset ID. Submitting the after-sale application verifies that every supplied asset belongs to the same authenticated user and order before creating protected usages.

Unclaimed temporary evidence is not shown in the asset library. It expires after 24 hours and is eligible for the scheduled retention cleanup only while it has no active usage.

### 5.3 Payment secret files

Payment secret files use a payment-owned endpoint:

```text
POST /admin/pay/configs/secret-files
```

The endpoint requires `payment:config:write`, stores a `SECRET + DOCUMENT + PRIVATE` asset, and returns its ID plus a two-hour staging expiry. Payment config save locks and validates the staged secret before establishing protected usages and clearing its expiry. Payment-config `PUT` treats file-ID fields as a full replacement: the private key and PUBLIC_KEY-mode WeChat public key remain required, while the merchant certificate is optional and an explicit `null` clears it. Replaced or explicitly cleared secrets receive a 24-hour release window after their last active payment reference disappears.

Secret files are never returned from the asset-library list or detail endpoints.

## 6. Admin Information Architecture

The visible menu title becomes `素材库`.

The library contains:

- A folder tree with create, rename, disable, and delete actions.
- `全部素材` and `未分组` virtual nodes.
- Image/video, filename, reference-state, and date filters.
- Grid and list views.
- Usage count on each item.
- Asset detail with a section named `引用位置`.

It does not contain:

- Purpose filters.
- Visibility filters.
- After-sale evidence.
- Payment keys or certificates.

## 7. Picker Contract

`AssetPicker` is library-only.

```ts
interface AssetPickerProps {
  modelValue: Api.Common.AssetValue
  mediaKind: 'IMAGE' | 'VIDEO'
  defaultFolderId?: number | null
  disabled?: boolean
  allowClear?: boolean
}
```

It lists all compatible library media regardless of prior upload screen. `usageRole` is deliberately not a picker prop; usages are written by the owner service when the product, banner, or other aggregate is saved.

Payment configuration uses a separate secret-file field. After-sale evidence stays in the after-sale pages.

## 8. Lifecycle and Security

- Public library assets may be downloaded only while active.
- Attachment and secret assets never receive public URLs.
- Generic asset-library endpoints reject non-library IDs.
- Active usages block deletion.
- URL fallback checks remain temporarily for legacy external URLs and rich-text HTML.
- Order snapshots remain protected.
- After-sale evidence ownership is checked both at temporary upload and at application submission.
- Temporary evidence records the order as upload context and may expire only while it has no active usage.
- All attachment/secret expiry windows and expiry checks use the database clock (`current_timestamp`) as the single time source, so JVM, JDBC, and database time-zone differences cannot make a fresh private asset appear expired.
- Staged payment secrets expire after two hours, are not readable by payment runtime until claimed by a saved configuration, and are retained without expiry while actively referenced.
- Replaced or explicitly cleared payment secrets become eligible for cleanup after a 24-hour release window, unless another active payment configuration still references them.
- A scheduled cleanup token-leases expired, unreferenced attachment/secret rows as `DELETE_PENDING`, deletes through their recorded object location, then finalizes them as `DELETED` only if the same worker still owns the lease. Provider failures are logged and retried with bounded backoff; active-expiry and retry batches are selected separately so persistent failures cannot starve newly expired assets.
- Payment secret content is read only through the private secret service and is never returned in API responses.
- Object-provider routing uses the provider, container, and region recorded on each asset, so changing the active provider, LOCAL root, COS bucket, or COS region does not redirect an existing object. COS reads and deletes still use the current configured credentials, which must retain access to the recorded bucket.

## 9. Breaking Migration

The approved direction does not preserve current storage metadata. Migrations V1 through V16 remain immutable. V17 creates the new asset tables, clears old file-ID bindings, disables database payment configurations that lose secret files, removes old storage tables, and does not copy old rows.

This deliberately preserves migration checksums while providing both V16-to-V17 and clean V1-to-latest paths. Physical LOCAL or COS objects are not removed by SQL and require a separate explicit cleanup after verification. The scheduled V17 retention job can clean only objects that still have V17 asset rows; it cannot discover pre-V17 physical orphans whose metadata was intentionally discarded.

The currently running V16 backend must be stopped before applying V17.

## 10. Compatibility Boundaries

The redesign must preserve:

- Product cover, gallery, video, SKU image, specification-value image, and guarantee-service icon file IDs.
- Product media fallback behavior.
- MP4/WebM validation and the 50 MB video limit.
- Rich-text public image upload and usage extraction.
- Home banner asset usage.
- Order item snapshot protection.
- Payment secret decryption and runtime config selection.
- Auth recovery and response-envelope validation in the mini-program evidence upload.
- Authenticated admin after-sale evidence preview.
- Runtime LOCAL/Tencent COS configuration.

Current storage rows, category rows, usage rows, and their external file-ID bindings are not preserved by V17.

## 11. Acceptance Criteria

- No persisted `purpose`, purpose filter, or default purpose-category mapping remains in the current runtime model.
- The same image can be reused for product cover, SKU, specification value, category icon, guarantee icon, banner, or rich text.
- The asset library returns only reusable public image/video assets.
- After-sale evidence and payment secrets are absent from the library.
- Folder CRUD and descendant filtering work.
- Folder parent integrity and sibling uniqueness remain correct under concurrent structural changes.
- Referenced/unreferenced filtering and usage counts are correct.
- In-use assets cannot be deleted.
- Expired unreferenced attachments and secrets are physically removed, while referenced assets remain active.
- Product V2 media behavior, payment secret reads, after-sale upload/preview, and object-provider routing pass regression tests.
- H2 and real MySQL migration paths reach V17.
- Admin build, mini-program tests/typecheck, and the full backend suite pass.
