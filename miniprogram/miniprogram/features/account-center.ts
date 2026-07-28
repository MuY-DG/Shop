import type { AddressUpsertRequest } from "../types/checkout";
import type {
  ClaimableCoupon,
  UserCoupon,
  UserCouponStatus
} from "../types/coupon";
import type {
  ProductBrowseHistoryItem,
  ProductFavoriteItem
} from "../types/product-engagement";

export const ACCOUNT_ROUTES = Object.freeze({
  profile: "/pages/account/profile/profile",
  addresses: "/pages/account/address/list/list",
  coupons: "/pages/account/coupon/coupon",
  favorites: "/pages/account/favorites/favorites",
  history: "/pages/account/history/history",
  afterSales: "/pages/after-sale/list/list"
});

export const COUPON_STATUS_TABS = Object.freeze([
  { value: "ALL", label: "全部" },
  { value: "CLAIMED", label: "待使用" },
  { value: "USED", label: "已使用" },
  { value: "EXPIRED", label: "已过期" }
]);

export type CouponStatusFilter = "ALL" | Exclude<UserCouponStatus, "LOCKED">;

export interface AddressFormValue {
  receiverName: string;
  receiverPhone: string;
  province: string;
  city: string;
  district: string;
  detailAddress: string;
  locationName: string;
  doorplate: string;
  isDefault: boolean;
}

export interface AccountProductView {
  spuId: number;
  navigationPath: string;
  title: string;
  subtitle: string;
  imageUrl: string;
  hasImage: boolean;
  placeholder: string;
  priceText: string;
  availabilityText: string;
  metaText: string;
  available: boolean;
}

export interface CouponCardView {
  id: number;
  name: string;
  description: string;
  amountText: string;
  conditionText: string;
  validityText: string;
  statusText: string;
  statusTone: "brand" | "muted" | "warning";
  actionText: string;
  actionDisabled: boolean;
}

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function nonNegativeCent(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : undefined;
}

function positiveId(value: unknown): number {
  const numberValue = Number(value);
  return Number.isSafeInteger(numberValue) && numberValue > 0 ? numberValue : 0;
}

function moneyText(cent: unknown): string {
  const normalized = nonNegativeCent(cent);
  if (normalized === undefined) {
    return "0";
  }
  const fixed = (normalized / 100).toFixed(2);
  return fixed.endsWith(".00") ? fixed.slice(0, -3) : fixed;
}

function dateText(value: unknown): string {
  const text = cleanText(value).slice(0, 10);
  return /^\d{4}-\d{2}-\d{2}$/.test(text) ? text.replace(/-/g, ".") : "";
}

function priceRangeText(minPriceCent: unknown, maxPriceCent: unknown): string {
  const minimum = nonNegativeCent(minPriceCent);
  const maximum = nonNegativeCent(maxPriceCent);
  if (minimum === undefined && maximum === undefined) {
    return "暂无报价";
  }
  const primary = minimum ?? maximum ?? 0;
  if (minimum !== undefined && maximum !== undefined && maximum > minimum) {
    return `¥${moneyText(minimum)}–${moneyText(maximum)}`;
  }
  return `¥${moneyText(primary)}`;
}

function buildProductView(
  item: ProductFavoriteItem | ProductBrowseHistoryItem,
  metaText: string
): AccountProductView | undefined {
  const spuId = positiveId(item?.spuId);
  const title = cleanText(item?.title);
  if (!spuId || !title) {
    return undefined;
  }
  const imageUrl = cleanText(item.mainImage);
  return {
    spuId,
    navigationPath: `/pages/product/detail/detail?id=${spuId}`,
    title,
    subtitle: cleanText(item.subtitle),
    imageUrl,
    hasImage: Boolean(imageUrl),
    placeholder: title.slice(0, 1),
    priceText: priceRangeText(item.minPriceCent, item.maxPriceCent),
    availabilityText: item.available ? "" : "商品已下架",
    metaText,
    available: Boolean(item.available)
  };
}

export function buildFavoriteProductViews(
  items: ProductFavoriteItem[]
): AccountProductView[] {
  return (Array.isArray(items) ? items : [])
    .map((item) => buildProductView(
      item,
      dateText(item.favoritedAt) ? `${dateText(item.favoritedAt)} 收藏` : "已收藏"
    ))
    .filter((item): item is AccountProductView => Boolean(item));
}

export function buildHistoryProductViews(
  items: ProductBrowseHistoryItem[]
): AccountProductView[] {
  return (Array.isArray(items) ? items : [])
    .map((item) => {
      const viewedAt = dateText(item.lastViewedAt);
      const count = positiveId(item.viewCount);
      return buildProductView(
        item,
        `${viewedAt ? `${viewedAt} · ` : ""}浏览 ${count || 1} 次`
      );
    })
    .filter((item): item is AccountProductView => Boolean(item));
}

function couponCondition(couponType: string, thresholdCent: unknown): string {
  return couponType === "MIN_SPEND"
    ? `满 ¥${moneyText(thresholdCent)} 可用`
    : "无门槛";
}

function couponValidity(validEndAt: unknown): string {
  const end = dateText(validEndAt);
  return end ? `有效期至 ${end}` : "有效期以使用规则为准";
}

function unavailableCouponText(reason: unknown): string {
  switch (cleanText(reason)) {
    case "OUT_OF_STOCK":
      return "已领完";
    case "CLAIM_LIMIT_REACHED":
      return "已领取";
    default:
      return "暂不可领取";
  }
}

