import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  buildOrderAddressOptions,
  canModifyOrderReceiver,
  normalizeSelectedAddressId,
  parseModifyOrderId
} from "../miniprogram/pages/order/modify/model";
import {
  buildPendingOrderReviewItems,
  isReviewableOrderStatus,
  parseReviewOrderId,
  reviewProgressText,
  updateOrderReviewDraft,
  type OrderReviewSourceItem
} from "../miniprogram/pages/order/review/model";
import type { AddressResponse } from "../miniprogram/types/checkout";
import { createPageOperationGuard } from "../miniprogram/features/order-center";

const sourceRoot = resolve(process.cwd(), "miniprogram");

function orderItem(
  orderItemId: number,
  reviewed: boolean,
  specText = "500g · 微辣",
  reviewable = !reviewed
): OrderReviewSourceItem {
  return {
    orderItemId,
    skuId: 20 + orderItemId,
    spuId: 30 + orderItemId,
    productTitle: `火锅底料 ${orderItemId}`,
    productSubtitle: "",
    mainImage: "https://example.test/main.jpg",
    skuImage: "https://example.test/sku.jpg",
    displayImage: "https://example.test/display.jpg",
    skuCode: `SKU-${orderItemId}`,
    specText,
    originalPriceCent: 1990,
    unitPriceCent: 1690,
    retailUnitPriceCent: 1990,
    quantity: 2,
    lineOriginalAmountCent: 3980,
    lineAmountCent: 3380,
    reviewed,
    reviewable
  };
}

function address(id: string, formattedAddress: string): AddressResponse {
  return {
    id,
    receiverName: "小灶",
    receiverPhone: "13800138000",
    province: "四川省",
    city: "成都市",
    district: "锦江区",
    detailAddress: "春熙路 1 号",
    isDefault: id === "2",
    formattedAddress
  };
}

test("独立评价页只接收已完成订单中的未评价商品和真实规格", () => {
  assert.equal(parseReviewOrderId("101"), 101);
  assert.equal(parseReviewOrderId("1e2"), 0);
  assert.equal(isReviewableOrderStatus("COMPLETED"), true);
  assert.equal(isReviewableOrderStatus("SHIPPED"), false);

  const pending = buildPendingOrderReviewItems([
    orderItem(1, false),
    orderItem(2, true),
    orderItem(3, false, ""),
    orderItem(4, false, "500g · 微辣", false)
  ]);
  assert.equal(pending.length, 2);
  assert.equal(pending[0]?.imageUrl, "https://example.test/display.jpg");
  assert.equal(pending[0]?.specTextDisplay, "500g · 微辣");
  assert.equal(pending[1]?.specTextDisplay, "");
  assert.equal(pending[0]?.rating, 5);
  assert.equal(pending[0]?.stars.filter((star) => star.filled).length, 5);

  const updated = updateOrderReviewDraft(pending, 1, {
    rating: 3,
    content: "  很香  ",
    anonymous: true
  });
  assert.equal(updated[0]?.rating, 3);
  assert.equal(updated[0]?.content, "  很香  ");
  assert.equal(updated[0]?.anonymous, true);
  assert.equal(reviewProgressText(1, 2), "已完成 1/2");
});

test("修改订单页只允许待付款状态并只选择本人已保存地址", () => {
  assert.equal(parseModifyOrderId("88"), 88);
  assert.equal(parseModifyOrderId("-1"), 0);
  assert.equal(canModifyOrderReceiver("CREATED"), true);
  assert.equal(canModifyOrderReceiver("PAYING"), true);
  assert.equal(canModifyOrderReceiver("PAID"), false);
  assert.equal(normalizeSelectedAddressId(" 2 "), "2");
  assert.equal(normalizeSelectedAddressId("2x"), "");

  const options = buildOrderAddressOptions([
    address("1", "四川省成都市锦江区春熙路 1 号"),
    address("2", "")
  ], "2");
  assert.equal(options[0]?.phoneDisplay, "138****8000");
  assert.equal(options[1]?.selected, true);
  assert.equal(options[1]?.detailDisplay, "四川省 成都市 锦江区 春熙路 1 号");
});

test("页面操作令牌在卸载和后续操作后立即失效", () => {
  const guard = createPageOperationGuard();
  const firstPage = guard.mount();
  const firstOperation = guard.begin(firstPage);
  assert.equal(guard.isCurrent(firstPage, firstOperation), true);

  const nextOperation = guard.begin(firstPage);
  assert.equal(guard.isCurrent(firstPage, firstOperation), false);
  assert.equal(guard.isCurrent(firstPage, nextOperation), true);

  guard.unmount(firstPage);
  assert.equal(guard.isCurrent(firstPage, nextOperation), false);
  assert.equal(guard.begin(firstPage), 0);

  const secondPage = guard.mount();
  const secondOperation = guard.begin(secondPage);
  assert.equal(guard.isCurrent(secondPage, secondOperation), true);
  assert.equal(guard.isCurrent(firstPage, nextOperation), false);
});

