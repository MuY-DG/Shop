import { parseAddressId } from "../../../../features/account-center";
import {
  deleteAddress,
  getAddresses,
  setDefaultAddress
} from "../../../../services/address";
import type { AddressResponse } from "../../../../types/checkout";
import { isApiError } from "../../../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: string;
    };
  };
}

let latestRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function confirmDelete(): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title: "删除地址",
      content: "删除后无法恢复，是否继续？",
      confirmText: "删除",
      confirmColor: "#B72B22",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    });
  });
}

Page({
  data: {
    addresses: [] as AddressResponse[],
    loading: true,
    loaded: false,
    errorText: "",
    actionAddressId: ""
  },

  onShow() {
    if (!this.data.actionAddressId) {
      void this.loadAddresses();
    }
  },

  onUnload() {
    latestRequest += 1;
  },

  async onPullDownRefresh() {
    await this.loadAddresses();
    wx.stopPullDownRefresh();
  },

  onRetry() {
    void this.loadAddresses();
  },

  async loadAddresses() {
    const requestId = ++latestRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const addresses = await getAddresses();
      if (requestId !== latestRequest) {
        return;
      }
      this.setData({
        addresses,
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({
          loading: false,
          loaded: this.data.addresses.length > 0,
          errorText: actionError(error, "地址加载失败，请稍后重试")
        });
      }
    }
  },

  onAddTap() {
    wx.navigateTo({ url: "/pages/account/address/edit/edit" });
  },

  onEditTap(event: DatasetEvent) {
    const addressId = parseAddressId(event.currentTarget.dataset.id);
    if (addressId) {
      wx.navigateTo({
        url: `/pages/account/address/edit/edit?id=${encodeURIComponent(addressId)}`
      });
    }
  },

  onDefaultTap(event: DatasetEvent) {
    const addressId = parseAddressId(event.currentTarget.dataset.id);
    if (addressId) {
      void this.setDefault(addressId);
    }
  },

  async setDefault(addressId: string) {
    if (this.data.actionAddressId) {
      return;
    }
    this.setData({ actionAddressId: addressId });
    try {
      const updated = await setDefaultAddress(addressId);
      this.setData({
        addresses: this.data.addresses.map((address) => ({
          ...address,
          isDefault: address.id === updated.id
        })),
        actionAddressId: ""
      });
      wx.showToast({ title: "已设为默认", icon: "success" });
    } catch (error) {
      this.setData({ actionAddressId: "" });
      wx.showToast({
        title: actionError(error, "设置失败，请重试"),
        icon: "none"
      });
    }
  },

  onDeleteTap(event: DatasetEvent) {
    const addressId = parseAddressId(event.currentTarget.dataset.id);
    if (addressId) {
      void this.removeAddress(addressId);
    }
  },

  async removeAddress(addressId: string) {
    if (this.data.actionAddressId || !await confirmDelete()) {
      return;
    }
    this.setData({ actionAddressId: addressId });
    try {
      await deleteAddress(addressId);
      this.setData({
        addresses: this.data.addresses.filter((address) => address.id !== addressId),
        actionAddressId: ""
      });
      wx.showToast({ title: "地址已删除", icon: "success" });
    } catch (error) {
      this.setData({ actionAddressId: "" });
      wx.showToast({
        title: actionError(error, "删除失败，请重试"),
        icon: "none"
      });
    }
  }
});
