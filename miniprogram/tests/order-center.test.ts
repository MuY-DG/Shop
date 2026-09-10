import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildOrderDetailUrl,
  buildOrderDetailView,
  buildOrderListUrl,
  buildOrderModifyUrl,
  buildOrderReviewUrl,
  buildOrderSummaryView,
  canContinueOrderAfterSale,
  filterRebuyableOrderItems,
  formatPaymentCountdown,
  ORDER_STATUS_TABS,
  parseOrderCenterGroup,
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
  assert.equal(paid.canAfterSale, false);
  assert.equal(paid.afterSaleActionText, "申请售后");

  const shipped = buildOrderSummaryView(summary("SHIPPED"));
  assert.equal(shipped.canPay, false);
  assert.equal(shipped.canRebuy, true);
  assert.equal(shipped.canDelete, false);
  assert.equal(shipped.canAfterSale, false);
  assert.equal(shipped.canViewLogistics, true);
  assert.equal(buildOrderSummaryView(summary("PARTIALLY_SHIPPED")).canViewLogistics, true);
  assert.equal(shipped.afterSaleActionText, "申请售后");
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
  assert.equal(refunded.statusText, "交易关闭");
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

test("订单列表展示最新售后结果并使用真实退款金额", () => {
  const refunded = summary("REFUNDED");
  refunded.latestAfterSale = {
    afterSaleType: "REFUND_ONLY",
    status: "REFUNDED",
    requestedAmountCent: 4540,
    approvedAmountCent: 4300,
    refundAmountCent: 4200
  };
  const refundedView = buildOrderSummaryView(refunded);
  assert.equal(refundedView.afterSaleStatusText, "");
  assert.equal(refundedView.refundSummaryText, "");
  assert.equal(refundedView.afterSaleStatusDescription, "原路返回支付金额¥42.00");

  const closedReturn = summary("COMPLETED");
  closedReturn.latestAfterSale = {
    afterSaleType: "RETURN_REFUND",
    status: "REJECTED",
    requestedAmountCent: 4540
  };
  const closedReturnView = buildOrderSummaryView(closedReturn);
  assert.equal(closedReturnView.afterSaleStatusText, "退货申请已关闭");
  assert.equal(closedReturnView.afterSaleStatusDescription, "因审核不通过");

  const failedRefund = summary("REFUNDING");
  failedRefund.latestAfterSale = {
    afterSaleType: "REFUND_ONLY",
    status: "REFUND_FAILED",
    requestedAmountCent: 4540,
    approvedAmountCent: 4540,
    refundAmountCent: 4540
  };
  const failedRefundView = buildOrderSummaryView(failedRefund);
  assert.equal(failedRefundView.statusText, "退款待处理");
  assert.equal(failedRefundView.afterSaleStatusText, "退款处理异常");
  assert.equal(failedRefundView.afterSaleStatusDescription, "后台客服正在加速处理退款异常");

  assert.equal(buildOrderSummaryView(summary("COMPLETED")).afterSaleStatusText, "");
});

test("多商品订单按累计退款显示部分或全部退款，新的售后不覆盖历史退款", () => {
  const order = summary("PAID");
  order.items.push({ ...order.items[0]!, orderItemId: 902, skuId: 22, spuId: 32,
    productTitle: "番茄火锅底料", quantity: 1 });
  order.itemCount = 4;
  order.refundedAmountCent = 1680;
  order.latestAfterSale = {
    afterSaleType: "REFUND_ONLY",
    status: "REFUNDED",
    requestedAmountCent: 1680,
    approvedAmountCent: 1680,
    refundAmountCent: 1680
  };
  for (const status of ["PAID", "PARTIALLY_SHIPPED", "SHIPPED", "COMPLETED"] as const) {
    const view = buildOrderSummaryView({ ...order, status });
    assert.equal(view.afterSaleStatusText, "", status);
    assert.equal(view.refundSummaryText, "部分退款 · 已退 ¥16.80");
    assert.equal(view.afterSaleStatusDescription, "原路返回支付金额¥16.80");
  }
  // 最新一笔金额仍小于整单实付，但累计已退满，订单的权威状态是 REFUNDED。
  const fullyRefunded = buildOrderSummaryView({ ...order, status: "REFUNDED", refundedAmountCent: 4540 });
  assert.equal(fullyRefunded.afterSaleStatusText, "");
  assert.equal(fullyRefunded.refundSummaryText, "退款成功");
  assert.equal(fullyRefunded.statusText, "交易关闭");

  const processing = buildOrderSummaryView({ ...order, latestAfterSale: {
    ...order.latestAfterSale, status: "REFUNDING"
  } });
  assert.equal(processing.afterSaleStatusText, "退款处理中");
  assert.equal(processing.refundSummaryText, "部分退款 · 已退 ¥16.80");
});

