import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

const sourceRoot = resolve(process.cwd(), "miniprogram");

function readSource(relativePath: string): string {
  return readFileSync(resolve(sourceRoot, relativePath), "utf8");
}

test("商品现价统一使用圆润、紧凑的比例数字", () => {
  const tokens = readSource("styles/tokens.less");
  const productCardStyle = readSource("components/product-card/product-card.less");
  assert.match(tokens, /@color-price:\s*#fa091d;/);
  assert.match(tokens, /@color-detail-price:\s*@color-price;/);
  assert.match(tokens, /@font-family-price:\s*@font-family-commerce-price;/);
  assert.doesNotMatch(tokens, /DIN Alternate|Arial Narrow/);
  assert.match(productCardStyle, /\.product-card__price\s*\{[\s\S]*color:\s*@color-price;/);

  const priceSurfaceStyles = [
    "components/product-card/product-card.less",
    "components/product-summary/product-summary.less",
    "styles/account-products.less",
    "pages/account/history/history.less",
    "pages/cart/cart.less",
    "pages/product/detail/detail.less",
    "pages/order/list/list.less",
    "pages/order/preview/preview.less",
    "pages/order/detail/detail.less",
    "pages/order/created/created.less",
    "pages/after-sale/apply/apply.less",
    "pages/after-sale/list/list.less",
    "pages/after-sale/detail/detail.less",
    "pages/customer-service/chat/chat.less"
  ];

  priceSurfaceStyles.forEach((relativePath) => {
    const source = readSource(relativePath);
    assert.match(
      source,
      /font-family:\s*@font-family-commerce-price;/,
      `${relativePath} should use the commerce price font`
    );
    assert.match(
      source,
      /font-variant-numeric:\s*proportional-nums;/,
      `${relativePath} should use compact proportional numbers`
    );
  });
});

test("划线价恢复为普通次要文字而不套用现价数字样式", () => {
  const strikePriceStyles = [
    ["components/product-card/product-card.less", ".product-card__original-price"],
    ["components/product-summary/product-summary.less", ".original-price"],
    ["pages/cart/cart.less", ".price-row__retail"],
    ["pages/product/detail/detail.less", ".sheet-original-price"]
  ] as const;

  strikePriceStyles.forEach(([relativePath, selector]) => {
    const source = readSource(relativePath);
    const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const block = source.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))?.[1] ?? "";
    assert.match(block, /font-family:\s*@font-family-base;/, `${selector} should use the base font`);
    assert.doesNotMatch(block, /font-variant-numeric:/, `${selector} should not force a numeric variant`);
  });
});

test("详情主价格的小数层级比整数克制", () => {
  const productCardStyle = readSource("components/product-card/product-card.less");
  const summaryStyle = readSource("components/product-summary/product-summary.less");
  const detailStyle = readSource("pages/product/detail/detail.less");

  assert.match(productCardStyle, /\.product-card__price\s*\{[^}]*letter-spacing:\s*0;/);
  assert.match(productCardStyle, /\.product-card__price-decimal\s*\{\s*margin-left:\s*1rpx;/);
  assert.match(summaryStyle, /\.price-integer\s*\{\s*font-size:\s*56rpx;/);
  assert.match(summaryStyle, /\.price-decimal\s*\{\s*margin-left:\s*1rpx;\s*font-size:\s*30rpx;/);
  assert.match(detailStyle, /\.sheet-price__integer\s*\{\s*font-size:\s*50rpx;/);
  assert.match(detailStyle, /\.sheet-price__decimal\s*\{\s*margin-left:\s*1rpx;\s*font-size:\s*28rpx;/);
});

test("小计、合计和售后金额沿用统一的金额样式", () => {
  const previewStyle = readSource("pages/order/preview/preview.less");
  const orderDetailStyle = readSource("pages/order/detail/detail.less");
  const orderDetailTemplate = readSource("pages/order/detail/detail.wxml");
  const afterSaleTemplate = readSource("pages/after-sale/detail/detail.wxml");

  assert.match(previewStyle, /\.amount-row\s*>\s*text:last-child[\s\S]*font-variant-numeric:\s*proportional-nums;/);
  assert.match(previewStyle, /\.submit-action__amount\s*\{[\s\S]*font-family:\s*@font-family-commerce-price;/);
  assert.match(orderDetailStyle, /\.amount-row\s*>\s*text:last-child[\s\S]*font-variant-numeric:\s*proportional-nums;/);
  assert.match(orderDetailTemplate, /class="payment-summary__amount"/);
  assert.match(afterSaleTemplate, /审核金额<\/text><text class="price-text">/);
  assert.match(afterSaleTemplate, /退款金额<\/text><text class="price-text">/);
});
