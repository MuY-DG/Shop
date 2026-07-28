import assert from "node:assert/strict";
import { test } from "node:test";

import {
  addProductSearchHistory,
  normalizeProductSearchHistory,
  PRODUCT_SEARCH_HISTORY_LIMIT
} from "../miniprogram/features/product-search";

test("搜索历史去空、去重并将最新关键词置顶", () => {
  assert.deepEqual(
    normalizeProductSearchHistory(["  牛油  锅底 ", "", "牛油 锅底", "番茄"]),
    ["牛油 锅底", "番茄"]
  );
  assert.deepEqual(
    addProductSearchHistory(["番茄", "牛油锅底"], "  牛油锅底 "),
    ["牛油锅底", "番茄"]
  );
});

test("搜索历史限制为最近十条", () => {
  const history = Array.from(
    { length: PRODUCT_SEARCH_HISTORY_LIMIT + 4 },
    (_item, index) => `关键词${index}`
  );
  const result = addProductSearchHistory(history, "最新");
  assert.equal(result.length, PRODUCT_SEARCH_HISTORY_LIMIT);
  assert.equal(result[0], "最新");
});
