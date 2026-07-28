import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildCatalogProductCard,
  buildCatalogParameterFilterGroups,
  buildCategoryTabs,
  buildGalleryImages,
  buildParameterViews,
  buildProductListQuery,
  buildSpecificationPreviewUrls,
  buildSkuOptions,
  buildSkuSpecificationGroups,
  findDefaultSku,
  normalizeProductKeyword,
  normalizeProductRouteKeyword,
  parsePositiveId,
  resolvePurchaseSelection,
  resolveSkuSpecificationSelection
} from "../miniprogram/features/product-catalog";
import type {
  ProductCategory,
  ProductDetail,
  ProductFilterGroup,
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
    displaySales: 36,
    saleState: "AVAILABLE",
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
    saleState: "AVAILABLE",
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
  assert.equal(
    normalizeProductRouteKeyword("%E6%89%8B%E6%9C%BA"),
    "手机"
  );
  assert.equal(normalizeProductRouteKeyword("手机"), "手机");
  assert.equal(normalizeProductRouteKeyword("100%"), "100%");
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
  assert.deepEqual(
    buildProductListQuery(5, "麻辣", 1, "SALES_DESC", {
      spice: "medium",
      "bad:code": "ignored"
    }),
    {
      current: 1,
      size: 10,
      categoryId: 5,
      keyword: "麻辣",
      sort: "SALES_DESC",
      parameterFilters: { SPICE: "MEDIUM" }
    }
  );
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

test("商品参数筛选项来自独立聚合接口并稳定保留零商品选项", () => {
  const facets: ProductFilterGroup[] = [{
    parameterId: 1,
    parameterCode: "SPICE",
    parameterName: "辣度",
    valueType: "SINGLE_SELECT",
    options: [
      { optionCode: "MILD", optionLabel: "微辣", displayLevel: 1, productCount: 0 },
      { optionCode: "MEDIUM", optionLabel: "中辣", displayLevel: 2, productCount: 2 },
      { optionCode: "HOT", optionLabel: "特辣", displayLevel: 3, productCount: 1 },
      { optionCode: "EXTREME", optionLabel: "变态辣", displayLevel: 4, productCount: 1 }
    ]
  }];
  const groups = buildCatalogParameterFilterGroups(facets, { SPICE: "MEDIUM" });
  assert.equal(groups[0]?.name, "辣度");
  assert.deepEqual(groups[0]?.options.map((option) => ({
    value: option.value,
    label: option.label,
    count: option.count,
    disabled: option.disabled,
    selected: option.selected
  })), [
    { value: "MILD", label: "微辣", count: 0, disabled: true, selected: false },
    { value: "MEDIUM", label: "中辣", count: 2, disabled: false, selected: true },
    { value: "HOT", label: "特辣", count: 1, disabled: false, selected: false },
    { value: "EXTREME", label: "变态辣", count: 1, disabled: false, selected: false }
  ]);
});

test("列表商品映射价格区间、销量、可售状态和参数卡片语义", () => {
  const card = buildCatalogProductCard(product());
  assert.ok(card);
  assert.equal(card.navigationPath, "/pages/product/detail/detail?id=41");
  assert.equal(card.priceText, "19.90–25.90");
  assert.equal(card.priceIntegerText, "19");
  assert.equal(card.priceDecimalText, ".90");
  assert.equal(card.rangePriceIntegerText, "25");
  assert.equal(card.rangePriceDecimalText, ".90");
  assert.equal(card.hasPriceRange, true);
  assert.equal(card.salesText, "已售 36+");
  assert.equal(card.soldOut, false);
  assert.deepEqual(card.features, [
    {
      text: "中辣",
      tone: "orange",
      kind: "spice",
      spiceTone: "medium",
      iconPath: "/assets/icons/chili-pepper-red.svg",
      spiceIconIndexes: [0, 1]
    },
    {
      text: "500g",
      tone: "neutral",
      kind: "weight",
      spiceTone: "",
      iconPath: "",
      spiceIconIndexes: [],
      servingText: "适合3-5人"
    }
  ]);

  const soldOut = buildCatalogProductCard(product({ saleState: "SOLD_OUT" }));
  assert.equal(soldOut?.badgeText, "暂时售罄");
  assert.equal(soldOut?.badgeTone, "neutral");
  assert.equal(soldOut?.soldOut, true);
  assert.equal(soldOut?.salesText, "已售 36+");

  const badged = buildCatalogProductCard(product({
    badgeText: "新品首发",
    badgeTone: "RED"
  }));
  assert.equal(badged?.badgeText, "新品首发");
  assert.equal(badged?.badgeTone, "brand");
});

test("详情图片去重并将参数映射为展示数据", () => {
  const detail: ProductDetail = {
    id: 41,
    categoryId: 5,
    categoryName: "麻辣",
    salesCount: 0,
    saleState: "AVAILABLE",
    title: "经典牛油锅底",
    mainImage: "https://example.test/main.png",
    sellingPoints: [],
    images: [
      { url: "https://example.test/b.png", sortOrder: 2 },
      { url: "https://example.test/a.png", sortOrder: 1 },
      { url: "https://example.test/a.png", sortOrder: 3 }
    ],
    skus: [],
    parameters: [parameter()],
    freightTemplate: {
      id: 1,
      name: "全国包邮",
      chargeMode: "FREE",
      fixedAmountCent: 0
    },
    guaranteeServices: []
  };
  assert.deepEqual(
    buildGalleryImages(detail).map((image) => image.url),
    ["https://example.test/a.png", "https://example.test/b.png"]
  );
  const parameters = buildParameterViews(detail.parameters);
  assert.equal(parameters.length, 1);
  assert.equal(parameters[0]?.name, "辣度");
  assert.equal(parameters[0]?.fact.kind, "spice");

  const genericParameters = buildParameterViews([
    parameter({
      parameterId: 9,
      parameterCode: "MATERIAL",
      parameterName: "原料",
      displayText: "精选牛油",
      cardRenderer: "TEXT",
      selectedOptions: []
    })
  ]);
  assert.equal(genericParameters[0]?.fact.kind, "default");
  assert.deepEqual(genericParameters[0]?.fact.spiceIconIndexes, []);
  assert.deepEqual(buildParameterViews([]), []);
});

test("默认规格、售罄禁用态和批发阶梯价随数量联动", () => {
  const unavailable = sku({ id: 100, saleState: "SOLD_OUT" });
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

  const fallbackImageOptions = buildSkuOptions(
    [sku({ image: "" })],
    101,
    "https://example.test/fallback.png"
  );
  assert.equal(fallbackImageOptions[0]?.imageUrl, "https://example.test/fallback.png");
  assert.equal(fallbackImageOptions[0]?.hasImage, true);

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
  assert.equal(clamped.quantity, 200);
  assert.equal(clamped.quantityMax, 999);
  assert.equal(clamped.priceText, "18.00");
  assert.equal(clamped.wholesaleTiers[1]?.active, true);

  const empty = resolvePurchaseSelection(unavailable, 1);
  assert.equal(empty.selectedSkuId, 0);
  assert.equal(empty.quantityMax, 0);
});

test("规格按名称和值分组并只允许选择真实可售组合", () => {
  const variants = [
    sku({ id: 201, specJson: "{\"颜色\":\"红色\",\"尺寸\":\"x\"}", specText: "红色 / x", image: "https://example.test/red.png" }),
    sku({ id: 202, specJson: "{\"颜色\":\"红色\",\"尺寸\":\"l\"}", specText: "红色 / l", image: "https://example.test/red.png" }),
    sku({ id: 203, specJson: "{\"颜色\":\"绿色\",\"尺寸\":\"x\"}", specText: "绿色 / x", image: "https://example.test/green.png", saleState: "SOLD_OUT" }),
    sku({ id: 204, specJson: "{\"颜色\":\"绿色\",\"尺寸\":\"l\"}", specText: "绿色 / l", image: "https://example.test/green.png" })
  ];

  const redSmallGroups = buildSkuSpecificationGroups(variants, 201);
  assert.deepEqual(redSmallGroups.map((group) => ({
    name: group.name,
    options: group.options.map((option) => ({
      value: option.value,
      selected: option.selected,
      disabled: option.disabled
    }))
  })), [
    {
      name: "颜色",
      options: [
        { value: "红色", selected: true, disabled: false },
        { value: "绿色", selected: false, disabled: true }
      ]
    },
    {
      name: "尺寸",
      options: [
        { value: "x", selected: true, disabled: false },
        { value: "l", selected: false, disabled: false }
      ]
    }
  ]);
  assert.equal(redSmallGroups[0]?.hasImages, true);
  assert.equal(redSmallGroups[0]?.options[0]?.imageUrl, "https://example.test/red.png");
  assert.equal(redSmallGroups[1]?.hasImages, false);
  assert.equal(redSmallGroups[1]?.options.every((option) => !option.hasImage), true);
  assert.deepEqual(buildSpecificationPreviewUrls(redSmallGroups), [
    "https://example.test/red.png",
    "https://example.test/green.png"
  ]);
  assert.deepEqual(
    buildSpecificationPreviewUrls(redSmallGroups, "https://example.test/green.png"),
    ["https://example.test/green.png", "https://example.test/red.png"]
  );

  const redLarge = resolveSkuSpecificationSelection(variants, 201, "尺寸", "l");
  assert.equal(redLarge?.id, 202);
  const redLargeGroups = buildSkuSpecificationGroups(variants, redLarge?.id ?? 0);
  assert.equal(redLargeGroups[0]?.options[1]?.disabled, false);
  assert.equal(resolveSkuSpecificationSelection(variants, 202, "颜色", "绿色")?.id, 204);
  assert.equal(resolveSkuSpecificationSelection(variants, 201, "颜色", "绿色"), undefined);

  const legacyGroups = buildSkuSpecificationGroups([
    sku({ id: 205, specJson: "invalid", specText: "500g 袋装" })
  ], 205);
  assert.equal(legacyGroups[0]?.name, "规格");
  assert.equal(legacyGroups[0]?.options[0]?.value, "500g 袋装");
});
