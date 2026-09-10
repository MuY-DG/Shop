import assert from "node:assert/strict";
import { test } from "node:test";
import { buildCatalogProductCard, productSearchMatchText } from "../miniprogram/features/product-catalog";
import type { ProductListItem, ProductSearchMatch } from "../miniprogram/types/product";

const match = (overrides: Partial<ProductSearchMatch> = {}): ProductSearchMatch => ({
  skuId: 11,
  specText: "牛油 / 500g",
  status: "ENABLED",
  saleState: "SOLD_OUT",
  ...overrides
});

test("规格命中说明标明缺货，并兼容无搜索信息的旧接口", () => {
  assert.equal(productSearchMatchText(), "");
  assert.equal(productSearchMatchText([]), "");
  assert.equal(productSearchMatchText([match()]), "相关规格：牛油 | 500g（缺货）");
  assert.equal(productSearchMatchText([match({ status: "DISABLED" })]), "");
  assert.equal(productSearchMatchText([match({ specText: "" })]), "");
  assert.equal(productSearchMatchText([
    match({ specText: "500g", saleState: "AVAILABLE" }),
    match({ specText: "200g" }),
    match({ specText: "100g" })
  ]), "相关规格：500g；200g（缺货） 等");
});

test("缺货的搜索规格不会覆盖整件商品的售价、封面和可购买状态", () => {
  const product: ProductListItem = {
    id: 1,
    categoryId: 1,
    title: "火锅底料",
    mainImage: "https://example.test/spu.png",
    minPriceCent: 1200,
    maxPriceCent: 5000,
    saleState: "AVAILABLE",
    displaySales: 12,
    sellingPoints: [],
    parameters: [],
    searchMatches: [match()]
  };
  const card = buildCatalogProductCard(product)!;
  assert.equal(card.searchMatchText, "相关规格：牛油 | 500g（缺货）");
  assert.equal(card.priceText, "12.00–50.00");
  assert.equal(card.imageUrl, product.mainImage);
  assert.equal(card.soldOut, false);
  assert.equal(card.navigationPath, "/pages/product/detail/detail?id=1");
});
