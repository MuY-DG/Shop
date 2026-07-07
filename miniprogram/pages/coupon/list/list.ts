import type { ClaimableCoupon } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import { claimCoupon, getClaimableCoupons } from "../../../services/coupon";
import { formatPrice } from "../../../services/product";

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface CouponPageData {
  loading: boolean;
  errorText: string;
  coupons: ClaimableCouponView[];
  claimingTemplateId: number;
}

interface ClaimableCouponView extends ClaimableCoupon {
  discountText: string;
  thresholdText: string;
  validityText: string;
  claimStateText: string;
  limitText: string;
  actionText: string;
  disabled: boolean;
}

function formatDate(dateTime: string): string {
  return dateTime.slice(0, 10);
}

function formatThresholdText(coupon: Pick<ClaimableCoupon, "couponType" | "thresholdCent">): string {
  if (coupon.couponType === "NO_THRESHOLD") {
    return "无门槛可用";
  }
  return `满${formatPrice(coupon.thresholdCent)}可用`;
}

function formatClaimStateText(coupon: ClaimableCoupon): string {
  if (coupon.claimable) {
    return "可领取";
  }
  return coupon.unavailableReason || "暂不可领取";
}

function toCouponView(coupon: ClaimableCoupon, claimingTemplateId: number): ClaimableCouponView {
  return {
    ...coupon,
    discountText: formatPrice(coupon.discountCent),
    thresholdText: formatThresholdText(coupon),
    validityText: `${formatDate(coupon.validStartAt)} - ${formatDate(coupon.validEndAt)}`,
    claimStateText: formatClaimStateText(coupon),
    limitText: `已领 ${coupon.claimedCount}/${coupon.perUserLimit}`,
    actionText: coupon.claimable ? "立即领取" : "暂不可领",
    disabled: !coupon.claimable || coupon.templateId === claimingTemplateId
  };
}

Page<CouponPageData, WechatMiniprogram.Page.CustomOption>({
  data: {
    loading: false,
    errorText: "",
    coupons: [] as ClaimableCouponView[],
    claimingTemplateId: 0
  },
  async onShow() {
    await this.loadCoupons();
  },
  async onPullDownRefresh() {
    await this.loadCoupons();
    wx.stopPullDownRefresh();
  },
  async loadCoupons() {
    this.setData({
      loading: true,
      errorText: ""
    });

    try {
      await ensureAppLogin();
      const coupons = await getClaimableCoupons();
      this.setData({
        coupons: coupons.map((coupon) => toCouponView(coupon, this.data.claimingTemplateId))
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "领券中心加载失败",
        coupons: []
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  },
  async onClaimTap(event: DatasetEvent) {
    const templateId = Number(event.currentTarget.dataset.id);
    if (!Number.isFinite(templateId) || templateId <= 0 || this.data.claimingTemplateId > 0) {
      return;
    }

    this.setData({
      claimingTemplateId: templateId,
      coupons: this.data.coupons.map((coupon) => toCouponView(coupon, templateId))
    });

    try {
      await ensureAppLogin();
      await claimCoupon(templateId);
      wx.showToast({
        title: "领取成功",
        icon: "success"
      });
      await this.loadCoupons();
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "领取失败",
        icon: "none"
      });
      this.setData({
        coupons: this.data.coupons.map((coupon) => toCouponView(coupon, 0))
      });
    } finally {
      this.setData({
        claimingTemplateId: 0
      });
    }
  },
  onMineTap() {
    wx.navigateTo({
      url: "/pages/coupon/mine/mine"
    });
  }
});
