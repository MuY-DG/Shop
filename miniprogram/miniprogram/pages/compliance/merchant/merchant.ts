import type { MerchantPublicationView } from "../../../features/compliance";
import { normalizeContactPhone } from "../../../features/contact";
import { getCurrentMerchantPublication } from "../../../services/compliance";
import { isApiError } from "../../../utils/api-error";
import { enableNativeShareMenu } from "../../../utils/share";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      kind?: string;
      phone?: string;
    };
  };
}

let latestRequest = 0;

function errorMessage(error: unknown): string {
  return isApiError(error)
    ? error.message
    : "商家经营资质加载失败，请稍后重试";
}

Page({
  data: {
    loading: true,
    loaded: false,
    errorText: "",
    publication: null as MerchantPublicationView | null
  },

  onLoad() {
    enableNativeShareMenu();
    void this.loadPublication();
  },

  onUnload() {
    latestRequest += 1;
  },

  onRetry() {
    void this.loadPublication();
  },

  async loadPublication() {
    const requestId = ++latestRequest;
    this.setData({ loading: true, loaded: false, errorText: "", publication: null });
    try {
      const publication = await getCurrentMerchantPublication();
      if (requestId === latestRequest) {
        this.setData({ loading: false, loaded: true, publication });
      }
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({
          loading: false,
          loaded: false,
          errorText: errorMessage(error),
          publication: null
        });
      }
    }
  },

  onPhoneTap(event: DatasetEvent) {
    const phoneNumber = event.currentTarget.dataset.phone?.trim() || "";
    const publication = this.data.publication;
    if (
      !publication
      || (phoneNumber !== publication.customerServicePhone
        && phoneNumber !== publication.complaintPhone)
    ) {
      return;
    }
    const callablePhone = normalizeContactPhone(phoneNumber);
    if (!callablePhone) {
      wx.showToast({ title: "联系电话暂不可用", icon: "none" });
      return;
    }
    wx.makePhoneCall({ phoneNumber: callablePhone });
  },

  onLicenseTap(event: DatasetEvent) {
    const publication = this.data.publication;
    if (!publication) {
      return;
    }
    const url = event.currentTarget.dataset.kind === "food"
      ? publication.foodQualificationUrl
      : event.currentTarget.dataset.kind === "business"
        ? publication.businessLicenseUrl
        : "";
    if (url) {
      wx.previewImage({ urls: [url], current: url });
    }
  },

  onShareAppMessage() {
    return {
      title: "MuYbaby商家经营资质",
      path: "/pages/compliance/merchant/merchant"
    };
  },

  onShareTimeline() {
    return { title: "MuYbaby商家经营资质" };
  }
});
