import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildOrderDetailUrl,
  buildOrderDetailView,
  buildOrderListUrl,
  buildOrderModifyUrl,
  buildOrderReviewUrl,
  buildOrderSummaryView,
  filterRebuyableOrderItems,
  formatPaymentCountdown,
  ORDER_STATUS_TABS,
  parseOrderStatusGroup,
  positiveOrderId,
  rebuyFailureMessage,
  rebuyPartialMessage
} from "../miniprogram/features/order-center";
import { ApiError } from "../miniprogram/utils/api-error";
import { isPaymentCancelled } from "../miniprogram/utils/wechat-payment";
import type {
  AppOrderDetailResponse,
  OrderStatus,
  OrderSummaryResponse
} from "../miniprogram/types/order";

function summary(status: OrderStatus, pendingReviewCount = 0): OrderSummaryResponse {
  return {
    orderId: 101,
    orderNo: "ORD-101",
    status,
    productAmountCent: 5040,
    couponDiscountCent: 500,
    freightCent: 0,
    payableAmountCent: 4540,
    paidAmountCent: status === "PAID" || status === "SHIPPED" || status === "COMPLETED"
      ? 4540
      : 0,
    productTitle: "牛油火锅底料",
    itemCount: 3,
    items: [{
      orderItemId: 901,
      skuId: 21,
      spuId: 31,
      productTitle: "牛油火锅底料",
      productSubtitle: "经典风味",
      mainImage: "https://example.com/main.png",
      skuImage: "https://example.com/sku.png",
      displayImage: "https://example.com/display.png",
      skuCode: "SKU-21",
      specText: "500g 袋装",
      unitPriceCent: 1680,
      quantity: 3,
      reviewed: pendingReviewCount === 0,
      reviewable: pendingReviewCount > 0
    }],
    pendingReviewCount,
    createdAt: "2026-07-20T12:30:00Z"
  };
}

function detail(status: OrderStatus = "PAYING"): AppOrderDetailResponse {
  return {
    orderId: 101,
    orderNo: "ORD-101",
    status,
    source: "DIRECT",
    productOriginalAmountCent: 6000,
    productAmountCent: 5040,
    userCouponId: 501,
    couponName: "新人券",
    couponDiscountCent: 500,
    freightCent: 0,
    payableAmountCent: 4540,
    paidAmountCent: 0,
    receiverName: "小灶",
    receiverPhone: "13800000000",
    receiverAddress: "四川省成都市武侯区灶香路 1 号",
    paymentExpiresAt: "2026-07-20T12:45:00Z",
    paymentRemainingSeconds: 899,
    createdAt: "2026-07-20T12:30:00Z",
    items: [{
      orderItemId: 901,
      skuId: 21,
      spuId: 31,
      productTitle: "牛油火锅底料",
      mainImage: "https://example.com/product.png",
      skuCode: "SKU-21",
      specText: "500g",
      originalPriceCent: 2000,
      unitPriceCent: 1680,
      retailUnitPriceCent: 1880,
      wholesaleTierMinQuantity: 3,
      quantity: 3,
      lineOriginalAmountCent: 6000,
      lineAmountCent: 5040,
      reviewed: false,
      reviewable: true
    }]
  };
}

