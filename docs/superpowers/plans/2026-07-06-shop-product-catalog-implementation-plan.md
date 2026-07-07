# Shop Product Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Milestone 2 product catalog for the Shop system: categories, SPU products, multi-spec SKU variants, SKU price, stock, images, publish state, admin product management APIs, mini program list/detail APIs, and real local smoke checks.

**Architecture:** Implement product catalog as the first commerce vertical slice in the existing modular monolith. The backend owns product state in MySQL through Flyway migrations, exposes authenticated `/admin/product/**` management APIs for Art Design Pro, and exposes read-only `/app/product/**` browsing APIs for the native WeChat mini program. Admin and mini program frontend work consumes those backend contracts without adding cart, order, coupon, payment, shipment, or refund behavior in this phase.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security, MyBatis-Plus 3.5.16, Flyway, MySQL 8/H2 test profile, Redis token infrastructure already present, Art Design Pro, Vue 3, TypeScript, Vite, Element Plus, native WeChat mini program TypeScript, TDesign MiniProgram.

## Global Constraints

- API response envelope remains `{ "code": 200, "msg": "success", "data": {} }`.
- Admin paged APIs return `data.records`, `data.total`, `data.current`, and `data.size`.
- Admin APIs require an `ADMIN` token and live under `/admin/product/**`.
- Mini program catalog browsing APIs live under `/app/product/**`; category/list/detail `GET` APIs are public read-only endpoints and must not expose user data.
- Money is stored and returned as integer cents: `priceCent`, `originalPriceCent`, `minPriceCent`, `maxPriceCent`.
- SKU stock is stored as integer units on `product_sku.stock_available`; order stock lock is excluded from this phase and remains for the order phase.
- Product publish state uses `DRAFT`, `ON_SALE`, and `OFF_SALE`.
- Category state uses `ENABLED` and `DISABLED`.
- SKU state uses `ENABLED` and `DISABLED`.
- No member levels, points, group buying, flash sale, distribution, live commerce, cart, checkout, coupon, order, payment, shipment, after-sale, or refund behavior is implemented in this phase.
- Backend tests run on the existing `test` profile with H2 in MySQL mode.
- Real local smoke uses the local backend and database path; it is not a mocked product service.
- Do not log secrets, WeChat tokens, login codes, phone codes, or production credentials.

---

## Scope Boundary

Included:

- Product category schema, entity, service, admin API, app API, and admin page.
- SPU schema, entity, service, admin API, app list/detail API, admin page, and mini program pages.
- Multi-SKU schema with spec JSON, spec text, SKU code, price, original price, stock, weight, image, status, and sort order.
- SPU image gallery table.
- Stock adjustment log for admin SKU stock changes.
- Product admin menu and permission seed data for backend-driven Art Design Pro routes.
- Backend controller/service/schema tests.
- Admin production build.
- Mini program TypeScript typecheck.
- Product catalog smoke checks.

Excluded:

- Cart item APIs and cart page.
- Checkout price calculation.
- Coupon and promotion scope matching.
- Order item snapshots.
- Stock lock, release, and payment-confirmed deduction.
- WeChat Pay, shipment upload, after-sale, and refund.
- File upload/storage adapter. This phase accepts image URLs supplied by admin input.

## References

- Product design: `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`
- Prior foundation plan: `docs/superpowers/plans/2026-07-06-shop-foundation-implementation-plan.md`
- Prior auth/RBAC plan: `docs/superpowers/plans/2026-07-06-shop-auth-rbac-implementation-plan.md`
- Local setup and auth smoke truth: `docs/dev-setup.md`

## API Contracts

Admin category tree:

```http
GET /admin/product/categories
Authorization: Bearer adm_access_token
```

```json
[
  {
    "id": 1,
    "parentId": 0,
    "name": "牛油锅底",
    "icon": "",
    "sortOrder": 10,
    "status": "ENABLED",
    "children": []
  }
]
```

Admin create/update category:

```http
POST /admin/product/categories
Authorization: Bearer adm_access_token
Content-Type: application/json

{
  "parentId": 0,
  "name": "牛油锅底",
  "icon": "",
  "sortOrder": 10,
  "status": "ENABLED"
}
```

Admin SPU page:

```http
GET /admin/product/spus?current=1&size=20&title=牛油&categoryId=1&status=ON_SALE
Authorization: Bearer adm_access_token
```

```json
{
  "records": [
    {
      "id": 100,
      "categoryId": 1,
      "categoryName": "牛油锅底",
      "title": "重庆牛油火锅底料",
      "subtitle": "厚重牛油香",
      "mainImage": "https://example.test/hotpot-main.jpg",
      "status": "ON_SALE",
      "sortOrder": 10,
      "minPriceCent": 3990,
      "maxPriceCent": 6990,
      "totalStock": 120,
      "skuCount": 2,
      "createdAt": "2026-07-06T12:00:00",
      "updatedAt": "2026-07-06T12:00:00"
    }
  ],
  "total": 1,
  "current": 1,
  "size": 20
}
```

Admin create/update SPU with SKUs:

```http
POST /admin/product/spus
Authorization: Bearer adm_access_token
Content-Type: application/json

{
  "categoryId": 1,
  "title": "重庆牛油火锅底料",
  "subtitle": "厚重牛油香",
  "mainImage": "https://example.test/hotpot-main.jpg",
  "sellingPoints": "牛油浓香,手工炒制",
  "detailHtml": "<p>适合3-5人火锅。</p>",
  "sortOrder": 10,
  "images": [
    {
      "url": "https://example.test/hotpot-gallery-1.jpg",
      "sortOrder": 1
    }
  ],
  "skus": [
    {
      "skuCode": "HY-NY-300G",
      "specJson": "{\"口味\":\"牛油\",\"重量\":\"300g\"}",
      "specText": "牛油 / 300g",
      "priceCent": 3990,
      "originalPriceCent": 4990,
      "stockAvailable": 100,
      "weightGram": 300,
      "image": "https://example.test/hotpot-sku-300.jpg",
      "status": "ENABLED",
      "sortOrder": 1
    }
  ]
}
```

Admin publish/unpublish:

```http
POST /admin/product/spus/100/publish
Authorization: Bearer adm_access_token
```

```http
POST /admin/product/spus/100/unpublish
Authorization: Bearer adm_access_token
```

Admin SKU stock adjustment:

```http
POST /admin/product/skus/1000/stock-adjustments
Authorization: Bearer adm_access_token
Content-Type: application/json

{
  "quantityDelta": 20,
  "reason": "首次入库"
}
```

Mini program category list:

```http
GET /app/product/categories
```

Mini program product list:

```http
GET /app/product/spus?current=1&size=10&categoryId=1&keyword=牛油
```

```json
{
  "records": [
    {
      "id": 100,
      "categoryId": 1,
      "title": "重庆牛油火锅底料",
      "subtitle": "厚重牛油香",
      "mainImage": "https://example.test/hotpot-main.jpg",
      "sellingPoints": ["牛油浓香", "手工炒制"],
      "minPriceCent": 3990,
      "maxPriceCent": 6990,
      "totalStock": 120
    }
  ],
  "total": 1,
  "current": 1,
  "size": 10
}
```

