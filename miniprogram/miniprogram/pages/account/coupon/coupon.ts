import {
  buildClaimableCouponViews,
  buildUserCouponViews,
  COUPON_STATUS_TABS,
  parseCouponStatusFilter,
  type CouponCardView,
  type CouponStatusFilter
} from "../../../features/account-center";
import { parsePositiveId } from "../../../features/product-catalog";
import {
  claimCoupon,
  getClaimableCoupons,
  getMyCoupons
} from "../../../services/coupon";
import type { UserCouponStatus } from "../../../types/coupon";
import { isApiError } from "../../../utils/api-error";

type CouponSection = "CLAIMABLE" | "MINE";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
      section?: string;
      status?: string;
    };
  };
}

interface RefreshOptions {
  silent?: boolean;
  suppressError?: boolean;
}

let latestRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function couponSection(value: unknown): CouponSection {
  return value === "MINE" ? "MINE" : "CLAIMABLE";
}

function emptyCopy(section: CouponSection, status: CouponStatusFilter): {
  title: string;
} {
  if (section === "CLAIMABLE") {
    return { title: "暂无可领取优惠券" };
  }
  if (status === "CLAIMED") {
    return { title: "暂无待使用优惠券" };
  }
  if (status === "USED") {
    return { title: "暂无已使用优惠券" };
  }
  if (status === "EXPIRED") {
    return { title: "暂无过期优惠券" };
  }
  return { title: "还没有优惠券" };
}

Page({
  data: {
    sections: [
      { value: "CLAIMABLE", label: "领券中心" },
      { value: "MINE", label: "我的优惠券" }
    ],
    statusTabs: COUPON_STATUS_TABS,
    activeSection: "CLAIMABLE" as CouponSection,
    activeStatus: "ALL" as CouponStatusFilter,
    coupons: [] as CouponCardView[],
    loading: true,
    loaded: false,
    contentRefreshing: false,
    errorText: "",
    emptyTitle: "暂无可领取优惠券",
    claimingTemplateId: 0
  },

  onLoad() {
    void this.loadCoupons();
  },

  onShow() {
    if (
      this.data.loaded
      && !this.data.loading
      && !this.data.contentRefreshing
      && !this.data.claimingTemplateId
    ) {
      void this.loadCoupons({ silent: true, suppressError: true });
    }
  },

  onUnload() {
    latestRequest += 1;
  },

  async onContentRefresh() {
    if (
      this.data.contentRefreshing
      || this.data.loading
      || this.data.claimingTemplateId
    ) {
      return;
    }
    this.setData({ contentRefreshing: true });
    try {
      await this.loadCoupons({ silent: true });
    } finally {
      this.setData({ contentRefreshing: false });
    }
  },

  onRetry() {
    void this.loadCoupons();
  },

  onSectionTap(event: DatasetEvent) {
    const activeSection = couponSection(event.currentTarget.dataset.section);
    if (activeSection === this.data.activeSection || this.data.loading || this.data.claimingTemplateId) {
      return;
    }
    const copy = emptyCopy(activeSection, this.data.activeStatus);
    this.setData({
      activeSection,
      coupons: [],
      loaded: false,
      errorText: "",
      emptyTitle: copy.title
    });
    void this.loadCoupons();
  },

  onStatusTap(event: DatasetEvent) {
    const activeStatus = parseCouponStatusFilter(event.currentTarget.dataset.status);
    if (
      this.data.activeSection !== "MINE" ||
      activeStatus === this.data.activeStatus ||
      this.data.loading ||
      this.data.claimingTemplateId
    ) {
      return;
    }
    const copy = emptyCopy("MINE", activeStatus);
    this.setData({
      activeStatus,
      coupons: [],
      loaded: false,
      errorText: "",
      emptyTitle: copy.title
    });
    void this.loadCoupons();
  },

  async loadCoupons(options: RefreshOptions = {}) {
    const requestId = ++latestRequest;
    const section = this.data.activeSection;
    const status = this.data.activeStatus;
    const copy = emptyCopy(section, status);
    const silent = options.silent === true && this.data.loaded;
    if (silent) {
      if (!options.suppressError) {
        this.setData({ errorText: "" });
      }
    } else {
      this.setData({
        loading: true,
        errorText: "",
        emptyTitle: copy.title
      });
    }
    try {
      const coupons = section === "CLAIMABLE"
        ? buildClaimableCouponViews(await getClaimableCoupons())
        : buildUserCouponViews(await getMyCoupons(
            status === "ALL" ? undefined : status as UserCouponStatus
          ));
      if (requestId !== latestRequest) {
        return;
      }
      this.setData({
        coupons,
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestRequest) {
        if (silent && options.suppressError) {
          return;
        }
        this.setData({
          loading: false,
          loaded: this.data.coupons.length > 0,
          errorText: actionError(error, "优惠券加载失败，请稍后重试")
        });
      }
    }
  },

  onCouponActionTap(event: DatasetEvent) {
    const couponId = parsePositiveId(event.currentTarget.dataset.id);
    const coupon = this.data.coupons.find((item) => item.id === couponId);
    if (!coupon || coupon.actionDisabled) {
      return;
    }
    if (this.data.activeSection === "CLAIMABLE") {
      void this.claim(couponId);
    } else {
      wx.switchTab({ url: "/pages/index/index" });
    }
  },

  async claim(templateId: number) {
    if (this.data.claimingTemplateId) {
      return;
    }
    this.setData({ claimingTemplateId: templateId });
    try {
      await claimCoupon(templateId);
      this.setData({ claimingTemplateId: 0 });
      wx.showToast({ title: "领取成功", icon: "success" });
      await this.loadCoupons();
    } catch (error) {
      this.setData({ claimingTemplateId: 0 });
      wx.showToast({
        title: actionError(error, "领取失败，请稍后重试"),
        icon: "none"
      });
    }
  }
});