test("订单状态映射稳定并只开放合法操作", () => {
  const created = buildOrderSummaryView(summary("CREATED"));
  assert.equal(created.statusText, "待付款");
  assert.equal(created.canPay, true);
  assert.equal(created.canCancel, true);
  assert.equal(created.canModify, true);
  assert.equal(created.canRebuy, false);
  assert.equal(created.paymentActionText, "去支付");

  const paying = buildOrderSummaryView(summary("PAYING"));
  assert.equal(paying.canPay, true);
  assert.equal(paying.canModify, true);
  assert.equal(paying.paymentActionText, "去支付");

  const paid = buildOrderSummaryView(summary("PAID"));
  assert.equal(paid.hasActions, true);
  assert.equal(paid.canRebuy, false);
  assert.equal(paid.canModify, true);
  assert.equal(paid.canAfterSale, true);
  assert.equal(paid.afterSaleActionText, "退款|售后");

  const shipped = buildOrderSummaryView(summary("SHIPPED"));
  assert.equal(shipped.canPay, false);
  assert.equal(shipped.canRebuy, true);
  assert.equal(shipped.canDelete, false);
  assert.equal(shipped.canAfterSale, true);
  assert.equal(shipped.afterSaleActionText, "退换|售后");
  assert.equal(shipped.amountText, "¥45.40");

  const pendingReview = buildOrderSummaryView(summary("COMPLETED", 1));
  assert.equal(pendingReview.statusText, "待评价");
  assert.equal(pendingReview.canReview, true);
  assert.equal(pendingReview.canDelete, true);
  assert.equal(pendingReview.canRebuy, true);
  assert.equal(pendingReview.canAfterSale, true);

  const completed = buildOrderSummaryView(summary("COMPLETED", 0));
  assert.equal(completed.statusText, "已完成");
  assert.equal(completed.canReview, false);
  assert.equal(completed.canDelete, true);
  assert.equal(completed.canRebuy, true);

  const refunded = buildOrderSummaryView(summary("REFUNDED"));
  assert.equal(refunded.statusText, "已退款");
  assert.equal(refunded.canDelete, true);
  assert.equal(refunded.canRebuy, true);

  const closed = buildOrderSummaryView(summary("CLOSED"));
  assert.equal(closed.statusText, "已取消");
  assert.equal(closed.canDelete, true);
  assert.equal(closed.canRebuy, true);
});

