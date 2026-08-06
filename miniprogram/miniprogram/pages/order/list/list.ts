import {
  buildOrderDetailUrl,
  buildOrderModifyUrl,
  buildOrderReviewUrl,
  buildOrderSummaryView,
  createPageOperationGuard,
  filterRebuyableOrderItems,
  ORDER_STATUS_TABS,
  parseOrderStatusGroup,
  positiveOrderId,
  REBUY_NO_AVAILABLE_ITEMS_MESSAGE,
  rebuyFailureMessage,
  rebuyPartialMessage,
  type OrderSummaryView
} from "../../../features/order-center";
import { buildCartCheckoutUrl } from "../../../features/checkout";
import { executeOrderPayment } from "../../../features/order-payment";
import { addCartItem } from "../../../services/cart";
import {
  cancelOrder,
  deleteOrder,
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
      itemId?: number | string;
    };
  };
}

interface RefreshOptions {
  silent?: boolean;
  suppressError?: boolean;
}

const PAGE_SIZE = 10;
let latestListRequest = 0;
const rebuyOperationGuard = createPageOperationGuard();

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
    lifecycleToken: 0,
    tabs: ORDER_STATUS_TABS,
    activeGroup: "ALL" as OrderStatusGroup,
    orders: [] as OrderSummaryView[],
    current: 1,
    total: 0,
    hasMore: false,
    loading: true,
    loaded: false,
    loadingMore: false,
    contentRefreshing: false,
    errorText: "",
    actionOrderId: 0,
    actionType: ""
  },

  onLoad(query: Record<string, string | undefined>) {
    this.setData({
      lifecycleToken: rebuyOperationGuard.mount(),
      activeGroup: parseOrderStatusGroup(query.group)
    });
    void this.refreshOrders();
  },

  onShow() {
    if (
      this.data.loaded
      && !this.data.loading
      && !this.data.loadingMore
      && !this.data.contentRefreshing
      && !this.data.actionOrderId
    ) {
      void this.refreshOrders({ silent: true, suppressError: true });
    }
  },

  onUnload() {
    rebuyOperationGuard.unmount(this.data.lifecycleToken);
    latestListRequest += 1;
  },

  async onContentRefresh() {
    if (
      this.data.contentRefreshing
      || this.data.loading
      || this.data.loadingMore
      || this.data.actionOrderId
    ) {
      return;
    }
    this.setData({ contentRefreshing: true });
    try {
      await this.refreshOrders({ silent: true });
    } finally {
      this.setData({ contentRefreshing: false });
    }
  },

  onReachBottom() {
    void this.loadMoreOrders();
  },

  onRetry() {
    void this.refreshOrders();
  },

  onTabTap(event: DatasetEvent) {
    const group = parseOrderStatusGroup(event.currentTarget.dataset.group);
    if (
      group === this.data.activeGroup ||
      this.data.loading ||
      this.data.loadingMore ||
      this.data.actionOrderId
    ) {
      return;
    }
    this.setData({ activeGroup: group, orders: [], loaded: false });
    void this.refreshOrders();
  },

  async refreshOrders(options: RefreshOptions = {}) {
    const requestId = ++latestListRequest;
    const silent = options.silent === true && this.data.loaded;
    if (silent) {
      if (!options.suppressError) {
        this.setData({ errorText: "" });
      }
    } else {
      this.setData({ loading: true, loadingMore: false, errorText: "" });
    }
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
        loadingMore: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestListRequest) {
        if (silent && options.suppressError) {
          return;
        }
        this.setData({
          loading: false,
          loadingMore: false,
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

  onItemImageError(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    const orderItemId = positiveOrderId(event.currentTarget.dataset.itemId);
    if (!orderId || !orderItemId) {
      return;
    }
    this.setData({
      orders: this.data.orders.map((order) => order.orderId === orderId
        ? {
          ...order,
          items: order.items.map((item) => item.orderItemId === orderItemId
            ? { ...item, imageUrl: "", hasImage: false }
            : item)
        }
        : order)
    });
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
    this.setData({ actionOrderId: orderId, actionType: "pay" });
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
      this.setData({ actionOrderId: 0, actionType: "" });
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
    this.setData({ actionOrderId: orderId, actionType: "cancel" });
    try {
      await cancelOrder(orderId);
      wx.showToast({ title: "订单已取消", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "订单取消失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionOrderId: 0, actionType: "" });
      await this.refreshOrders();
    }
  },

  onDeleteTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (orderId) {
      void this.deleteSelectedOrder(orderId);
    }
  },

  async deleteSelectedOrder(orderId: number) {
    if (
      this.data.actionOrderId ||
      !await confirmAction("删除订单", "删除后将不再在订单列表中显示。", "确认删除")
    ) {
      return;
    }
    this.setData({ actionOrderId: orderId, actionType: "delete" });
    try {
      await deleteOrder(orderId);
      wx.showToast({ title: "订单已删除", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "订单删除失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionOrderId: 0, actionType: "" });
      await this.refreshOrders();
    }
  },

  onRebuyTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (orderId) {
      void this.rebuySelectedOrder(orderId);
    }
  },

  async rebuySelectedOrder(orderId: number) {
    if (this.data.actionOrderId) {
      return;
    }
    const lifecycleToken = this.data.lifecycleToken;
    const operationToken = rebuyOperationGuard.begin(lifecycleToken);
    if (!operationToken) {
      return;
    }
    this.setData({ actionOrderId: orderId, actionType: "rebuy" });
    const cartItemIds: number[] = [];
    let firstError: unknown = null;
    try {
      const detail = await getOrderDetail(orderId);
      if (!rebuyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
        return;
      }
      const rebuyItems = filterRebuyableOrderItems(
        detail.items,
        detail.rebuyableOrderItemIds
      );
      for (const item of rebuyItems) {
        if (!rebuyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
          return;
        }
        try {
          const cartItem = await addCartItem({
            skuId: item.skuId,
            quantity: item.quantity
          });
          if (!rebuyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
            return;
          }
          cartItemIds.push(cartItem.id);
        } catch (error) {
          if (!rebuyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
            return;
          }
          firstError ??= error;
        }
      }
      if (!rebuyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
        return;
      }
      if (!cartItemIds.length) {
        wx.showToast({
          title: rebuyItems.length
            ? rebuyFailureMessage(firstError)
            : REBUY_NO_AVAILABLE_ITEMS_MESSAGE,
          icon: "none"
        });
        return;
      }
      if (cartItemIds.length < detail.items.length) {
        wx.showToast({
          title: rebuyPartialMessage(cartItemIds.length, detail.items.length),
          icon: "none",
          duration: 2500
        });
      }
      wx.redirectTo({
        url: buildCartCheckoutUrl(cartItemIds),
        fail: () => {
          if (rebuyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
            wx.switchTab({ url: "/pages/cart/cart" });
          }
        }
      });
    } catch (error) {
      if (!rebuyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
        return;
      }
      wx.showToast({
        title: actionError(error, "商品暂时无法再次购买"),
        icon: "none"
      });
    } finally {
      if (rebuyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
        this.setData({ actionOrderId: 0, actionType: "" });
      }
    }
  },

  onModifyTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (orderId && !this.data.actionOrderId) {
      wx.navigateTo({ url: buildOrderModifyUrl(orderId) });
    }
  },

  onReviewTap(event: DatasetEvent) {
    const orderId = positiveOrderId(event.currentTarget.dataset.id);
    if (orderId && !this.data.actionOrderId) {
      wx.navigateTo({ url: buildOrderReviewUrl(orderId) });
    }
  }
});
