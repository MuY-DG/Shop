import {
  buildOrderDetailUrl,
  orderStatusText,
  positiveOrderId
} from "../../../features/order-center";
import { formatMoney } from "../../../features/product-catalog";
import { getOrderDetail } from "../../../services/order";
import { isApiError } from "../../../utils/api-error";

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

Page({
  data: {
    orderId: 0,
    orderNo: "",
    paymentAmountText: "¥0.00",
    statusText: "支付成功",
    loading: false
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderId = positiveOrderId(query.order_id);
    if (!orderId) {
      wx.showToast({ title: "订单参数无效", icon: "none" });
      return;
    }
    if (query.payment_status !== "PAID") {
      wx.redirectTo({ url: buildOrderDetailUrl(orderId) });
      return;
    }
    const amount = Number(query.amount);
    this.setData({
      orderId,
      orderNo: (query.order_no || "").trim(),
      paymentAmountText: `¥${formatMoney(amount) || "0.00"}`
    });
    void this.refreshOrder();
  },

  async refreshOrder() {
    if (!this.data.orderId) {
      return;
    }
    this.setData({ loading: true });
    try {
      const order = await getOrderDetail(this.data.orderId);
      if (order.status === "CREATED" || order.status === "PAYING" || order.status === "CLOSED") {
        wx.redirectTo({ url: buildOrderDetailUrl(order.orderId) });
        return;
      }
      const displayAmountCent = order.paidAmountCent > 0
        ? order.paidAmountCent
        : order.payableAmountCent;
      this.setData({
        orderNo: order.orderNo,
        paymentAmountText: `¥${formatMoney(displayAmountCent) || "0.00"}`,
        statusText: orderStatusText(order.status),
        loading: false
      });
    } catch (error) {
      this.setData({ loading: false });
      wx.showToast({
        title: actionError(error, "订单状态加载失败"),
        icon: "none"
      });
    }
  },

  onDetailTap() {
    if (this.data.orderId) {
      wx.redirectTo({ url: buildOrderDetailUrl(this.data.orderId) });
    }
  },

  onHomeTap() {
    wx.switchTab({ url: "/pages/index/index" });
  }
});
