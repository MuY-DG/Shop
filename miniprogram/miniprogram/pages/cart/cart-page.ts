import {
  buildCartCheckoutUrl,
  buildCartSummary,
  buildOrderPreviewView,
  reconcileCartSelection,
  toggleCartSelection,
  type CartItemView,
  type CartSummaryView
} from "../../features/checkout";
import {
  deleteCartItems,
  getCartItems,
  updateCartItemQuantity
} from "../../services/cart";
import { previewOrder } from "../../services/order";
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

interface CartPageConfig {
  loginRedirect: string;
  navigationBack: boolean;
  syncTabBar: boolean;
}

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

export function registerCartPage(config: CartPageConfig): void {
  let latestCartRequest = 0;
  let latestPricingRequest = 0;
  let checkoutSelectionBeforeManage: number[] = [];

  Page({
    data: {
      navigationBack: config.navigationBack,
      loaded: false,
      loading: false,
      loginRequired: false,
      errorText: "",
      rawItems: [] as CartItemResponse[],
      items: [] as CartItemView[],
      cartTotalQuantity: 0,
      selectedIds: [] as number[],
      selectedQuantity: 0,
      selectedAmountText: "¥0.00",
      availableCount: 0,
      unavailableCount: 0,
      allAvailableSelected: false,
      checkoutDisabled: true,
      selectionInitialized: false,
      managing: false,
      pricingLoading: false,
      updatingId: 0,
      deletingId: 0,
      deletingBatch: false
    },

    onShow() {
      if (config.syncTabBar) {
        syncCustomTabBar(this, 2);
      }
      const session = getSessionState();
      if (!session.user || (!session.accessToken && !session.refreshToken)) {
        this.setData({
          loaded: false,
          loading: false,
          loginRequired: true,
          errorText: "",
          cartTotalQuantity: 0,
          managing: false,
          pricingLoading: false
        });
        return;
      }
      this.setData({ loginRequired: false });
      void this.loadCart();
    },

    onUnload() {
      latestCartRequest += 1;
      latestPricingRequest += 1;
    },

    async onPullDownRefresh() {
      if (!this.data.loginRequired) {
        await this.loadCart();
      }
      wx.stopPullDownRefresh();
    },

    async loadCart() {
      const requestId = ++latestCartRequest;
      latestPricingRequest += 1;
      this.setData({
        loading: !this.data.loaded,
        errorText: "",
        pricingLoading: false
      });
      try {
        const response = await getCartItems();
        if (requestId !== latestCartRequest) {
          return;
        }
        const managing = this.data.managing && response.items.length > 0;
        const selectedIds = reconcileCartSelection(
          response.items,
          this.data.selectedIds,
          !this.data.selectionInitialized,
          managing
        );
        const summary = buildCartSummary(response.items, selectedIds, managing);
        this.setData({
          loaded: true,
          loading: false,
          errorText: "",
          rawItems: response.items,
          cartTotalQuantity: response.totalQuantity,
          unavailableCount: response.unavailableCount,
          selectionInitialized: true,
          managing,
          ...summary,
          selectedAmountText: summary.selectedIds.length
            ? this.data.selectedAmountText
            : "¥0.00"
        });
        setCustomTabBarCartCount(this, response.totalQuantity);
        this.refreshSelectedPricing(summary.selectedIds, managing);
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
      openLoginPage(config.loginRedirect);
    },

    onManageToggle() {
      if (this.data.deletingBatch || this.data.updatingId) {
        return;
      }
      const managing = !this.data.managing;
      let selectedIds: number[];
      if (managing) {
        checkoutSelectionBeforeManage = [...this.data.selectedIds];
        selectedIds = [];
      } else {
        selectedIds = reconcileCartSelection(
          this.data.rawItems,
          checkoutSelectionBeforeManage,
          false
        );
      }
      this.setData({ managing });
      this.applySummary(
        buildCartSummary(this.data.rawItems, selectedIds, managing),
        managing
      );
    },

    onSelectionToggle(event: DatasetEvent) {
      const item = this.findItem(event);
      if (!item || (!this.data.managing && !item.available)) {
        return;
      }
      this.applySummary(buildCartSummary(
        this.data.rawItems,
        toggleCartSelection(this.data.selectedIds, item.id),
        this.data.managing
      ), this.data.managing);
    },

    onSelectAllToggle() {
      const selectedIds = this.data.allAvailableSelected
        ? []
        : this.data.rawItems
            .filter((item) => item.available || this.data.managing)
            .map((item) => item.id);
      this.applySummary(
        buildCartSummary(this.data.rawItems, selectedIds, this.data.managing),
        this.data.managing
      );
    },

    onQuantityMinus(event: DatasetEvent) {
      const item = this.findItem(event);
      if (item && item.quantity > 1) {
        void this.updateQuantity(item.id, item.quantity - 1);
      }
    },

    onQuantityPlus(event: DatasetEvent) {
      const item = this.findItem(event);
      if (item?.available && item.quantity < 999) {
        void this.updateQuantity(item.id, item.quantity + 1);
      }
    },

    async updateQuantity(cartItemId: number, quantity: number) {
      if (this.data.updatingId || this.data.deletingBatch || this.data.managing) {
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

    onItemDeleteTap(event: DatasetEvent) {
      const item = this.findItem(event);
      if (!item || this.data.deletingBatch) {
        return;
      }
      this.confirmDelete([item.id]);
    },

    onBatchDeleteTap() {
      if (this.data.deletingBatch) {
        return;
      }
      if (!this.data.selectedIds.length) {
        wx.showToast({ title: "请选择要删除的商品", icon: "none" });
        return;
      }
      this.confirmDelete(this.data.selectedIds);
    },

    confirmDelete(cartItemIds: number[]) {
      const normalizedIds = Array.from(new Set(cartItemIds)).filter((id) => (
        Number.isSafeInteger(id) && id > 0
      ));
      if (!normalizedIds.length) {
        return;
      }
      wx.showModal({
        title: "删除商品",
        content: `确认要删除这${normalizedIds.length}种商品吗？`,
        confirmColor: "#FF172B",
        success: (result) => {
          if (result.confirm) {
            void this.deleteConfirmed(normalizedIds);
          }
        }
      });
    },

    async deleteConfirmed(cartItemIds: number[]) {
      this.setData({
        deletingBatch: true,
        deletingId: cartItemIds.length === 1 ? cartItemIds[0] : 0
      });
      try {
        await deleteCartItems(cartItemIds);
        const deletedIds = new Set(cartItemIds);
        checkoutSelectionBeforeManage = checkoutSelectionBeforeManage.filter(
          (id) => !deletedIds.has(id)
        );
        this.setData({ selectedIds: [] });
        await this.loadCart();
        wx.showToast({ title: "删除成功", icon: "success" });
      } catch (error) {
        wx.showToast({
          title: actionError(error, "删除失败"),
          icon: "none"
        });
      } finally {
        this.setData({
          deletingBatch: false,
          deletingId: 0
        });
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
      if (this.data.loading) {
        return;
      }
      if (this.data.checkoutDisabled) {
        wx.showToast({
          title: "您还没有选择商品",
          icon: "none"
        });
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

    applySummary(summary: CartSummaryView, managing: boolean) {
      this.setData({
        ...summary,
        selectedAmountText: summary.selectedIds.length
          ? this.data.selectedAmountText
          : "¥0.00"
      });
      this.refreshSelectedPricing(summary.selectedIds, managing);
    },

    refreshSelectedPricing(selectedIds: number[], managing: boolean) {
      const requestId = ++latestPricingRequest;
      if (managing || !selectedIds.length) {
        this.setData({
          pricingLoading: false,
          selectedAmountText: selectedIds.length ? this.data.selectedAmountText : "¥0.00"
        });
        return;
      }
      this.setData({ pricingLoading: true });
      void previewOrder({
        source: "CART",
        cartItemIds: [...selectedIds]
      }).then((preview) => {
        if (requestId !== latestPricingRequest) {
          return;
        }
        this.setData({
          pricingLoading: false,
          selectedAmountText: buildOrderPreviewView(preview).payableAmountText
        });
      }).catch((error) => {
        if (requestId !== latestPricingRequest) {
          return;
        }
        this.setData({
          pricingLoading: false,
          selectedAmountText: "¥--"
        });
        wx.showToast({
          title: actionError(error, "金额计算失败，请稍后重试"),
          icon: "none"
        });
      });
    },

    findItem(event: DatasetEvent): CartItemView | undefined {
      const cartItemId = Number(event.currentTarget.dataset.id);
      if (!Number.isSafeInteger(cartItemId) || cartItemId <= 0) {
        return undefined;
      }
      return this.data.items.find((item) => item.id === cartItemId);
    }
  });
}
