import assert from "node:assert/strict";
import test from "node:test";

import {
  MAX_PRODUCT_SEARCH_KEYWORD_LENGTH,
  buildProductListFilters,
  normalizeProductSearchKeyword
} from "../features/product-search";

test("normalizes product search terms to the analytics and API boundary", () => {
  assert.equal(normalizeProductSearchKeyword("  牛油锅底  "), "牛油锅底");
  assert.equal(
    normalizeProductSearchKeyword("火".repeat(100)).length,
    MAX_PRODUCT_SEARCH_KEYWORD_LENGTH
  );
});

test("keeps category and keyword filters together and clears each explicitly", () => {
  assert.deepEqual(buildProductListFilters(12, "  菌汤  "), {
    categoryId: 12,
    keyword: "菌汤"
  });
  assert.deepEqual(buildProductListFilters(0, ""), {});
  assert.deepEqual(buildProductListFilters(12, "   "), { categoryId: 12 });
});
