import { createBrandLogoView } from "../../config/brand-logo";
import {
  buildCartCheckoutUrl,
  buildCartSummary,
  buildOrderPreviewView,
  preserveCartItemOrder,
  reconcileCartSelection,
  toggleCartSelection,
  type CartItemView,
  type CartSummaryView
} from "../../features/checkout";
import {
  isStockShortageError
} from "../../features/cart-feedback";
import {
  normalizeQuantityInput,
  stockQuantityCorrectedMessage
} from "../../features/quantity";
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

interface QuantityInputEvent extends DatasetEvent {
  detail: {
    value: string;
  };
}

interface CartPageConfig {
  loginRedirect: string;
  navigationBack: boolean;
  syncTabBar: boolean;
}

interface CartLoadOptions {
  preserveItemOrder?: boolean;
  suppressError?: boolean;
}

interface PricingRefreshOptions {
  fallbackAmountText: string;
  suppressError?: boolean;
}

const MAX_PRICING_CACHE_ENTRIES = 24;

function pricingSignature(items: CartItemResponse[], selectedIds: number[]): string {
  const quantityById = new Map(items.map((item) => [item.id, item.quantity]));
  return [...selectedIds]
    .sort((left, right) => left - right)
    .map((id) => `${id}:${quantityById.get(id) ?? 0}`)
    .join("|");
}

