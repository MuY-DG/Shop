import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  buildCurrentReceiverView,
  buildOrderAddressOptions,
  buildSelectedReceiverView,
  canModifyOrderReceiver,
  normalizeSelectedAddressId,
  parseModifyOrderId,
  resolveCurrentOrderAddressId
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
    orderItem(3, false, "默认规格"),
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

test("修改订单页允许发货前状态并只选择本人已保存地址", () => {
  assert.equal(parseModifyOrderId("88"), 88);
  assert.equal(parseModifyOrderId("-1"), 0);
  assert.equal(canModifyOrderReceiver("CREATED"), true);
  assert.equal(canModifyOrderReceiver("PAYING"), true);
  assert.equal(canModifyOrderReceiver("PAID"), true);
  assert.equal(canModifyOrderReceiver("SHIPPED"), false);
  assert.equal(normalizeSelectedAddressId(" 2 "), "2");
  assert.equal(normalizeSelectedAddressId("2x"), "");

  const options = buildOrderAddressOptions([
    address("1", "四川省成都市锦江区春熙路 1 号"),
    address("2", "")
  ], "2");
  assert.equal(options[0]?.phoneDisplay, "138****8000");
  assert.equal(options[1]?.selected, true);
  assert.equal(options[1]?.detailDisplay, "四川省 成都市 锦江区 春熙路 1 号");

  const selectedReceiver = buildSelectedReceiverView(options[0]!);
  assert.equal(selectedReceiver.receiverPhone, "13800138000");
  assert.equal(selectedReceiver.receiverRegion, "四川省 成都市 锦江区");
  assert.equal(selectedReceiver.receiverDetailAddress, "春熙路 1 号");

  const currentReceiver = buildCurrentReceiverView({
    receiverName: "勇敢牛牛",
    receiverPhone: "15212347668",
    receiverAddress: "宁夏回族自治区吴忠市盐池县紫都学府商业楼"
  }, []);
  assert.equal(currentReceiver.receiverPhone, "15212347668");
  assert.equal(currentReceiver.receiverRegion, "宁夏回族自治区 吴忠市 盐池县");
  assert.equal(currentReceiver.receiverDetailAddress, "紫都学府商业楼");
  assert.equal(resolveCurrentOrderAddressId({
    receiverAddress: "四川省成都市锦江区春熙路 1 号"
  }, options), "1");

  const doorplateOption = buildOrderAddressOptions([{
    ...address("3", ""),
    locationName: "春熙里",
    doorplate: "3 栋 201"
  }], "3")[0]!;
  const doorplateReceiver = buildSelectedReceiverView(doorplateOption);
  assert.equal(
    doorplateReceiver.receiverDetailAddress,
    "春熙路 1 号 春熙里 3 栋 201"
  );
  assert.equal(
    doorplateOption.detailDisplay,
    "四川省 成都市 锦江区 春熙路 1 号 春熙里 3 栋 201"
  );
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
  ) as {
    pages: string[];
    subPackages?: Array<{ root: string; pages: string[] }>;
  };
  const pagePaths = [
    ...appConfig.pages,
    ...(appConfig.subPackages ?? []).flatMap(({ root, pages }) =>
      pages.map((pagePath) => `${root}/${pagePath}`)
    )
  ];
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
  const orderCenterLogic = readFileSync(
    resolve(sourceRoot, "features/order-center.ts"),
    "utf8"
  );

  assert.ok(pagePaths.includes("pages/order/review/review"));
  assert.ok(pagePaths.includes("pages/order/modify/modify"));
  assert.ok(pagePaths.includes("pages/order/search/search"));
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
  assert.match(modifyLogic, /onAddressSheetOpen/);
  assert.match(modifyLogic, /onAddressSheetClose/);
  assert.match(
    modifyLogic,
    /onConfirmTap[\s\S]*wx\.showModal[\s\S]*title: "确认修改"[\s\S]*result\.confirm[\s\S]*this\.saveReceiver\(\)/
  );
  assert.match(
    modifyLogic,
    /onAddressSelect[\s\S]*selectedAddressId[\s\S]*currentReceiver[\s\S]*addressSheetOpen: false/
  );
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
  assert.match(modifyTemplate, /发货前可修改收货地址/);
  assert.match(modifyTemplate, /<navigation-bar[\s\S]*?<scroll-view[\s\S]*?class="modify-scroll"[\s\S]*?scroll-y="\{\{true\}\}"/);
  assert.match(modifyTemplate, /修改收货人信息/);
  assert.match(modifyTemplate, /class="switch-address"[\s\S]*profile-address\.svg[\s\S]*切换地址/);
  assert.match(modifyTemplate, /receiver-field__label">收货人/);
  assert.match(modifyTemplate, /receiver-field__label">手机号码/);
  assert.match(modifyTemplate, /receiver-field__label">所在地区/);
  assert.match(modifyTemplate, /receiver-field__label">详细地址/);
  assert.match(modifyTemplate, /addressSheetOpen[\s\S]*选择收货地址[\s\S]*onAddressSelect/);
  assert.match(modifyTemplate, /disabled="\{\{saving\}\}"[\s\S]*bindtap="onConfirmTap"/);
  assert.doesNotMatch(modifyTemplate, /disabled="\{\{saving \|\| !selectedAddressId\}\}"/);
  assert.doesNotMatch(modifyTemplate, /当前收货信息|订单 \{\{detail\.orderNo\}\}/);
  assert.doesNotMatch(modifyTemplate, /选择新的收货地址/);
  assert.doesNotMatch(modifyTemplate, /普通快递|配送方式/);
  assert.match(modifyTemplate, /bindtap="onAddAddress"/);
  assert.doesNotMatch(modifyTemplate, /修改商品|修改数量|修改价格/);
  assert.match(modifyStyle, /\.modify-page \{[\s\S]*height: 100vh;[\s\S]*display: flex;[\s\S]*overflow: hidden;[\s\S]*flex-direction: column/);
  assert.match(modifyStyle, /\.modify-scroll \{[\s\S]*height: 0;[\s\S]*min-height: 0;[\s\S]*flex: 1/);
  assert.match(modifyStyle, /\.info-card \{[\s\S]*background: @color-surface-white/);
  assert.match(modifyStyle, /\.switch-address \{[\s\S]*color: @color-text-black;[\s\S]*background: #eeeeee/);
  assert.match(modifyStyle, /\.receiver-field__label \{[\s\S]*color: @color-text-gray/);
  assert.match(modifyStyle, /\.receiver-field__value \{[\s\S]*color: @color-text-black;[\s\S]*text-align: right/);
  assert.match(modifyStyle, /\.modify-footer \{[\s\S]*background: @color-surface-white/);
  assert.match(modifyStyle, /\.modify-submit \{[\s\S]*background: @color-action-primary/);

  assert.match(orderListLogic, /rebuyOperationGuard\.unmount\(this\.data\.lifecycleToken\)/);
  assert.match(orderListLogic, /keyword: normalizeOrderRouteKeyword\(query\.keyword\)/);
  assert.match(orderListLogic, /buildAfterSaleApplyUrl\(orderId\)/);
  assert.doesNotMatch(orderListLogic, /showActionSheet/);
  assert.match(orderListLogic, /onCopyOrderNoTap[\s\S]*copyOrderNo\(event\.currentTarget\.dataset\.orderNo\)/);
  assert.match(orderListLogic, /onDeleteMenuTap[\s\S]*if \(!order\?\.canDelete\)[\s\S]*deleteSelectedOrder\(orderId\)/);
  assert.match(orderCenterLogic, /setClipboardData\([\s\S]*data: orderNo[\s\S]*clipboardFailureMessage\(error\)/);
  assert.doesNotMatch(orderCenterLogic, /getClipboardData\(/);
  assert.match(
    orderListLogic,
    /await getOrderDetail[\s\S]*rebuyOperationGuard\.isCurrent\(lifecycleToken, operationToken\)[\s\S]*await addCartItem/
  );
  assert.match(
    orderListLogic,
    /fail: \(\) => \{[\s\S]*rebuyOperationGuard\.isCurrent\(lifecycleToken, operationToken\)[\s\S]*wx\.switchTab/
  );
});
