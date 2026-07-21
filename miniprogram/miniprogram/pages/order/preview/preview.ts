import {
  buildCouponOptionViews,
  buildOrderPreviewView,
  buildPreviewRequest,
  buildSubmitRequest,
  createIdempotencyKey,
  parseCheckoutQuery,
  resolveAddressSelection,
  type CouponOptionView,
  type OrderPreviewView
} from "../../../features/checkout";
import { executeOrderPayment } from "../../../features/order-payment";
import { buildOrderDetailUrl } from "../../../features/order-center";
import { createAddress, getAddresses } from "../../../services/address";
import { getAvailableCoupons } from "../../../services/coupon";
import { previewOrder, submitOrder } from "../../../services/order";
import type {
  AddressResponse,
  AddressUpsertRequest,
  CheckoutSelection
} from "../../../types/checkout";
import { isApiError } from "../../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: string;
      index?: number | string;
    };
  };
}

let latestPreviewRequest = 0;
let latestCouponRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function chooseWechatAddress(): Promise<WechatMiniprogram.ChooseAddressSuccessCallbackResult> {
  return new Promise((resolve, reject) => {
    wx.chooseAddress({
      success: resolve,
      fail: reject
    });
  });
}

function addressPayload(
  address: WechatMiniprogram.ChooseAddressSuccessCallbackResult,
  isDefault: boolean
): AddressUpsertRequest {
  const province = address.provinceName.trim();
  const city = address.cityName.trim() || province;
  const district = address.countyName.trim() || city;
  return {
    receiverName: address.userName.trim(),
    receiverPhone: address.telNumber.trim(),
    province,
    city,
    district,
    detailAddress: address.detailInfo.trim(),
    isDefault
  };
}

Page({
  data: {
    selection: null as CheckoutSelection | null,
    addresses: [] as AddressResponse[],
    selectedAddress: null as AddressResponse | null,
    preview: null as OrderPreviewView | null,
    coupons: [] as CouponOptionView[],
    couponAvailableCount: 0,
    idempotencyKey: "",
    loading: true,
    loaded: false,
    errorText: "",
    addressSheetOpen: false,
    couponSheetOpen: false,
    couponLoading: false,
    couponLoaded: false,
    couponErrorText: "",
    couponSelectingId: 0,
    importingAddress: false,
    submitting: false
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
  },

  async onPullDownRefresh() {
    await this.refreshCheckout();
    wx.stopPullDownRefresh();
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
      couponLoading: true,
      couponErrorText: ""
    });
    try {
      const couponResultPromise = getAvailableCoupons(selection)
        .then((response) => ({ response, errorText: "" }))
        .catch((error: unknown) => ({
          response: null,
          errorText: actionError(error, "优惠券加载失败，请重试")
        }));
      const addresses = await getAddresses();
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
        coupons,
        couponAvailableCount: coupons.filter((coupon) => coupon.available).length,
        couponLoading: false,
        couponLoaded: couponResult.response !== null,
        couponErrorText: couponResult.errorText,
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId !== latestPreviewRequest) {
        return;
      }
      this.setData({
        loading: false,
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
    if (!this.data.importingAddress) {
      this.setData({ addressSheetOpen: false });
    }
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

  async reloadPreviewForAddress(selectedAddress: AddressResponse) {
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
      this.setData({
        selectedAddress,
        preview: buildOrderPreviewView(response),
        coupons: buildCouponOptionViews(this.data.coupons, response.userCouponId),
        loading: false,
        loaded: true
      });
    } catch (error) {
      if (requestId === latestPreviewRequest) {
        this.setData({
          loading: false,
          errorText: actionError(error, "地址切换失败，请重试")
        });
      }
    }
  },

  onCouponTap() {
    if (!this.data.submitting && this.data.preview) {
      this.setData({ couponSheetOpen: true });
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
        coupons,
        couponAvailableCount: coupons.filter((coupon) => coupon.available).length,
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
      this.setData({
        preview: buildOrderPreviewView(response),
        coupons: buildCouponOptionViews(this.data.coupons, response.userCouponId),
        couponSheetOpen: false,
        couponSelectingId: 0
      });
    } catch (error) {
      if (requestId === latestPreviewRequest) {
        this.setData({ couponSelectingId: 0 });
        wx.showToast({
          title: actionError(error, "优惠券选择失败，请重试"),
          icon: "none"
        });
      }
    }
  },

  async onImportAddress() {
    if (this.data.importingAddress || this.data.submitting) {
      return;
    }
    this.setData({ importingAddress: true });
    try {
      const wechatAddress = await chooseWechatAddress();
      const created = await createAddress(addressPayload(
        wechatAddress,
        this.data.addresses.length === 0
      ));
      const addresses = [
        created,
        ...this.data.addresses.filter((address) => address.id !== created.id)
      ];
      this.setData({
        addresses,
        selectedAddress: created,
        addressSheetOpen: false,
        importingAddress: false
      });
      await this.reloadPreviewForAddress(created);
      wx.showToast({ title: "地址已保存", icon: "success" });
    } catch (error) {
      this.setData({ importingAddress: false });
      const message = error instanceof Error
        ? error.message
        : typeof error === "object" && error !== null && "errMsg" in error
          ? String(error.errMsg)
          : "";
      if (!message.toLowerCase().includes("cancel")) {
        wx.showToast({
          title: actionError(error, "地址导入失败，请重试"),
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