test("评价与修改订单页面注册真实端点并支持评价图片", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as { pages: string[] };
  const endpoints = readFileSync(
    resolve(sourceRoot, "constants/api-endpoints.ts"),
    "utf8"
  );
  const orderService = readFileSync(
    resolve(sourceRoot, "services/order.ts"),
    "utf8"
  );
  const reviewLogic = readFileSync(
    resolve(sourceRoot, "pages/order/review/review.ts"),
    "utf8"
  );
  const reviewTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/review/review.wxml"),
    "utf8"
  );
  const reviewStyle = readFileSync(
    resolve(sourceRoot, "pages/order/review/review.less"),
    "utf8"
  );
  const modifyLogic = readFileSync(
    resolve(sourceRoot, "pages/order/modify/modify.ts"),
    "utf8"
  );
  const modifyTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/modify/modify.wxml"),
    "utf8"
  );
  const modifyStyle = readFileSync(
    resolve(sourceRoot, "pages/order/modify/modify.less"),
    "utf8"
  );
  const orderListLogic = readFileSync(
    resolve(sourceRoot, "pages/order/list/list.ts"),
    "utf8"
  );

  assert.ok(appConfig.pages.includes("pages/order/review/review"));
  assert.ok(appConfig.pages.includes("pages/order/modify/modify"));
  assert.match(endpoints, /`\/app\/orders\/\$\{orderId\}\/receiver`/);
  assert.match(orderService, /updateOrderReceiver[\s\S]*method: "PUT"[\s\S]*data: \{ addressId \}/);

  assert.match(reviewLogic, /createProductReview/);
  assert.match(reviewLogic, /detail\.status/);
  assert.match(reviewLogic, /\[200001, 200201, 200202, 400001\]/);
  assert.match(reviewLogic, /pendingItems: \[\][\s\S]*selectedItem: null[\s\S]*loaded: false/);
  assert.match(reviewLogic, /await this\.loadOrder\(\)/);
  assert.match(reviewLogic, /reviewOperationGuard\.unmount\(this\.data\.lifecycleToken\)/);
  assert.match(
    reviewLogic,
    /await createProductReview[\s\S]*if \(!reviewOperationGuard\.isCurrent\(lifecycleToken, operationToken\)\)[\s\S]*this\.setData/
  );
  assert.match(
    reviewLogic,
    /success: \(\) => \{[\s\S]*reviewOperationGuard\.isCurrent\(lifecycleToken, operationToken\)[\s\S]*this\.leavePage\(\)/
  );
  assert.match(reviewTemplate, /发布评价/);
  assert.match(reviewTemplate, /maxlength="1000"/);
  assert.match(reviewTemplate, /bindchange="onAnonymousChange"/);
  assert.match(reviewTemplate, /评价图片（选填）/);
  assert.match(reviewTemplate, /bindtap="onChooseReviewImage"/);
  assert.match(reviewLogic, /uploadProductReviewImage/);
  assert.match(reviewLogic, /imageFileIds: item\.reviewImages\.map/);
  assert.match(endpoints, /review-images\/upload-sessions/);
  assert.doesNotMatch(reviewTemplate, /视频|晒单/);
  assert.doesNotMatch(reviewTemplate, /默认规格/);
  assert.match(reviewStyle, /\.rating-star \{[\s\S]*width: 88rpx/);
  assert.match(reviewStyle, /\.rating-star \{[\s\S]*height: 88rpx/);

  assert.match(modifyLogic, /getAddresses/);
  assert.match(modifyLogic, /updateOrderReceiver/);
  assert.match(modifyLogic, /await this\.loadPage\(\)/);
  assert.match(modifyLogic, /modifyOperationGuard\.unmount\(this\.data\.lifecycleToken\)/);
  assert.match(
    modifyLogic,
    /await updateOrderReceiver[\s\S]*if \(!modifyOperationGuard\.isCurrent\(lifecycleToken, operationToken\)\)[\s\S]*this\.setData/
  );
  assert.match(
    modifyLogic,
    /setTimeout\([\s\S]*modifyOperationGuard\.isCurrent\(lifecycleToken, operationToken\)[\s\S]*this\.leavePage\(\)/
  );
  assert.match(modifyTemplate, /仅支持修改收货地址/);
  assert.doesNotMatch(modifyTemplate, /普通快递|配送方式/);
  assert.match(modifyTemplate, /bindtap="onAddAddress"/);
  assert.doesNotMatch(modifyTemplate, /修改商品|修改数量|修改价格/);
  assert.match(modifyStyle, /\.add-address \{[\s\S]*height: 88rpx/);
  assert.match(modifyStyle, /\.address-empty button \{[\s\S]*height: 88rpx/);

  assert.match(orderListLogic, /rebuyOperationGuard\.unmount\(this\.data\.lifecycleToken\)/);
  assert.match(
    orderListLogic,
    /await getOrderDetail[\s\S]*rebuyOperationGuard\.isCurrent\(lifecycleToken, operationToken\)[\s\S]*await addCartItem/
  );
  assert.match(
    orderListLogic,
    /fail: \(\) => \{[\s\S]*rebuyOperationGuard\.isCurrent\(lifecycleToken, operationToken\)[\s\S]*wx\.switchTab/
  );
});