Mini program product detail:

```http
GET /app/product/spus/100
```

```json
{
  "id": 100,
  "categoryId": 1,
  "categoryName": "牛油锅底",
  "title": "重庆牛油火锅底料",
  "subtitle": "厚重牛油香",
  "mainImage": "https://example.test/hotpot-main.jpg",
  "sellingPoints": ["牛油浓香", "手工炒制"],
  "detailHtml": "<p>适合3-5人火锅。</p>",
  "images": [
    {
      "id": 500,
      "url": "https://example.test/hotpot-gallery-1.jpg",
      "sortOrder": 1
    }
  ],
  "skus": [
    {
      "id": 1000,
      "skuCode": "HY-NY-300G",
      "specJson": "{\"口味\":\"牛油\",\"重量\":\"300g\"}",
      "specText": "牛油 / 300g",
      "priceCent": 3990,
      "originalPriceCent": 4990,
      "stockAvailable": 100,
      "weightGram": 300,
      "image": "https://example.test/hotpot-sku-300.jpg",
      "status": "ENABLED"
    }
  ]
}
```

## File Structure

Planned files and responsibilities:

```text
backend/shop-server/src/main/java/org/muybaby/shopserver/
  product/
    ProductStatus.java                         SPU lifecycle values.
    CategoryStatus.java                        Category availability values.
    SkuStatus.java                             SKU availability values.
    StockChangeType.java                       Stock log change values.
    AdminProductCategoryController.java        Admin category tree and writes.
    AdminProductSpuController.java             Admin SPU/SKU page, detail, create, update, publish.
    AdminProductSkuController.java             Admin SKU stock adjustment endpoint.
    AppProductController.java                  Mini program category/list/detail browsing APIs.
    dto/
      AdminCategoryRequest.java
      AdminCategoryResponse.java
      AdminSpuListItemResponse.java
      AdminSpuDetailResponse.java
      AdminSpuUpsertRequest.java
      AdminSkuUpsertRequest.java
      AdminSpuQueryRequest.java
      AdminStockAdjustmentRequest.java
      AppCategoryResponse.java
      AppSpuListItemResponse.java
      AppSpuDetailResponse.java
      AppSkuResponse.java
      ProductImageResponse.java
      ProductPageRequest.java
    entity/
      ProductCategory.java
      ProductSpu.java
      ProductSpuImage.java
      ProductSku.java
      StockLog.java
    mapper/
      ProductCategoryMapper.java
      ProductSpuMapper.java
      ProductSpuImageMapper.java
      ProductSkuMapper.java
      StockLogMapper.java
    service/
      AdminProductService.java                 Admin product write/read workflow.
      AppProductService.java                   Public read model for mini program.
      ProductReadMapper.java                   JdbcClient joins and aggregate projections.

backend/shop-server/src/main/resources/db/migration/
  V3__product_catalog.sql                      Product schema, indexes, menu, permissions.

backend/shop-server/src/test/java/org/muybaby/shopserver/product/
  ProductCatalogSchemaTest.java
  AdminProductCategoryControllerTest.java
  AdminProductSpuControllerTest.java
  AppProductControllerTest.java
  AdminProductServiceTest.java

admin/src/
  api/product.ts                               Product admin HTTP functions.
  types/api/api.d.ts                           `Api.Product` namespace.
  views/product/category/index.vue             Category tree management.
  views/product/category/modules/category-dialog.vue
  views/product/spu/index.vue                  SPU table and publish controls.
  views/product/spu/modules/spu-editor.vue     SPU, images, and SKU editor dialog.

miniprogram/
  services/product.ts                          Mini program product API calls.
  types/api.ts                                 Product response types.
  app.json                                     Register product list/detail pages and category tab.
  pages/home/home.ts                           Load featured products instead of health-only state.
  pages/home/home.wxml
  pages/home/home.wxss
  pages/product/list/list.json
  pages/product/list/list.ts
  pages/product/list/list.wxml
  pages/product/list/list.wxss
  pages/product/detail/detail.json
  pages/product/detail/detail.ts
  pages/product/detail/detail.wxml
  pages/product/detail/detail.wxss

docs/
  smoke-checks.md                              Add product catalog smoke commands.
```

## Task 1: Product Schema, Menu, Permissions, And Smoke Seed Boundary

**Files:**

