import { createPageOperationGuard } from "../../../features/order-center";
import { getAddresses } from "../../../services/address";
import {
  getOrderDetail,
  updateOrderReceiver
} from "../../../services/order";
import type { AppOrderDetailResponse } from "../../../types/order";
import { isApiError } from "../../../utils/api-error";
import {
  buildOrderAddressOptions,
  canModifyOrderReceiver,
  maskReceiverPhone,
  normalizeSelectedAddressId,
  parseModifyOrderId,
  type OrderAddressOptionView
} from "./model";

interface RadioChangeEvent {
  detail: {
    value: string;
  };
}

interface CurrentReceiverView {
  receiverName: string;
  receiverPhoneDisplay: string;
  receiverAddress: string;
}

let latestRequest = 0;
let leaveTimer: ReturnType<typeof setTimeout> | null = null;
const modifyOperationGuard = createPageOperationGuard();

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function currentReceiver(detail: AppOrderDetailResponse): CurrentReceiverView {
  return {
    receiverName: detail.receiverName.trim(),
    receiverPhoneDisplay: maskReceiverPhone(detail.receiverPhone),
    receiverAddress: detail.receiverAddress.trim()
  };
}

Page({
  data: {
    lifecycleToken: 0,
    orderId: 0,
    detail: null as AppOrderDetailResponse | null,
    currentReceiver: null as CurrentReceiverView | null,
    addressOptions: [] as OrderAddressOptionView[],
    selectedAddressId: "",
    loading: true,
    loaded: false,
    saving: false,
    errorText: "",
    blockingText: ""
  },

  onLoad(query: Record<string, string | undefined>) {
    const lifecycleToken = modifyOperationGuard.mount();
    const orderId = parseModifyOrderId(query.order_id);
    if (!orderId) {
      this.setData({
        lifecycleToken,
        loading: false,
        errorText: "订单参数无效"
      });
      return;
    }
    this.setData({ lifecycleToken, orderId });
  },

  onShow() {
    if (this.data.orderId && !this.data.saving) {
      void this.loadPage();
    }
  },

  onUnload() {
    modifyOperationGuard.unmount(this.data.lifecycleToken);
    latestRequest += 1;
    if (leaveTimer !== null) {
      clearTimeout(leaveTimer);
      leaveTimer = null;
    }
  },

  async onPullDownRefresh() {
    await this.loadPage();
    wx.stopPullDownRefresh();
  },

  onRetry() {
    void this.loadPage();
  },

  async loadPage() {
    if (!this.data.orderId || this.data.saving) {
      return;
    }
    const requestId = ++latestRequest;
    this.setData({ loading: true, errorText: "", blockingText: "" });
    try {
      const [detail, addresses] = await Promise.all([
        getOrderDetail(this.data.orderId),
        getAddresses()
      ]);
      if (requestId !== latestRequest) {
        return;
      }
      const modifiable = canModifyOrderReceiver(detail.status);
      const selectedAddressId = addresses.some(
        (address) => address.id === this.data.selectedAddressId
      )
        ? this.data.selectedAddressId
        : "";
      this.setData({
        detail,
        currentReceiver: currentReceiver(detail),
        addressOptions: buildOrderAddressOptions(addresses, selectedAddressId),
        selectedAddressId,
        loading: false,
        loaded: true,
        errorText: "",
        blockingText: modifiable ? "" : "只有待付款或待发货订单可以修改收货地址"
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({
          loading: false,
          loaded: this.data.detail !== null,
          errorText: actionError(error, "订单和地址加载失败，请稍后重试")
        });
      }
    }
  },

  onAddressChange(event: RadioChangeEvent) {
    if (this.data.saving || this.data.blockingText) {
      return;
    }
    const selectedAddressId = normalizeSelectedAddressId(event.detail.value);
    if (!selectedAddressId) {
      return;
    }
    this.setData({
      selectedAddressId,
      addressOptions: buildOrderAddressOptions(
        this.data.addressOptions,
        selectedAddressId
      )
    });
  },

  onAddAddress() {
    if (!this.data.saving) {
      wx.navigateTo({ url: "/pages/account/address/edit/edit" });
    }
  },

  onConfirmTap() {
    void this.saveReceiver();
  },

  async saveReceiver() {
    const detail = this.data.detail;
    const addressId = normalizeSelectedAddressId(this.data.selectedAddressId);
    if (
      !detail ||
      !addressId ||
      this.data.saving ||
      !canModifyOrderReceiver(detail.status)
    ) {
      if (!addressId) {
        wx.showToast({ title: "请选择新的收货地址", icon: "none" });
      }
      return;
    }
    const lifecycleToken = this.data.lifecycleToken;
    const operationToken = modifyOperationGuard.begin(lifecycleToken);
    if (!operationToken) {
      return;
    }
    this.setData({ saving: true });
    try {
      const response = await updateOrderReceiver(this.data.orderId, addressId);
      if (!modifyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
        return;
      }
      this.setData({
        saving: false,
        currentReceiver: {
          receiverName: response.receiverName.trim(),
          receiverPhoneDisplay: maskReceiverPhone(response.receiverPhone),
          receiverAddress: response.receiverAddress.trim()
        }
      });
      wx.showToast({ title: "收货地址已修改", icon: "success" });
      leaveTimer = setTimeout(() => {
        leaveTimer = null;
        if (modifyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
          this.leavePage();
        }
      }, 700);
    } catch (error) {
      if (!modifyOperationGuard.isCurrent(lifecycleToken, operationToken)) {
        return;
      }
      this.setData({ saving: false });
      wx.showToast({
        title: actionError(error, "收货地址修改失败，请稍后重试"),
        icon: "none"
      });
      await this.loadPage();
    }
  },

  onBackToOrder() {
    this.leavePage();
  },

  leavePage() {
    wx.navigateBack({
      delta: 1,
      fail: () => wx.redirectTo({
        url: `/pages/order/detail/detail?order_id=${this.data.orderId}`
      })
    });
  }
});
