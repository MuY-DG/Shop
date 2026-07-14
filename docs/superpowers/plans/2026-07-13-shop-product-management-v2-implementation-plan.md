# Shop Product Management V2 Implementation Plan

Date: 2026-07-13

## Goal

Implement the approved product-management V2 design with backend work first, admin work second, and no mini-program source changes.

## Source of Truth

- Design: docs/superpowers/specs/2026-07-13-shop-product-management-v2-design.md
- Existing catalog plan: docs/superpowers/plans/2026-07-06-shop-product-catalog-implementation-plan.md
- Setup: docs/dev-setup.md
- Smoke checks: docs/smoke-checks.md

## Global Constraints

- Start from current main after V12.
- V13 owns the original product-management V2 schema; the approved recycle-bin follow-up is added only through V14.
- Never edit V1 through V12.
- Preserve subtitle, selling_points, spec_json, spec_text, URL snapshots, and existing app response fields.
- Money remains integer cents in backend contracts and is converted to yuan only in admin form state.
- Ordinary product deletion is recoverable; permanent deletion is a guarded, non-restorable purge that preserves historical identities and audit data.
- Existing SKU ids are updated in place.
- Every stock change is logged.
- Product controllers use method-level PreAuthorize checks in addition to admin authentication.
- No file under miniprogram may change.
- Backend product, cart, checkout, order, coupon, payment, storage, and fulfillment regressions are mandatory.
- Each task follows test-first development and receives a focused review before the next dependent task.

## Task 1: Freeze Baseline and Documentation

Files:

- Create docs/superpowers/specs/2026-07-13-shop-product-management-v2-design.md
- Create docs/superpowers/plans/2026-07-13-shop-product-management-v2-implementation-plan.md

Steps:

1. Record git status, latest migration, and current product/admin file inventory.
2. Confirm the worktree is clean and V12 is latest.
3. Write the design decisions and this execution plan.
4. Run markdown path and diff checks.

Gate:

- Both documents exist.
- No functional code changed in this task.
- git diff --check passes.

## Task 2: Add V13 Schema and Domain Enums

Files:

- Create backend/shop-server/src/main/resources/db/migration/V13__product_management_v2.sql
- Create or modify product enums for specification type, tag, freight mode, and service visibility.
- Modify storage purpose, usage type, and owner type enums.
- Create backend/shop-server/src/test/java/org/muybaby/shopserver/product/ProductManagementV2SchemaTest.java
- Modify clean and legacy migration tests under backend/shop-server/src/test/java/org/muybaby/shopserver/fulfillment.

Test-first steps:

1. Add failing assertions for every V13 table, column, index, seed, menu, and permission.
2. Add MySQL migration assertions from the current legacy baseline.
3. Implement V13.
4. Add enum parsing tests.
5. Run focused schema and migration tests.

Schema gate:

- Existing rows receive the default freight template.
- Legacy SKU ids and product fields remain unchanged.
- Legacy SKU combination keys are unique.
- Existing migrations stay untouched.

## Task 3: Refactor Product Aggregate Persistence

Files:

- Modify backend product entities and DTOs.
- Modify AdminProductService.
- Modify ProductReadMapper.
- Add focused product aggregate tests.

Steps:

1. Add failing tests for single-spec create and read.
2. Add failing tests for multi-spec create with normalized group/value rows.
3. Add failing tests proving SKU ids survive updates.
4. Add failing tests proving omitted SKUs are soft deleted, not physically deleted.
5. Add failing tests for automatic SKU-code generation.
6. Add failing tests for default-SKU, image-group, combination-limit, price, nullable weight/volume, and publish invariants.
7. Replace delete-all SKU persistence with incremental upsert.
8. Derive compatibility specJson and specText.
9. Normalize legacy spec JSON on first save.
10. Preserve absent compatibility fields during updates.

Focused command:

cd backend/shop-server
./mvnw -Dtest='AdminProductServiceTest,AdminProductSpuControllerTest,ProductManagementV2SchemaTest' test

## Task 4: Add Product List Sales, Status, and Soft Delete

Files:

- Modify AdminSpuListItemResponse and query DTOs.
- Modify ProductReadMapper list queries.
- Modify AdminProductSpuController and AdminProductService.
- Modify AppProductService reads to exclude deleted rows.
- Modify cart and checkout reads when needed to exclude deleted SKUs/SPUs.
- Add list, delete, cart-unavailable, and order-reference tests.