- Create: `backend/shop-server/src/main/resources/db/migration/V3__product_catalog.sql`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/product/ProductCatalogSchemaTest.java`

**Interfaces:**

- Produces tables: `product_category`, `product_spu`, `product_spu_image`, `product_sku`, `stock_log`.
- Produces admin menus: Product root, Category page, SPU page.
- Produces permission marks: `product:category:create`, `product:category:update`, `product:spu:create`, `product:spu:update`, `product:spu:publish`, `product:sku:stock`.

- [ ] **Step 1: Write the failing schema test**

Create `ProductCatalogSchemaTest`:

```java
package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProductCatalogSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void productTablesAcceptCategorySpuSkuImageAndStockLogRows() {
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, icon, sort_order, status)
                        values (9901, 0, 'Schema Category', '', 1, 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu (id, category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values (9902, 9901, 'Schema SPU', 'Schema subtitle', 'https://example.test/main.jpg', 'A,B', '<p>detail</p>', 1, 'DRAFT')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu_image (id, spu_id, url, sort_order)
                        values (9903, 9902, 'https://example.test/gallery.jpg', 1)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_sku (id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent, stock_available, weight_gram, image, status, sort_order)
                        values (9904, 9902, 'SCHEMA-SKU', '{"口味":"牛油"}', '牛油', 3990, 4990, 10, 300, 'https://example.test/sku.jpg', 'ENABLED', 1)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into stock_log (id, sku_id, change_type, quantity_before, quantity_delta, quantity_after, reason, operator_type, operator_id)
                        values (9905, 9904, 'INITIAL', 0, 10, 10, 'schema test', 'SYSTEM', 0)
                        """)
                .update();

        Integer skuCount = jdbcClient.sql("select count(*) from product_sku where spu_id = 9902")
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("select count(*) from admin_permission where auth_mark like 'product:%'")
                .query(Integer.class)
                .single();

        assertThat(skuCount).isEqualTo(1);
        assertThat(permissionCount).isGreaterThanOrEqualTo(6);
    }
}
```

- [ ] **Step 2: Run the schema test and verify it fails before migration exists**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=ProductCatalogSchemaTest test
```

Expected:

```text
Table "product_category" not found
```

- [ ] **Step 3: Add the Flyway migration**

Create `V3__product_catalog.sql`:

```sql
CREATE TABLE product_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(64) NOT NULL,
    icon VARCHAR(255) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_category_parent_name UNIQUE (parent_id, name)
);

CREATE TABLE product_spu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    subtitle VARCHAR(255) NOT NULL DEFAULT '',
    main_image VARCHAR(500) NOT NULL DEFAULT '',
    selling_points TEXT NOT NULL,
    detail_html TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_spu_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    spu_id BIGINT NOT NULL,
    url VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product_sku (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    spu_id BIGINT NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    spec_json TEXT NOT NULL,
    spec_text VARCHAR(255) NOT NULL,
    price_cent BIGINT NOT NULL,
    original_price_cent BIGINT NOT NULL DEFAULT 0,
    stock_available INT NOT NULL DEFAULT 0,
    weight_gram INT NOT NULL DEFAULT 0,
    image VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_sku_code UNIQUE (sku_code),
    CONSTRAINT uk_product_sku_spu_spec UNIQUE (spu_id, spec_text)
);

CREATE TABLE stock_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku_id BIGINT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    quantity_before INT NOT NULL,
    quantity_delta INT NOT NULL,
    quantity_after INT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    operator_type VARCHAR(20) NOT NULL,
    operator_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_product_category_parent_sort ON product_category(parent_id, sort_order);
CREATE INDEX idx_product_spu_category_status_sort ON product_spu(category_id, status, sort_order);
CREATE INDEX idx_product_spu_status_sort ON product_spu(status, sort_order);
CREATE INDEX idx_product_spu_image_spu_sort ON product_spu_image(spu_id, sort_order);
CREATE INDEX idx_product_sku_spu_status_sort ON product_sku(spu_id, status, sort_order);
CREATE INDEX idx_stock_log_sku_created ON stock_log(sku_id, created_at);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (2001, 'product:category:create', 'Create product category'),
    (2002, 'product:category:update', 'Update product category'),
    (2101, 'product:spu:create', 'Create product SPU'),
    (2102, 'product:spu:update', 'Update product SPU'),
    (2103, 'product:spu:publish', 'Publish product SPU'),
    (2201, 'product:sku:stock', 'Adjust SKU stock');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (300, NULL, 'Product', '/product', '/index/index', '商品管理', 'ri:shopping-bag-3-line', 30, FALSE, TRUE, TRUE),
    (301, 300, 'ProductCategory', 'category', '/product/category', '商品分类', 'ri:folder-3-line', 31, TRUE, TRUE, TRUE),
    (302, 300, 'ProductSpu', 'spu', '/product/spu', 'SPU商品', 'ri:shopping-bag-line', 32, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 300), (1, 301), (1, 302);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 2001), (1, 2002), (1, 2101), (1, 2102), (1, 2103), (1, 2201);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (301, 2001), (301, 2002),
    (302, 2101), (302, 2102), (302, 2103), (302, 2201);
```

- [ ] **Step 4: Run the schema test and full migration path**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=ProductCatalogSchemaTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add backend/shop-server/src/main/resources/db/migration/V3__product_catalog.sql backend/shop-server/src/test/java/org/muybaby/shopserver/product/ProductCatalogSchemaTest.java
git commit -m "feat: add product catalog schema"
```

## Task 2: Backend Product Domain And Admin Service

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/ProductStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/CategoryStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/SkuStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/StockChangeType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/entity/ProductCategory.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/entity/ProductSpu.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/entity/ProductSpuImage.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/entity/ProductSku.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/entity/StockLog.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/mapper/ProductCategoryMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/mapper/ProductSpuMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/mapper/ProductSpuImageMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/mapper/ProductSkuMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/mapper/StockLogMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminCategoryRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminSpuUpsertRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminSkuUpsertRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminStockAdjustmentRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/service/AdminProductService.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/product/AdminProductServiceTest.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`

**Interfaces:**

- Consumes tables from Task 1.
- Produces `AdminProductService#createCategory`, `#updateCategory`, `#createSpu`, `#updateSpu`, `#publishSpu`, `#unpublishSpu`, `#adjustSkuStock`.
- Produces validation used by admin controllers in Tasks 3 and 4.

- [ ] **Step 1: Write failing service tests**

Create `AdminProductServiceTest`:

```java
package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminStockAdjustmentRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AdminProductServiceTest {

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createSpuPersistsImagesSkusAndInitialStockLog() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Service Category", "", 1, "ENABLED"));

        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Service SPU",
                "Service subtitle",
                "https://example.test/main.jpg",
                "A,B",
                "<p>detail</p>",
                1,
                List.of("https://example.test/gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(
                        null,
                        "SERVICE-SKU-1",
                        "{\"口味\":\"牛油\"}",
                        "牛油",
                        3990L,
                        4990L,
                        8,
                        300,
                        "https://example.test/sku.jpg",
                        "ENABLED",
                        1
                ))
        ));

        Integer imageCount = jdbcClient.sql("select count(*) from product_spu_image where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        Integer skuCount = jdbcClient.sql("select count(*) from product_sku where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        Integer stockLogCount = jdbcClient.sql("""
                        select count(*)
                        from stock_log l
                        join product_sku s on s.id = l.sku_id
                        where s.spu_id = :spuId and l.change_type = 'INITIAL'
                        """)
                .param("spuId", spuId)
                .query(Integer.class)
                .single();

        assertThat(imageCount).isEqualTo(1);
        assertThat(skuCount).isEqualTo(1);
        assertThat(stockLogCount).isEqualTo(1);
    }

    @Test
    void publishRequiresEnabledCategoryAndEnabledSku() {
        Long disabledCategoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Disabled Category", "", 1, "DISABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                disabledCategoryId,
                "Unpublishable SPU",
                "",
                "https://example.test/main.jpg",
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "UNPUBLISHABLE-SKU", "{}", "默认", 1990L, 0L, 1, 100, "", "ENABLED", 1))
        ));

        assertThatThrownBy(() -> adminProductService.publishSpu(spuId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
    }

    @Test
    void adjustSkuStockWritesAdjustmentLog() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Stock Category", "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Stock SPU",
                "",
                "https://example.test/main.jpg",
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "STOCK-SKU", "{}", "默认", 1990L, 0L, 5, 100, "", "ENABLED", 1))
        ));
        Long skuId = jdbcClient.sql("select id from product_sku where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single();

        adminProductService.adjustSkuStock(skuId, new AdminStockAdjustmentRequest(7, "追加库存"), 1L);

        Integer stock = jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single();
        Integer adjustmentLogs = jdbcClient.sql("select count(*) from stock_log where sku_id = :skuId and change_type = 'ADJUST'")
                .param("skuId", skuId)
                .query(Integer.class)
                .single();

        assertThat(stock).isEqualTo(12);
        assertThat(adjustmentLogs).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run service tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminProductServiceTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
```

- [ ] **Step 3: Add domain enums, entities, mappers, DTO records, and service contracts**

Create the enums with these exact values:

```java
package org.muybaby.shopserver.product;

public enum ProductStatus {
    DRAFT,
    ON_SALE,
    OFF_SALE
}
```

```java
package org.muybaby.shopserver.product;

public enum CategoryStatus {
    ENABLED,
    DISABLED
}
```

```java
package org.muybaby.shopserver.product;

public enum SkuStatus {
    ENABLED,
    DISABLED
}
```

```java
package org.muybaby.shopserver.product;

public enum StockChangeType {
    INITIAL,
    ADJUST
}
```

Entity pattern:

```java
package org.muybaby.shopserver.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("product_category")
public record ProductCategory(
        @TableId(type = IdType.AUTO) Long id,
        Long parentId,
        String name,
        String icon,
        Integer sortOrder,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

Create `ProductSpu`, `ProductSpuImage`, `ProductSku`, and `StockLog` with fields matching the migration column names converted to camel case. Each entity uses `@TableName("<table_name>")` and `@TableId(type = IdType.AUTO)` on `id`.

Create mapper interfaces:

```java
package org.muybaby.shopserver.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.product.entity.ProductCategory;

@Mapper
public interface ProductCategoryMapper extends BaseMapper<ProductCategory> {
}
```

Create the remaining mappers with the same pattern for `ProductSpu`, `ProductSpuImage`, `ProductSku`, and `StockLog`.

Create DTO records:

```java
package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminCategoryRequest(
        @NotNull Long parentId,
        @NotBlank String name,
        String icon,
        @NotNull @Min(0) Integer sortOrder,
        @NotBlank String status
) {
}
```

```java
package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AdminSpuUpsertRequest(
        @NotNull Long categoryId,
        @NotBlank String title,
        String subtitle,
        @NotBlank String mainImage,
        String sellingPoints,
        String detailHtml,
        @NotNull @Min(0) Integer sortOrder,
        List<String> images,
        @Valid List<AdminSkuUpsertRequest> skus
) {
}
```

```java
package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminSkuUpsertRequest(
        Long id,
        @NotBlank String skuCode,
        @NotBlank String specJson,
        @NotBlank String specText,
        @NotNull @Min(1) Long priceCent,
        @NotNull @Min(0) Long originalPriceCent,
        @NotNull @Min(0) Integer stockAvailable,
        @NotNull @Min(0) Integer weightGram,
        String image,
        @NotBlank String status,
        @NotNull @Min(0) Integer sortOrder
) {
}
```

```java
package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminStockAdjustmentRequest(
        Integer quantityDelta,
        @NotBlank String reason
) {
}
```

Keep existing product error codes and add only the missing category code in `ErrorCode`. The enum block must contain these product entries exactly once:

```java
PRODUCT_CATEGORY_UNAVAILABLE(200000, "Product category unavailable"),
PRODUCT_UNAVAILABLE(200001, "Product unavailable"),
SKU_UNAVAILABLE(200002, "SKU unavailable"),
STOCK_SHORTAGE(200100, "Stock shortage"),
```

- [ ] **Step 4: Implement `AdminProductService` transaction boundaries**

`AdminProductService` must be annotated with `@Service`. Methods that write data must be annotated with `@Transactional`.

Required method signatures:

```java
public Long createCategory(AdminCategoryRequest request)
public void updateCategory(Long categoryId, AdminCategoryRequest request)
public Long createSpu(AdminSpuUpsertRequest request)
public void updateSpu(Long spuId, AdminSpuUpsertRequest request)
public void publishSpu(Long spuId)
public void unpublishSpu(Long spuId)
public void adjustSkuStock(Long skuId, AdminStockAdjustmentRequest request, Long operatorId)
```

Required rules:

- `createCategory` and `updateCategory` accept only `ENABLED` or `DISABLED`.
- `createSpu` stores the SPU as `DRAFT`.
- `createSpu` inserts image rows in request order.
- `createSpu` inserts SKU rows and writes one `INITIAL` stock log per SKU.
- `updateSpu` replaces image rows and SKU rows for the SPU inside one transaction.
- `publishSpu` requires an enabled category, non-blank title, non-blank main image, and at least one enabled SKU with positive price.
- `unpublishSpu` changes status to `OFF_SALE`.
- `adjustSkuStock` rejects a final stock below zero with `ErrorCode.STOCK_SHORTAGE`.
- `adjustSkuStock` writes one `ADJUST` stock log with `operator_type = 'ADMIN'` and the authenticated admin id.

- [ ] **Step 5: Run the service tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminProductServiceTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/product backend/shop-server/src/test/java/org/muybaby/shopserver/product/AdminProductServiceTest.java backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java
git commit -m "feat: add product catalog domain service"
```

## Task 3: Admin Category API

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/AdminProductCategoryController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminCategoryResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/service/ProductReadMapper.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/product/AdminProductCategoryControllerTest.java`

**Interfaces:**

- Consumes `AdminProductService` from Task 2.
- Produces `GET /admin/product/categories`.
- Produces `POST /admin/product/categories`.
- Produces `PUT /admin/product/categories/{categoryId}`.

- [ ] **Step 1: Write controller tests**

Create `AdminProductCategoryControllerTest`:

```java
package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminProductCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void adminCanCreateUpdateAndListCategories() throws Exception {
        String token = loginAndExtractToken();

        String createResponse = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Controller Category","icon":"","sortOrder":1,"status":"ENABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long categoryId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(put("/admin/product/categories/" + categoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Controller Category Updated","icon":"","sortOrder":2,"status":"ENABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/product/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", hasItem("Controller Category Updated")));
    }

    @Test
    void appTokenCannotCallAdminCategoryApi() throws Exception {
        String appToken = appLoginAndExtractToken();

        mockMvc.perform(get("/admin/product/categories")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String appLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-login-code"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
```

- [ ] **Step 2: Run category controller tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminProductCategoryControllerTest test
```

Expected:

```text
404
```

- [ ] **Step 3: Implement category response and tree read model**

Create `AdminCategoryResponse`:

```java
package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AdminCategoryResponse(
        Long id,
        Long parentId,
        String name,
        String icon,
        Integer sortOrder,
        String status,
        List<AdminCategoryResponse> children
) {
}
```

Add `ProductReadMapper#adminCategoryTree()` using `JdbcClient` ordered by `parent_id`, `sort_order`, and `id`. It must return root categories where `parent_id = 0` with recursive `children`.

- [ ] **Step 4: Implement `AdminProductCategoryController`**

Required controller:

```java
package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminCategoryResponse;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/product/categories")
public class AdminProductCategoryController {

    private final AdminProductService adminProductService;
    private final ProductReadMapper productReadMapper;

    public AdminProductCategoryController(AdminProductService adminProductService, ProductReadMapper productReadMapper) {
        this.adminProductService = adminProductService;
        this.productReadMapper = productReadMapper;
    }

    @GetMapping
    public ApiResponse<List<AdminCategoryResponse>> list() {
        return ApiResponse.success(productReadMapper.adminCategoryTree());
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody AdminCategoryRequest request) {
        return ApiResponse.success(adminProductService.createCategory(request));
    }

    @PutMapping("/{categoryId}")
    public ApiResponse<Void> update(@PathVariable Long categoryId, @Valid @RequestBody AdminCategoryRequest request) {
        adminProductService.updateCategory(categoryId, request);
        return ApiResponse.success();
    }
}
```

- [ ] **Step 5: Run category controller tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminProductCategoryControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/product/AdminProductCategoryController.java backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminCategoryResponse.java backend/shop-server/src/main/java/org/muybaby/shopserver/product/service/ProductReadMapper.java backend/shop-server/src/test/java/org/muybaby/shopserver/product/AdminProductCategoryControllerTest.java
git commit -m "feat: add admin product category api"
```

## Task 4: Admin SPU, SKU, Publish, And Stock APIs

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/AdminProductSpuController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/AdminProductSkuController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminSpuQueryRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminSpuListItemResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AdminSpuDetailResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/ProductImageResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AppSkuResponse.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/product/AdminProductSpuControllerTest.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/service/ProductReadMapper.java`

**Interfaces:**

- Consumes `AdminProductService` from Task 2 and `ProductReadMapper` from Task 3.
- Produces `GET /admin/product/spus`.
- Produces `GET /admin/product/spus/{spuId}`.
- Produces `POST /admin/product/spus`.
- Produces `PUT /admin/product/spus/{spuId}`.
- Produces `POST /admin/product/spus/{spuId}/publish`.
- Produces `POST /admin/product/spus/{spuId}/unpublish`.
- Produces `POST /admin/product/skus/{skuId}/stock-adjustments`.

- [ ] **Step 1: Write admin SPU controller tests**

Create `AdminProductSpuControllerTest`:

```java
package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminProductSpuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void adminCanCreatePublishListDetailUnpublishAndAdjustStock() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);

        String createResponse = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Controller SPU",
                                  "subtitle": "Controller subtitle",
                                  "mainImage": "https://example.test/main.jpg",
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p>detail</p>",
                                  "sortOrder": 1,
                                  "images": ["https://example.test/gallery.jpg"],
                                  "skus": [
                                    {
                                      "skuCode": "CTRL-SKU-1",
                                      "specJson": "{\\"口味\\":\\"牛油\\"}",
                                      "specText": "牛油",
                                      "priceCent": 3990,
                                      "originalPriceCent": 4990,
                                      "stockAvailable": 5,
                                      "weightGram": 300,
                                      "image": "https://example.test/sku.jpg",
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(post("/admin/product/spus/" + spuId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .param("current", "1")
                        .param("size", "20")
                        .param("title", "Controller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.records[0].status").value("ON_SALE"));

        String detailResponse = mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].stockAvailable").value(5))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long skuId = objectMapper.readTree(detailResponse).path("data").path("skus").get(0).path("id").asLong();

        mockMvc.perform(post("/admin/product/skus/" + skuId + "/stock-adjustments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityDelta": 3, "reason": "controller adjustment"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/product/spus/" + spuId + "/unpublish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private long createCategory(String token) throws Exception {
        String response = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"SPU Controller Category","icon":"","sortOrder":1,"status":"ENABLED"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
```

- [ ] **Step 2: Run admin SPU controller tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminProductSpuControllerTest test
```

Expected:

```text
404
```

- [ ] **Step 3: Implement admin SPU query and response DTOs**

Create `AdminSpuQueryRequest`:

```java
package org.muybaby.shopserver.product.dto;

public record AdminSpuQueryRequest(
        Long categoryId,
        String title,
        String status,
        Long current,
        Long size
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 20 : Math.min(size, 100);
    }
}
```

Create response records:

```java
package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;

public record AdminSpuListItemResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String subtitle,
        String mainImage,
        String status,
        Integer sortOrder,
        Long minPriceCent,
        Long maxPriceCent,
        Integer totalStock,
        Integer skuCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

```java
package org.muybaby.shopserver.product.dto;

public record ProductImageResponse(Long id, String url, Integer sortOrder) {
}
```

```java
package org.muybaby.shopserver.product.dto;

public record AppSkuResponse(
        Long id,
        String skuCode,
        String specJson,
        String specText,
        Long priceCent,
        Long originalPriceCent,
        Integer stockAvailable,
        Integer weightGram,
        String image,
        String status
) {
}
```

```java
package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminSpuDetailResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String subtitle,
        String mainImage,
        String sellingPoints,
        String detailHtml,
        Integer sortOrder,
        String status,
        List<ProductImageResponse> images,
        List<AppSkuResponse> skus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

- [ ] **Step 4: Implement read mapper queries**

Add these methods to `ProductReadMapper`:

```java
public PageResult<AdminSpuListItemResponse> adminSpuPage(AdminSpuQueryRequest query)
public AdminSpuDetailResponse adminSpuDetail(Long spuId)
```

`adminSpuPage` must join category and aggregate SKU stats:

```sql
select s.id, s.category_id, c.name as category_name, s.title, s.subtitle, s.main_image,
       s.status, s.sort_order, s.created_at, s.updated_at,
       min(k.price_cent) as min_price_cent,
       max(k.price_cent) as max_price_cent,
       coalesce(sum(k.stock_available), 0) as total_stock,
       count(k.id) as sku_count
from product_spu s
join product_category c on c.id = s.category_id
left join product_sku k on k.spu_id = s.id
where (:categoryId is null or s.category_id = :categoryId)
  and (:status is null or s.status = :status)
  and (:title is null or s.title like :titleLike)
group by s.id, s.category_id, c.name, s.title, s.subtitle, s.main_image, s.status, s.sort_order, s.created_at, s.updated_at
order by s.sort_order asc, s.id desc
limit :limit offset :offset
```

- [ ] **Step 5: Implement controllers**

`AdminProductSpuController` routes:

```java
@GetMapping
public ApiResponse<PageResult<AdminSpuListItemResponse>> page(AdminSpuQueryRequest query)

@GetMapping("/{spuId}")
public ApiResponse<AdminSpuDetailResponse> detail(@PathVariable Long spuId)

@PostMapping
public ApiResponse<Long> create(@Valid @RequestBody AdminSpuUpsertRequest request)

@PutMapping("/{spuId}")
public ApiResponse<Void> update(@PathVariable Long spuId, @Valid @RequestBody AdminSpuUpsertRequest request)

@PostMapping("/{spuId}/publish")
public ApiResponse<Void> publish(@PathVariable Long spuId)

@PostMapping("/{spuId}/unpublish")
public ApiResponse<Void> unpublish(@PathVariable Long spuId)
```

`AdminProductSkuController` route:

```java
@PostMapping("/{skuId}/stock-adjustments")
public ApiResponse<Void> adjustStock(
        @PathVariable Long skuId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @Valid @RequestBody AdminStockAdjustmentRequest request
)
```

- [ ] **Step 6: Run admin SPU controller tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='AdminProductSpuControllerTest,AdminProductServiceTest' test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 7: Commit**

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/product backend/shop-server/src/test/java/org/muybaby/shopserver/product/AdminProductSpuControllerTest.java
git commit -m "feat: add admin spu sku api"
```

## Task 5: Mini Program Product Browsing APIs

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/AppProductController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AppCategoryResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AppSpuListItemResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/AppSpuDetailResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/dto/ProductPageRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/service/AppProductService.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/product/AppProductControllerTest.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java`
- Modify: `backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java`

**Interfaces:**

- Consumes published product data from Tasks 1-4.
- Produces public read-only `GET /app/product/categories`.
- Produces public read-only `GET /app/product/spus`.
- Produces public read-only `GET /app/product/spus/{spuId}`.

- [ ] **Step 1: Write app controller tests**

Create `AppProductControllerTest`:

```java
package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publicAppApisReturnOnlyPublishedProductsWithoutToken() throws Exception {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "App Category", "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "App Published SPU",
                "App subtitle",
                "https://example.test/main.jpg",
                "A,B",
                "<p>detail</p>",
                1,
                List.of("https://example.test/gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, "APP-SKU-1", "{\"口味\":\"牛油\"}", "牛油", 3990L, 4990L, 9, 300, "https://example.test/sku.jpg", "ENABLED", 1))
        ));
        adminProductService.publishSpu(spuId);

        mockMvc.perform(get("/app/product/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"));

        mockMvc.perform(get("/app/product/spus")
                        .param("current", "1")
                        .param("size", "10")
                        .param("keyword", "Published"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.records[0].title").value("App Published SPU"));

        mockMvc.perform(get("/app/product/spus/" + spuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(spuId))
                .andExpect(jsonPath("$.data.skus[0].skuCode").value("APP-SKU-1"));

        adminProductService.unpublishSpu(spuId);

        mockMvc.perform(get("/app/product/spus/" + spuId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));
    }
}
```

- [ ] **Step 2: Run app product tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AppProductControllerTest test
```

Expected:

```text
401
```

- [ ] **Step 3: Permit public app product reads in security config**

Modify `SecurityConfig` request matchers to permit public reads:

```java
.requestMatchers(HttpMethod.GET, "/app/product/categories", "/app/product/spus", "/app/product/spus/*").permitAll()
```

Add the `HttpMethod` import:

```java
import org.springframework.http.HttpMethod;
```

Keep all non-GET `/app/**` routes authenticated.

- [ ] **Step 4: Implement app product DTOs and service**

Create `ProductPageRequest`:

```java
package org.muybaby.shopserver.product.dto;

public record ProductPageRequest(Long categoryId, String keyword, Long current, Long size) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 10 : Math.min(size, 50);
    }
}
```

Create app response records:

```java
package org.muybaby.shopserver.product.dto;

public record AppCategoryResponse(
        Long id,
        Long parentId,
        String name,
        String icon,
        Integer sortOrder,
        String status
) {
}
```

```java
package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AppSpuListItemResponse(
        Long id,
        Long categoryId,
        String title,
        String subtitle,
        String mainImage,
        List<String> sellingPoints,
        Long minPriceCent,
        Long maxPriceCent,
        Integer totalStock
) {
}
```

```java
package org.muybaby.shopserver.product.dto;

import java.util.List;

public record AppSpuDetailResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String subtitle,
        String mainImage,
        List<String> sellingPoints,
        String detailHtml,
        List<ProductImageResponse> images,
        List<AppSkuResponse> skus
) {
}
```

`AppProductService` methods:

```java
public List<AppCategoryResponse> categories()
public PageResult<AppSpuListItemResponse> page(ProductPageRequest request)
public AppSpuDetailResponse detail(Long spuId)
```

Filtering rules:

- Categories include only `status = 'ENABLED'`.
- List includes only `product_spu.status = 'ON_SALE'`.
- List and detail include only SKUs with `status = 'ENABLED'`.
- Detail throws `ErrorCode.PRODUCT_UNAVAILABLE` if the SPU is not `ON_SALE`.
- Selling points split by comma into a list and remove blank entries.

- [ ] **Step 5: Implement `AppProductController`**

Required controller:

```java
package org.muybaby.shopserver.product;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.dto.AppCategoryResponse;
import org.muybaby.shopserver.product.dto.AppSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AppSpuListItemResponse;
import org.muybaby.shopserver.product.dto.ProductPageRequest;
import org.muybaby.shopserver.product.service.AppProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/app/product")
public class AppProductController {

    private final AppProductService appProductService;

    public AppProductController(AppProductService appProductService) {
        this.appProductService = appProductService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<AppCategoryResponse>> categories() {
        return ApiResponse.success(appProductService.categories());
    }

    @GetMapping("/spus")
    public ApiResponse<PageResult<AppSpuListItemResponse>> page(ProductPageRequest request) {
        return ApiResponse.success(appProductService.page(request));
    }

    @GetMapping("/spus/{spuId}")
    public ApiResponse<AppSpuDetailResponse> detail(@PathVariable Long spuId) {
        return ApiResponse.success(appProductService.detail(spuId));
    }
}
```

- [ ] **Step 6: Run app product and security tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='AppProductControllerTest,SecurityConfigTest' test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 7: Commit**

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/product backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java backend/shop-server/src/test/java/org/muybaby/shopserver/product/AppProductControllerTest.java backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java
git commit -m "feat: add app product browsing api"
```

## Task 6: Admin Product Management UI And Build

**Files:**

- Create: `admin/src/api/product.ts`
- Modify: `admin/src/types/api/api.d.ts`
- Create: `admin/src/views/product/category/index.vue`
- Create: `admin/src/views/product/category/modules/category-dialog.vue`
- Create: `admin/src/views/product/spu/index.vue`
- Create: `admin/src/views/product/spu/modules/spu-editor.vue`

**Interfaces:**

- Consumes backend APIs from Tasks 3 and 4.
- Produces Art Design Pro views matching backend menu components `/product/category` and `/product/spu`.
- Produces TypeScript types under `Api.Product`.

- [ ] **Step 1: Add admin API functions**

Create `admin/src/api/product.ts`:

```ts
import request from '@/utils/http'

export function fetchProductCategories() {
  return request.get<Api.Product.Category[]>({
    url: '/admin/product/categories'
  })
}

export function createProductCategory(data: Api.Product.CategoryForm) {
  return request.post<number>({
    url: '/admin/product/categories',
    data,
    showSuccessMessage: true
  })
}

export function updateProductCategory(categoryId: number, data: Api.Product.CategoryForm) {
  return request.put<void>({
    url: `/admin/product/categories/${categoryId}`,
    data,
    showSuccessMessage: true
  })
}

export function fetchProductSpus(params: Api.Product.SpuSearchParams) {
  return request.get<Api.Product.SpuList>({
    url: '/admin/product/spus',
    params
  })
}

export function fetchProductSpuDetail(spuId: number) {
  return request.get<Api.Product.SpuDetail>({
    url: `/admin/product/spus/${spuId}`
  })
}

export function createProductSpu(data: Api.Product.SpuForm) {
  return request.post<number>({
    url: '/admin/product/spus',
    data,
    showSuccessMessage: true
  })
}

export function updateProductSpu(spuId: number, data: Api.Product.SpuForm) {
  return request.put<void>({
    url: `/admin/product/spus/${spuId}`,
    data,
    showSuccessMessage: true
  })
}

export function publishProductSpu(spuId: number) {
  return request.post<void>({
    url: `/admin/product/spus/${spuId}/publish`,
    showSuccessMessage: true
  })
}

export function unpublishProductSpu(spuId: number) {
  return request.post<void>({
    url: `/admin/product/spus/${spuId}/unpublish`,
    showSuccessMessage: true
  })
}

export function adjustSkuStock(skuId: number, data: Api.Product.StockAdjustmentForm) {
  return request.post<void>({
    url: `/admin/product/skus/${skuId}/stock-adjustments`,
    data,
    showSuccessMessage: true
  })
}
```

- [ ] **Step 2: Add `Api.Product` types**

Append to `admin/src/types/api/api.d.ts`:

```ts
  namespace Product {
    type ProductStatus = 'DRAFT' | 'ON_SALE' | 'OFF_SALE'
    type CategoryStatus = 'ENABLED' | 'DISABLED'
    type SkuStatus = 'ENABLED' | 'DISABLED'

    interface Category {
      id: number
      parentId: number
      name: string
      icon: string
      sortOrder: number
      status: CategoryStatus
      children: Category[]
    }

    interface CategoryForm {
      parentId: number
      name: string
      icon: string
      sortOrder: number
      status: CategoryStatus
    }

    type SpuList = Api.Common.PaginatedResponse<SpuListItem>

    interface SpuListItem {
      id: number
      categoryId: number
      categoryName: string
      title: string
      subtitle: string
      mainImage: string
      status: ProductStatus
      sortOrder: number
      minPriceCent: number
      maxPriceCent: number
      totalStock: number
      skuCount: number
      createdAt: string
      updatedAt: string
    }

    type SpuSearchParams = Partial<Api.Common.CommonSearchParams & {
      categoryId: number
      title: string
      status: ProductStatus
    }>

    interface ProductImage {
      id: number
      url: string
      sortOrder: number
    }

    interface Sku {
      id?: number
      skuCode: string
      specJson: string
      specText: string
      priceCent: number
      originalPriceCent: number
      stockAvailable: number
      weightGram: number
      image: string
      status: SkuStatus
      sortOrder: number
    }

    interface SpuDetail extends SpuListItem {
      sellingPoints: string
      detailHtml: string
      images: ProductImage[]
      skus: Sku[]
    }

    interface SpuForm {
      categoryId: number
      title: string
      subtitle: string
      mainImage: string
      sellingPoints: string
      detailHtml: string
      sortOrder: number
      images: string[]
      skus: Sku[]
    }

    interface StockAdjustmentForm {
      quantityDelta: number
      reason: string
    }
  }
```

- [ ] **Step 3: Build category page**

`admin/src/views/product/category/index.vue` must provide:

- Search-free tree table loaded from `fetchProductCategories`.
- Columns: ID, category name, status tag, sort order, operation.
- Add and edit buttons.
- Dialog uses `category-dialog.vue`.
- Save calls `createProductCategory` or `updateProductCategory`.

Validation rules:

- `name` required.
- `sortOrder` integer greater than or equal to 0.
- `parentId` defaults to `0`.
- `status` defaults to `ENABLED`.

- [ ] **Step 4: Build SPU page and SKU editor**

`admin/src/views/product/spu/index.vue` must provide:

- `useTable` with `fetchProductSpus`.
- Search fields: title, category, status.
- Columns: image, title, category, price range, total stock, status, update time, operation.
- Operations: edit, publish, unpublish, adjust stock.

`admin/src/views/product/spu/modules/spu-editor.vue` must provide:

- SPU form fields for category, title, subtitle, main image URL, selling points, detail HTML, sort order.
- Image URL list editor.
- SKU table editor with skuCode, specText, specJson, priceCent, originalPriceCent, stockAvailable, weightGram, image, status, sortOrder.
- On edit, call `fetchProductSpuDetail` and convert `images[].url` into form `images`.

- [ ] **Step 5: Run admin build**

Run:

```bash
cd admin
pnpm build
```

Expected:

```text
vite build
```

and the command exits with status 0.

- [ ] **Step 6: Commit**

```bash
git add admin/src/api/product.ts admin/src/types/api/api.d.ts admin/src/views/product
git commit -m "feat: add admin product management views"
```

## Task 7: Mini Program Product List And Detail UI

**Files:**

- Create: `miniprogram/services/product.ts`
- Modify: `miniprogram/types/api.ts`
- Modify: `miniprogram/app.json`
- Modify: `miniprogram/pages/home/home.ts`
- Modify: `miniprogram/pages/home/home.wxml`
- Modify: `miniprogram/pages/home/home.wxss`
- Create: `miniprogram/pages/product/list/list.json`
- Create: `miniprogram/pages/product/list/list.ts`
- Create: `miniprogram/pages/product/list/list.wxml`
- Create: `miniprogram/pages/product/list/list.wxss`
- Create: `miniprogram/pages/product/detail/detail.json`
- Create: `miniprogram/pages/product/detail/detail.ts`
- Create: `miniprogram/pages/product/detail/detail.wxml`
- Create: `miniprogram/pages/product/detail/detail.wxss`

**Interfaces:**

- Consumes `/app/product/categories`, `/app/product/spus`, and `/app/product/spus/{spuId}` from Task 5.
- Produces native mini program category entry, product list, product detail, image gallery, SKU selection state, and disabled add-to-cart/buy-now buttons that remain inert until the cart phase.

- [ ] **Step 1: Add mini program product types**

Append to `miniprogram/types/api.ts`:

```ts
export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

export interface ProductCategory {
  id: number;
  parentId: number;
  name: string;
  icon: string;
  sortOrder: number;
  status: "ENABLED" | "DISABLED";
}

export interface ProductListItem {
  id: number;
  categoryId: number;
  title: string;
  subtitle: string;
  mainImage: string;
  sellingPoints: string[];
  minPriceCent: number;
  maxPriceCent: number;
  totalStock: number;
}

export interface ProductImage {
  id: number;
  url: string;
  sortOrder: number;
}

export interface ProductSku {
  id: number;
  skuCode: string;
  specJson: string;
  specText: string;
  priceCent: number;
  originalPriceCent: number;
  stockAvailable: number;
  weightGram: number;
  image: string;
  status: "ENABLED" | "DISABLED";
}

export interface ProductDetail {
  id: number;
  categoryId: number;
  categoryName: string;
  title: string;
  subtitle: string;
  mainImage: string;
  sellingPoints: string[];
  detailHtml: string;
  images: ProductImage[];
  skus: ProductSku[];
}
```

- [ ] **Step 2: Add product service**

Create `miniprogram/services/product.ts`:

```ts
import type {
  PageResult,
  ProductCategory,
  ProductDetail,
  ProductListItem
} from "../types/api";
import { request } from "../utils/request";

export function getProductCategories(): Promise<ProductCategory[]> {
  return request<ProductCategory[]>({
    url: "/app/product/categories",
    auth: false
  });
}

export function getProductList(params: {
  current: number;
  size: number;
  categoryId?: number;
  keyword?: string;
}): Promise<PageResult<ProductListItem>> {
  const query = [
    `current=${params.current}`,
    `size=${params.size}`,
    params.categoryId ? `categoryId=${params.categoryId}` : "",
    params.keyword ? `keyword=${encodeURIComponent(params.keyword)}` : ""
  ].filter(Boolean).join("&");

  return request<PageResult<ProductListItem>>({
    url: `/app/product/spus?${query}`,
    auth: false
  });
}

export function getProductDetail(spuId: number): Promise<ProductDetail> {
  return request<ProductDetail>({
    url: `/app/product/spus/${spuId}`,
    auth: false
  });
}

export function formatPrice(priceCent: number): string {
  return `¥${(priceCent / 100).toFixed(2)}`;
}
```

- [ ] **Step 3: Register pages and category tab**

Modify `miniprogram/app.json` pages:

```json
{
  "pages": [
    "pages/home/home",
    "pages/product/list/list",
    "pages/product/detail/detail",
    "pages/profile/profile",
    "pages/order/detail/detail"
  ]
}
```

Modify tabBar list to include product list:

```json
{
  "pagePath": "pages/product/list/list",
  "text": "分类"
}
```

- [ ] **Step 4: Update home page**

`home.ts` behavior:

- Load product categories through `getProductCategories`.
- Load first page of product list through `getProductList({ current: 1, size: 6 })`.
- Show backend health only as a small diagnostics line.
- Navigate category tap to `/pages/product/list/list?categoryId=<id>`.
- Navigate product tap to `/pages/product/detail/detail?id=<id>`.

- [ ] **Step 5: Build product list page**

`pages/product/list/list.ts` behavior:

- Parse `categoryId` from `onLoad` options.
- Load categories and products.
- Selecting a category resets `current = 1`.
- Pull-down refresh reloads first page.
- Reaching bottom loads next page until `records.length >= total`.
- Product card tap navigates to detail.

`pages/product/list/list.json`:

```json
{
  "navigationBarTitleText": "商品分类"
}
```

- [ ] **Step 6: Build product detail page**

`pages/product/detail/detail.ts` behavior:

- Parse numeric `id` from `onLoad`.
- Call `getProductDetail(id)`.
- Select the first enabled SKU with stock greater than 0.
- SKU tap updates selected SKU.
- Price display uses selected SKU price.
- Add-to-cart and buy-now buttons remain disabled with text `购物车下一阶段开放` and `下单下一阶段开放`.

`pages/product/detail/detail.json`:

```json
{
  "navigationBarTitleText": "商品详情"
}
```

- [ ] **Step 7: Run mini program typecheck**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected:

```text
tsc --noEmit
```

and the command exits with status 0.

- [ ] **Step 8: Commit**

```bash
git add miniprogram/services/product.ts miniprogram/types/api.ts miniprogram/app.json miniprogram/pages/home miniprogram/pages/product
git commit -m "feat: add mini program product browsing"
```

## Task 8: Verification, Product Smoke Checks, And Documentation

**Files:**

- Modify: `docs/dev-setup.md`
- Modify: `docs/smoke-checks.md`

**Interfaces:**

- Consumes all tasks above.
- Produces documented backend tests, admin build, mini program typecheck, and real local product catalog smoke commands.

- [ ] **Step 1: Run focused backend tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='ProductCatalogSchemaTest,AdminProductServiceTest,AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,SecurityConfigTest' test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 2: Run full backend tests**

Run:

```bash
cd backend/shop-server
./mvnw test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 3: Run admin build**

Run:

```bash
cd admin
pnpm build
```

Expected: command exits with status 0.

- [ ] **Step 4: Run mini program typecheck**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected: command exits with status 0.

- [ ] **Step 5: Document real local product catalog smoke**

Add this section to `docs/smoke-checks.md`:

````markdown
## Product Catalog Smoke Checks

This is a real local smoke check for product catalog. It uses the local backend and local database path. In the `test` profile, WeChat login is still backed by the mock WeChat mini program client described in `docs/dev-setup.md`; product catalog requests are not mocked.

Start backend:

```bash
cd backend/shop-server
./mvnw -Dspring-boot.run.profiles=test \
  -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.arguments=--spring.config.additional-location=file:src/test/resources/ \
  spring-boot:run
```

Admin login:

```bash
ADMIN_TOKEN=$(
  curl -s -X POST http://localhost:8080/admin/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"userName":"Super","password":"123456"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.token));'
)
```

Create category:

```bash
CATEGORY_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/categories \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"parentId":0,"name":"牛油锅底","icon":"","sortOrder":10,"status":"ENABLED"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Create SPU and SKU:

```bash
SPU_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/spus \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"categoryId\":${CATEGORY_ID},\"title\":\"重庆牛油火锅底料\",\"subtitle\":\"厚重牛油香\",\"mainImage\":\"https://example.test/hotpot-main.jpg\",\"sellingPoints\":\"牛油浓香,手工炒制\",\"detailHtml\":\"<p>适合3-5人火锅。</p>\",\"sortOrder\":10,\"images\":[\"https://example.test/hotpot-gallery-1.jpg\"],\"skus\":[{\"skuCode\":\"HY-NY-300G\",\"specJson\":\"{\\\"口味\\\":\\\"牛油\\\",\\\"重量\\\":\\\"300g\\\"}\",\"specText\":\"牛油 / 300g\",\"priceCent\":3990,\"originalPriceCent\":4990,\"stockAvailable\":100,\"weightGram\":300,\"image\":\"https://example.test/hotpot-sku-300.jpg\",\"status\":\"ENABLED\",\"sortOrder\":1}]}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Publish SPU:

```bash
curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/publish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Mini program product list:

```bash
curl -s "http://localhost:8080/app/product/spus?current=1&size=10&categoryId=${CATEGORY_ID}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.total < 1) process.exit(1); console.log(body.data.records[0].title); });'
```

Mini program product detail:

```bash
curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.skus.length < 1) process.exit(1); console.log(body.data.skus[0].skuCode); });'
```

Unpublish SPU and verify it disappears from app detail:

```bash
curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/unpublish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'

curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200001) process.exit(1); console.log(body.msg); });'
```

Expected result:

```text
success
重庆牛油火锅底料
HY-NY-300G
success
Product unavailable
```
````

- [ ] **Step 6: Execute product smoke locally**

Run the commands from the new `Product Catalog Smoke Checks` section.

Expected:

```text
success
重庆牛油火锅底料
HY-NY-300G
success
Product unavailable
```

- [ ] **Step 7: Commit**

```bash
git add docs/dev-setup.md docs/smoke-checks.md
git commit -m "docs: add product catalog smoke checks"
```

## Final Verification Matrix

Run before claiming the product catalog phase is complete:

```bash
cd backend/shop-server
./mvnw test
```

```bash
cd admin
pnpm build
```

```bash
cd miniprogram
pnpm typecheck
```

Real local smoke:

```bash
docs/smoke-checks.md#product-catalog-smoke-checks
```

Expected completion evidence:

- Backend full test suite exits with `BUILD SUCCESS`.
- Admin build exits with status 0.
- Mini program typecheck exits with status 0.
- Real local product smoke creates a category, creates a SPU with SKU, publishes it, reads it from `/app/product/spus`, reads detail from `/app/product/spus/{id}`, unpublishes it, and confirms app detail returns `Product unavailable`.

## Self-Review

- Spec coverage: category, SPU, SKU multi-spec, SKU price, stock, image, publish/unpublish, admin APIs, mini program list/detail APIs, MySQL/Flyway migration, backend tests, admin build, mini program typecheck, and smoke checks are each mapped to at least one task.
- Scope control: cart, checkout, coupon, order, payment, shipment, after-sale, refund, stock lock, and file upload stay outside this plan.
- Type consistency: backend and frontend names use `priceCent`, `originalPriceCent`, `stockAvailable`, `mainImage`, `sellingPoints`, `DRAFT`, `ON_SALE`, `OFF_SALE`, `ENABLED`, and `DISABLED` consistently.
- Execution mode: implement this plan with `superpowers:subagent-driven-development`, one task at a time, with review after each task before the next task starts.
