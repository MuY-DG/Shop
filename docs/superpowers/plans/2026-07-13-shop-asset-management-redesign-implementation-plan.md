# Shop Asset Management Redesign Implementation Plan

Date: 2026-07-13

## Goal

Implement the approved asset-management redesign with a clean library/folder/usage model, domain-owned private uploads, and no preservation of current storage metadata.

## Source of Truth

- Design: `docs/superpowers/specs/2026-07-13-shop-asset-management-redesign.md`
- Product V2 design: `docs/superpowers/specs/2026-07-13-shop-product-management-v2-design.md`
- Setup: `docs/dev-setup.md`
- Smoke checks: `docs/smoke-checks.md`

## Global Constraints

- Start from clean `main` at `2d770bba` or its direct descendant.
- Do not edit V1 through V16; V17 is the only migration for this redesign.
- V17 is intentionally breaking for storage metadata and file-ID bindings.
- Do not apply V17 while a V16 backend is still running.
- Do not delete LOCAL/COS physical objects until the new model and all tests pass.
- `StorageUploadProfile` is transient and must never be stored on an asset row.
- Client requests cannot choose arbitrary scope or visibility.
- Only `LIBRARY` assets are visible through generic admin asset APIs.
- Usage roles remain business semantics and are not picker/upload properties.
- Preserve all Product V2 file-ID fields, fallbacks, media limits, recycle behavior, and usage lifecycle.
- Preserve object storage runtime configuration and recorded provider/container/region reads and deletes.
- Each dependent task starts with failing focused tests and ends with a focused review.

## Task 1: Freeze Baseline and Documentation

Files:

- Create `docs/superpowers/specs/2026-07-13-shop-asset-management-redesign.md`.
- Create `docs/superpowers/plans/2026-07-13-shop-asset-management-redesign-implementation-plan.md`.

Steps:

1. Confirm the worktree, HEAD, latest Flyway version, and running processes.
2. Record the old storage tables and every external file-ID reference.
3. Write the approved design and this implementation plan.
4. Run `git diff --check`.

Gate:

- Documents exist and contain the breaking-data boundary.
- No functional source file changed in this task.

## Task 2: Add the V17 Asset Schema

Files:

- Create `backend/shop-server/src/main/resources/db/migration/V17__replace_storage_with_asset_model.sql`.
- Create `StorageAssetScope`.
- Modify `StorageMediaKind`.
- Replace category DTOs with folder DTOs.
- Rewrite storage schema and migration tests.

Test-first steps:

1. Assert the final `storage_asset`, `storage_asset_folder_guard`, `storage_asset_folder`, and `storage_asset_usage` columns, constraints, and indexes.
2. Assert the folder guard contains only `id = 1`, root folders store `parent_id = NULL` while the API exposes `parentId = 0`, and the self-FK plus generated `parent_key` enforce parent existence and sibling-name uniqueness; self-parent/cycle validation runs under the guard lock in the service.
3. Assert `purpose`, `storage_file`, `storage_file_usage`, and `storage_asset_category` are absent after V17.
4. Add a V16-to-V17 H2 migration fixture with all external file-ID bindings populated.
5. Assert V17 clears those bindings, removes after-sale evidence rows, and disables unusable DB payment configurations.
6. Add the same V16-to-V17 assertions to the MySQL Testcontainers migration path.
7. Implement V17 with new tables first, idempotent reference cleanup second, and old-table drops last.

Focused command:

```bash
cd backend/shop-server
./mvnw -Dtest='StorageSchemaTest,AssetModelMigrationTest,CommerceFulfillmentMigrationTest,CommerceFulfillmentMySqlMigrationTest' test
```

## Task 3: Replace Purpose with Upload Profiles

Files:

- Delete `StoragePurpose`.
- Create `StorageUploadProfile`.
- Modify `UploadPolicy`.
- Modify `StorageObjectKeyGenerator`.
- Rewrite upload-policy tests.

Steps:

1. Test library image, library video, after-sale attachment, and payment secret profiles.
2. Prove each profile fixes scope, media kind, visibility, key path, extension/content type, and limit.
3. Prove invalid media pairs and client-selected scope/visibility are rejected.
4. Preserve image readability validation and MP4/WebM limits.
5. Generate keys from scope/media kind rather than business purpose.

## Task 4: Implement Asset and Folder Services

Files:

- Refactor `StorageService` around `storage_asset`.
- Refactor `StorageUsageService` around `storage_asset_usage`.
- Replace file/category controllers and DTOs with asset/folder contracts.
- Add folder delete and descendant-query support.
- Rewrite storage controller/service/usage tests.

Steps:

1. Add library upload tests for detected image/video media kind.
2. Add keyword, media kind, folder subtree, ungrouped, date, and reference-state list tests.
3. Return active usage count on list rows and full usage records on detail.
4. Add folder create/update/disable/delete and cycle-prevention tests.
5. Restrict move, detail, and delete to library assets.
6. Persist the exact provider/container/region chosen at upload time and prove later provider/location changes do not redirect reads or deletes; document that COS still requires current credentials with access to the recorded bucket.
7. Back the folder tree with self-referential constraints and serialize structural validation through the folder-tree guard.
8. Preserve active-usage and URL-fallback deletion protection.

## Task 5: Move Private Uploads into Their Domains

