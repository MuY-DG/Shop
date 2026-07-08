# Task 2 Report: Backend Storage APIs, Public File Route, Categories, And Delete Protection

## Status

DONE

## Scope

Implemented Task 2 backend-only storage delivery in `backend/shop-server`:

- Admin storage upload/list/detail/usages/move/delete APIs.
- App authenticated upload API with app-purpose restriction.
- Public file read route for `GET /files/public/**` only.
- Asset category tree create/update/list APIs.
- Delete protection against any active usage, including protected usage.
- Minimal `StorageUsageService` hooks for later product/order/banner tasks.

## RED Evidence

Ran:

```bash
cd backend/shop-server && ./mvnw -Dtest=StorageControllerTest,SecurityConfigTest,AdminRbacSchemaTest test
```

Observed expected failing coverage before implementation:

- `SecurityConfigTest.publicEndpointsAreNotBlockedByAuthentication` failed because `GET /files/public/health-probe.png` returned `401`.
- `StorageControllerTest.*` failed with `404` on:
  - `/admin/files/upload`
  - `/app/files/upload`
  - `/admin/file-categories`
  - `/files/public/**`

This confirmed the new route/controller surface was absent before production changes.

## Implemented Changes

### Backend API Surface

- Added controllers:
  - `org.muybaby.shopserver.storage.AdminFileController`
  - `org.muybaby.shopserver.storage.AppFileController`
  - `org.muybaby.shopserver.storage.AdminFileCategoryController`
  - `org.muybaby.shopserver.storage.PublicFileController`

### Services / DTOs

- Added `StorageService` for upload, paging, detail/usages, move, delete, and public resource serving.
- Added `StorageUsageService` with:
  - `replaceOwnerUsages(...)`
  - `addProtectedUsage(...)`
  - `removeOwnerUsages(...)`
- Added storage DTOs for file/category/query/move/usage responses.
- Added `StorageConfiguration` bean wiring for `StorageProvider`, `UploadPolicy`, and `StorageObjectKeyGenerator`.

### Security / Validation

- Permitted only `GET /files/public/**` anonymously in `SecurityConfig`.
- Kept `/admin/**` and `/app/**` authenticated.
- Enforced app upload purposes to `AFTER_SALE_IMAGE` and `REFUND_EVIDENCE` only.
- Rejected path traversal filenames, empty files, unsupported extensions, oversized files, and unreadable/corrupted images.
- Ensured clients never control `object_key`; keys are backend-generated.
- Ensured private files omit `url` and `publicUrl`.

### Delete Protection

- Added `ErrorCode.STORAGE_FILE_IN_USE` and `ErrorCode.STORAGE_ASSET_CATEGORY_UNAVAILABLE`.
- `DELETE /admin/files/{id}` now:
  - blocks on any `storage_file_usage.status = 'ACTIVE'`
  - soft-deletes metadata via `status = 'DELETED'` + `deleted_at`
  - best-effort deletes provider content only after metadata soft-delete and only when no active usage remains

## Verification

Re-ran:

```bash
cd backend/shop-server && ./mvnw -Dtest=StorageControllerTest,SecurityConfigTest,AdminRbacSchemaTest test
```

Result:

- `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`
- Build status: `BUILD SUCCESS`

## Files Intentionally Left Unstaged

Per task instructions, I did not stage unrelated existing doc/spec/plan changes:

- `docs/dev-setup.md`
- `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`
- `docs/superpowers/specs/2026-07-08-shop-file-storage-design.md`
- `docs/superpowers/plans/2026-07-08-shop-file-storage-implementation-plan.md`

## Commit Scope For Task 2

Stage only Task 2 backend files:

- `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/**`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/storage/StorageControllerTest.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/admin/rbac/AdminRbacSchemaTest.java`

## Concerns

None at the focused Task 2 backend scope. Broader product/order/banner usage integration is intentionally deferred to later tasks.

---

## 2026-07-08 Review Fix Follow-Up

### Scope

Addressed Task 2 review findings without touching Task 3+ feature behavior:

- moved cheap upload policy checks ahead of `MultipartFile.getBytes()` / `ImageIO`
- preserved `{ code, msg, data }` JSON failures for malformed upload requests
- blocked orphan `storage_file_usage` inserts for missing/deleted files
- switched `storage_asset_category.id` to auto-increment plus generated-key creation

### RED Evidence

Ran:

```bash
cd backend/shop-server && ./mvnw -Dtest=StorageControllerTest,StorageServiceTest,StorageUsageServiceTest,StorageSchemaTest,UploadPolicyTest,GlobalExceptionHandlerTest,SecurityConfigTest,AdminRbacSchemaTest test
```

Observed expected failures before the fix:

- `StorageServiceTest.unsupportedExtensionIsRejectedBeforeReadingBytes` and `oversizedImageIsRejectedBeforeReadingBytes` showed `getBytes()` was called too early.
- `StorageControllerTest.uploadBindingFailuresStillReturnApiResponseEnvelope` showed invalid `purpose` returned Spring's empty default 400 response instead of JSON.
- `StorageUsageServiceTest.addProtectedUsageRejectsMissingFile` and `replaceOwnerUsagesRejectsDeletedFiles` proved orphan usages were still insertable.
- `StorageSchemaTest.storageMigrationCreatesTablesColumnsAndSeeds` showed `storage_asset_category.id` was not identity/auto-increment.

### Implemented Fixes

- `AdminFileController` / `AppFileController` now accept `purpose` as `String`; `StorageService` parses and rejects invalid purpose with `STORAGE_UPLOAD_POLICY_REJECTED`.
- `StorageService.upload*()` now validates filename, content type, extension, non-empty, and size from `MultipartFile.getSize()` before reading bytes, then re-validates after byte read while still rejecting corrupted images.
- `UploadPolicy` now enforces allowed content types in addition to extension and size rules.
- `GlobalExceptionHandler` now wraps multipart/binding/request-shape failures into `ApiResponse.fail(...)` with HTTP 400.
- `StorageUsageService` now requires `storage_file.status = 'ACTIVE'` before inserting usage rows.
- `V7__storage.sql` now defines `storage_asset_category.id BIGINT PRIMARY KEY AUTO_INCREMENT`, and `StorageService.createCategory(...)` uses generated keys instead of `select coalesce(max(id), 0) + 1`.

### GREEN Evidence

Specified verification command:

```bash
cd backend/shop-server && ./mvnw -Dtest=StorageControllerTest,SecurityConfigTest,AdminRbacSchemaTest test
```

Result:

- `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

Additional focused verification:

```bash
cd backend/shop-server && ./mvnw -Dtest=StorageServiceTest,StorageUsageServiceTest,StorageSchemaTest,UploadPolicyTest,GlobalExceptionHandlerTest test
```

Result:

- `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`
