import type { AppUserOverview } from "../types/profile-overview";

export interface ProfileOverviewDisplay {
  couponValue: string;
  favoriteValue: string;
  browseHistoryValue: string;
  orderBadges: string[];
  customerServiceBadge: string;
  customerServiceOnline: boolean;
}

export function overviewCount(value: unknown): number {
  const count = Number(value);
  return Number.isSafeInteger(count) && count > 0 ? count : 0;
}

export function countBadge(value: unknown): string {
  const count = overviewCount(value);
  if (!count) {
    return "";
  }
  return count > 99 ? "99+" : String(count);
}

export function guestProfileOverviewDisplay(): ProfileOverviewDisplay {
  return {
    couponValue: "***张",
    favoriteValue: "***",
    browseHistoryValue: "***",
    orderBadges: ["", "", "", "", ""],
    customerServiceBadge: "",
    customerServiceOnline: false
  };
}

export function loadingProfileOverviewDisplay(): ProfileOverviewDisplay {
  return {
    couponValue: "—张",
    favoriteValue: "—",
    browseHistoryValue: "—",
    orderBadges: ["", "", "", "", ""],
    customerServiceBadge: "",
    customerServiceOnline: false
  };
}

export function profileOverviewDisplay(
  overview: AppUserOverview
): ProfileOverviewDisplay {
  return {
    couponValue: `${overviewCount(overview.availableCouponCount)}张`,
    favoriteValue: String(overviewCount(overview.favoriteCount)),
    browseHistoryValue: String(overviewCount(overview.browseHistoryCount)),
    orderBadges: [
      countBadge(overview.unpaidOrderCount),
      countBadge(overview.toShipOrderCount),
      countBadge(overview.toReceiveOrderCount),
      countBadge(overview.toReviewOrderCount),
      countBadge(overview.activeAfterSaleCount)
    ],
    customerServiceBadge: countBadge(overview.customerServiceUnreadCount),
    customerServiceOnline: overview.customerServiceOnline === true
  };
}

export function profileOverviewFingerprint(
  display: ProfileOverviewDisplay
): string {
  return [
    display.couponValue,
    display.favoriteValue,
    display.browseHistoryValue,
    display.orderBadges.join(","),
    display.customerServiceBadge,
    display.customerServiceOnline ? "online" : "offline"
  ].join("|");
}