test("部分退款后优先申请剩余商品，并保留历史售后入口", () => {
  const order = summary("SHIPPED");
  order.paidAmountCent = 200;
  order.refundedAmountCent = 100;
  order.latestAfterSale = { afterSaleType: "REFUND_ONLY", status: "REFUNDED", requestedAmountCent: 100 };
  order.items[0] = { ...order.items[0]!, quantity: 1, afterSale: {
    refundedQuantity: 1, refundedAmountCent: 100, fullyRefunded: true, records: []
  } };
  order.items.push({ ...order.items[0], orderItemId: 902, afterSale: undefined });
  for (const status of ["PAID", "PARTIALLY_SHIPPED", "SHIPPED", "COMPLETED"] as const) {
    const view = buildOrderSummaryView({ ...order, status });
    assert.equal(view.canAfterSale, true, status);
    assert.equal(view.afterSaleActionMode, "APPLY", status);
    assert.equal(view.afterSaleActionText, "申请售后", status);
    assert.equal(view.canViewAfterSale, true, status);
  }
  assert.equal(canContinueOrderAfterSale({ ...order, refundedAmountCent: 200 }), false);
  assert.equal(canContinueOrderAfterSale({ ...order, status: "REFUNDED" }), false);
  assert.equal(canContinueOrderAfterSale({ ...order, items: order.items.slice(0, 1) }), false);
  assert.equal(canContinueOrderAfterSale({ ...order, latestAfterSale: {
    ...order.latestAfterSale, status: "REFUNDING"
  } }), false);
  // Another item's ongoing application also blocks a new application, even if the latest record is refunded.
  order.items[1]!.afterSale = { refundedQuantity: 0, refundedAmountCent: 0, fullyRefunded: false, records: [{
    afterSaleId: 302, afterSaleNo: "AS302", status: "WAITING_RETURN", quantity: 1, amountCent: 100, appVisible: true
  }] };
  assert.equal(canContinueOrderAfterSale(order), false);
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
  assert.equal(view.statusIcon, "/assets/icons/profile-order-wallet.svg");
  assert.equal(view.receiverPhoneDisplay, "138****0000");
  assert.equal(view.totalQuantity, 3);
  assert.equal(view.orderInfoItemCount, 2);

  const legacySingle = detail();
  legacySingle.items[0] = { ...legacySingle.items[0]!, specText: "默认规格" };
  assert.equal(buildOrderDetailView(legacySingle).items[0]?.specText, "");
});

test("订单详情统一申请售后文案并保留多商品申请能力", () => {
  const single = detail("PAID");
  assert.equal(single.items[0]?.quantity, 3);
  assert.equal(buildOrderDetailView(single).afterSaleActionText, "申请售后");

  const multiple = detail("PAID");
  multiple.items.push({ ...multiple.items[0]!, orderItemId: 902, skuId: 22 });
  const batchView = buildOrderDetailView(multiple);
  assert.equal(batchView.afterSaleActionText, "申请售后");
  assert.equal(batchView.afterSaleActionMode, "APPLY");
  assert.equal(batchView.showAfterSaleAction, true);

  multiple.items[1]!.quantity = 0;
  assert.equal(buildOrderDetailView(multiple).afterSaleActionText, "申请售后");
});