test("订单列表下单时间显示到秒", () => {
  const view = buildOrderSummaryView(summary("COMPLETED"));
  assert.match(view.createdAtText, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
});

test("订单列表项只格式化后端真实图片、规格、单价与数量", () => {
  const view = buildOrderSummaryView(summary("COMPLETED", 1));
  assert.equal(view.items[0]?.imageUrl, "https://example.com/display.png");
  assert.equal(view.items[0]?.hasImage, true);
  assert.equal(view.items[0]?.titleText, "牛油火锅底料");
  assert.equal(view.items[0]?.specificationText, "500g 袋装");
  assert.equal(view.items[0]?.unitPriceText, "¥16.80");
  assert.equal(view.items[0]?.quantityText, "共3件");

  const withoutSpec = summary("CLOSED");
  withoutSpec.items[0] = {
    ...withoutSpec.items[0]!,
    displayImage: "",
    skuImage: "",
    specText: ""
  };
  const fallback = buildOrderSummaryView(withoutSpec).items[0];
  assert.equal(fallback?.imageUrl, "https://example.com/main.png");
  assert.equal(fallback?.specificationText, "");

  const legacySingle = summary("COMPLETED");
  legacySingle.items[0] = { ...legacySingle.items[0]!, specText: "默认规格" };
  assert.equal(buildOrderSummaryView(legacySingle).items[0]?.specificationText, "");
});

test("订单详情使用零售金额与真实批发成交价生成可核对明细", () => {
  const view = buildOrderDetailView(detail());
  assert.equal(view.productAmountText, "¥56.40");
  assert.equal(view.wholesaleDiscountText, "¥6.00");
  assert.equal(view.hasWholesaleDiscount, true);
  assert.equal(view.couponDiscountText, "¥5.00");
  assert.equal(view.freightText, "¥0.00");
  assert.equal(view.payableAmountText, "¥45.40");
  assert.equal(view.originalPayableAmountText, "¥56.40");
  assert.equal(view.totalDiscountText, "¥11.00");
  assert.equal(view.hasTotalDiscount, true);
  assert.equal(view.items[0]?.unitPriceText, "¥16.80");
  assert.equal(view.items[0]?.retailLineAmountText, "¥56.40");
  assert.equal(view.items[0]?.hasRetailLineAmount, true);
  assert.equal(view.items[0]?.wholesaleText, "3 件起批发价");
  assert.equal(view.canSyncPayment, true);
  assert.equal(view.canModifyReceiver, true);
  assert.equal(view.paymentActionText, "继续支付");
  assert.equal(view.statusHeadline, "等待付款");
  assert.equal(view.statusIcon, "/assets/icons/order-wallet.svg");
  assert.equal(view.receiverPhoneDisplay, "138****0000");
  assert.equal(view.totalQuantity, 3);
  assert.equal(view.orderInfoItemCount, 2);

  const legacySingle = detail();
  legacySingle.items[0] = { ...legacySingle.items[0]!, specText: "默认规格" };
  assert.equal(buildOrderDetailView(legacySingle).items[0]?.specText, "");
});

test("订单详情为实体快递生成独立于 token 的静态物流视图", () => {
  const shipped = detail("SHIPPED");
  shipped.shippedAt = "2026-08-08T10:20:30Z";
  shipped.shipment = {
    shipmentId: 701,
    orderId: shipped.orderId,
    logisticsType: 1,
    deliveryMode: 1,
    itemDesc: "火锅底料",
    expressCompanyCode: "  SF  ",
    expressCompanyName: "  顺丰速运  ",
    trackingNo: "  SF1234567890  ",
    shipmentSource: "MANUAL",
    localShipmentStatus: "SHIPPED",
    wechatProviderMode: "REAL",
    wechatUploadStatus: "FAILED",
    wechatUploadMessage: "上传失败",
    waybillTrackingSupported: true,
    waybillRegistrationKind: "TRACE",
    waybillRegistrationStatus: "FAILED",
    waybillRegistrationMessage: "登记失败",
    shippedAt: "2026-08-08T10:20:30Z",
    uploadTime: "2026-08-08T10:20:30+00:00",
    wechatUploadedAt: null
  };

  const view = buildOrderDetailView(shipped);
  assert.deepEqual(view.shipmentView, {
    shipmentId: 701,
    packageNo: 1,
    carrierName: "顺丰速运",
    trackingNo: "SF1234567890",
    shippedAtText: "2026-08-08 10:20",
    itemsText: "",
    canCopyTrackingNo: true,
    canOpenTracking: true
  });
});

test("物流查询条件不完整时保留静态卡但不误开插件", () => {
  const shipped = detail("SHIPPED");
  shipped.shipment = {
    shipmentId: 702,
    orderId: shipped.orderId,
    logisticsType: 1,
    deliveryMode: 1,
    itemDesc: "火锅底料",
    expressCompanyCode: "",
    expressCompanyName: "",
    trackingNo: "SF0002",
    shipmentSource: "WECHAT_WAYBILL",
    electronicWaybillId: 801,
    localShipmentStatus: "SHIPPED",
    wechatProviderMode: "REAL",
    wechatUploadStatus: "UNKNOWN",
    wechatUploadMessage: null,
    waybillTrackingSupported: true,
    waybillRegistrationKind: null,
    waybillRegistrationStatus: "UNKNOWN",
    waybillRegistrationMessage: null,
    shippedAt: "2026-08-08T11:20:30Z",
    uploadTime: "2026-08-08T11:20:30+00:00",
    wechatUploadedAt: null
  };

  const withoutCarrier = buildOrderDetailView(shipped);
  assert.equal(withoutCarrier.shipmentView?.carrierName, "快递");
  assert.equal(withoutCarrier.shipmentView?.trackingNo, "SF0002");
  assert.equal(withoutCarrier.shipmentView?.canOpenTracking, false);

  shipped.shipment = {
    ...shipped.shipment,
    expressCompanyCode: "SF",
    waybillTrackingSupported: false
  };
  const unsupported = buildOrderDetailView(shipped);
  assert.equal(unsupported.shipmentView?.canOpenTracking, false);

  shipped.shipment = {
    ...shipped.shipment,
    waybillTrackingSupported: true,
    waybillRegistrationStatus: "SKIPPED"
  };
  const sandboxSkipped = buildOrderDetailView(shipped);
  assert.equal(sandboxSkipped.shipmentView?.canOpenTracking, false);

  shipped.shipment = {
    ...shipped.shipment,
    logisticsType: 2,
    expressCompanyCode: null,
    expressCompanyName: null,
    trackingNo: null
  };
  assert.equal(buildOrderDetailView(shipped).shipmentView, undefined);
});

test("订单详情顶部按真实订单状态显示履约标题且完成态不受待评价分组影响", () => {
  const expected = new Map<OrderStatus, string>([
    ["CREATED", "等待付款"],
    ["PAYING", "等待付款"],
    ["PAID", "正在出库"],
    ["SHIPPED", "等待收货"],
    ["COMPLETED", "已完成"],
    ["CLOSED", "已取消"],
    ["REFUNDING", "退款中"],
    ["REFUNDED", "已退款"]
  ]);

  expected.forEach((headline, status) => {
    assert.equal(buildOrderDetailView(detail(status)).statusHeadline, headline);
  });
  assert.equal(buildOrderDetailView(detail("PAID")).canModifyReceiver, true);
  assert.equal(buildOrderDetailView(detail("SHIPPED")).canModifyReceiver, false);
});

test("支付倒计时使用冒号格式并收敛非法输入", () => {
  assert.equal(formatPaymentCountdown(899), "00:14:59");
  assert.equal(formatPaymentCountdown(3661), "01:01:01");
  assert.equal(formatPaymentCountdown(-1), "00:00:00");
  assert.equal(formatPaymentCountdown("invalid"), "00:00:00");
});

test("订单中心路由和查询参数拒绝非法订单 ID 与状态组", () => {
  assert.equal(buildOrderListUrl("UNPAID"), "/pages/order/list/list?group=UNPAID");
  assert.equal(buildOrderDetailUrl(101), "/pages/order/detail/detail?order_id=101");
  assert.equal(buildOrderReviewUrl(101), "/pages/order/review/review?order_id=101");
  assert.equal(buildOrderModifyUrl(101), "/pages/order/modify/modify?order_id=101");
  assert.equal(parseOrderStatusGroup("to_receive"), "TO_RECEIVE");
  assert.equal(parseOrderStatusGroup("to_review"), "TO_REVIEW");
  assert.equal(parseOrderStatusGroup("cancelled"), "CANCELLED");
  assert.equal(parseOrderStatusGroup("unknown"), "ALL");
  assert.deepEqual(ORDER_STATUS_TABS.map((tab) => tab.value), [
    "ALL",
    "UNPAID",
    "TO_SHIP",
    "TO_RECEIVE",
    "TO_REVIEW",
    "COMPLETED",
    "CANCELLED"
  ]);
  assert.equal(positiveOrderId("101"), 101);
  assert.equal(positiveOrderId("1e2"), 0);
  assert.throws(() => buildOrderDetailUrl(0), /无效/);
  assert.throws(() => buildOrderReviewUrl(0), /无效/);
  assert.throws(() => buildOrderModifyUrl(0), /无效/);
});

test("微信支付取消只识别用户主动取消错误", () => {
  assert.equal(isPaymentCancelled({ errMsg: "requestPayment:fail cancel" }), true);
  assert.equal(isPaymentCancelled({ errMsg: "requestPayment:fail system error" }), false);
  assert.equal(isPaymentCancelled(new Error("cancel")), false);
});

test("再次购买对下架、库存和部分成功给出明确提示", () => {
  const items = [{ orderItemId: 1 }, { orderItemId: 2 }];
  assert.deepEqual(filterRebuyableOrderItems(items, [2]), [{ orderItemId: 2 }]);
  assert.deepEqual(filterRebuyableOrderItems(items, undefined), items);
  assert.equal(rebuyFailureMessage(new ApiError({
    kind: "API",
    message: "SKU unavailable",
    code: 200002
  })), "商品已下架，暂时无法再次购买");
  assert.equal(rebuyFailureMessage(new ApiError({
    kind: "API",
    message: "Stock shortage",
    code: 200100
  })), "商品库存不足，暂时无法再次购买");
  assert.equal(rebuyPartialMessage(2, 3), "已加入2款，1款暂不可购");
});
