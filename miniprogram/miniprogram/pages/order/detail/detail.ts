import {
  buildOrderDetailView,
  positiveOrderId,
  type OrderDetailView
} from "../../../features/order-center";
import {
  executeOrderPayment,
  recoverOrderPayment
} from "../../../features/order-payment";
import {
  cancelOrder,
  confirmOrderReceipt,
  getOrderDetail
} from "../../../services/order";
import { isApiError } from "../../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
    };
  };
}

let latestDetailRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function confirmAction(title: string, content: string, confirmText: string): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title,
      content,
      confirmText,
      confirmColor: "#B72B22",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    });
  });
}

Page({
  data: {
    orderId: 0,
    detail: null as OrderDetailView | null,
    loading: true,
    loaded: false,
    errorText: "",
    actionType: ""
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderId = positiveOrderId(query.order_id);
    if (!orderId) {
      this.setData({
        loading: false,
        errorText: "订单参数无效"
      });
      return;
    }
    this.setData({ orderId });
    void this.refreshDetail();
  },

  onShow() {
    if (this.data.loaded && this.data.detail?.status === "PAYING" && !this.data.actionType) {
      void this.recoverPayment(false);
    }
  },

  onUnload() {
    latestDetailRequest += 1;
  },

  async onPullDownRefresh() {
    await this.refreshDetail();
    wx.stopPullDownRefresh();
  },

  onRetry() {
    void this.refreshDetail();
  },

  async refreshDetail() {
    if (!this.data.orderId) {
      return;
    }
    const requestId = ++latestDetailRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const response = await getOrderDetail(this.data.orderId);
      if (requestId !== latestDetailRequest) {
        return;
      }
      this.setData({
        detail: buildOrderDetailView(response),
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestDetailRequest) {
        this.setData({
          loading: false,
          loaded: this.data.detail !== null,
          errorText: actionError(error, "订单详情加载失败")
        });
      }
    }
  },

  onPayTap() {
    void this.payOrder();
  },

  async payOrder() {
    if (!this.data.orderId || this.data.actionType) {
      return;
    }
    this.setData({ actionType: "pay" });
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
      await this.refreshDetail();
    }
  },

  onSyncTap() {
    void this.recoverPayment(true);
  },

  async recoverPayment(showResult: boolean) {
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
      await this.refreshDetail();
    }
  },

  onCancelTap() {
    void this.cancelCurrentOrder();
  },

  async cancelCurrentOrder() {
    if (
      !this.data.orderId ||
      this.data.actionType ||
      !await confirmAction("取消订单", "取消后库存和优惠券会释放，订单不能恢复。", "确认取消")
    ) {
      return;
    }
    this.setData({ actionType: "cancel" });
    try {
      await cancelOrder(this.data.orderId);
      wx.showToast({ title: "订单已取消", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "订单取消失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionType: "" });
      await this.refreshDetail();
    }
  },

  onConfirmTap() {
    void this.confirmCurrentOrder();
  },

  async confirmCurrentOrder() {
    if (
      !this.data.orderId ||
      this.data.actionType ||
      !await confirmAction("确认收货", "请确认已经收到商品；确认后订单将完成。", "确认收货")
    ) {
      return;
    }
    this.setData({ actionType: "confirm" });
    try {
      await confirmOrderReceipt(this.data.orderId);
      wx.showToast({ title: "已确认收货", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "确认收货失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionType: "" });
      await this.refreshDetail();
    }
  },

  onItemImageError(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isSafeInteger(index) || index < 0 || !this.data.detail) {
      return;
    }
    this.setData({
      detail: {
        ...this.data.detail,
        items: this.data.detail.items.map((item, itemIndex) => (
          itemIndex === index ? { ...item, hasImage: false } : item
        ))
      }
    });
  }
});
