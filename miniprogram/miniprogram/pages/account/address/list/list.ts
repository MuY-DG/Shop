import { parseAddressId } from "../../../../features/account-center";
import { getAddresses } from "../../../../services/address";
import type { AddressResponse } from "../../../../types/checkout";
import { isApiError } from "../../../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: string;
    };
  };
}

interface AddressListItem extends AddressResponse {
  detailDisplay: string;
  phoneDisplay: string;
}

let latestRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function maskPhone(value: string): string {
  const normalized = value.trim();
  return /^\d{11}$/.test(normalized)
    ? `${normalized.slice(0, 3)}****${normalized.slice(-4)}`
    : normalized;
}

function addressListItem(address: AddressResponse): AddressListItem {
  return {
    ...address,
    detailDisplay: address.detailAddress.trim() || address.formattedAddress.trim(),
    phoneDisplay: maskPhone(address.receiverPhone)
  };
}

Page({
  data: {
    addresses: [] as AddressListItem[],
    loading: true,
    loaded: false,
    errorText: ""
  },

  onShow() {
    void this.loadAddresses();
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
        addresses: addresses.map(addressListItem),
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
  }
});