export function buildClaimableCouponViews(coupons: ClaimableCoupon[]): CouponCardView[] {
  return (Array.isArray(coupons) ? coupons : [])
    .filter((coupon) => positiveId(coupon?.templateId) && cleanText(coupon?.name))
    .map((coupon) => ({
      id: coupon.templateId,
      name: cleanText(coupon.name),
      description: cleanText(coupon.description),
      amountText: moneyText(coupon.discountCent),
      conditionText: couponCondition(coupon.couponType, coupon.thresholdCent),
      validityText: couponValidity(coupon.validEndAt),
      statusText: coupon.claimable ? "可领取" : unavailableCouponText(coupon.unavailableReason),
      statusTone: coupon.claimable ? "brand" : "muted",
      actionText: coupon.claimable ? "立即领取" : unavailableCouponText(coupon.unavailableReason),
      actionDisabled: !coupon.claimable
    }));
}

function userCouponStatus(status: UserCouponStatus): Pick<
  CouponCardView,
  "statusText" | "statusTone" | "actionText" | "actionDisabled"
> {
  switch (status) {
    case "CLAIMED":
      return {
        statusText: "待使用",
        statusTone: "brand",
        actionText: "去选购",
        actionDisabled: false
      };
    case "LOCKED":
      return {
        statusText: "订单使用中",
        statusTone: "warning",
        actionText: "使用中",
        actionDisabled: true
      };
    case "USED":
      return {
        statusText: "已使用",
        statusTone: "muted",
        actionText: "已使用",
        actionDisabled: true
      };
    case "EXPIRED":
      return {
        statusText: "已过期",
        statusTone: "muted",
        actionText: "已过期",
        actionDisabled: true
      };
  }
}

export function buildUserCouponViews(coupons: UserCoupon[]): CouponCardView[] {
  return (Array.isArray(coupons) ? coupons : [])
    .filter((coupon) => positiveId(coupon?.userCouponId) && cleanText(coupon?.name))
    .map((coupon) => ({
      id: coupon.userCouponId,
      name: cleanText(coupon.name),
      description: coupon.scopeType === "ALL" ? "全场商品可用" : "指定商品可用",
      amountText: moneyText(coupon.discountCent),
      conditionText: couponCondition(coupon.couponType, coupon.thresholdCent),
      validityText: couponValidity(coupon.validEndAt),
      ...userCouponStatus(coupon.status)
    }));
}

export function parseCouponStatusFilter(value: unknown): CouponStatusFilter {
  return value === "CLAIMED" || value === "USED" || value === "EXPIRED"
    ? value
    : "ALL";
}

export function parseAddressId(value: unknown): string {
  const text = cleanText(value);
  return /^\d+$/.test(text) && !/^0+$/.test(text) ? text : "";
}

export function normalizeAddressForm(value: AddressFormValue): AddressUpsertRequest {
  return {
    receiverName: cleanText(value.receiverName),
    receiverPhone: cleanText(value.receiverPhone).replace(/\s+/g, ""),
    province: cleanText(value.province),
    city: cleanText(value.city),
    district: cleanText(value.district),
    detailAddress: cleanText(value.detailAddress).replace(/\s+/g, " "),
    locationName: cleanText(value.locationName).replace(/\s+/g, " "),
    doorplate: cleanText(value.doorplate).replace(/\s+/g, " "),
    isDefault: Boolean(value.isDefault)
  };
}

export function composeAddressListTitle(
  locationName: unknown,
  doorplate: unknown,
  detailAddress: unknown,
  formattedAddress: unknown
): string {
  const name = cleanText(locationName).replace(/\s+/g, " ");
  const supplement = cleanText(doorplate).replace(/\s+/g, " ");
  if (name) {
    return `${name}${supplement}`;
  }
  const detail = cleanText(detailAddress).replace(/\s+/g, " ");
  if (supplement && detail && !detail.endsWith(supplement)) {
    return `${detail}${supplement}`;
  }
  return detail || cleanText(formattedAddress).replace(/\s+/g, " ");
}

export function validateAddressForm(value: AddressFormValue): string {
  const normalized = normalizeAddressForm(value);
  if (!normalized.receiverName) {
    return "请填写收货人姓名";
  }
  if (normalized.receiverName.length > 10) {
    return "收货人姓名不能超过 10 个字";
  }
  if (!normalized.receiverPhone) {
    return "请填写手机号码";
  }
  if (!/^1[3-9]\d{9}$/.test(normalized.receiverPhone)) {
    return "请填写有效的手机号码";
  }
  if (!normalized.province || !normalized.city || !normalized.district) {
    return "请通过地图选择完整地址";
  }
  if (!normalized.detailAddress) {
    return "请通过地图选择详细地址";
  }
  if (normalized.detailAddress.length > 255) {
    return "详细地址不能超过 255 个字";
  }
  if ((normalized.locationName?.length || 0) > 128) {
    return "地点名称不能超过 128 个字";
  }
  if ((normalized.doorplate?.length || 0) > 128) {
    return "门牌号不能超过 128 个字";
  }
  return "";
}

export function accountNavigationPath(value: unknown): string {
  const path = cleanText(value);
  const routes = Object.values(ACCOUNT_ROUTES) as readonly string[];
  return routes.includes(path) ? path : "";
}
