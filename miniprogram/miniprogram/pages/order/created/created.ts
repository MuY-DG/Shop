import {
  buildOrderDetailUrl,
  orderStatusText,
  positiveOrderId
} from "../../../features/order-center";
import {
  executeOrderPayment,
  recoverOrderPayment
} from "../../../features/order-payment";
import { formatMoney } from "../../../features/product-catalog";
import { cancelOrder, getOrderDetail } from "../../../services/order";
import type { OrderStatus } from "../../../types/order";
import { isApiError } from "../../../utils/api-error";

type OrderResultType = "PENDING" | "SUCCESS" | "CLOSED";

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function resultStatusText(status: OrderStatus): string {
  return status === "CREATED" || status === "PAYING"
    ? "待付款"
    : orderStatusText(status);
}

function resultPageTitle(status: OrderStatus): string {
  if (status === "PAID") {
    return "支付成功";
  }
  if (status === "CLOSED") {
    return "订单已取消";
  }
  return status === "CREATED" || status === "PAYING"
    ? "待付款"
    : orderStatusText(status);
}

function orderResultType(status: OrderStatus): OrderResultType {
  if (status === "CREATED" || status === "PAYING") {
    return "PENDING";
  }
  return status === "CLOSED" ? "CLOSED" : "SUCCESS";
}

let paymentRecoveryTimer: ReturnType<typeof setTimeout> | null = null;

Page({
  data: {
    orderId: 0,
    orderNo: "",
    payableAmountText: "¥0.00",
    status: "CREATED" as OrderStatus,
    statusText: "待付款",
    pageTitle: "待付款",
    resultType: "PENDING" as OrderResultType,
    loading: false,
    loaded: false,
    actionType: "",
    paymentRecoveryAttempted: false
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderId = positiveOrderId(query.order_id);
    const amount = Number(query.amount);
    const initialStatus: OrderStatus = query.payment_status === "PAID"
      ? "PAID"
      : "CREATED";
    this.setData({
      orderId,
      orderNo: (query.order_no || "").trim(),
      payableAmountText: `¥${formatMoney(amount) || "0.00"}`,
      status: initialStatus,
      statusText: resultStatusText(initialStatus),
      pageTitle: resultPageTitle(initialStatus),
      resultType: orderResultType(initialStatus)
    });
    if (orderId) {
      void this.refreshOrder();
    }
  },

  onShow() {
    if (this.data.loaded && this.data.status === "PAYING" && !this.data.actionType) {
      void this.syncPayment(false);
    }
  },

  onUnload() {
    if (paymentRecoveryTimer !== null) {
      clearTimeout(paymentRecoveryTimer);
      paymentRecoveryTimer = null;
    }
  },

  async refreshOrder() {
    if (!this.data.orderId) {
      return;
    }
    this.setData({ loading: true });
    try {
      const order = await getOrderDetail(this.data.orderId);
      const displayAmountCent = order.paidAmountCent > 0
        ? order.paidAmountCent
        : order.payableAmountCent;
      const shouldRecoverPayment = order.status === "PAYING"
        && !this.data.paymentRecoveryAttempted;
      this.setData({
        orderNo: order.orderNo,
        payableAmountText: `¥${formatMoney(displayAmountCent) || "0.00"}`,
        status: order.status,
        statusText: resultStatusText(order.status),
        pageTitle: resultPageTitle(order.status),
        resultType: orderResultType(order.status),
        loading: false,
        loaded: true,
        paymentRecoveryAttempted: shouldRecoverPayment
          ? true
          : this.data.paymentRecoveryAttempted
      });
      if (shouldRecoverPayment) {
        this.queuePaymentRecovery();
      }
    } catch (error) {
      this.setData({ loading: false });
      wx.showToast({
        title: actionError(error, "订单状态加载失败"),
        icon: "none"
      });
    }
  },

  onPayTap() {
    void this.payOrder();
  },

  queuePaymentRecovery() {
    if (paymentRecoveryTimer !== null) {
      clearTimeout(paymentRecoveryTimer);
    }
    paymentRecoveryTimer = setTimeout(() => {
      paymentRecoveryTimer = null;
      if (this.data.status === "PAYING" && !this.data.actionType) {
        void this.syncPayment(false);
      }
    }, 800);
  },

  async payOrder() {
    if (!this.data.orderId || this.data.actionType) {
      return;
    }
    this.setData({
      actionType: "pay",
      paymentRecoveryAttempted: false
    });
    try {
      const outcome = await executeOrderPayment(this.data.orderId);
      if (outcome === "PAID") {
        wx.showToast({ title: "支付成功", icon: "success" });
      } else if (outcome === "PENDING") {
        wx.showToast({ title: "正在确认支付结果", icon: "none" });
      } else {
        wx.showToast({ title: "已取消支付", icon: "none" });
      }
    } catch (error) {
      wx.showToast({
        title: actionError(error, "支付未完成，请稍后重试"),
        icon: "none"
      });
    } finally {
      this.setData({ actionType: "" });
      await this.refreshOrder();
    }
  },

  async syncPayment(showResult: boolean) {
    if (!this.data.orderId || this.data.actionType) {
      return;
    }
    this.setData({ actionType: "sync" });
    try {
      const response = await recoverOrderPayment(this.data.orderId);
      if (showResult || response.status === "PAID") {
        wx.showToast({
          title: response.status === "PAID" ? "支付成功" : "暂未查询到支付结果",
          icon: response.status === "PAID" ? "success" : "none"
        });
      }
    } catch (error) {
      if (showResult) {
        wx.showToast({
          title: actionError(error, "支付结果同步失败"),
          icon: "none"
        });
      }
    } finally {
      this.setData({ actionType: "" });
      await this.refreshOrder();
    }
  },

  onCancelTap() {
    if (!this.data.orderId || this.data.actionType) {
      return;
    }
    wx.showModal({
      title: "取消订单",
      content: "取消后库存和优惠券会释放，订单不能恢复。",
      confirmText: "确认取消",
      confirmColor: "#B72B22",
      success: (result) => {
        if (result.confirm) {
          void this.cancelCurrentOrder();
        }
      }
    });
  },

  async cancelCurrentOrder() {
    this.setData({ actionType: "cancel" });
    try {
      const response = await cancelOrder(this.data.orderId);
      this.setData({
        status: response.status,
        statusText: resultStatusText(response.status),
        pageTitle: resultPageTitle(response.status),
        resultType: orderResultType(response.status)
      });
      wx.showToast({ title: "订单已取消", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "订单取消失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionType: "" });
      await this.refreshOrder();
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
