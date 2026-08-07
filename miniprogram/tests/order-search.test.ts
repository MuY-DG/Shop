import assert from "node:assert/strict";
import { test } from "node:test";

import {
  addOrderSearchHistory,
  buildOrderSearchResultUrl,
  normalizeOrderRouteKeyword,
  normalizeOrderSearchHistory,
  ORDER_SEARCH_HISTORY_KEY,
  ORDER_SEARCH_HISTORY_LIMIT
} from "../miniprogram/features/order-search";
import { PRODUCT_SEARCH_HISTORY_KEY } from "../miniprogram/features/product-search";

test("订单搜索历史与商品搜索历史使用不同存储键", () => {
  assert.notEqual(ORDER_SEARCH_HISTORY_KEY, PRODUCT_SEARCH_HISTORY_KEY);
});

test("订单搜索历史去空去重并限制最近十条", () => {
  assert.deepEqual(
    normalizeOrderSearchHistory(["  牛油  锅底 ", "", "牛油 锅底", "ORD-101"]),
    ["牛油 锅底", "ORD-101"]
  );
  const history = Array.from(
    { length: ORDER_SEARCH_HISTORY_LIMIT + 4 },
    (_item, index) => `订单关键词${index}`
  );
  const result = addOrderSearchHistory(history, "最新订单");
  assert.equal(result.length, ORDER_SEARCH_HISTORY_LIMIT);
  assert.equal(result[0], "最新订单");
});

test("订单搜索结果路由安全编码商品名或订单号", () => {
  assert.equal(
    buildOrderSearchResultUrl(" 牛油 锅底 "),
    "/pages/order/list/list?keyword=%E7%89%9B%E6%B2%B9%20%E9%94%85%E5%BA%95"
  );
  assert.equal(normalizeOrderRouteKeyword("ORD%2F101"), "ORD/101");
  assert.throws(() => buildOrderSearchResultUrl("  "), /不能为空/);
});
