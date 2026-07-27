import {
  buildOrderDetailUrl,
  buildOrderSummaryView,
  ORDER_STATUS_TABS,
  parseOrderStatusGroup,
  positiveOrderId,
  type OrderSummaryView
} from "../../../features/order-center";
import {
  executeOrderPayment,
  recoverOrderPayment
} from "../../../features/order-payment";
import { openWechatReceiptConfirmation } from "../../../features/wechat-order-receipt";
import {
  cancelOrder,
  confirmOrderReceipt,
  getOrderDetail,
  getOrders
} from "../../../services/order";
import type { OrderStatusGroup } from "../../../types/order";
import { isApiError } from "../../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      group?: string;
      id?: number | string;
    };
  };
}

const PAGE_SIZE = 10;
let latestListRequest = 0;

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
    tabs: ORDER_STATUS_TABS,
    activeGroup: "ALL" as OrderStatusGroup,
    orders: [] as OrderSummaryView[],
    current: 1,
    total: 0,
    hasMore: false,
    loading: true,
    loaded: false,
    loadingMore: false,
    errorText: "",
    actionOrderId: 0
  },

  onLoad(query: Record<string, string | undefined>) {
    this.setData({ activeGroup: parseOrderStatusGroup(query.group) });
    void this.refreshOrders();
  },

  onShow() {
    if (this.data.loaded && !this.data.loading && !this.data.actionOrderId) {
      void this.refreshOrders();
    }
  },

  onUnload() {
    latestListRequest += 1;
  },

  async onPullDownRefresh() {
    await this.refreshOrders();
    wx.stopPullDownRefresh();
  },

  onReachBottom() {
    void this.loadMoreOrders();
  },

  onRetry() {
    void this.refreshOrders();
  },

  onTabTap(event: DatasetEvent) {
    const group = parseOrderStatusGroup(event.currentTarget.dataset.group);
    if (group === this.data.activeGroup || this.data.loading || this.data.actionOrderId) {
      return;
    }
    this.setData({ activeGroup: group, orders: [], loaded: false });
    void this.refreshOrders();
  },

  async refreshOrders() {
    const requestId = ++latestListRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const response = await getOrders({
        current: 1,
        size: PAGE_SIZE,
        statusGroup: this.data.activeGroup
      });
      if (requestId !== latestListRequest) {
        return;
      }
      this.setData({
        orders: response.records.map(buildOrderSummaryView),
        current: response.current,
        total: response.total,
        hasMore: response.current * response.size < response.total,
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestListRequest) {
        this.setData({
          loading: false,
          loaded: this.data.orders.length > 0,
          errorText: actionError(error, "订单加载失败，请稍后重试")
        });
      }
    }
  },

  async loadMoreOrders() {
    if (!this.data.hasMore || this.data.loading || this.data.loadingMore) {
      return;
    }
    const requestId = ++latestListRequest;
    const nextPage = this.data.current + 1;
    this.setData({ loadingMore: true });
    try {
      const response = await getOrders({
        current: nextPage,
        size: PAGE_SIZE,
        statusGroup: this.data.activeGroup
      });
      if (requestId !== latestListRequest) {
        return;
      }
      this.setData({
        orders: [...this.data.orders, ...response.records.map(buildOrderSummaryView)],
        current: response.current,
        total: response.total,
        hasMore: response.current * response.size < response.total,
        loadingMore: false
      });
    } catch (error) {
      if (requestId === latestListRequest) {
        this.setData({ loadingMore: false });
        wx.showToast({
          title: actionError(error, "更多订单加载失败"),
          icon: "none"
        });
      }
    }
  },

  onOrderTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (!orderId) {
      return;
    }
    wx.navigateTo({ url: buildOrderDetailUrl(orderId) });
  },

  onPayTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (orderId) {
      void this.payOrder(orderId);
    }
  },

  async payOrder(orderId: number) {
    if (this.data.actionOrderId) {
      return;
    }
    this.setData({ actionOrderId: orderId });
    try {
      const outcome = await executeOrderPayment(orderId);
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
      this.setData({ actionOrderId: 0 });
      await this.refreshOrders();
    }
  },

  onSyncTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (orderId) {
      void this.syncPayment(orderId);
    }
  },

  async syncPayment(orderId: number) {
    if (this.data.actionOrderId) {
      return;
    }
    this.setData({ actionOrderId: orderId });
    try {
      const response = await recoverOrderPayment(orderId);
      wx.showToast({
        title: response.status === "PAID" ? "支付成功" : "暂未查询到支付结果",
        icon: response.status === "PAID" ? "success" : "none"
      });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "支付结果同步失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionOrderId: 0 });
      await this.refreshOrders();
    }
  },

  onCancelTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (orderId) {
      void this.cancelSelectedOrder(orderId);
    }
  },

  async cancelSelectedOrder(orderId: number) {
    if (
      this.data.actionOrderId ||
      !await confirmAction("取消订单", "取消后库存和优惠券会释放，订单不能恢复。", "确认取消")
    ) {
      return;
    }
    this.setData({ actionOrderId: orderId });
    try {
      await cancelOrder(orderId);
      wx.showToast({ title: "订单已取消", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "订单取消失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionOrderId: 0 });
      await this.refreshOrders();
    }
  },

  onConfirmTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (orderId) {
      void this.confirmSelectedOrder(orderId);
    }
  },

  async confirmSelectedOrder(orderId: number) {
    if (this.data.actionOrderId) {
      return;
    }
    this.setData({ actionOrderId: orderId });
    try {
      const detail = await getOrderDetail(orderId);
      const transactionId = detail.transactionId || detail.paymentTransactionId || "";
      const componentResult = await openWechatReceiptConfirmation(transactionId);
      if (componentResult.outcome === "CANCELLED") {
        return;
      }
      if (componentResult.outcome === "FAILED") {
        wx.showToast({
          title: componentResult.message || "微信确认收货失败",
          icon: "none"
        });
        return;
      }
      await confirmOrderReceipt(orderId);
      wx.showToast({ title: "已确认收货", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "确认收货失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionOrderId: 0 });
      await this.refreshOrders();
    }
  }
});
