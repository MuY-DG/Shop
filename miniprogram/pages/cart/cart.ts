import type { CartItem } from "../../types/api";
import { ensureAppLogin } from "../../services/auth";
import {
  clearCart,
  deleteCartItem,
  getCartItems,
  updateCartItemQuantity
} from "../../services/cart";
import { formatPrice } from "../../services/product";

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface CartItemView extends CartItem {
  priceText: string;
  lineAmountText: string;
  stockText: string;
  unavailableText: string;
}

function unavailableText(item: CartItem): string {
  if (!item.unavailableReason) {
    return "";
  }
  if (item.unavailableReason === "SKU_UNAVAILABLE") {
    return "规格已下架";
  }
  if (item.unavailableReason === "PRODUCT_UNAVAILABLE") {
    return "商品已下架";
  }
  return "库存不足";
}

function toCartItemView(item: CartItem): CartItemView {
  return {
    ...item,
    priceText: formatPrice(item.priceCent),
    lineAmountText: formatPrice(item.lineAmountCent),
    stockText: `库存 ${item.stockAvailable}`,
    unavailableText: unavailableText(item)
  };
}

Page({
  data: {
    loading: false,
    clearing: false,
    errorText: "",
    items: [] as CartItemView[],
    totalQuantity: 0,
    totalAmountText: formatPrice(0),
    unavailableCount: 0
  },
  async onShow() {
    await this.loadCart();
  },
  async onPullDownRefresh() {
    await this.loadCart();
    wx.stopPullDownRefresh();
  },
  async loadCart() {
    this.setData({
      loading: true,
      errorText: ""
    });

    try {
      await ensureAppLogin();
      const response = await getCartItems();
      this.setData({
        items: response.items.map(toCartItemView),
        totalQuantity: response.totalQuantity,
        totalAmountText: formatPrice(response.totalAmountCent),
        unavailableCount: response.unavailableCount
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "购物车加载失败"
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  },
  async onQuantityMinus(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item || item.quantity <= 1) {
      return;
    }
    await this.updateQuantity(item.id, item.quantity - 1);
  },
  async onQuantityPlus(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item) {
      return;
    }
    await this.updateQuantity(item.id, item.quantity + 1);
  },
  async updateQuantity(cartItemId: number, quantity: number) {
    try {
      await updateCartItemQuantity(cartItemId, { quantity });
      await this.loadCart();
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "数量修改失败",
        icon: "none"
      });
    }
  },
  async onDeleteTap(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item) {
      return;
    }

    try {
      await deleteCartItem(item.id);
      await this.loadCart();
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "删除失败",
        icon: "none"
      });
    }
  },
  async onClearTap() {
    if (this.data.items.length === 0 || this.data.clearing) {
      return;
    }

    this.setData({
      clearing: true
    });

    try {
      await clearCart();
      await this.loadCart();
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "清空失败",
        icon: "none"
      });
    } finally {
      this.setData({
        clearing: false
      });
    }
  },
  onProductTap(event: DatasetEvent) {
    const item = this.findItem(event);
    if (!item || item.spuId <= 0) {
      return;
    }
    wx.navigateTo({
      url: `/pages/product/detail/detail?id=${item.spuId}`
    });
  },
  findItem(event: DatasetEvent): CartItemView | undefined {
    const cartItemId = Number(event.currentTarget.dataset.id);
    if (!Number.isFinite(cartItemId) || cartItemId <= 0) {
      return undefined;
    }
    return this.data.items.find((item) => item.id === cartItemId);
  }
});