Steps:

1. Add actualSales, virtualSales, and displaySales list tests.
2. Add paid, unpaid, and refunded order fixtures.
3. Add delete endpoint tests.
4. Prove delete preserves SKU, order_item, stock_lock, and stock_log rows.
5. Prove deleted products disappear from app reads and become unavailable in cart/checkout.
6. Add and verify the order_item.spu_id index.
7. Implement the list aggregate and soft-delete transaction.

## Task 5: Add Specification Template APIs

Files:

- Create specification-template entities/DTOs/controllers/services/read mappers.
- Add product specification-template controller routes.
- Add controller and service tests.

Steps:

1. Test nested template creation.
2. Test list/detail reads in stable sort order.
3. Test rename-only updates.
4. Test backend rejection when an update adds or removes a group/value.
5. Test exactly one image-enabled group.
6. Test save-as-template from an existing product snapshot.
7. Implement APIs and mappings.

## Task 6: Add Guarantee Service APIs

Files:

- Create guarantee-service entities/DTOs/controller/service/read mapper.
- Modify storage usage enums and product aggregate mappings.
- Add controller, service, and storage-usage tests.

Steps:

1. Test create, edit, list, visibility switch, and ordering.
2. Test product association.
3. Test deletion while referenced.
4. Prove deletion removes current associations and soft deletes the service.
5. Prove icon usage is released without removing order snapshot usage.
6. Implement the APIs.

## Task 7: Add Product Video Storage

Files:

- Modify StoragePurpose and upload media-kind handling.
- Modify StorageProperties and application configuration defaults.
- Modify UploadPolicy and StorageService.
- Modify storage usage enums and product aggregate mapping.
- Add upload-policy, controller, usage, and product update tests.

Steps:

1. Add failing MP4 and WebM policy tests.
2. Add failing invalid extension/content-type and size tests.
3. Preserve all existing image and certificate policies.
4. Add PRODUCT_VIDEO and the configurable 50 MB default.
5. Track video file usage on product save, replace, clear, and delete.

## Task 8: Add Freight Template and Checkout Calculation

Files:

- Create freight-template DTOs/controller/service/read mapper.
- Extend product aggregate freight binding.
- Modify CheckoutSelection and CheckoutSelectionService.
- Modify AppOrderService preview/submit amount calculation and request digest inputs.
- Add freight-template, checkout, order, and digest tests.

Steps:

1. Test default free template and product binding.
2. Test FREE and FIXED validation.
3. Test disabled/deleted template rejection on publish.
4. Test one product fixed freight.
5. Test multiple products use the maximum fixed fee.
6. Test preview and submit calculate identical freight/payable amounts.
7. Test freight is stored in shop_order and included in idempotency digest.
8. Implement APIs and calculation.
9. Add missing read/write authorities and HTTP 403 controller tests.

## Task 9: Enable Product-Specific Coupons

Files:

- Modify AdminCouponService and product coupon controllers/services.
- Modify CouponDiscountCalculator and CheckoutContext/CheckoutItem if required.
- Modify AppCouponService claimable/claim/available queries.
- Add product coupon association reads.
- Add coupon schema, controller, service, promotion, checkout, and regression tests.

Steps:

1. Keep ALL coupon tests green.
2. Test PRODUCT coupon validation with exactly one existing SPU id.
3. Keep CATEGORY rejected with an explicit unsupported result.
4. Test product-editor coupon creation forces PRODUCT scope and path SPU id.
5. Test ALL and matching PRODUCT coupons can be bound.
6. Test PRODUCT coupon claim by template id.
7. Test available-coupon calculation uses matching line amount only.
8. Test nonmatching product coupon is unavailable.
9. Test payable amount remains positive.
10. Implement app product-coupon read endpoint without modifying mini-program callers.
11. Add product coupon authorities and HTTP 403 controller tests.

## Task 10: Backend Integration Review and Verification

Steps:

1. Review V13 for H2/MySQL portability and seed collisions.
2. Review product updates for destructive delete statements.
3. Review stock logs and operator ids.
4. Review coupon and freight amount idempotency.
5. Review storage usage lifecycle.
6. Run focused suites:

