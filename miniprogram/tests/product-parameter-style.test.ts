import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

const sourceRoot = resolve(process.cwd(), "miniprogram");

function readSource(relativePath: string): string {
  return readFileSync(resolve(sourceRoot, relativePath), "utf8");
}

test("辣度色阶以商品卡片和详情页颜色为统一语义令牌", () => {
  const tokens = readSource("styles/tokens.less");
  const cardStyle = readSource("components/product-card/product-card.less");
  const detailStyle = readSource("pages/product/detail/detail.less");

  assert.match(tokens, /@color-spice-mild:\s*#7baa6d;/);
  assert.match(tokens, /@color-spice-medium:\s*@color-warning;/);
  assert.match(tokens, /@color-spice-hot:\s*#ff172b;/);

  [
    ["mild", "@color-spice-mild"],
    ["medium", "@color-spice-medium"],
    ["hot", "@color-spice-hot"]
  ].forEach(([tone, token]) => {
    assert.match(cardStyle, new RegExp(`\\.product-card__fact--${tone}\\s*\\{[^}]*color:\\s*${token};`));
    assert.match(detailStyle, new RegExp(`\\.commerce-parameter-spice--${tone}\\s*\\{\\s*color:\\s*${token};`));
    assert.match(detailStyle, new RegExp(`\\.parameter-sheet-spice--${tone}\\s*\\{\\s*color:\\s*${token};`));
  });
});

test("商品卡片和详情参数面板只给辣度着色", () => {
  const cardTemplate = readSource("components/product-card/product-card.wxml");
  const cardStyle = readSource("components/product-card/product-card.less");
  const detailTemplate = readSource("pages/product/detail/detail.wxml");

  assert.match(cardTemplate, /feature\.kind === 'spice'[\s\S]*product-card__fact--\{\{feature\.spiceTone\}\}/);
  assert.match(cardStyle, /\.product-card__fact--weight\s*\{[^}]*color:\s*@color-text-gray;/);
  assert.match(detailTemplate, /item\.fact\.kind === 'spice' \? 'parameter-sheet-spice parameter-sheet-spice--'/);
  assert.match(detailTemplate, /class="parameter-sheet-spice-icons"[\s\S]*wx:for="\{\{item\.fact\.spiceIconIndexes\}\}"/);
});
