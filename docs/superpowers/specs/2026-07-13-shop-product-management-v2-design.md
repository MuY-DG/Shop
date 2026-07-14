# Shop Product Management V2 Design

Date: 2026-07-13

## 1. Goal

Extend the existing Shop product catalog into a complete backend and admin product-management workflow while preserving all current mini-program contracts.

The delivered admin scope contains:

- Product category management using the existing category tree.
- Product list filters, complete columns, status switch, visible edit and stock actions, publish/unpublish, and a recoverable recycle bin.
- A four-tab product editor: product information, specification inventory, product detail, and other settings.
- Single-spec and multi-spec products.
- Reusable specification templates.
- Product guarantee services.
- Product tags, actual and virtual sales, product video, freight-template binding, and coupon binding/creation.
- Backend enforcement for product-specific coupons and freight calculation.

No file under miniprogram is modified in this phase.

## 2. Existing Baseline

The current repository already has:

- product_category, product_spu, product_spu_image, product_sku, and stock_log.
- DRAFT, ON_SALE, and OFF_SALE product states.
- Category create/update and tree reads.
- SPU create/update, publish/unpublish, admin list/detail, and public app list/detail.
- SKU price, original price, stock, weight, image, status, sorting, and stock adjustment logs.
- Product image, SKU image, and rich-text storage usage.
- An admin product list, one long SPU drawer, AssetPicker, and WangEditor.
- Coupon scope enum values ALL, PRODUCT, and CATEGORY, although validation and calculation currently accept ALL only.
- Order snapshots containing SPU and SKU ids and existing freight_cent fields.

Compatibility fields subtitle, selling_points, spec_json, and spec_text remain present because current mini-program, cart, checkout, and order code consumes them.

## 3. Scope Boundaries

### Included

- Backend schema migrations V13 and V14.
- Product aggregate persistence and read contracts.
- Incremental SKU updates with stable ids.
- Recoverable product deletion, restore, and guarded permanent purge.
- Admin list and four-tab editor.
- Specification-template and guarantee-service management.
- Product video upload policy and storage usage.
- Product tags and sales values.
- Minimal but real freight templates: free shipping or a fixed amount.
- Product-specific coupon creation, binding, claiming, availability, and checkout validation.
- Backend, admin, migration, regression, and local smoke verification.

### Excluded

- Mini-program page, service, type, or configuration changes.
- Region-based freight rules, weight tiers, piece tiers, remote-area rules, and multi-warehouse freight.
- Member prices, flash sales, group buying, points, or additional promotion types.
- Product-review, content-community, distribution, or live-commerce features.
- Physical deletion of ordered product/SKU identities, guarantee services, orders, inventory locks, or inventory history.

## 4. Admin Information Architecture

The Product parent menu contains:

- 商品分类: preserve the existing category tree page.
- 商品管理: rename the current SPU商品 child.
- 商品规格: specification-template management.
- 保障服务: guarantee-service management.

Freight templates are managed from an inline dialog beside the product freight-template selector. Product-specific coupons are created from an inline dialog in Other Settings. They do not add extra top-level menu entries in this phase.

The current 960px editor becomes a full-page editor inside the existing /product/spu route. The page shell uses query state so it does not require a hidden backend route:

- /product/spu: list mode.
- /product/spu?mode=create: create mode.
- /product/spu?mode=edit&id=123: edit mode.

The page shell owns one aggregate form and renders four child tab components. Browser back returns to the list, the Product menu stays active, and the SKU matrix receives the full content width.

## 5. Product List Contract

### Filters

The search form retains only:

- 商品名称
- 商品分类

Product state is a separate segmented row:

- 全部
- 草稿
- 销售中
- 已下架
- 回收站

### Columns

The table shows, in order:

1. 商品 ID
2. 商品主图
3. 商品信息
4. 分类
5. 价格区间
6. 销量
7. 库存
8. 状态
9. 排序
10. 添加时间
11. 修改时间
12. 操作

For active rows, the operation column shows 编辑 and 调库存, plus 上架 or 下架 and 删除. Recycled rows show only 恢复 and 永久删除.

### State switch

Product status remains a three-state backend model.

- Switch on means ON_SALE.
- Switch off can mean DRAFT or OFF_SALE; the textual state remains visible beside the switch.
- Clicking the switch or the operation action invokes the same confirmation and the same publish/unpublish API.
- The UI changes only after the API succeeds. Cancelling or a failed API call leaves the original state unchanged.

## 6. Product Editor Contract

### Shared navigation

- Next validates the active tab before advancing.
- Previous does not discard values.
- Submit validates all required tabs and persists one product aggregate.
- Creating a product always creates DRAFT; publishing remains a separate explicit action.
- When an existing ON_SALE product is edited into an invalid publishable state, the backend rejects the update instead of silently unpublishing it.

