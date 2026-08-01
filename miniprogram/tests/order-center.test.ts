import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildOrderDetailUrl,
  buildOrderDetailView,
  buildOrderListUrl,
  buildOrderSummaryView,
  formatPaymentCountdown,
  orderStatusText,
  parseOrderStatusGroup,
  positiveOrderId
} from "../miniprogram/features/order-center";
import { isPaymentCancelled } from "../miniprogram/utils/wechat-payment";
import type {
  AppOrderDetailResponse,
  OrderStatus,
  OrderSummaryResponse
} from "../miniprogram/types/order";

function summary(status: OrderStatus): OrderSummaryResponse {
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
      lineAmountCent: 5040
    }]
  };
}

test("订单状态映射稳定并只开放合法操作", () => {
  const created = buildOrderSummaryView(summary("CREATED"));
  assert.equal(created.statusText, "等待支付");
  assert.equal(created.canPay, true);
  assert.equal(created.canCancel, true);
  assert.equal(created.canSyncPayment, false);
  assert.equal(created.paymentActionText, "立即支付");

  const paying = buildOrderSummaryView(summary("PAYING"));
  assert.equal(paying.canPay, true);
  assert.equal(paying.canSyncPayment, true);
  assert.equal(paying.paymentActionText, "继续支付");

  const shipped = buildOrderSummaryView(summary("SHIPPED"));
  assert.equal(shipped.canPay, false);
  assert.equal(shipped.canConfirmReceipt, true);
  assert.equal(shipped.amountText, "¥45.40");

  const completed = buildOrderSummaryView(summary("COMPLETED"));
  assert.equal(completed.statusText, "已完成");
  assert.equal(completed.canConfirmReceipt, false);
  assert.equal(orderStatusText("REFUNDED"), "已退款");

  const closed = buildOrderSummaryView(summary("CLOSED"));
  assert.equal(closed.statusText, "已取消");
  assert.equal(closed.canDelete, true);
  assert.equal(closed.canRebuy, true);
});

test("订单详情使用零售金额与真实批发成交价生成可核对明细", () => {
  const view = buildOrderDetailView(detail());
  assert.equal(view.productAmountText, "¥56.40");
  assert.equal(view.wholesaleDiscountText, "¥6.00");
  assert.equal(view.hasWholesaleDiscount, true);
  assert.equal(view.couponDiscountText, "¥5.00");
  assert.equal(view.freightText, "¥0.00");
  assert.equal(view.payableAmountText, "¥45.40");
  assert.equal(view.items[0]?.unitPriceText, "¥16.80");
  assert.equal(view.items[0]?.wholesaleText, "3 件起批发价");
  assert.equal(view.canSyncPayment, true);
});

test("支付倒计时稳定显示时分秒并收敛非法输入", () => {
  assert.equal(formatPaymentCountdown(899), "00时14分59秒");
  assert.equal(formatPaymentCountdown(3661), "01时01分01秒");
  assert.equal(formatPaymentCountdown(-1), "00时00分00秒");
  assert.equal(formatPaymentCountdown("invalid"), "00时00分00秒");
});

test("订单中心路由和查询参数拒绝非法订单 ID 与状态组", () => {
  assert.equal(buildOrderListUrl("UNPAID"), "/pages/order/list/list?group=UNPAID");
  assert.equal(buildOrderDetailUrl(101), "/pages/order/detail/detail?order_id=101");
  assert.equal(parseOrderStatusGroup("to_receive"), "TO_RECEIVE");
  assert.equal(parseOrderStatusGroup("unknown"), "ALL");
  assert.equal(positiveOrderId("101"), 101);
  assert.equal(positiveOrderId("1e2"), 0);
  assert.throws(() => buildOrderDetailUrl(0), /无效/);
});

test("微信支付取消只识别用户主动取消错误", () => {
  assert.equal(isPaymentCancelled({ errMsg: "requestPayment:fail cancel" }), true);
  assert.equal(isPaymentCancelled({ errMsg: "requestPayment:fail system error" }), false);
  assert.equal(isPaymentCancelled(new Error("cancel")), false);
});
