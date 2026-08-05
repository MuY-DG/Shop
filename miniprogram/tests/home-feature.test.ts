import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildHomeViewModel,
  formatPriceRange,
  normalizeHomePath
} from "../miniprogram/features/home";
import { adaptProductFact } from "../miniprogram/features/product-facts";
import {
  parseWeightGram,
  servingTextByWeight
} from "../miniprogram/features/weight-servings";
import type {
  HomeProduct,
  HomeProductSection,
  HomeResponse
} from "../miniprogram/types/home";

function product(
  overrides: Partial<HomeProduct> & Pick<HomeProduct, "placementId" | "spuId" | "title">
): HomeProduct {
  return {
    subtitle: "",
    imageUrl: "",
    price: {},
    highlights: [],
    metaFacts: [],
    displaySales: 0,
    saleState: "AVAILABLE",
    path: "",
    ...overrides
  };
}

function section(
  code: string,
  presentation: HomeProductSection["presentation"],
  products: HomeProduct[]
): HomeProductSection {
  return { code, presentation, products };
}

function response(overrides: Partial<HomeResponse> = {}): HomeResponse {
  return {
    schemaVersion: 3,
    banners: [],
    categories: [],
    productSections: [],
    ...overrides
  };
}

test("schema v3 首页响应映射为可直接渲染的 view-model", () => {
  const hotProduct = product({
    placementId: 301,
    spuId: 41,
    title: "  经典牛油锅底  ",
    subtitle: "  醇厚香辣  ",
    imageUrl: "  https://example.test/hot.png  ",
    price: {
      minPriceCent: 1990,
      maxPriceCent: 2590,
      originalPriceCent: 2990
    },
    badge: {
      text: "  店长推荐  ",
      source: "MANUAL",
      tone: "ORANGE"
    },
    highlights: [
      {
        code: "SPICE",
        name: "辣度",
        displayText: "中辣",
        renderer: "SPICE",
        level: 2
      }
    ],
    metaFacts: [
      {
        code: "WEIGHT",
        name: "净含量",
        displayText: "300g",
        renderer: "PILL"
      }
    ],
    wholesaleSummary: {
      available: true,
      label: "  支持批量价  "
    },
    displaySales: 7,
    path: "/pages/product/detail/detail?id=41"
  });
  const compactProduct = product({
    placementId: 302,
    spuId: 42,
    title: "番茄汤底",
    price: {
      minPriceCent: 3990,
      maxPriceCent: 3990
    },
    displaySales: 0,
    saleState: "SOLD_OUT",
    path: "/pages/product/detail/detail?id=42"
  });

  const viewModel = buildHomeViewModel(response({
    banners: [
      {
        id: 11,
        title: "  夏日尝鲜  ",
        subtitle: "  限时推荐  ",
        imageUrl: "  https://example.test/banner.png  ",
        jumpType: "PRODUCT",
        jumpTargetId: 41
      }
    ],
    categories: [
      {
        id: 101,
        categoryId: 21,
        name: "  火锅底料  ",
        imageUrl: "  https://example.test/category.png  ",
        path: "/pages/product/list/list?categoryId=21"
      }
    ],
    productSections: [
      section("HOT", "FEATURED", [hotProduct]),
      section("RECOMMENDED", "COMPACT", [compactProduct])
    ]
  }));

  assert.equal(viewModel.schemaVersion, 3);
  assert.equal(viewModel.hasContent, true);
  assert.deepEqual(viewModel.banners.map((banner) => ({
    id: banner.id,
    title: banner.title,
    subtitle: banner.subtitle,
    imageUrl: banner.imageUrl,
    navigationPath: banner.navigationPath,
    ariaLabel: banner.ariaLabel
  })), [{
    id: 11,
    title: "夏日尝鲜",
    subtitle: "限时推荐",
    imageUrl: "https://example.test/banner.png",
    navigationPath: "/pages/product/detail/detail?id=41",
    ariaLabel: "夏日尝鲜"
  }]);
  assert.deepEqual(viewModel.categories[0], {
    id: 101,
    categoryId: 21,
    name: "火锅底料",
    imageUrl: "https://example.test/category.png",
    hasImage: true,
    placeholder: "火",
    navigationPath: "/pages/product/list/list?categoryId=21"
  });

  const featured = viewModel.featuredProducts[0];
  assert.ok(featured);
  assert.equal(featured.placementId, 301);
  assert.equal(featured.spuId, 41);
  assert.equal(featured.title, "经典牛油锅底");
  assert.equal(featured.subtitle, "醇厚香辣");
  assert.equal(featured.priceText, "19.90");
  assert.equal(featured.hasPrice, true);
  assert.equal(featured.priceIntegerText, "19");
  assert.equal(featured.priceDecimalText, ".90");
  assert.equal(featured.rangePriceIntegerText, "");
  assert.equal(featured.rangePriceDecimalText, "");
  assert.equal(featured.hasPriceRange, false);
  assert.equal(featured.originalPriceText, "29.90");
  assert.equal(featured.hasOriginalPrice, true);
  assert.equal(featured.badgeText, "店长推荐");
  assert.equal(featured.badgeTone, "orange");
  assert.equal(featured.soldOut, false);
  assert.deepEqual(featured.features, [
    {
      text: "中辣",
      tone: "orange",
      kind: "spice",
      spiceTone: "medium",
      iconPath: "/assets/icons/chili-pepper-red.svg",
      spiceIconIndexes: [0, 1]
    },
    {
      text: "300g",
      tone: "neutral",
      kind: "weight",
      spiceTone: "",
      iconPath: "",
      spiceIconIndexes: [],
      servingText: "适合3-5人"
    }
  ]);
  assert.equal(featured.wholesaleText, "支持批量价");
  assert.equal(featured.salesText, "已售 7+");
  assert.equal(featured.navigationPath, "/pages/product/detail/detail?id=41");

  const compact = viewModel.compactProducts[0];
  assert.ok(compact);
  assert.equal(compact.spuId, 42);
  assert.equal(compact.priceText, "39.90");
  assert.equal(compact.hasImage, false);
  assert.equal(compact.salesText, "已售 0+");
  assert.equal(compact.soldOut, true);
  assert.equal(compact.badgeText, "暂时售罄");
  assert.equal(compact.badgeTone, "neutral");
  assert.equal(compact.hasOriginalPrice, false);
});

