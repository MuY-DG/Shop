import type {
  AddressResponse,
  CheckoutQuery,
  OrderPreviewItem,
  OrderPreviewResponse
} from "../../../types/api";
import { getAddresses } from "../../../services/address";
import { ensureAppLogin } from "../../../services/auth";
import { previewOrder, submitOrder } from "../../../services/order";
import { formatPrice } from "../../../services/product";
import {
  buildPreviewRequest,
  buildSubmitRequest,
  createIdempotencyKey,
  isAddressResponse,
  isCheckoutSubmitDisabled,
  parseCheckoutQuery,
  replaceAddressFromEvent,
  resolveAddressSelection
} from "../../../features/checkout";

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
  selection: CheckoutQuery | null;
  selectedAddress: AddressResponse | null;
  idempotencyKey: string;
  preview: OrderPreviewView | null;
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
  initializationComplete: false,
  previewRequestSequence: 0,
  submitLocked: false,
  data: {
    loading: false,
    submitting: false,
    errorText: "",
    selection: null as CheckoutQuery | null,
    selectedAddress: null as AddressResponse | null,
    idempotencyKey: "",
    preview: null as OrderPreviewView | null
  },
  async onLoad(query: Record<string, string | undefined>) {
    try {
      const selection = parseCheckoutQuery(query);
      this.setData({
        selection,
        idempotencyKey: createIdempotencyKey()
      });
      await this.refreshCheckout();
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "结算参数无效"
      });
    } finally {
      this.initializationComplete = true;
    }
  },
  async onShow() {
    if (this.initializationComplete) {
      await this.refreshCheckout();
    }
  },
  async onPullDownRefresh() {
    await this.refreshCheckout();
    wx.stopPullDownRefresh();
  },
  onRetryTap() {
    void this.refreshCheckout();
  },
  async refreshCheckout() {
    const selection = this.data.selection;
    if (!selection) {
      return;
    }
    const requestSequence = ++this.previewRequestSequence;
    this.setData({ loading: true, errorText: "" });
    try {
      await ensureAppLogin();
      const addresses = await getAddresses();
      if (requestSequence !== this.previewRequestSequence) {
        return;
      }
      const selectedAddress = resolveAddressSelection(
        addresses,
        this.data.selectedAddress
      );
      this.setData({ selectedAddress });
      await this.loadPreview(selectedAddress, requestSequence);
    } catch (error) {
      if (requestSequence === this.previewRequestSequence) {
        this.setData({
          errorText: error instanceof Error ? error.message : "订单预览加载失败",
          preview: null
        });
      }
    } finally {
      if (requestSequence === this.previewRequestSequence) {
        this.setData({ loading: false });
      }
    }
  },
  async loadPreview(
    selectedAddress: AddressResponse | null,
    requestSequence?: number
  ) {
    const activeRequestSequence =
      requestSequence ?? ++this.previewRequestSequence;
    const selection = this.data.selection;
    if (!selection) {
      return;
    }
    this.setData({ loading: true, errorText: "" });
    try {
      await ensureAppLogin();
      const preview = await previewOrder(
        buildPreviewRequest(
          selection,
          selectedAddress?.id ?? null,
          this.data.preview?.userCouponId ?? null
        )
      );
      if (activeRequestSequence !== this.previewRequestSequence) {
        return;
      }
      this.setData({ preview: toPreviewView(preview) });
    } catch (error) {
      if (activeRequestSequence === this.previewRequestSequence) {
        this.setData({
          errorText: error instanceof Error ? error.message : "订单预览加载失败",
          preview: null
        });
      }
    } finally {
      if (activeRequestSequence === this.previewRequestSequence) {
        this.setData({ loading: false });
      }
    }
  },
  onAddressTap() {
    wx.navigateTo({
      url: "/pages/address/list/list?mode=select",
      events: {
        addressSelected: (value: unknown) => {
          if (!isAddressResponse(value)) {
            wx.showToast({ title: "地址信息无效，请重试", icon: "none" });
            return;
          }
          const selectedAddress = replaceAddressFromEvent(
            this.data.selectedAddress,
            value
          );
          this.setData({ selectedAddress });
          void this.loadPreview(selectedAddress);
        }
      }
    });
  },
  async onSubmitTap() {
    const selection = this.data.selection;
    const address = this.data.selectedAddress;
    if (
      this.submitLocked ||
      !selection ||
      !address ||
      isCheckoutSubmitDisabled(
        this.data.preview !== null,
        address,
        this.data.submitting
      )
    ) {
      return;
    }

    this.submitLocked = true;
    this.setData({ submitting: true });
    try {
      await ensureAppLogin();
      const response = await submitOrder(
        buildSubmitRequest(
          selection,
          address.id,
          this.data.preview?.userCouponId ?? null,
          this.data.idempotencyKey
        )
      );
      wx.redirectTo({
        url: `/pages/order/detail/detail?order_id=${response.orderId}`,
        fail: () => {
          this.submitLocked = false;
          this.setData({ submitting: false });
          wx.showToast({ title: "订单已创建，请到订单列表查看", icon: "none" });
        }
      });
    } catch (error) {
      this.submitLocked = false;
      this.setData({ submitting: false });
      wx.showToast({
        title: error instanceof Error ? error.message : "提交失败",
        icon: "none"
      });
    }
  }
});
