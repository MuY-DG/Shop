import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildCatalogProductCard,
  buildCategoryTabs,
  buildGalleryImages,
  buildParameterViews,
  buildProductListQuery,
  buildSkuOptions,
  findDefaultSku,
  normalizeProductKeyword,
  parsePositiveId,
  resolvePurchaseSelection
} from "../miniprogram/features/product-catalog";
import type {
  ProductCategory,
  ProductDetail,
  ProductListItem,
  ProductParameterValue,
  ProductSku
} from "../miniprogram/types/product";

function parameter(
  overrides: Partial<ProductParameterValue> = {}
): ProductParameterValue {
  return {
    parameterId: 1,
    parameterCode: "SPICE",
    parameterName: "辣度",
    valueType: "SINGLE_SELECT",
    unit: "",
    displayText: "中辣",
    cardRole: "HIGHLIGHT",
    cardRenderer: "SPICE",
    cardPriority: 0,
    selectedOptions: [{
      optionCode: "MEDIUM",
      optionLabel: "中辣",
      displayLevel: 2
    }],
    ...overrides
  };
}

function product(overrides: Partial<ProductListItem> = {}): ProductListItem {
  return {
    id: 41,
    categoryId: 5,
    title: "经典牛油锅底",
    subtitle: "醇厚香辣",
    mainImage: "https://example.test/product.png",
    sellingPoints: [],
    minPriceCent: 1990,
    maxPriceCent: 2590,
    totalStock: 12,
    parameters: [
      parameter(),
      parameter({
        parameterId: 2,
        parameterCode: "WEIGHT",
        parameterName: "重量",
        displayText: "500g",
        cardRole: "META",
        cardRenderer: "TEXT",
        selectedOptions: []
      })
    ],
    ...overrides
  };
}

function sku(overrides: Partial<ProductSku> = {}): ProductSku {
  return {
    id: 101,
    skuCode: "SKU-101",
    specJson: "{}",
    specText: "500g 袋装",
    priceCent: 2000,
    originalPriceCent: 2600,
    stockAvailable: 20,
    status: "ENABLED",
    wholesaleTiers: [
      { minQuantity: 10, unitPriceCent: 1800 },
      { minQuantity: 5, unitPriceCent: 1900 }
    ],
    ...overrides
  };
}

test("商品列表查询只保留安全分类、关键词和分页", () => {
  assert.equal(parsePositiveId("42"), 42);
  assert.equal(parsePositiveId("42x"), 0);
  assert.equal(parsePositiveId(Number.MAX_SAFE_INTEGER + 1), 0);
  assert.equal(normalizeProductKeyword("  牛油   锅底  "), "牛油 锅底");
  assert.deepEqual(buildProductListQuery("5", "  麻辣  ", 3), {
    current: 3,
    size: 10,
    categoryId: 5,
    keyword: "麻辣"
  });
  assert.deepEqual(buildProductListQuery("bad", "   ", 0), {
    current: 1,
    size: 10
  });
});

test("分类标签去重并稳定保留全部入口和选中态", () => {
  const categories: ProductCategory[] = [
    { id: 1, parentId: 0, name: "火锅底料", sortOrder: 0, status: "ENABLED" },
    { id: 5, parentId: 1, name: "麻辣", sortOrder: 0, status: "ENABLED" },
    { id: 5, parentId: 1, name: "重复分类", sortOrder: 1, status: "ENABLED" }
  ];
  assert.deepEqual(buildCategoryTabs(categories, 5), [
    { id: 0, name: "全部", selected: false },
    { id: 1, name: "火锅底料", selected: false },
    { id: 5, name: "麻辣", selected: true }
  ]);
});

test("列表商品映射价格区间、库存和参数卡片语义", () => {
  const card = buildCatalogProductCard(product());
  assert.ok(card);
  assert.equal(card.navigationPath, "/pages/product/detail/detail?id=41");
  assert.equal(card.priceText, "19.90–25.90");
  assert.equal(card.priceIntegerText, "19");
  assert.equal(card.priceDecimalText, ".90");
  assert.equal(card.rangePriceIntegerText, "25");
  assert.equal(card.rangePriceDecimalText, ".90");
  assert.equal(card.hasPriceRange, true);
  assert.equal(card.salesText, "库存 12");
  assert.deepEqual(card.features, [
    {
      text: "中辣",
      tone: "orange",
      kind: "spice",
      spiceTone: "medium",
      iconPath: "/assets/icons/chili-pepper-red.svg"
    },
    {
      text: "500g",
      tone: "neutral",
      kind: "weight",
      spiceTone: "",
      iconPath: "",
      servingText: "适合3-5人"
    }
  ]);

  const soldOut = buildCatalogProductCard(product({ totalStock: 0 }));
  assert.equal(soldOut?.badgeText, "暂时售罄");
  assert.equal(soldOut?.salesText, "补货中");
});

test("详情图片去重并将参数映射为展示数据", () => {
  const detail: ProductDetail = {
    id: 41,
    categoryId: 5,
    categoryName: "麻辣",
    title: "经典牛油锅底",
    mainImage: "https://example.test/main.png",
    sellingPoints: [],
    images: [
      { url: "https://example.test/b.png", sortOrder: 2 },
      { url: "https://example.test/a.png", sortOrder: 1 },
      { url: "https://example.test/a.png", sortOrder: 3 }
    ],
    skus: [],
    parameters: [parameter()]
  };
  assert.deepEqual(
    buildGalleryImages(detail).map((image) => image.url),
    ["https://example.test/a.png", "https://example.test/b.png"]
  );
  const parameters = buildParameterViews(detail.parameters);
  assert.equal(parameters.length, 1);
  assert.equal(parameters[0]?.name, "辣度");
  assert.equal(parameters[0]?.fact.kind, "spice");
});

test("默认规格、库存禁用态和批发阶梯价随数量联动", () => {
  const unavailable = sku({ id: 100, stockAvailable: 0 });
  const available = sku();
  assert.equal(findDefaultSku([unavailable, available])?.id, 101);
  assert.deepEqual(
    buildSkuOptions([unavailable, available], 101).map((item) => ({
      id: item.id,
      selected: item.selected,
      disabled: item.disabled
    })),
    [
      { id: 100, selected: false, disabled: true },
      { id: 101, selected: true, disabled: false }
    ]
  );

  const retail = resolvePurchaseSelection(available, 1);
  assert.equal(retail.priceText, "20.00");
  assert.equal(retail.quantity, 1);
  assert.equal(retail.wholesaleApplied, false);
  assert.match(retail.wholesaleHint, /再买 4 件/);

  const wholesale = resolvePurchaseSelection(available, 7);
  assert.equal(wholesale.priceText, "19.00");
  assert.equal(wholesale.wholesaleApplied, true);
  assert.match(wholesale.wholesaleHint, /已享 5 件起批发价/);
  assert.match(wholesale.wholesaleHint, /再买 3 件/);
  assert.equal(wholesale.hasOriginalPrice, true);

  const clamped = resolvePurchaseSelection(available, 200);
  assert.equal(clamped.quantity, 20);
  assert.equal(clamped.priceText, "18.00");
  assert.equal(clamped.wholesaleTiers[1]?.active, true);

  const empty = resolvePurchaseSelection(unavailable, 1);
  assert.equal(empty.selectedSkuId, 0);
  assert.equal(empty.quantityMax, 0);
});