cd backend/shop-server
./mvnw -Dtest='ProductManagementV2SchemaTest,ProductCatalogSchemaTest,AdminProductServiceTest,AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,CartSchemaTest,AppCartControllerTest,AppCartServiceTest,CouponSchemaTest,AdminCouponTemplateControllerTest,AppCouponControllerTest,CouponDiscountCalculatorTest,OrderSchemaTest,CheckoutSelectionServiceTest,CheckoutRequestDigestTest,AppOrderServiceTest,PaymentCallbackServiceTest,StorageSchemaTest,StorageControllerTest,StorageServiceTest,StorageUsageServiceTest,UploadPolicyTest,CommerceFulfillmentMigrationTest,CommerceFulfillmentMySqlMigrationTest' test

7. Run ./mvnw test.
8. Fix, re-run, and re-review before admin work begins.

Backend gate:

- Full backend suite is green.
- V13/V14 work on clean H2 and legacy MySQL migration paths.
- Existing app product/cart/order contracts remain compatible.

## Task 11: Extend Admin API Types and Clients

Files:

- Modify admin/src/types/api/api.d.ts
- Modify admin/src/api/product.ts
- Modify admin/src/api/coupon.ts only where product coupon creation needs it.
- Add pure TypeScript helper tests if the project test setup supports them.

Steps:

1. Add complete product aggregate, specification, service, freight, sales, tag, and coupon-binding types.
2. Add API client methods for every new admin endpoint.
3. Add yuan/cent and nullable numeric conversion helpers.
4. Preserve existing API namespaces used by other pages.
5. Run admin type checking through the production build.

## Task 12: Rebuild the Product List

Files:

- Modify admin/src/views/product/spu/index.vue
- Add small product-list helper modules/components where useful.

Steps:

1. Keep only title and category in ArtSearchBar.
2. Add the separate status segmented row.
3. Add all required columns in the approved order.
4. Add the status switch plus state text.
5. Make edit and stock actions directly visible.
6. Add publish/unpublish and delete actions.
7. Route every status action through one confirmation helper.
8. Preserve and reuse the existing stock-adjustment dialog.

## Task 13: Build the Four-Tab Editor Shell

Files:

- Turn admin/src/views/product/spu/index.vue into a list/editor route shell driven by mode and id query values.
- Replace admin/src/views/product/spu/modules/spu-editor.vue with a full-page aggregate editor.
- Create product-info-tab.vue.
- Create product-specification-tab.vue.
- Create product-detail-tab.vue.
- Create product-other-settings-tab.vue.
- Create shared editor state/types/helpers.

Steps:

1. Add query-driven list/create/edit modes, tabs, loading, dirty-state confirmation, browser-back behavior, and a fixed footer.
2. Implement tab-local validation and final aggregate validation.
3. Keep existing image and rich-text values when loading legacy products.
4. Save new products as DRAFT.
5. After the first save, retain the SPU id so product coupon creation becomes available.

## Task 14: Implement Product Information and Detail Tabs

Steps:

1. Add fields in the approved vertical order.
2. Reuse AssetPicker for cover, gallery, specification, and service images.
3. Add a video picker with upload and preview.
4. Add gallery fallback preview without persisting a duplicate row.
5. Add guarantee-service selection and inline refresh after service changes.
6. Add freight-template selection and inline create/edit dialog.
7. Move WangEditor into Product Detail.

## Task 15: Implement Single and Multi Specification UI

Files:

- Add specification-tree editor.
- Add SKU-matrix component.
- Add combination-generation and reconciliation helper.
- Add specification-template selector and save-as-template dialog.

Steps:

1. Implement SINGLE with one SKU and optional fields.
2. Implement MULTI group/value creation and one image-group radio behavior.
3. Generate stable value keys and sorted combination keys.
4. Reconcile regenerated combinations with existing SKU ids and entered values.
5. Enforce the 100-row limit before rendering.
6. Implement default-SKU radio and SKU enabled switch.
7. Apply SKU image fallback previews.
8. Load a selected template as a detached snapshot.
9. Save the current structure as a named template.

## Task 16: Implement Other Settings, Specification Page, and Guarantee Page

Files:

- Complete product-other-settings-tab.vue.
- Create admin/src/views/product/spec-template/index.vue and modules.
- Create admin/src/views/product/guarantee-service/index.vue and modules.

