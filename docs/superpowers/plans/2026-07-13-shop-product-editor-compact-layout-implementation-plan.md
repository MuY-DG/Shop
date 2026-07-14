# Shop Product Editor Compact Layout Implementation Plan

Date: 2026-07-13

## Goal

Make the admin product create/edit flow compact and easy to scan without changing product, SKU, specification-template, freight, coupon, or asset API contracts.

## Current-State Constraints

- The worktree already contains an in-progress asset-management redesign. Preserve those changes and build on the new `AssetItem`, `mediaKind`, and `uploadAsset` contracts.
- Keep the four-step product editor and its existing submit/validation flow.
- Keep managed asset IDs and URL snapshots together so existing edit, order snapshot, and fallback behavior remains intact.
- Keep responsive behavior: desktop uses horizontal label/control rows and compact inline fields; narrow screens may stack for usability.
- Do not modify backend or mini-program source for this UI pass.

## UX Decisions

- Product information fields use left labels and right controls.
- Product images and video use a square clickable upload target. A selected asset exposes a small red remove control on hover; compact mode keeps a small in-square material-library entry instead of separate large action buttons.
- Upload and URL entry are mutually exclusive views. Upload is the default for a new field, with a small text action to switch source.
- Upload limits live behind a small warning/info icon tooltip instead of occupying permanent page space.
- Carousel items flow horizontally, always show at least one empty image target, and place a compact plus target after the current items.
- Single-spec SKU fields form one compact inline specification area.
- Multi-spec values flow horizontally. No specification is selected for specification images by default; choosing one specification reveals a small square image target under each of its values, and choosing it again cancels the selection.
- Empty specification names use a neutral numbered fallback in summaries and tables instead of exposing “未命名规格”.
- Specification-template selection and refresh stay in the type row. Add-specification and save-as-product-specification actions stay below the last specification group.
- The SKU price column shows an explicit red required marker.
- Other settings use compact horizontal rows and remove the redundant guarantee-service summary.
- The footer offers both save-in-place and submit-and-return-to-product-list actions.

## Task 1: Compact Asset Picker Mode

Files:

- Modify `admin/src/components/business/asset-picker/index.vue`.
- Add `admin/src/views/product/spu/modules/compact-asset-field.vue`.

Steps:

1. Add an opt-in compact square presentation while retaining the existing full picker for other admin pages.
2. Make the square itself the upload trigger.
3. Add hover-to-remove behavior and upload progress feedback.
4. Enforce and describe current image/video type and size policies.
5. Add the mutually exclusive upload/URL wrapper used by the product editor.

## Task 2: Compact Product Information

Files:

- Modify `admin/src/views/product/spu/modules/product-info-tab.vue`.

Steps:

1. Convert the main form to horizontal label/control rows.
2. Replace cover, carousel, and video fields with compact square media controls.
3. Keep at least one visual carousel slot and add further slots with a square plus target.
4. Retain guarantee-service and freight-template functionality in compact rows.
5. Preserve existing validation and payload state.

## Task 3: Compact Specification Editing

Files:

- Modify `admin/src/views/product/spu/modules/product-specification-tab.vue`.
- Modify `admin/src/views/product/spu/modules/spec-tree-editor.vue`.
- Modify `admin/src/views/product/spu/modules/sku-matrix.vue`.
- Modify `admin/src/views/product/spu/modules/sku-matrix.ts`.
- Modify `admin/src/views/product/spu/modules/sku-matrix.test.ts`.

Steps:

1. Flatten single-spec inputs into one compact area and use the small media target.
2. Keep multi-spec toolbar actions on one row.
3. Stop auto-selecting the first image specification on create or after deletion.
4. Lay out specification values horizontally and reveal small image targets only for the explicitly selected image specification.
5. Replace “未命名规格” fallbacks with numbered neutral labels.
6. Mark SKU sale price as required.

## Task 4: Compact Other Settings

Files:

- Modify `admin/src/views/product/spu/modules/product-other-settings-tab.vue`.

Steps:

1. Replace separated cards with compact bordered rows.
2. Put labels and controls side by side on desktop.
3. Keep coupon permission and create flows intact.
4. Remove the redundant guarantee-service summary.

## Verification

Run:

```bash
cd admin
pnpm exec tsx --test src/views/product/spu/modules/sku-matrix.test.ts
pnpm typecheck
pnpm build
pnpm exec prettier --check \
  src/components/business/asset-picker/index.vue \
  src/views/product/spu/modules/compact-asset-field.vue \
  src/views/product/spu/modules/product-info-tab.vue \
  src/views/product/spu/modules/product-specification-tab.vue \
  src/views/product/spu/modules/spec-tree-editor.vue \
  src/views/product/spu/modules/sku-matrix.vue \
  src/views/product/spu/modules/sku-matrix.ts \
  src/views/product/spu/modules/sku-matrix.test.ts \
  src/views/product/spu/modules/product-other-settings-tab.vue
git diff --check
```

Visual gate:

- Open “新增商品” at desktop width and verify the product information, single-spec, multi-spec, and other-settings tabs.
- Verify upload, replace, hover-remove, URL switching, carousel add/remove, specification-image selection, template save, and required-price presentation.
- Recheck at a narrow viewport to ensure controls stack without horizontal clipping outside the SKU table’s intentional scroll region.