function actionError(error: unknown, fallback: string): string {
  if (isStockShortageError(error)) {
    return "商品库存不足";
  }
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
  const pricingCache = new Map<string, string>();
  let pricingOwnerKey = "";

  Page({
    data: {
      brandLogo: createBrandLogoView(136, 120),
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
      deletingBatch: false,
      contentRefreshing: false
    },

    onShow() {
      if (config.syncTabBar) {
        syncCustomTabBar(this, 2);
      }
      const session = getSessionState();
      if (!session.user || (!session.accessToken && !session.refreshToken)) {
        pricingCache.clear();
        pricingOwnerKey = "";
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
      const nextPricingOwnerKey = session.user.userId || session.refreshToken || session.accessToken;
      if (pricingOwnerKey !== nextPricingOwnerKey) {
        pricingCache.clear();
        pricingOwnerKey = nextPricingOwnerKey;
      }
      this.setData({ loginRequired: false });
      void this.loadCart({ suppressError: this.data.loaded });
    },

    onUnload() {
      latestCartRequest += 1;
      latestPricingRequest += 1;
    },

    async onContentRefresh() {
      if (this.data.contentRefreshing) {
        return;
      }
      this.setData({ contentRefreshing: true });
      try {
        if (!this.data.loginRequired) {
          await this.loadCart();
        }
      } finally {
        this.setData({ contentRefreshing: false });
      }
    },

    async loadCart(options: CartLoadOptions = {}) {
      const requestId = ++latestCartRequest;
      latestPricingRequest += 1;
      this.setData({
        loading: !this.data.loaded,
        ...(options.suppressError ? {} : { errorText: "" }),
        pricingLoading: false
      });
      try {
        const response = await getCartItems();
        if (requestId !== latestCartRequest) {
          return;
        }
        const responseItems = options.preserveItemOrder
          ? preserveCartItemOrder(
              response.items,
              this.data.rawItems.map((item) => item.id)
            )
          : response.items;
        const managing = this.data.managing && responseItems.length > 0;
        const selectedIds = reconcileCartSelection(
          responseItems,
          this.data.selectedIds,
          !this.data.selectionInitialized,
          managing
        );
        const summary = buildCartSummary(responseItems, selectedIds, managing);
        const signature = pricingSignature(responseItems, summary.selectedIds);
        const fallbackAmountText = summary.selectedIds.length
          ? pricingCache.get(signature) ?? summary.selectedAmountText
          : "¥0.00";
        this.setData({
          loaded: true,
          loading: false,
          errorText: "",
          rawItems: responseItems,
          cartTotalQuantity: response.totalQuantity,
          unavailableCount: response.unavailableCount,
          selectionInitialized: true,
          managing,
          ...summary,
          selectedAmountText: fallbackAmountText
        });
        setCustomTabBarCartCount(this, response.totalQuantity);
        this.refreshSelectedPricing(summary.selectedIds, managing, {
          fallbackAmountText,
          suppressError: options.suppressError
        });
      } catch (error) {
        if (requestId !== latestCartRequest) {
          return;
        }
        const loginRequired = isApiError(error) && error.kind === "AUTH";
        if (options.suppressError && this.data.loaded && !loginRequired) {
          this.setData({ loading: false, pricingLoading: false });
          return;
        }
        this.setData({
          loaded: this.data.rawItems.length > 0,
          loading: false,
          loginRequired,
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
      if (!item) {
        return;
      }
      const maximum = item.maxPurchaseQuantity ?? 999;
      if (!item.available || maximum <= 0 || item.quantity >= maximum) {
        wx.showToast({ title: "商品已达最大可购买数", icon: "none" });
        return;
      }
      void this.updateQuantity(item.id, item.quantity + 1);
    },

    onQuantityInputCommit(event: QuantityInputEvent) {
      const item = this.findItem(event);
      if (!item) {
        return;
      }
      const maximum = item.maxPurchaseQuantity ?? 999;
      const result = normalizeQuantityInput(
        event.detail.value,
        item.quantity,
        maximum
      );
      if (result.quantity <= 0) {
        this.refreshQuantityInput(item.id);
        wx.showToast({ title: "商品库存不足", icon: "none" });
        return;
      }
      if (result.exceededStock) {
        wx.showToast({
          title: stockQuantityCorrectedMessage(maximum),
          icon: "none"
        });
      }
      if (result.quantity === item.quantity) {
        this.refreshQuantityInput(item.id);
        return;
      }
      void this.updateQuantity(item.id, result.quantity);
    },

    async updateQuantity(cartItemId: number, quantity: number) {
      if (this.data.updatingId || this.data.deletingBatch || this.data.managing) {
        return;
      }
      this.setData({ updatingId: cartItemId });
      try {
        await updateCartItemQuantity(cartItemId, { quantity });
        await this.loadCart({ preserveItemOrder: true });
      } catch (error) {
        if (isStockShortageError(error) && await this.recoverStockShortage(cartItemId)) {
          return;
        }
        wx.showToast({
          title: actionError(error, "数量修改失败"),
          icon: "none"
        });
      } finally {
        this.setData({ updatingId: 0 });
      }
    },

    refreshQuantityInput(cartItemId: number) {
      this.setData({
        items: this.data.items.map((item) => (
          item.id === cartItemId ? { ...item } : item
        ))
      });
    },

    async recoverStockShortage(cartItemId: number): Promise<boolean> {
      try {
        const response = await getCartItems();
        const item = response.items.find((candidate) => candidate.id === cartItemId);
        const maximum = item?.maxPurchaseQuantity ?? 0;
        if (!item || maximum <= 0) {
          await this.loadCart({ preserveItemOrder: true, suppressError: true });
          wx.showToast({ title: "商品库存不足", icon: "none" });
          return true;
        }
        if (item.quantity !== maximum) {
          await updateCartItemQuantity(cartItemId, { quantity: maximum });
        }
        await this.loadCart({ preserveItemOrder: true });
        wx.showToast({
          title: stockQuantityCorrectedMessage(maximum),
          icon: "none"
        });
        return true;
      } catch {
        return false;
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
      const signature = pricingSignature(this.data.rawItems, summary.selectedIds);
      const fallbackAmountText = summary.selectedIds.length
        ? pricingCache.get(signature) ?? summary.selectedAmountText
        : "¥0.00";
      this.setData({
        ...summary,
        selectedAmountText: fallbackAmountText
      });
      this.refreshSelectedPricing(summary.selectedIds, managing, {
        fallbackAmountText
      });
    },

    refreshSelectedPricing(
      selectedIds: number[],
      managing: boolean,
      options: PricingRefreshOptions
    ) {
      const requestId = ++latestPricingRequest;
      if (managing || !selectedIds.length) {
        this.setData({
          pricingLoading: false,
          selectedAmountText: selectedIds.length ? this.data.selectedAmountText : "¥0.00"
        });
        return;
      }
      const signature = pricingSignature(this.data.rawItems, selectedIds);
      this.setData({ pricingLoading: true });
      void previewOrder({
        source: "CART",
        cartItemIds: [...selectedIds]
      }).then((preview) => {
        if (requestId !== latestPricingRequest) {
          return;
        }
        const selectedAmountText = buildOrderPreviewView(preview).payableAmountText;
        pricingCache.set(signature, selectedAmountText);
        if (pricingCache.size > MAX_PRICING_CACHE_ENTRIES) {
          const oldestKey = pricingCache.keys().next().value as string | undefined;
          if (oldestKey) {
            pricingCache.delete(oldestKey);
          }
        }
        this.setData({
          pricingLoading: false,
          selectedAmountText
        });
      }).catch((error) => {
        if (requestId !== latestPricingRequest) {
          return;
        }
        this.setData({
          pricingLoading: false,
          selectedAmountText: options.fallbackAmountText
        });
        if (!options.suppressError) {
          wx.showToast({
            title: actionError(error, "金额计算失败，请稍后重试"),
            icon: "none"
          });
        }
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
