import assert from "node:assert/strict";
import { test } from "node:test";

import {
  cartAddErrorMessage,
  isStockShortageError
} from "../miniprogram/features/cart-feedback";
import { ApiError } from "../miniprogram/utils/api-error";

test("购物车库存与下架错误统一显示中文提示", () => {
  const shortage = new ApiError({
    kind: "API",
    code: 200100,
    message: "Stock shortage"
  });
  assert.equal(cartAddErrorMessage(shortage, "失败"), "商品已达最大可购买数");
  assert.equal(isStockShortageError(shortage), true);
  assert.equal(cartAddErrorMessage(new ApiError({
    kind: "API",
    code: 200001,
    message: "Product unavailable"
  }), "失败"), "商品已下架");
  assert.equal(cartAddErrorMessage(new ApiError({
    kind: "API",
    code: 200002,
    message: "SKU unavailable"
  }), "失败"), "该规格已下架");
});