### Product Information

Fields are ordered vertically:

1. 商品名称, required.
2. 商品分类, required.
3. 商品封面图, required.
4. 商品轮播图, optional.
5. 保障服务, optional multi-select.
6. 主图视频, optional.
7. 运费模板, required.

Compatibility fields subtitle and sellingPoints remain in the API and database. The new admin editor does not require them and does not erase existing values unless explicitly supplied.

Carousel fallback is resolved at read time:

- An empty gallery returns or previews the product cover.
- The cover is not duplicated as an extra gallery database row.

### Specification Inventory

Specification type is SINGLE or MULTI.

#### Single specification

Exactly one active SKU is persisted. Fields:

- 图片
- 售价（元）, required
- 成本价（元）, optional
- 划线价（元）, optional
- 库存, optional and defaults to zero
- 商品编码, optional and generated by the backend when blank
- 重量（g）, optional with no UI default
- 体积（m³）, optional with no UI default

The single SKU is always the default SKU.

#### Multi specification

The editor provides:

- An optional specification-template selector.
- Add specification group.
- Specification-group name with a maximum length of 30.
- Exactly one image-specification group.
- Add specification value.
- Image upload under each value of the image-specification group.
- Continue adding groups or save the current structure as a template.
- A generated SKU combination matrix.

The Cartesian product is capped at 100 SKU rows. The editor blocks combinations beyond that limit and explains which group sizes caused it.

Each generated SKU row contains:

- Attribute values
- Optional SKU image
- Sale price
- Optional cost price
- Optional strike-through price
- Stock
- Optional SKU code
- Optional weight
- Optional volume
- Default selection radio
- Enabled switch

Exactly one enabled SKU is the default. The first enabled SKU becomes the default only when no explicit default exists.

Image fallback:

1. Explicit SKU image.
2. Image on the selected value from the image-specification group.
3. Product cover image.

### Product Detail

The existing WangEditor edits detailHtml. Existing stored HTML and storage usage remain compatible.

### Other Settings

- sortOrder, optional and defaults to zero.
- virtualSales, optional and defaults to zero.
- Fixed multi-select tags:
  - PROMOTION: 促销单品
  - HOT_SALE: 是否热卖
  - HOT_RANK: 热门榜单
  - PREMIUM: 精品推荐
  - NEW_ARRIVAL: 首发新品
- Existing coupon-template selection.
- Create product-specific coupon.

Guarantee services have one canonical editor in Product Information. Other Settings may show a summary but must not maintain a second independent value.

## 7. Sales Definition

Actual sales are the cumulative sum of order_item.quantity for orders whose paid_at is not null.

- Closed unpaid orders are excluded.
- Refunded orders remain part of cumulative paid sales.
- No mutable sold counter is added, avoiding payment callback idempotency drift.
- An index on order_item.spu_id supports the aggregate.

The backend returns:

- actualSales
- virtualSales
- displaySales = actualSales + virtualSales

The list displays displaySales and may show the actual/virtual breakdown in a tooltip.

## 8. Product and SKU Lifecycle

### Product recycle bin

DELETE /admin/product/spus/{spuId} moves an active product to the recycle bin:

- The product is set to OFF_SALE and product_spu.deleted_at is set.
- SKU enabled/default state, specification rows, gallery rows, tags, guarantee bindings, coupon bindings, and active product media usages remain unchanged.
- App product reads, cart display, checkout, coupon, publish, edit, and direct SKU mutations all require an active parent SPU and therefore exclude the recycled product.
- Cart rows remain hidden and become usable again if the product is restored.
- Storage files remain protected while the product is recoverable.

POST /admin/product/spus/{spuId}/restore restores only a non-purged recycled product:

- product_spu.deleted_at is cleared.
- The product remains OFF_SALE and must be explicitly published again.
- The complete SKU/specification/media/association aggregate becomes available unchanged.
- Legacy rows deleted by the earlier V13 behavior are restored on a best-effort basis and media usages are rebuilt, but already removed tag, guarantee, or coupon associations cannot be invented.

### Permanent product purge

POST /admin/product/spus/{spuId}/purge is available only inside the recycle bin and requires the exact product title as confirmation.

- A locked stock_lock blocks the purge because order close still needs the SKU row to return inventory.
- An enabled home banner that jumps to the product blocks the purge until the banner is disabled or unlinked.
- Product-private gallery rows, specification structures, tag/service/coupon bindings, cart rows, and product-owned media usages are removed.
- Shared product categories, asset categories, freight templates, guarantee-service masters, specification templates, coupon templates, storage files, and banners are never cascade-deleted.
- shop_order, order_item snapshots, stock_lock, stock_log, payment, shipping, refund, after-sale records, and protected ORDER_ITEM_SNAPSHOT media usages are never deleted.
- The SPU and SKU primary keys become minimal purged tombstones so historical ids and audit rows stay valid. product_spu.purged_at makes the aggregate permanently non-restorable and excludes it from both active lists and the recycle bin.
- Storage objects are never deleted by product purge. File management may delete an object separately only when no active usage or historical order URL references it.