test("已有售后统一查看售后入口并支持撤销后重新申请", () => {
  const order = detail("PAID");
  order.items.push({ ...order.items[0]!, orderItemId: 902, skuId: 22 });
  order.latestAfterSale = {
    id: 301,
    afterSaleNo: "AS-301",
    orderId: order.orderId,
    orderNo: order.orderNo,
    userId: "USER-1",
    afterSaleType: "REFUND_ONLY",
    status: "REQUESTED",
    reason: "不想要了",
    requestedAmountCent: 4540,
    createdAt: "2026-07-20T12:30:00Z",
    evidenceFileIds: [],
    evidenceFiles: [],
    items: [],
    allowedActions: ["CANCEL"]
  };
  const active = buildOrderDetailView(order);
  assert.equal(active.afterSaleActionMode, "DETAIL");
  assert.equal(active.afterSaleActionText, "查看售后");
  assert.equal(active.canApplyAfterSale, false);

  order.latestAfterSale.status = "REFUNDED";
  assert.equal(buildOrderDetailView(order).afterSaleActionText, "查看售后");
  assert.equal(buildOrderDetailView(order).afterSaleActionMode, "DETAIL");

  order.latestAfterSale.status = "CANCELLED";
  assert.equal(buildOrderDetailView(order).afterSaleActionText, "申请售后");
  assert.equal(buildOrderDetailView(order).afterSaleActionMode, "APPLY");
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
    senderAddress: "",
    carrierName: "顺丰速运",
    trackingNo: "SF1234567890",
    shippedAtText: "2026-08-08 10:20:30",
    itemsText: "",
    canCopyTrackingNo: true,
    isElectronicWaybill: false,
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

test("订单详情顶部按真实订单状态显示履约标题和共用图标且完成态不受待评价分组影响", () => {
  const expected: Array<[OrderStatus, string, string]> = [
    ["CREATED", "等待付款", "profile-order-wallet.svg"],
    ["PAYING", "等待付款", "profile-order-wallet.svg"],
    ["PAID", "正在出库", "profile-order-package.svg"],
    ["PARTIALLY_SHIPPED", "部分已发货", "profile-order-package.svg"],
    ["SHIPPED", "等待收货", "profile-order-receive.svg"],
    ["COMPLETED", "已完成", "profile-about.svg"],
    ["CLOSED", "已取消", "close-material-symbols.svg"],
    ["REFUNDING", "退款中", "profile-order-after-sale.svg"],
    ["REFUNDED", "已退款", "profile-order-after-sale.svg"]
  ];

  expected.forEach(([status, headline, icon]) => {
    const view = buildOrderDetailView(detail(status));
    assert.equal(view.statusHeadline, headline);
    assert.equal(view.statusIcon, `/assets/icons/${icon}`);
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
  assert.equal(parseOrderCenterGroup("after_sale"), "AFTER_SALE");
  assert.deepEqual(ORDER_STATUS_TABS.map((tab) => tab.value), [
    "ALL",
    "UNPAID",
    "TO_SHIP",
    "TO_RECEIVE",
    "TO_REVIEW",
    "COMPLETED",
    "CANCELLED",
    "AFTER_SALE"
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

test('订单卡片只保留已发货，并在整单退款后隐藏物流', () => {
  const snapshot = { shipmentId: 8, statusText: '运输中', latestMessage: '到达成都转运中心', packageCount: 2 };
  const shipped = buildOrderSummaryView({ ...summary('SHIPPED'), logisticsSummary: snapshot });
  assert.equal(shipped.logisticsStatusText, '已发货');
  assert.equal(shipped.canViewLogistics, true);
  const refunded = buildOrderSummaryView({ ...summary('REFUNDED'), logisticsSummary: snapshot });
  assert.equal(refunded.logisticsStatusText, '');
  assert.equal(refunded.canViewLogistics, false);
  assert.equal(buildOrderSummaryView(summary('PAID')).logisticsStatusText, '');
  assert.equal(buildOrderSummaryView(summary('SHIPPED')).logisticsStatusText, '已发货');
});
