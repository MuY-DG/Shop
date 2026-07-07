import type { OrderPreviewItem, OrderPreviewResponse } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import { previewOrder, submitOrder } from "../../../services/order";
import { formatPrice } from "../../../services/product";

interface OrderPreviewItemView extends OrderPreviewItem {
  imageUrl: string;
  unitPriceText: string;
  originalPriceText: string;
  lineAmountText: string;
  lineOriginalAmountText: string;
}

interface OrderPreviewView extends OrderPreviewResponse {
  items: OrderPreviewItemView[];
  productOriginalAmountText: string;
  productAmountText: string;
  couponDiscountText: string;
  freightText: string;
  payableAmountText: string;
}

interface PreviewPageData {
  loading: boolean;
  submitting: boolean;
  errorText: string;
  cartItemIds: number[];
  idempotencyKey: string;
  preview: OrderPreviewView | null;
}

function parseCartItemIds(value: string | undefined): number[] {
  if (!value) {
    return [];
  }

  return value
    .split(",")
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item) && item > 0);
}

function createIdempotencyKey(): string {
  return `mp_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}

function toItemView(item: OrderPreviewItem): OrderPreviewItemView {
  return {
    ...item,
    imageUrl: item.displayImage || item.skuImage || item.mainImage,
    unitPriceText: formatPrice(item.unitPriceCent),
    originalPriceText: formatPrice(item.originalPriceCent),
    lineAmountText: formatPrice(item.lineAmountCent),
    lineOriginalAmountText: formatPrice(item.lineOriginalAmountCent)
  };
}

function toPreviewView(preview: OrderPreviewResponse): OrderPreviewView {
  return {
    ...preview,
    items: preview.items.map(toItemView),
    productOriginalAmountText: formatPrice(preview.productOriginalAmountCent),
    productAmountText: formatPrice(preview.productAmountCent),
    couponDiscountText: formatPrice(preview.couponDiscountCent),
    freightText: formatPrice(preview.freightCent),
    payableAmountText: formatPrice(preview.payableAmountCent)
  };
}

Page<PreviewPageData, WechatMiniprogram.Page.CustomOption>({
  data: {
    loading: false,
    submitting: false,
    errorText: "",
    cartItemIds: [] as number[],
    idempotencyKey: "",
    preview: null as OrderPreviewView | null
  },
  async onLoad(query: Record<string, string | undefined>) {
    const cartItemIds = parseCartItemIds(query.cart_item_ids);
    this.setData({
      cartItemIds,
      idempotencyKey: createIdempotencyKey()
    });

    if (cartItemIds.length === 0) {
      this.setData({
        errorText: "请选择可结算商品"
      });
      return;
    }

    await this.loadPreview();
  },
  async onPullDownRefresh() {
    await this.loadPreview();
    wx.stopPullDownRefresh();
  },
  async loadPreview() {
    if (this.data.cartItemIds.length === 0) {
      return;
    }

    this.setData({
      loading: true,
      errorText: ""
    });

    try {
      await ensureAppLogin();
      const preview = await previewOrder({
        cartItemIds: this.data.cartItemIds
      });
      this.setData({
        preview: toPreviewView(preview)
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "订单预览加载失败",
        preview: null
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  },
  async onSubmitTap() {
    if (!this.data.preview || this.data.submitting) {
      return;
    }

    this.setData({
      submitting: true
    });

    try {
      await ensureAppLogin();
      const response = await submitOrder({
        cartItemIds: this.data.cartItemIds,
        userCouponId: this.data.preview.userCouponId,
        idempotencyKey: this.data.idempotencyKey
      });

      wx.redirectTo({
        url: `/pages/order/detail/detail?order_id=${response.orderId}`
      });
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "提交失败",
        icon: "none"
      });
    } finally {
      this.setData({
        submitting: false
      });
    }
  }
});
