import assert from "node:assert/strict";
import { test } from "node:test";

import {
  normalizeQuantityInput,
  stockQuantityCorrectedMessage
} from "../miniprogram/features/quantity";

test("数量输入按实时最大可购买数修正并保留合法输入", () => {
  assert.deepEqual(normalizeQuantityInput("6", 2, 8), {
    quantity: 6,
    exceededStock: false
  });
  assert.deepEqual(normalizeQuantityInput("12", 2, 8), {
    quantity: 8,
    exceededStock: true
  });
  assert.deepEqual(normalizeQuantityInput("", 3, 8), {
    quantity: 3,
    exceededStock: false
  });
  assert.deepEqual(normalizeQuantityInput("3.5", 2, 8), {
    quantity: 2,
    exceededStock: false
  });
  assert.deepEqual(normalizeQuantityInput("9", 2, 0), {
    quantity: 0,
    exceededStock: false
  });
  assert.equal(stockQuantityCorrectedMessage(8), "库存不足，已修改为8");
});