Steps:

1. Add sort, virtual sales, and fixed tags.
2. Add coupon multi-select and product-specific coupon dialog.
3. Disable exclusive coupon creation until a new product has been saved once.
4. Implement template list and rename-only editor.
5. Implement guarantee list with every required column, visibility switch, edit, and delete.
6. Add auth directives for new actions.

## Task 17: Admin Review and Build

Steps:

1. Review form reset, async loading, stale request, and cancel behavior.
2. Review long-id handling.
3. Review currency conversion and optional-number empty states.
4. Review SKU combination reconciliation.
5. Review switch rollback and confirmation behavior.
6. Run:

cd admin
CI=true pnpm build

7. Fix, rebuild, and re-review.

## Task 18: Real Local Smoke and Final Completion Audit

Files:

- Modify docs/dev-setup.md.
- Modify docs/smoke-checks.md.

Smoke coverage:

1. Admin login.
2. Create guarantee service and fixed freight template.
3. Create single-spec draft, verify generated SKU code, publish, list, unpublish.
4. Create multi-spec product with one image group and multiple values.
5. Save as template and create a second product from the template.
6. Confirm template rename does not mutate product snapshots.
7. Adjust stock and inspect stock behavior.
8. Create and claim a product-specific coupon.
9. Verify matching and nonmatching coupon checkout results.
10. Verify fixed freight preview equals submitted order freight.
11. Verify list sales actual/virtual/display values.
12. Delete a referenced guarantee service.
13. Soft delete a product and verify app detail/cart unavailability while order snapshots remain.
14. Restore the product from the recycle bin and verify it remains OFF_SALE with its aggregate intact.
15. Permanently purge a recycled product and verify order snapshots/images and shared master data remain.

Final commands:

- cd backend/shop-server && ./mvnw test
- cd admin && CI=true pnpm build
- cd miniprogram && pnpm typecheck
- cd miniprogram && pnpm test
- git diff --check
- git status --short --ignored
- git diff --name-only -- miniprogram

Completion gate:

- Every explicit design requirement has direct schema, API, UI, test, or smoke evidence.
- Full backend tests and admin build pass.
- Real local smoke passes.
- git diff --name-only -- miniprogram is empty.
- No ignored secret or local runtime file is staged or summarized as product work.

## Task 19: Add Recoverable Product Recycle Bin

Files:

- Create backend/shop-server/src/main/resources/db/migration/V14__product_recycle_bin.sql.
- Modify admin product query/list DTOs, controller, service, and read mapper.
- Modify admin product API/types/list page.
- Add focused recycle-bin backend tests.

Steps:

1. Add product_spu.purged_at plus restore/purge permissions and mappings in V14.
2. Change ordinary deletion to OFF_SALE plus parent deleted_at only; preserve SKU state, specification rows, gallery, associations, and active media usages.
3. Require the active parent SPU for every direct product/SKU mutation.
4. Add recycled=true list filtering and deletedAt output.
5. Add restore that clears deleted_at, keeps OFF_SALE, and performs best-effort repair for rows deleted by the previous behavior.
6. Add a 回收站 list state with 恢复 and 永久删除 actions only.
7. Change the ordinary-delete confirmation to explicitly promise recoverability.

Gate:

- A newly recycled product restores without losing any aggregate field or association.
- Recycled products stay unavailable to app/cart/checkout and cannot be mutated through stale ids.
- Recoverable images retain active storage usages.

## Task 20: Add Guarded Permanent Purge

Steps:

1. Require a recycled, non-purged product and exact title confirmation.
2. Reject purge while any SKU has a LOCKED stock_lock.
3. Reject purge while an enabled product banner targets the SPU.
4. Transactionally remove only product-private rows, bindings, cart rows, and product-owned media usages.
5. Preserve shared category/template/service/coupon/file rows and every order, payment, shipping, refund, after-sale, stock-lock, stock-log, and protected order-image row.
6. Replace SPU/SKU catalog content with minimal purged tombstones and set purged_at so restore is impossible.
7. Require product-name input and explicit irreversible-warning copy in the admin.
8. Run focused safety tests, backend full tests, admin typecheck/tests/lint/style/build, git diff --check, and miniprogram zero-diff verification.