### Incremental SKU persistence

The current delete-all-and-reinsert update strategy is replaced.

- Existing SKU ids must belong to the edited SPU.
- Existing rows are updated in place.
- New combinations create new rows.
- Omitted combinations are disabled and soft deleted.
- A re-added combination restores its prior row when possible.
- Stock changes produce stock_log rows.
- SKU codes are globally unique; blank codes are generated by the backend.

### Publish validation

A product can enter ON_SALE only when:

- It is not soft deleted.
- Its category exists and is enabled.
- Title and cover are present.
- The freight template exists and is enabled.
- At least one SKU is enabled with a positive sale price.
- Exactly one enabled SKU is the default.
- SINGLE has exactly one active SKU and no active specification groups.
- MULTI has at least one group and value, exactly one image-specification group, unique combinations, and no more than 100 active SKU combinations.

## 9. Structured Specification Model

Stable string keys are used for groups and values so renaming a label does not change a SKU combination identity.

### Template tables

- product_spec_template
  - id, name, created_at, updated_at
- product_spec_template_group
  - id, template_id, group_key, name, image_enabled, sort_order
- product_spec_template_value
  - id, group_id, value_key, value_name, sort_order

Template selection copies a snapshot into the product form. Products never read live labels from a template.

Template creation and save-as-template can add groups and values. Editing an existing template may rename:

- Template name
- Existing group names
- Existing value names

Template update must submit exactly the same group ids and value ids. Backend validation rejects additions or removals even if the UI is bypassed.

### Product specification tables

- product_spu_spec_group
  - id, spu_id, group_key, name, image_enabled, sort_order, deleted_at
- product_spu_spec_value
  - id, group_id, value_key, value_name, image, image_file_id, sort_order, deleted_at
- product_sku_spec_value
  - sku_id, spec_value_id, created_at

product_sku.combination_key is a stable, sorted concatenation of value keys scoped by SPU.

spec_json and spec_text are derived compatibility snapshots and continue to be returned to the mini program.

Legacy products with no normalized specification rows remain readable. Their legacy spec_json values are synthesized into the admin editor and become normalized the first time the product is saved.

## 10. Guarantee Services

Tables:

- product_guarantee_service
  - id
  - terms_name
  - content_description
  - icon
  - icon_file_id
  - sort_order
  - visible
  - deleted_at
  - created_at
  - updated_at
- product_spu_guarantee_service
  - spu_id
  - service_id
  - sort_order
  - created_at

The list shows ID, service terms, icon, description, sort, creation time, visibility switch, edit, and delete.

Deleting a referenced service is allowed. One transaction removes active product associations and soft deletes the service. Historical rows remain available for audit and future order snapshots.

## 11. Product Tags

product_spu_tag stores one row per SPU and tag code. Tag codes are backend enums and unknown values are rejected.

The fixed tag list is not user-configurable in this phase.

## 12. Product Video and Storage

product_spu adds:

- main_video
- main_video_file_id

Storage adds a PRODUCT_VIDEO purpose and a video media kind. Allowed formats are mp4 and webm with matching content types. A separate configurable video-size limit defaults to 50 MB.

New storage usage values protect:

- Product video
- Specification-value images
- Guarantee-service icons

The admin video field supports upload, preview, replacement, and clearing. Image-only AssetPicker behavior remains unchanged for existing callers.

## 13. Freight Templates

The initial real freight model intentionally stays small:

- FREE: zero freight.
- FIXED: one fixed fee in cents.

Table freight_template contains id, name, charge_mode, fixed_amount_cent, status, sort_order, deleted_at, created_at, and updated_at.

A default enabled 全国包邮 template is seeded and assigned to existing products.

For an order containing multiple products:

- Resolve every distinct active product freight template.
- The order freight is the maximum fixed fee among those templates.
- FREE contributes zero.
- The calculated value is included in preview, payable amount, request digest, and shop_order.freight_cent.

This rule avoids charging the same parcel once per line while remaining deterministic until region and weight rules are designed.

The product editor can list templates and create or edit FREE/FIXED templates in an inline dialog.

## 14. Product Coupons

### Binding

product_spu_coupon associates coupon templates shown or selected for a product.

- An ALL coupon may be bound to one or more products for product-page presentation.
- A PRODUCT coupon must have scope_value equal to exactly one SPU id and is automatically bound to that SPU.

### Creation

POST /admin/product/spus/{spuId}/coupons creates a product-specific coupon by forcing:

- scopeType = PRODUCT
- scopeValue = the path SPU id

