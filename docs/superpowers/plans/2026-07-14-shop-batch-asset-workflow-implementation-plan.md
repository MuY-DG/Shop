# Shop Batch Asset Workflow Implementation Plan

Date: 2026-07-14

## Goal

Add a consistent batch workflow for library uploads, asset-folder moves, and product carousel images while preserving the current asset, URL snapshot, and usage-tracking contracts.

## Current-State Constraints

- Preserve the in-progress asset-reference synchronization and asset-detail UI changes already present in the worktree.
- Keep physical uploads as independent requests so partial failures can be retried without rolling back successful objects.
- Make batch folder moves atomic in the backend and apply the existing `asset:folder` permission.
- Moving a library asset changes only `folder_id`; it must not alter URLs or usages.
- Product carousel order remains the request-array order and is persisted as `sort_order = index + 1`.
- Cap product carousel images at 9 in both admin behavior and backend validation.
- Reuse the carousel's existing upload/material-library entry; multi-select is the default capability, not a second pair of actions.
- Start each backend behavior with a failing focused test and finish with full admin/backend verification.

## Task 1: Atomic Batch Move API

Files:

- Add a batch-move request DTO.
- Modify `AdminAssetController` and `StorageService`.
- Modify `StorageControllerTest`.

Steps:

1. Prove two active library assets can move to one enabled folder in one request.
2. Prove duplicate IDs are normalized and an unavailable ID rolls back the entire move.
3. Add `POST /admin/assets/batch-move` with a maximum of 100 asset IDs.
4. Validate and lock the target folder and all selected assets before updating any row.

## Task 2: Asset Library Batch Operations

Files:

- Modify `admin/src/api/assets.ts` and storage API types.
- Modify `admin/src/views/storage/files/index.vue`.

Steps:

1. Allow up to 50 files to be selected in the upload dialog.
2. Upload files through the existing single-file endpoint with concurrency limited to 3.
3. Keep failed files available for retry and report a single success/failure summary.
4. Add shared selection state for list and grid views.
5. Add a selection toolbar and invoke the atomic batch-move endpoint.
6. Clear hidden selections when the active folder or filter changes.

## Task 3: Product Carousel Batch Add

Files:

- Add reusable batch-upload and multi-asset-selection components/utilities.
- Modify `admin/src/views/product/spu/modules/product-info-tab.vue`.
- Add focused utility tests.
- Add backend request validation for at most 9 carousel images.

Steps:

1. Make the existing carousel upload entry accept one or many files, validate images, upload with bounded concurrency, and append successful assets once.
2. Make the existing carousel material-library entry use a dialog that supports multi-select, pagination, folder filtering, duplicate exclusion, and an explicit confirm action.
3. Preserve selected/uploaded order, ignore duplicate file IDs, and stop at 9 images.
4. Make existing carousel cards draggable so their array order is explicit before save.
5. Prove append/deduplication/limit behavior with a focused TypeScript test.

## Verification

Backend:

```bash
cd backend/shop-server
./mvnw -Dtest='StorageControllerTest,AdminProductSpuControllerTest' test
./mvnw test
```

Admin:

```bash
cd admin
pnpm exec tsx --test src/utils/asset-batch.test.ts
pnpm exec eslint <changed files>
pnpm exec stylelint <changed vue files>
pnpm build
```

Final:

```bash
git diff --check
```
