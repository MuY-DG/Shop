import {
  buildCheckoutAddressView,
  buildCouponOptionViews,
  buildOrderPreviewView,
  buildPreviewRequest,
  buildStockShortageItemViews,
  buildSubmitRequest,
  createIdempotencyKey,
  parseCheckoutQuery,
  resolveAddressSelection,
  type CheckoutAddressView,
  type CouponOptionView,
  type OrderPreviewView
} from "../../../features/checkout";
import { isStockShortageError } from "../../../features/cart-feedback";
import { executeOrderPayment } from "../../../features/order-payment";
import { buildOrderDetailUrl } from "../../../features/order-center";
import { getAddresses } from "../../../services/address";
import { getAvailableCoupons } from "../../../services/coupon";
import { getCartItems } from "../../../services/cart";
import { previewOrder, submitOrder } from "../../../services/order";
import type { CheckoutSelection } from "../../../types/checkout";
import type { StockShortageItemView } from "../../../features/checkout";
import { isApiError } from "../../../utils/api-error";

type CouponSheetTab = "available" | "unavailable";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: string;
      index?: number | string;
      tab?: CouponSheetTab;
    };
  };
}

let latestPreviewRequest = 0;
let latestCouponRequest = 0;
let latestAddressRequest = 0;

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

function couponViewState(coupons: CouponOptionView[]) {
  const availableCoupons = coupons.filter((coupon) => coupon.available);
  return {
    coupons,
    availableCoupons,
    unavailableCoupons: coupons.filter((coupon) => !coupon.available),
    couponAvailableCount: availableCoupons.length
  };
}