Files:

- Add an order-scoped after-sale evidence upload endpoint.
- Modify `AppAfterSaleService` evidence ownership checks.
- Add a payment secret-file upload endpoint.
- Refactor `PrivateStorageFileService` to validate `SECRET + DOCUMENT`.
- Modify payment config validation/resolution.
- Add `StorageAssetCleanupService` and the scheduled `StorageAssetCleanupJob`.
- Add after-sale, payment-secret lifecycle, provider-routing, and cleanup focused tests.

Steps:

1. Prove the after-sale endpoint requires APP authentication and order ownership.
2. Prove the upload records order context and expiration, and only the uploading user can submit that asset for the same order.
3. Prove evidence is private, attachment-scoped, and never listed in the library.
4. Prove payment secret upload requires `payment:config:write`.
5. Prove secret assets are private, document-scoped, and never listed or previewed by generic APIs.
6. Give staged payment secrets a two-hour expiry, claim them when a configuration is saved, and release replaced secrets after 24 hours without another active reference.
7. Prove an update can explicitly clear the optional merchant certificate, remove its protected usage, and release it after 24 hours; payment config `PUT` is a full replacement for file-ID fields.
8. Use database time consistently for private-asset TTL creation, validation, release windows, and cleanup; add a regression with deliberately different JVM and database time zones.
9. Add scheduled, token-leased physical cleanup for expired unreferenced attachments and secrets, with bounded retry backoff and separate fresh-expiry/retry batches.
10. Preserve protected usage creation and authenticated after-sale blob preview.

## Task 6: Migrate Business Usage Callers

Files:

- Modify product, guarantee-service, home-banner, order-snapshot, payment, and after-sale services.
- Update every SQL fixture that references old storage tables or columns.

Steps:

1. Change public-media validation to `LIBRARY + expected media kind + PUBLIC + ACTIVE`.
2. Keep each existing `StorageFileUsageType` role and owner mapping.
3. Update table/column names from file usage to asset usage.
4. Re-run product create/update/recycle/restore/purge usage tests.
5. Re-run home-banner, order snapshot, payment, and after-sale usage tests.

## Task 7: Rebuild the Admin Asset Library

Files:

- Split asset-library calls from `admin/src/api/storage.ts`.
- Modify `admin/src/types/api/api.d.ts`.
- Rewrite `admin/src/views/storage/files/index.vue`.
- Add folder management UI.

Steps:

1. Replace purpose/category/visibility/status filters with filename, media kind, folder, reference state, and dates.
2. Add `全部素材` and `未分组` nodes.
3. Add folder create, rename, disable, and delete actions.
4. Display usage count and rename detail usage section to `引用位置`.
5. Remove all private-file cards and purpose label maps.
6. Rename the visible menu to `素材库`.

## Task 8: Rebuild AssetPicker and Private Fields

Files:

- Rewrite `admin/src/components/business/asset-picker/index.vue`.
- Merge Product V2 video selection into the common picker where practical.
- Update all product, category, guarantee, banner, and rich-text callers.
- Add a payment secret-file field and payment API client.

Steps:

1. Make `mediaKind` required and remove purpose/visibility props.
2. Prove one image can be selected for multiple business roles.
3. Preserve image/video preview, replacement, clearing, and current external-URL fallbacks.
4. Preserve Product V2 video formats and size guidance.
5. Ensure payment secret fields never open the asset library.

## Task 9: Migrate Mini-Program Evidence Upload

Files:

- Modify mini-program storage/after-sale services, types, page, and request-recovery tests.

Steps:

1. Remove `EvidenceUploadPurpose` from the client contract.
2. Upload against the order-scoped after-sale endpoint.
3. Validate `ATTACHMENT + IMAGE + PRIVATE` in the response.
4. Preserve token refresh/retry, second-401 logout, error envelope, and long-ID string handling.
5. Submit only evidence IDs owned by the active order/user context.

## Task 10: Integrated Verification and Review

Backend:

```bash
cd backend/shop-server
./mvnw test
```

Admin:

```bash
cd admin
pnpm typecheck
CI=true pnpm build
```

Mini-program:

```bash
cd miniprogram
pnpm test
pnpm typecheck
```

Review checklist:

1. Search runtime sources for `StoragePurpose`, persisted `purpose`, `assetCategory`, and old storage tables.
2. Confirm only migration history and historical documentation retain old terminology.
3. Review all generic asset endpoints for `LIBRARY` scope enforcement.
4. Review after-sale evidence ownership and payment secret authorization.
5. Review provider routing and public/private URL behavior.
6. Review asset deletion protection and Product V2 lifecycle behavior.
7. Review folder-tree locking and temporary/private asset retention.
8. Run `git diff --check`.

## Task 11: Local Cutover

Steps:

1. Stop the current port-8080 V16 backend.
2. Back up or retain the old `hotpot_shop` database name until verification completes.
3. Create a fresh local database and run V1 through V17 with the new backend.
4. Run real local library image/video upload, folder, reuse, delete-protection, after-sale evidence, and payment-secret smoke checks.
5. Only after successful verification, explicitly remove the obsolete database and orphaned LOCAL objects if desired.

Gate:

- New backend and admin use only the V17 asset model.
- Real local smoke covers all three scopes.
- Old database and physical objects are not deleted implicitly.