The product must already exist as a draft or saved product. For a new unsaved form, the admin prompts the user to save the draft first and remains in the editor after the save.

### Claim and calculation

- Direct claim by template id supports PRODUCT coupons.
- An optional SPU-filtered claimable endpoint exposes product coupons without adding mini-program UI in this phase.
- Available-coupon evaluation includes ALL and applicable PRODUCT coupons.
- PRODUCT threshold and discount caps use only matching SPU line amounts.
- Total payable amount remains at least one cent.
- CATEGORY behavior remains rejected until it receives an explicit design; this phase does not silently claim to implement it.

## 15. API Surface

Existing endpoints remain and are extended compatibly:

- GET /admin/product/categories
- POST /admin/product/categories
- PUT /admin/product/categories/{categoryId}
- GET /admin/product/spus
- GET /admin/product/spus/{spuId}
- POST /admin/product/spus
- PUT /admin/product/spus/{spuId}
- POST /admin/product/spus/{spuId}/publish
- POST /admin/product/spus/{spuId}/unpublish
- POST /admin/product/skus/{skuId}/stock-adjustments

New endpoints:

- DELETE /admin/product/spus/{spuId}
- POST /admin/product/spus/{spuId}/restore
- POST /admin/product/spus/{spuId}/purge
- GET /admin/product/spec-templates
- GET /admin/product/spec-templates/{templateId}
- POST /admin/product/spec-templates
- PUT /admin/product/spec-templates/{templateId}
- POST /admin/product/spus/{spuId}/spec-template
- GET /admin/product/guarantee-services
- POST /admin/product/guarantee-services
- PUT /admin/product/guarantee-services/{serviceId}
- POST /admin/product/guarantee-services/{serviceId}/visibility
- DELETE /admin/product/guarantee-services/{serviceId}
- GET /admin/product/freight-templates
- POST /admin/product/freight-templates
- PUT /admin/product/freight-templates/{templateId}
- GET /admin/product/spus/{spuId}/coupons
- PUT /admin/product/spus/{spuId}/coupons
- POST /admin/product/spus/{spuId}/coupons
- GET /app/product/spus/{spuId}/coupons

All admin endpoints use the existing response envelope and ADMIN-token boundary. Read and mutation methods also use the matching method-level PreAuthorize checks; button visibility is not treated as the security boundary.

## 16. RBAC

V13 adds:

- product:spu:delete
- product:spec-template:create
- product:spec-template:update
- product:guarantee:create
- product:guarantee:update
- product:guarantee:delete
- product:guarantee:visibility
- product:freight:create
- product:freight:update
- product:coupon:bind
- product:coupon:create

V14 adds:

- product:spu:restore
- product:spu:purge

The Super role receives the new menu and permission mappings. Existing role-management screens can grant them to other roles.

Controller tests must prove that an authenticated administrator without each required authority receives HTTP 403.

## 17. Migration V13

V13__product_management_v2.sql owns:

- New product_spu and product_sku columns.
- Freight-template, specification-template, product-specification, guarantee-service, tag, and coupon-binding tables.
- Required indexes.
- Default freight template and legacy product backfill.
- Product menu rename and new specification/guarantee menu rows.
- New permissions and Super-role mappings.

Previously applied migrations are never edited.

## 17.1 Migration V14

V14__product_recycle_bin.sql owns:

- product_spu.purged_at and the active/recycled/purged lookup index.
- Restore and purge permissions plus Super-role and product-menu mappings.
- No destructive backfill. Existing product rows remain active or recycled according to their current deleted_at value.

## 18. Compatibility and No-Mini-Program Rule

- No miniprogram file is edited.
- Existing app product JSON fields remain present.
- Additional backend response fields are additive.
- App reads exclude soft-deleted SPUs and SKUs.
- specJson and specText remain generated for every active SKU.
- Existing image URL snapshots and file ids remain supported.
- Checkout, coupon, order, payment, and stock-close tests run because product changes affect their backend dependencies.

## 19. Verification

Completion requires:

- V13/V14 schema tests on H2 MySQL mode.
- Clean-schema and legacy-to-V14 MySQL migration verification.
- Product aggregate, template, guarantee, video, freight, coupon-scope, recycle, restore, purge, publish, stock-log, and list-sales tests.
- Explicit regression tests proving historical order snapshots and protected order images survive purge, locked inventory blocks purge, and shared categories/templates/files are retained.
- Existing product, cart, checkout, order, coupon, payment, storage, and fulfillment tests.
- Full backend test suite.
- Admin production build.
- Existing mini-program typecheck and tests, without changing mini-program source.
- Local real-backend smoke for product creation, single/multi specifications, template reuse, service deletion, status switch, soft deletion, product coupon, and fixed freight.
- git diff --check.
- A final diff audit proving no miniprogram path changed.
