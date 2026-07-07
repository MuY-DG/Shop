import type { UserCoupon } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import { getMyCoupons } from "../../../services/coupon";
import { formatPrice } from "../../../services/product";

interface CouponSection {
  status: UserCoupon["status"];
  title: string;
  coupons: UserCouponView[];
}

interface MineCouponPageData {
  loading: boolean;
  errorText: string;
  sections: CouponSection[];
}

interface UserCouponView extends UserCoupon {
  discountText: string;
  thresholdText: string;
  validityText: string;
  statusText: string;
}

const STATUS_ORDER: UserCoupon["status"][] = [
  "CLAIMED",
  "LOCKED",
  "USED",
  "RELEASED",
  "EXPIRED"
];

function formatDate(dateTime: string): string {
  return dateTime.slice(0, 10);
}

function getStatusText(status: UserCoupon["status"]): string {
  if (status === "CLAIMED") {
    return "待使用";
  }
  if (status === "LOCKED") {
    return "已锁定";
  }
  if (status === "USED") {
    return "已使用";
  }
  if (status === "RELEASED") {
    return "已释放";
  }
  return "已过期";
}

function formatThresholdText(coupon: Pick<UserCoupon, "couponType" | "thresholdCent">): string {
  if (coupon.couponType === "NO_THRESHOLD") {
    return "无门槛可用";
  }
  return `满${formatPrice(coupon.thresholdCent)}可用`;
}

function toUserCouponView(coupon: UserCoupon): UserCouponView {
  return {
    ...coupon,
    discountText: formatPrice(coupon.discountCent),
    thresholdText: formatThresholdText(coupon),
    validityText: `${formatDate(coupon.validStartAt)} - ${formatDate(coupon.validEndAt)}`,
    statusText: getStatusText(coupon.status)
  };
}

function toSections(coupons: UserCoupon[]): CouponSection[] {
  const groups = new Map<UserCoupon["status"], UserCouponView[]>();

  coupons.forEach((coupon) => {
    const list = groups.get(coupon.status) || [];
    list.push(toUserCouponView(coupon));
    groups.set(coupon.status, list);
  });

  return STATUS_ORDER
    .map((status) => ({
      status,
      title: getStatusText(status),
      coupons: groups.get(status) || []
    }))
    .filter((section) => section.coupons.length > 0);
}

Page<MineCouponPageData, WechatMiniprogram.Page.CustomOption>({
  data: {
    loading: false,
    errorText: "",
    sections: [] as CouponSection[]
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
      const coupons = await getMyCoupons();
      this.setData({
        sections: toSections(coupons)
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "我的优惠券加载失败",
        sections: []
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  }
});
