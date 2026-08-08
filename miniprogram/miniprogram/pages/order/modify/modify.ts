import { createPageOperationGuard } from "../../../features/order-center";
import { getAddresses } from "../../../services/address";
import {
  getOrderDetail,
  updateOrderReceiver
} from "../../../services/order";
import type { AppOrderDetailResponse } from "../../../types/order";
import { isApiError } from "../../../utils/api-error";
import {
  buildCurrentReceiverView,
  buildOrderAddressOptions,
  buildSelectedReceiverView,
  canModifyOrderReceiver,
  normalizeSelectedAddressId,
  parseModifyOrderId,
  resolveCurrentOrderAddressId,
  type OrderAddressOptionView,
  type OrderReceiverView
} from "./model";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: string | number;
    };
  };
}

interface RefreshOptions {
  silent?: boolean;
  suppressError?: boolean;
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

Page({
  data: {
    lifecycleToken: 0,
    orderId: 0,
    detail: null as AppOrderDetailResponse | null,
    currentReceiver: null as OrderReceiverView | null,
    addressOptions: [] as OrderAddressOptionView[],
    selectedAddressId: "",
    addressChanged: false,
    addressSheetOpen: false,
    contentRefreshing: false,
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
      void this.loadPage(this.data.loaded
        ? { silent: true, suppressError: true }
        : {});
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

  async onContentRefresh() {
    if (this.data.contentRefreshing || this.data.loading) {
      return;
    }
    this.setData({ contentRefreshing: true });
    try {
      await this.loadPage({ silent: true });
    } finally {
      this.setData({ contentRefreshing: false });
    }
  },

  onRetry() {
    void this.loadPage();
  },

  async loadPage(options: RefreshOptions = {}) {
    if (!this.data.orderId || this.data.saving) {
      return;
    }
    const requestId = ++latestRequest;
    const silent = options.silent === true && this.data.loaded;
    if (silent) {
      if (!options.suppressError) {
        this.setData({ errorText: "", blockingText: "" });
      }
    } else {
      this.setData({ loading: true, errorText: "", blockingText: "" });
    }
    try {
      const [detail, addresses] = await Promise.all([
        getOrderDetail(this.data.orderId),
        getAddresses()
      ]);
      if (requestId !== latestRequest) {
        return;
      }
      const modifiable = canModifyOrderReceiver(detail.status);
      const retainedAddressId = this.data.addressChanged && addresses.some(
        (address) => address.id === this.data.selectedAddressId
      ) ? this.data.selectedAddressId : "";
      const unselectedAddressOptions = buildOrderAddressOptions(addresses, "");
      const selectedAddressId = retainedAddressId
        || resolveCurrentOrderAddressId(detail, unselectedAddressOptions);
      const addressOptions = buildOrderAddressOptions(addresses, selectedAddressId);
      const selectedAddress = addressOptions.find(
        (address) => address.id === selectedAddressId
      );
      this.setData({
        detail,
        currentReceiver: retainedAddressId && selectedAddress
          ? buildSelectedReceiverView(selectedAddress)
          : buildCurrentReceiverView(detail, addressOptions),
        addressOptions,
        selectedAddressId,
        addressChanged: Boolean(retainedAddressId),
        loading: false,
        loaded: true,
        errorText: "",
        blockingText: modifiable ? "" : "只有待付款或待发货订单可以修改收货地址"
      });
    } catch (error) {
      if (requestId === latestRequest) {
        if (silent && options.suppressError) {
          return;
        }
        this.setData({
          loading: false,
          loaded: this.data.detail !== null,
          errorText: actionError(error, "订单和地址加载失败，请稍后重试")
        });
      }
    }
  },

  onAddressSheetOpen() {
    if (!this.data.saving && !this.data.blockingText) {
      this.setData({ addressSheetOpen: true });
    }
  },

  onAddressSheetClose() {
    this.setData({ addressSheetOpen: false });
  },

  onAddressSelect(event: DatasetEvent) {
    if (this.data.saving || this.data.blockingText) {
      return;
    }
    const selectedAddressId = normalizeSelectedAddressId(event.currentTarget.dataset.id);
    const selectedAddress = this.data.addressOptions.find(
      (address) => address.id === selectedAddressId
    );
    if (!selectedAddress) {
      return;
    }
    this.setData({
      selectedAddressId,
      addressChanged: true,
      currentReceiver: buildSelectedReceiverView(selectedAddress),
      addressOptions: buildOrderAddressOptions(
        this.data.addressOptions,
        selectedAddressId
      ),
      addressSheetOpen: false
    });
  },

  onAddAddress() {
    if (!this.data.saving) {
      wx.navigateTo({ url: "/pages/account/address/edit/edit" });
    }
  },

  onConfirmTap() {
    if (this.data.saving || this.data.blockingText) {
      return;
    }
    if (!normalizeSelectedAddressId(this.data.selectedAddressId)) {
      wx.showModal({
        title: "请选择收货地址",
        content: "当前订单地址未匹配到已保存地址，请先切换地址后再确认修改。",
        showCancel: false,
        confirmText: "去选择",
        confirmColor: "#ff172b",
        success: (result) => {
          if (result.confirm) {
            this.onAddressSheetOpen();
          }
        }
      });
      return;
    }
    wx.showModal({
      title: "确认修改",
      content: "确认将订单收货信息修改为当前显示的地址吗？",
      cancelText: "取消",
      confirmText: "确认修改",
      confirmColor: "#ff172b",
      success: (result) => {
        if (result.confirm) {
          void this.saveReceiver();
        }
      }
    });
  },

  onSheetTouchMove() {
    // 阻止底层页面随地址弹层一起滚动。
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
        addressChanged: false,
        currentReceiver: buildCurrentReceiverView(response, this.data.addressOptions)
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