test("商品区块按 code 映射而不是依赖后端数组顺序", () => {
  const recommended = product({
    placementId: 1,
    spuId: 101,
    title: "推荐商品"
  });
  const hot = product({
    placementId: 2,
    spuId: 102,
    title: "热门商品"
  });

  const viewModel = buildHomeViewModel(response({
    productSections: [
      section("RECOMMENDED", "COMPACT", [recommended]),
      section("HOT", "FEATURED", [hot])
    ]
  }));

  assert.deepEqual(viewModel.featuredProducts.map((item) => item.spuId), [102]);
  assert.deepEqual(viewModel.compactProducts.map((item) => item.spuId), [101]);
});

test("schema v3 空响应得到稳定空页面模型", () => {
  assert.deepEqual(buildHomeViewModel(response()), {
    schemaVersion: 3,
    banners: [],
    categories: [],
    featuredProducts: [],
    compactProducts: [],
    hasContent: false
  });
});

test("价格格式化覆盖零分、区间、单边价格和非法边界", () => {
  assert.equal(formatPriceRange(0, 0), "0.00");
  assert.equal(formatPriceRange(1, 1), "0.01");
  assert.equal(formatPriceRange(1990, 1990), "19.90");
  assert.equal(formatPriceRange(1990, 2590), "19.90–25.90");
  assert.equal(formatPriceRange(1990, undefined), "19.90 起");
  assert.equal(formatPriceRange(undefined, 2590), "25.90");
  assert.equal(formatPriceRange(undefined, undefined), "");
  assert.equal(formatPriceRange(2590, 1990), "");
  assert.equal(formatPriceRange(-1, -1), "");
  assert.equal(formatPriceRange(1.5, 1.5), "");
  assert.equal(formatPriceRange(Number.NaN, Number.POSITIVE_INFINITY), "");
  assert.equal(formatPriceRange(Number.MAX_SAFE_INTEGER + 1, Number.MAX_SAFE_INTEGER + 1), "");
  assert.equal(formatPriceRange("1990", "2590"), "");
});

test("辣度参数按 level 从绿色过渡到红色并显示对应数量的本地图标", () => {
  const spiceFact = (level: number) => adaptProductFact({
    code: "SPICE",
    name: "辣度",
    displayText: level === 1 ? "微辣" : level === 2 ? "中辣" : "特辣",
    renderer: "SPICE",
    level
  });

  assert.deepEqual(
    [1, 2, 3].map((level) => {
      const fact = spiceFact(level);
      return fact && [fact.spiceTone, fact.tone, fact.iconPath, fact.spiceIconIndexes];
    }),
    [
      ["mild", "success", "/assets/icons/chili-pepper-red.svg", [0]],
      ["medium", "orange", "/assets/icons/chili-pepper-red.svg", [0, 1]],
      ["hot", "brand", "/assets/icons/chili-pepper-red.svg", [0, 1, 2]]
    ]
  );
});