Page({
  data: {
    selection: null as CheckoutSelection | null,
    addresses: [] as CheckoutAddressView[],
    selectedAddress: null as CheckoutAddressView | null,
    preview: null as OrderPreviewView | null,
    coupons: [] as CouponOptionView[],
    availableCoupons: [] as CouponOptionView[],
    unavailableCoupons: [] as CouponOptionView[],
    couponAvailableCount: 0,
    idempotencyKey: "",
    loading: true,
    loaded: false,
    errorText: "",
    addressSheetOpen: false,
    addressLoading: false,
    addressLoaded: false,
    addressErrorText: "",
    addressEditorOpen: false,
    couponSheetOpen: false,
    couponSheetTab: "available" as CouponSheetTab,
    couponLoading: false,
    couponLoaded: false,
    couponErrorText: "",
    couponSelectingId: 0,
    submitting: false,
    stockShortageOpen: false,
    stockShortageItems: [] as StockShortageItemView[]
  },

  onLoad(query: Record<string, string | undefined>) {
    try {
      this.setData({
        selection: parseCheckoutQuery(query),
        idempotencyKey: createIdempotencyKey()
      });
      void this.refreshCheckout();
    } catch (error) {
      this.setData({
        loading: false,
        loaded: false,
        errorText: actionError(error, "结算参数无效")
      });
    }
  },

  onUnload() {
    latestPreviewRequest += 1;
    latestCouponRequest += 1;
    latestAddressRequest += 1;
  },

  onShow() {
    if (!this.data.addressEditorOpen) {
      return;
    }
    this.setData({ addressEditorOpen: false });
    void this.reloadAddressesAfterEdit();
  },

  onRetry() {
    void this.refreshCheckout();
  },

  async refreshCheckout() {
    const selection = this.data.selection;
    if (!selection) {
      return;
    }
    const requestId = ++latestPreviewRequest;
    this.setData({
      loading: true,
      errorText: "",
      addressLoading: true,
      addressErrorText: "",
      couponLoading: true,
      couponErrorText: "",
      stockShortageOpen: false,
      stockShortageItems: []
    });
    try {
      const couponResultPromise = getAvailableCoupons(selection)
        .then((response) => ({ response, errorText: "" }))
        .catch((error: unknown) => ({
          response: null,
          errorText: actionError(error, "优惠券加载失败，请重试")
        }));
      const addresses = (await getAddresses()).map(buildCheckoutAddressView);
      const selectedAddress = resolveAddressSelection(addresses, this.data.selectedAddress);
      const response = await previewOrder(buildPreviewRequest(
        selection,
        selectedAddress?.id
      ));
      const couponResult = await couponResultPromise;
      if (requestId !== latestPreviewRequest) {
        return;
      }
      const coupons = couponResult.response
        ? buildCouponOptionViews(couponResult.response.coupons, response.userCouponId)
        : [];
      this.setData({
        addresses,
        selectedAddress,
        preview: buildOrderPreviewView(response),
        ...couponViewState(coupons),
        couponLoading: false,
        couponLoaded: couponResult.response !== null,
        couponErrorText: couponResult.errorText,
        addressLoading: false,
        addressLoaded: true,
        addressErrorText: "",
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId !== latestPreviewRequest) {
        return;
      }
      if (await this.showCartStockShortage(error)) {
        return;
      }
      this.setData({
        loading: false,
        addressLoading: false,
        addressLoaded: this.data.addresses.length > 0,
        addressErrorText: actionError(error, "收货地址加载失败，请稍后重试"),
        couponLoading: false,
        loaded: this.data.preview !== null,
        errorText: actionError(error, "订单预览加载失败，请稍后重试")
      });
    }
  },

  onAddressTap() {
    if (!this.data.loading && !this.data.submitting) {
      this.setData({ addressSheetOpen: true });
    }
  },

  onAddressSheetClose() {
    this.setData({ addressSheetOpen: false });
  },

  onAddressSelect(event: DatasetEvent) {
    const addressId = String(event.currentTarget.dataset.id || "");
    const selectedAddress = this.data.addresses.find((address) => address.id === addressId);
    if (!selectedAddress) {
      return;
    }
    this.setData({
      selectedAddress,
      addressSheetOpen: false
    });
    void this.reloadPreviewForAddress(selectedAddress);
  },

  async reloadPreviewForAddress(selectedAddress: CheckoutAddressView) {
    const selection = this.data.selection;
    if (!selection) {
      return;
    }
    const requestId = ++latestPreviewRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const response = await previewOrder(buildPreviewRequest(
        selection,
        selectedAddress.id,
        this.data.preview?.userCouponId
      ));
      if (requestId !== latestPreviewRequest) {
        return;
      }
      const coupons = buildCouponOptionViews(this.data.coupons, response.userCouponId);
      this.setData({
        selectedAddress,
        preview: buildOrderPreviewView(response),
        ...couponViewState(coupons),
        loading: false,
        loaded: true
      });
    } catch (error) {
      if (requestId === latestPreviewRequest) {
        if (await this.showCartStockShortage(error)) {
          return;
        }
        this.setData({
          loading: false,
          errorText: actionError(error, "地址切换失败，请重试")
        });
      }
    }
  },

  onAddressRetry() {
    if (!this.data.addressLoading) {
      void this.reloadAddressOptions();
    }
  },

  async reloadAddressOptions() {
    const requestId = ++latestAddressRequest;
    const previousAddress = this.data.selectedAddress;
    this.setData({ addressLoading: true, addressErrorText: "" });
    try {
      const addresses = (await getAddresses()).map(buildCheckoutAddressView);
      if (requestId !== latestAddressRequest) {
        return;
      }
      const selectedAddress = resolveAddressSelection(addresses, previousAddress);
      this.setData({
        addresses,
        selectedAddress,
        addressLoading: false,
        addressLoaded: true,
        addressErrorText: ""
      });
      if (selectedAddress && selectedAddress.id !== previousAddress?.id) {
        await this.reloadPreviewForAddress(selectedAddress);
      }
    } catch (error) {
      if (requestId === latestAddressRequest) {
        this.setData({
          addressLoading: false,
          addressLoaded: this.data.addresses.length > 0,
          addressErrorText: actionError(error, "收货地址加载失败，请稍后重试")
        });
      }
    }
  },

  onAddAddress() {
    if (this.data.addressEditorOpen || this.data.submitting) {
      return;
    }
    this.setData({ addressEditorOpen: true });
    wx.navigateTo({
      url: "/pages/account/address/edit/edit",
      fail: () => {
        this.setData({ addressEditorOpen: false });
        wx.showToast({ title: "暂时无法打开新增地址页", icon: "none" });
      }
    });
  },

  async reloadAddressesAfterEdit() {
    const requestId = ++latestAddressRequest;
    const previousIds = new Set(this.data.addresses.map((address) => address.id));
    const previousAddress = this.data.selectedAddress;
    this.setData({ addressLoading: true, addressErrorText: "" });
    try {
      const addresses = (await getAddresses()).map(buildCheckoutAddressView);
      if (requestId !== latestAddressRequest) {
        return;
      }
      const addedAddress = addresses.find((address) => !previousIds.has(address.id));
      const selectedAddress = addedAddress ||
        resolveAddressSelection(addresses, previousAddress);
      this.setData({
        addresses,
        selectedAddress,
        addressLoading: false,
        addressLoaded: true,
        addressErrorText: ""
      });
      if (selectedAddress && selectedAddress.id !== previousAddress?.id) {
        await this.reloadPreviewForAddress(selectedAddress);
      }
    } catch (error) {
      if (requestId === latestAddressRequest) {
        const addressErrorText = actionError(
          error,
          "新增地址后刷新失败，请点击重试"
        );
        this.setData({
          addressLoading: false,
          addressLoaded: this.data.addresses.length > 0,
          addressErrorText
        });
        wx.showToast({ title: addressErrorText, icon: "none" });
      }
    }
  },

  onCouponTap() {
    if (!this.data.submitting && this.data.preview) {
      this.setData({
        couponSheetOpen: true,
        couponSheetTab: "available"
      });
    }
  },

  onCouponTabTap(event: DatasetEvent) {
    const couponSheetTab = event.currentTarget.dataset.tab;
    if (
      (couponSheetTab === "available" || couponSheetTab === "unavailable") &&
      couponSheetTab !== this.data.couponSheetTab
    ) {
      this.setData({ couponSheetTab });
    }
  },

  onCouponSheetClose() {
    if (!this.data.couponSelectingId) {
      this.setData({ couponSheetOpen: false });
    }
  },

  onCouponRetry() {
    void this.reloadCoupons();
  },

  async reloadCoupons() {
    const selection = this.data.selection;
    if (!selection || this.data.couponLoading) {
      return;
    }
    const requestId = ++latestCouponRequest;
    this.setData({ couponLoading: true, couponErrorText: "" });
    try {
      const response = await getAvailableCoupons(selection);
      if (requestId !== latestCouponRequest) {
        return;
      }
      const coupons = buildCouponOptionViews(
        response.coupons,
        this.data.preview?.userCouponId
      );
      this.setData({
        ...couponViewState(coupons),
        couponLoading: false,
        couponLoaded: true,
        couponErrorText: ""
      });
    } catch (error) {
      if (requestId === latestCouponRequest) {
        this.setData({
          couponLoading: false,
          couponLoaded: false,
          couponErrorText: actionError(error, "优惠券加载失败，请重试")
        });
      }
    }
  },

  async onCouponSelect(event: DatasetEvent) {
    const userCouponId = Number(event.currentTarget.dataset.id);
    const coupon = this.data.coupons.find(
      (item) => item.userCouponId === userCouponId
    );
    const selection = this.data.selection;
    if (
      !selection ||
      !coupon ||
      !coupon.available ||
      this.data.couponSelectingId ||
      this.data.submitting
    ) {
      return;
    }
    if (coupon.selected) {
      this.setData({ couponSheetOpen: false });
      return;
    }
    const requestId = ++latestPreviewRequest;
    this.setData({ couponSelectingId: userCouponId });
    try {
      const response = await previewOrder(buildPreviewRequest(
        selection,
        this.data.selectedAddress?.id,
        userCouponId
      ));
      if (requestId !== latestPreviewRequest) {
        return;
      }
      const coupons = buildCouponOptionViews(this.data.coupons, response.userCouponId);
      this.setData({
        preview: buildOrderPreviewView(response),
        ...couponViewState(coupons),
        couponSheetOpen: false,
        couponSelectingId: 0
      });
    } catch (error) {
      if (requestId === latestPreviewRequest) {
        if (await this.showCartStockShortage(error)) {
          return;
        }
        this.setData({ couponSelectingId: 0 });
        wx.showToast({
          title: actionError(error, "优惠券选择失败，请重试"),
          icon: "none"
        });
      }
    }
  },

  onPreviewImageError(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isSafeInteger(index) || index < 0 || !this.data.preview) {
      return;
    }
    this.setData({
      preview: {
        ...this.data.preview,
        items: this.data.preview.items.map((item, itemIndex) => (
          itemIndex === index ? { ...item, hasImage: false } : item
        ))
      }
    });
  },

  onStockShortageImageError(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isSafeInteger(index) || index < 0) {
      return;
    }
    this.setData({
      stockShortageItems: this.data.stockShortageItems.map((item, itemIndex) => (
        itemIndex === index ? { ...item, hasImage: false } : item
      ))
    });
  },

  async showCartStockShortage(error: unknown): Promise<boolean> {
    const selection = this.data.selection;
    if (selection?.source !== "CART" || !isStockShortageError(error)) {
      return false;
    }
    try {
      const cart = await getCartItems();
      const stockShortageItems = buildStockShortageItemViews(
        cart.items,
        selection.cartItemIds
      );
      if (!stockShortageItems.length) {
        return false;
      }
      this.setData({
        loading: false,
        loaded: true,
        errorText: "",
        addressLoading: false,
        couponLoading: false,
        submitting: false,
        couponSelectingId: 0,
        couponSheetOpen: false,
        addressSheetOpen: false,
        stockShortageOpen: true,
        stockShortageItems
      });
      return true;
    } catch {
      return false;
    }
  },

  onStockShortageBackTap() {
    wx.navigateBack({
      delta: 1,
      fail: () => wx.switchTab({ url: "/pages/cart/cart" })
    });
  },

  onPayTap() {
    if (
      !this.data.selection ||
      !this.data.preview ||
      !this.data.selectedAddress ||
      this.data.loading ||
      this.data.submitting
    ) {
      if (!this.data.selectedAddress) {
        this.setData({ addressSheetOpen: true });
      }
      return;
    }
    void this.submitAndPay();
  },

  async submitAndPay() {
    const selection = this.data.selection;
    const address = this.data.selectedAddress;
    const preview = this.data.preview;
    if (!selection || !address || !preview || this.data.submitting) {
      return;
    }
    this.setData({ submitting: true });
    try {
      const response = await submitOrder(buildSubmitRequest(
        selection,
        address.id,
        preview.userCouponId,
        this.data.idempotencyKey
      ));
      let paymentStatus = "PENDING";
      try {
        const outcome = await executeOrderPayment(Number(response.orderId));
        paymentStatus = outcome === "PAID" ? "PAID" : "PENDING";
      } catch (error) {
        wx.showToast({
          title: actionError(error, "支付未完成，可在待付款订单中继续支付"),
          icon: "none"
        });
      }
      const successQuery = [
        `order_id=${encodeURIComponent(String(response.orderId))}`,
        `order_no=${encodeURIComponent(response.orderNo)}`,
        `amount=${encodeURIComponent(String(response.payableAmountCent))}`,
        "payment_status=PAID"
      ].join("&");
      const targetUrl = paymentStatus === "PAID"
        ? `/pages/order/created/created?${successQuery}`
        : buildOrderDetailUrl(Number(response.orderId));
      wx.redirectTo({
        url: targetUrl,
        fail: () => {
          this.setData({ submitting: false });
          wx.showToast({ title: "订单已创建，请到订单中心查看", icon: "none" });
        }
      });
    } catch (error) {
      if (await this.showCartStockShortage(error)) {
        return;
      }
      this.setData({ submitting: false });
      wx.showToast({
        title: actionError(error, "订单提交失败，请重试"),
        icon: "none"
      });
    }
  },

  onSheetTouchMove() {
    // 阻止底层页面随地址弹层一起滚动。
  }
});
