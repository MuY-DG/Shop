import type { AddressResponse } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import {
  deleteAddress,
  getAddresses,
  setDefaultAddress
} from "../../../services/address";
import {
  createLatestRequestTracker,
  type LatestRequestTracker
} from "../../../features/checkout";

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface AddressListData {
  selectionMode: boolean;
  loading: boolean;
  errorText: string;
  addresses: AddressResponse[];
  settingDefaultId: number;
  deletingId: number;
}

Page<AddressListData, WechatMiniprogram.Page.CustomOption>({
  addressLoadTracker: createLatestRequestTracker() as LatestRequestTracker,
  data: {
    selectionMode: false,
    loading: false,
    errorText: "",
    addresses: [] as AddressResponse[],
    settingDefaultId: 0,
    deletingId: 0
  },
  onLoad(options: Record<string, string | undefined>) {
    this.addressLoadTracker = createLatestRequestTracker();
    const selectionMode = options.mode === "select";
    this.setData({ selectionMode });
    if (selectionMode) {
      wx.setNavigationBarTitle({ title: "选择收货地址" });
    }
  },
  async onShow() {
    await this.loadAddresses();
  },
  async onPullDownRefresh() {
    await this.loadAddresses();
    wx.stopPullDownRefresh();
  },
  async loadAddresses() {
    const requestToken = this.addressLoadTracker.begin();
    this.setData({ loading: true, errorText: "" });
    try {
      await ensureAppLogin();
      const addresses = await getAddresses();
      if (this.addressLoadTracker.isLatest(requestToken)) {
        this.setData({ addresses });
      }
    } catch (error) {
      if (this.addressLoadTracker.isLatest(requestToken)) {
        this.setData({
          errorText: error instanceof Error ? error.message : "地址加载失败"
        });
      }
    } finally {
      if (this.addressLoadTracker.isLatest(requestToken)) {
        this.setData({ loading: false });
      }
    }
  },
  onRetryTap() {
    void this.loadAddresses();
  },
  onCreateTap() {
    wx.navigateTo({ url: "/pages/address/edit/edit" });
  },
  onAddressTap(event: DatasetEvent) {
    const selected = this.findAddress(event);
    if (!selected) {
      return;
    }
    if (this.data.selectionMode) {
      const eventChannel = this.getOpenerEventChannel();
      if (!eventChannel.emit) {
        wx.showToast({ title: "无法返回地址，请重试", icon: "none" });
        return;
      }
      eventChannel.emit("addressSelected", { ...selected });
      wx.navigateBack();
      return;
    }
    wx.navigateTo({ url: `/pages/address/edit/edit?id=${selected.id}` });
  },
  onEditTap(event: DatasetEvent) {
    const selected = this.findAddress(event);
    if (selected) {
      wx.navigateTo({ url: `/pages/address/edit/edit?id=${selected.id}` });
    }
  },
  async onDefaultTap(event: DatasetEvent) {
    const selected = this.findAddress(event);
    if (!selected || selected.isDefault || this.data.settingDefaultId > 0) {
      return;
    }
    this.setData({ settingDefaultId: selected.id });
    try {
      await ensureAppLogin();
      await setDefaultAddress(selected.id);
      await this.loadAddresses();
      wx.showToast({ title: "已设为默认", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "设置失败",
        icon: "none"
      });
    } finally {
      this.setData({ settingDefaultId: 0 });
    }
  },
  onDeleteTap(event: DatasetEvent) {
    const selected = this.findAddress(event);
    if (!selected || this.data.deletingId > 0) {
      return;
    }
    wx.showModal({
      title: "删除收货地址",
      content: `确认删除 ${selected.receiverName} 的地址吗？`,
      confirmColor: "#b3261e",
      success: (result) => {
        if (result.confirm) {
          void this.deleteConfirmed(selected.id);
        }
      }
    });
  },
  async deleteConfirmed(addressId: number) {
    if (this.data.deletingId > 0) {
      return;
    }
    this.setData({ deletingId: addressId });
    try {
      await ensureAppLogin();
      await deleteAddress(addressId);
      await this.loadAddresses();
      wx.showToast({ title: "已删除", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "删除失败",
        icon: "none"
      });
    } finally {
      this.setData({ deletingId: 0 });
    }
  },
  findAddress(event: DatasetEvent): AddressResponse | undefined {
    const addressId = Number(event.currentTarget.dataset.id);
    if (!Number.isSafeInteger(addressId) || addressId <= 0) {
      return undefined;
    }
    return this.data.addresses.find((address) => address.id === addressId);
  }
});
