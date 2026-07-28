import {
  buildCartCheckoutUrl,
  buildCartSummary,
  reconcileCartSelection,
  toggleCartSelection,
  type CartItemView,
  type CartSummaryView
} from "../../features/checkout";
import {
  clearCart,
  deleteCartItem,
  getCartItems,
  updateCartItemQuantity
} from "../../services/cart";
import { getSessionState } from "../../services/session";
import type { CartItemResponse } from "../../types/cart";
import { isApiError } from "../../utils/api-error";
import { openLoginPage } from "../../utils/login-navigation";
import {
  setCustomTabBarCartCount,
  syncCustomTabBar
} from "../../utils/tab-bar";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
    };
  };
}

let latestCartRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

Page({
  data: {
    loaded: false,
    loading: false,
    loginRequired: false,
    errorText: "",
    rawItems: [] as CartItemResponse[],
    items: [] as CartItemView[],
    selectedIds: [] as number[],
    selectedQuantity: 0,
    selectedAmountText: "¥0.00",
    availableCount: 0,
    unavailableCount: 0,
    allAvailableSelected: false,
    checkoutDisabled: true,
    selectionInitialized: false,
    updatingId: 0,
    deletingId: 0,
    clearing: false
  },

  onShow() {
    syncCustomTabBar(this, 2);
    const session = getSessionState();
    if (!session.user || (!session.accessToken && !session.refreshToken)) {
      this.setData({
        loaded: false,
        loading: false,
        loginRequired: true,
        errorText: ""
      });
      return;
    }
    this.setData({ loginRequired: false });
    void this.loadCart();
  },

  onUnload() {
    latestCartRequest += 1;
  },

  async onPullDownRefresh() {
    if (!this.data.loginRequired) {
      await this.loadCart();
    }
    wx.stopPullDownRefresh();
  },

  async loadCart() {
    const requestId = ++latestCartRequest;
    this.setData({
      loading: !this.data.loaded,
      errorText: ""
    });
    try {
      const response = await getCartItems();
      if (requestId !== latestCartRequest) {
        return;
      }
      const selectedIds = reconcileCartSelection(
        response.items,
        this.data.selectedIds,
        !this.data.selectionInitialized
      );
      const summary = buildCartSummary(response.items, selectedIds);
      this.setData({
        loaded: true,
        loading: false,
        errorText: "",
        rawItems: response.items,
        unavailableCount: response.unavailableCount,
        selectionInitialized: true,
        ...summary
      });
      setCustomTabBarCartCount(this, response.totalQuantity);
    } catch (error) {
      if (requestId !== latestCartRequest) {
        return;
      }
      this.setData({
        loaded: this.data.rawItems.length > 0,
        loading: false,
        loginRequired: isApiError(error) && error.kind === "AUTH",
        errorText: actionError(error, "购物车加载失败，请稍后重试")
      });
    }
  },

  onRetry() {
    void this.loadCart();
  },

  onLoginTap() {
    openLoginPage("/pages/cart/cart");
  },

  onSelectionToggle(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item?.available) {
      return;
    }
    this.applySummary(buildCartSummary(
      this.data.rawItems,
      toggleCartSelection(this.data.selectedIds, item.id)
    ));
  },

  onSelectAllToggle() {
    const selectedIds = this.data.allAvailableSelected
      ? []
      : this.data.rawItems.filter((item) => item.available).map((item) => item.id);
    this.applySummary(buildCartSummary(this.data.rawItems, selectedIds));
  },

  onQuantityMinus(event: DatasetEvent) {
    const item = this.findItem(event);
    if (item && item.quantity > 1) {
      void this.updateQuantity(item.id, item.quantity - 1);
    }
  },

  onQuantityPlus(event: DatasetEvent) {
    const item = this.findItem(event);
    if (item && item.quantity < Math.min(999, item.stockAvailable)) {
      void this.updateQuantity(item.id, item.quantity + 1);
    }
  },

  async updateQuantity(cartItemId: number, quantity: number) {
    if (this.data.updatingId || this.data.deletingId || this.data.clearing) {
      return;
    }
    this.setData({ updatingId: cartItemId });
    try {
      await updateCartItemQuantity(cartItemId, { quantity });
      await this.loadCart();
    } catch (error) {
      wx.showToast({
        title: actionError(error, "数量修改失败"),
        icon: "none"
      });
    } finally {
      this.setData({ updatingId: 0 });
    }
  },

  onDeleteTap(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item || this.data.deletingId || this.data.clearing) {
      return;
    }
    wx.showModal({
      title: "移除商品",
      content: `确认移除“${item.productTitle}”吗？`,
      confirmColor: "#B72B22",
      success: (result) => {
        if (result.confirm) {
          void this.deleteConfirmed(item.id);
        }
      }
    });
  },

  async deleteConfirmed(cartItemId: number) {
    this.setData({ deletingId: cartItemId });
    try {
      await deleteCartItem(cartItemId);
      await this.loadCart();
      wx.showToast({ title: "已移除", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "移除失败"),
        icon: "none"
      });
    } finally {
      this.setData({ deletingId: 0 });
    }
  },

  onClearTap() {
    if (!this.data.rawItems.length || this.data.clearing || this.data.deletingId) {
      return;
    }
    wx.showModal({
      title: "清空购物车",
      content: "购物车中的全部商品都会被移除，是否继续？",
      confirmColor: "#B72B22",
      success: (result) => {
        if (result.confirm) {
          void this.clearConfirmed();
        }
      }
    });
  },

  async clearConfirmed() {
    this.setData({ clearing: true });
    try {
      await clearCart();
      this.setData({
        selectedIds: [],
        selectionInitialized: false
      });
      await this.loadCart();
      wx.showToast({ title: "购物车已清空", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "清空失败"),
        icon: "none"
      });
    } finally {
      this.setData({ clearing: false });
    }
  },

  onProductTap(event: DatasetEvent) {
    const item = this.findItem(event);
    if (item?.spuId) {
      wx.navigateTo({ url: `/pages/product/detail/detail?id=${item.spuId}` });
    }
  },

  onImageError(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item) {
      return;
    }
    this.setData({
      items: this.data.items.map((candidate) => (
        candidate.id === item.id ? { ...candidate, hasImage: false } : candidate
      ))
    });
  },

  onCheckoutTap() {
    if (this.data.checkoutDisabled || this.data.loading) {
      return;
    }
    try {
      wx.navigateTo({
        url: buildCartCheckoutUrl(this.data.selectedIds)
      });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "请选择需要结算的商品"),
        icon: "none"
      });
    }
  },

  applySummary(summary: CartSummaryView) {
    this.setData(summary);
  },

  findItem(event: DatasetEvent): CartItemView | undefined {
    const cartItemId = Number(event.currentTarget.dataset.id);
    if (!Number.isSafeInteger(cartItemId) || cartItemId <= 0) {
      return undefined;
    }
    return this.data.items.find((item) => item.id === cartItemId);
  }
});