test("辣度 level 未配置或非法时不显示辣椒图标", () => {
  const spiceFact = (level?: number) => adaptProductFact({
    code: "SPICE",
    name: "辣度",
    displayText: "辣度未知",
    renderer: "SPICE",
    ...(level === undefined ? {} : { level })
  });

  [spiceFact(), spiceFact(Number.NaN), spiceFact(1.5)].forEach((fact) => {
    assert.equal(fact?.spiceTone, "");
    assert.equal(fact?.tone, "neutral");
    assert.deepEqual(fact?.spiceIconIndexes, []);
  });
});

test("辣度 level 缺失时可从标准辣度文案补齐展示等级", () => {
  const spiceFact = (displayText: string) => adaptProductFact({
    code: "SPICE",
    name: "辣度",
    displayText,
    renderer: "SPICE"
  });

  assert.deepEqual(
    ["微辣", "中辣", "特辣", "变态辣"].map((label) => {
      const fact = spiceFact(label);
      return fact && [fact.spiceTone, fact.tone, fact.spiceIconIndexes?.length ?? 0];
    }),
    [
      ["mild", "success", 1],
      ["medium", "orange", 2],
      ["hot", "brand", 3],
      ["hot", "brand", 4]
    ]
  );
});

test("异常偏大的辣度等级最多展示五个辣椒图标", () => {
  const fact = adaptProductFact({
    code: "SPICE",
    name: "辣度",
    displayText: "超辣",
    renderer: "SPICE",
    level: 99
  });

  assert.equal(fact?.spiceTone, "hot");
  assert.deepEqual(fact?.spiceIconIndexes, [0, 1, 2, 3, 4]);
});

test("重量参数按克数追加建议人数并保持独立类型", () => {
  assert.equal(parseWeightGram("净含量 300g"), 300);
  assert.equal(parseWeightGram("0.5kg"), 500);
  assert.equal(parseWeightGram("1 千克"), 1000);
  assert.equal(parseWeightGram("2公斤"), 2000);
  assert.equal(parseWeightGram("未知"), undefined);

  assert.equal(servingTextByWeight("150g"), "适合1-2人");
  assert.equal(servingTextByWeight("250克"), "适合2-3人");
  assert.equal(servingTextByWeight("300g"), "适合3-5人");
  assert.equal(servingTextByWeight("750g"), "适合5-7人");
  assert.equal(servingTextByWeight("1kg"), "适合8-10人");
  assert.equal(servingTextByWeight("1.5kg"), "适合10人以上");
  assert.equal(servingTextByWeight("未知"), "");

  assert.deepEqual(adaptProductFact({
    code: "NET_WEIGHT",
    name: "净含量",
    displayText: "300g",
    renderer: "PILL"
  }), {
    text: "300g",
    tone: "neutral",
    kind: "weight",
    spiceTone: "",
    iconPath: "",
    spiceIconIndexes: [],
    servingText: "适合3-5人"
  });
});

test("首页路径只允许白名单内的商品列表和详情路径", () => {
  assert.equal(
    normalizeHomePath("  /pages/product/detail/detail?id=41  "),
    "/pages/product/detail/detail?id=41"
  );
  assert.equal(
    normalizeHomePath("/pages/product/list/list?categoryId=21"),
    "/pages/product/list/list?categoryId=21"
  );
  assert.equal(normalizeHomePath("/pages/product/detail/detail"), "");
});

test("外部协议、未知页面和可疑本地路径不会进入导航", () => {
  const overlongPath = `/pages/product/detail/detail?id=${"1".repeat(513)}`;
  const invalidPaths = [
    "",
    "https://evil.example/pages/product/detail/detail?id=41",
    "//evil.example/pages/product/detail/detail?id=41",
    "javascript:alert(1)",
    "/pages/admin/index",
    "/pages/product/detail/../detail?id=41",
    "/pages/product/detail\\detail?id=41",
    "/pages/product/detail/detail?id=41#fragment",
    "/pages/product/detail/detail\n?id=41",
    "/pages/product/detail/detail?id=41&redirect=https://evil.example",
    "/pages/product/detail/detail?id=41&id=42",
    "/pages/product/detail/detail?id=0",
    "/pages/product/list/list?categoryId=-1",
    overlongPath
  ];

  invalidPaths.forEach((path) => {
    assert.equal(normalizeHomePath(path), "", `应拒绝路径：${JSON.stringify(path)}`);
  });
});

test("schemaVersion 不匹配时显式拒绝渲染", () => {
  assert.throws(
    () => buildHomeViewModel(response({ schemaVersion: 1 })),
    /暂不支持首页数据版本 1/
  );
  assert.throws(
    () => buildHomeViewModel(response({ schemaVersion: 4 })),
    /暂不支持首页数据版本 4/
  );
});
