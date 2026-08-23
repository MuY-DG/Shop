import {
  ACCOUNT_ROUTES,
  accountNavigationPath
} from "../../features/account-center";
import { normalizeContactPhone } from "../../features/contact";
import { COMPLIANCE_ROUTES } from "../../features/compliance";
import { buildOrderListUrl, parseOrderStatusGroup } from "../../features/order-center";
import {
  guestProfileOverviewDisplay,
  loadingProfileOverviewDisplay,
  profileOverviewDisplay,
  profileOverviewFingerprint
} from "../../features/profile-overview";
import type { ProfileOverviewDisplay } from "../../features/profile-overview";
import { getPublicContact } from "../../services/contact";
import { getCustomerServicePresence } from "../../services/customer-service";
import { getSessionState } from "../../services/session";
import { getMyOverview } from "../../services/user-profile";
import { openLoginPage } from "../../utils/login-navigation";
import { syncCustomTabBar } from "../../utils/tab-bar";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      group?: string;
      kind?: string;
      path?: string;
    };
  };
}

const DEFAULT_AVATAR_URL = "/assets/images/profile-default-avatar.png";
let latestOverviewRequest = 0;

function accountMetrics(display: ProfileOverviewDisplay) {
  return [
    {
      label: "优惠券",
      ariaLabel: "优惠券",
      value: display.couponValue,
      iconPath: "/assets/icons/profile-coupon.svg",
      path: ACCOUNT_ROUTES.coupons
    },
    {
      label: "收藏",
      ariaLabel: "我的收藏",
      value: display.favoriteValue,
      iconPath: "/assets/icons/favorite-outline-rounded.svg",
      path: ACCOUNT_ROUTES.favorites
    },
    {
      label: "足迹",
      ariaLabel: "浏览记录",
      value: display.browseHistoryValue,
      iconPath: "/assets/icons/profile-footprint.svg",
      path: ACCOUNT_ROUTES.history
    }
  ];
}

function orderShortcuts(display: ProfileOverviewDisplay) {
  return [
    {
      group: "UNPAID",
      label: "待付款",
      iconPath: "/assets/icons/order-wallet.svg",
      badge: display.orderBadges[0]
    },
    {
      group: "TO_SHIP",
      label: "待发货",
      iconPath: "/assets/icons/order-package.svg",
      badge: display.orderBadges[1]
    },
    {
      group: "TO_RECEIVE",
      label: "待收货",
      iconPath: "/assets/icons/order-receive.svg",
      badge: display.orderBadges[2]
    },
    {
      group: "TO_REVIEW",
      label: "待评价",
      iconPath: "/assets/icons/order-review.svg",
      badge: display.orderBadges[3]
    },
    {
      path: ACCOUNT_ROUTES.afterSales,
      label: "退款/售后",
      ariaLabel: "退款售后",
      iconPath: "/assets/icons/order-after-sale.svg",
      badge: display.orderBadges[4]
    }
  ];
}

function serviceItems(display: ProfileOverviewDisplay) {
  const customerServiceState = display.customerServiceOnline ? "在线" : "离线";
  const customerServiceReply = display.customerServiceBadge
    ? `，${display.customerServiceBadge}条未读回复`
    : "";
  return [
    {
      label: "收货地址",
      iconPath: "/assets/icons/profile-location.svg",
      path: ACCOUNT_ROUTES.addresses,
      kind: "route"
    },
    {
      label: "联系客服",
      ariaLabel: `联系客服，${customerServiceState}${customerServiceReply}`,
      iconPath: "/assets/icons/support-agent-outline-rounded.svg",
      path: ACCOUNT_ROUTES.customerService,
      kind: "route",
      showPresence: true,
      online: display.customerServiceOnline,
      badge: display.customerServiceBadge
    },
    {
      label: "售后服务",
      iconPath: "/assets/icons/account-after-sale.svg",
      path: ACCOUNT_ROUTES.afterSales,
      kind: "route"
    },
    {
      label: "联系电话",
      iconPath: "/assets/icons/service-phone.svg",
      path: "",
      kind: "phone"
    },
    {
      label: "关于我们",
      iconPath: "/assets/icons/verified-user-outline-rounded.svg",
      path: COMPLIANCE_ROUTES.settings,
      kind: "public-route"
    }
  ];
}

function profileOverviewState(display: ProfileOverviewDisplay) {
  return {
    accountMetrics: accountMetrics(display),
    orderShortcuts: orderShortcuts(display),
    serviceItems: serviceItems(display)
  };
}

const GUEST_OVERVIEW_DISPLAY = guestProfileOverviewDisplay();
const GUEST_OVERVIEW_STATE = profileOverviewState(GUEST_OVERVIEW_DISPLAY);

Page({
  data: {
    loggedIn: false,
    nickname: "点击登录",
    avatarUrl: DEFAULT_AVATAR_URL,
    avatarMode: "aspectFill",
    memberCopy: "登录后查看订单与会员服务",
    contactLoading: false,
    overviewOwnerKey: "guest",
    overviewFingerprint: profileOverviewFingerprint(GUEST_OVERVIEW_DISPLAY),
    ...GUEST_OVERVIEW_STATE
  },

  onShow() {
    syncCustomTabBar(this, 3);
    const session = getSessionState();
    const loggedIn = Boolean(
      session.user && (session.accessToken || session.refreshToken)
    );
    const requestId = ++latestOverviewRequest;
    const overviewOwnerKey = loggedIn
      ? `user:${session.user?.userId || "unknown"}`
      : "guest";
    const sameOverviewOwner = this.data.overviewOwnerKey === overviewOwnerKey;
    const initialOverviewDisplay = loggedIn
      ? loadingProfileOverviewDisplay()
      : GUEST_OVERVIEW_DISPLAY;
    const overviewState = sameOverviewOwner
      ? {}
      : {
          overviewFingerprint: profileOverviewFingerprint(initialOverviewDisplay),
          ...profileOverviewState(initialOverviewDisplay)
        };
    this.setData({
      loggedIn,
      overviewOwnerKey,
      nickname: loggedIn && session.user?.nickname
        ? session.user.nickname
        : "点击登录",
      avatarUrl: loggedIn && session.user?.avatarUrl
        ? session.user.avatarUrl
        : DEFAULT_AVATAR_URL,
      avatarMode: "aspectFill",
      memberCopy: loggedIn
        ? session.user?.phoneNumberMasked
          ? `已绑定手机 ${session.user.phoneNumberMasked}`
          : "欢迎回来，会员服务已为你开启"
        : "登录后查看订单与会员服务",
      ...overviewState
    });
    if (loggedIn) {
      void this.loadOverview(requestId, overviewOwnerKey);
    } else {
      void this.loadCustomerServicePresence(requestId, overviewOwnerKey);
    }
  },

  async loadCustomerServicePresence(requestId: number, overviewOwnerKey: string) {
    try {
      const presence = await getCustomerServicePresence();
      if (
        requestId !== latestOverviewRequest ||
        this.data.overviewOwnerKey !== overviewOwnerKey ||
        this.data.loggedIn
      ) {
        return;
      }
      const display = {
        ...GUEST_OVERVIEW_DISPLAY,
        customerServiceOnline: presence.online === true
      };
      const overviewFingerprint = profileOverviewFingerprint(display);
      if (this.data.overviewFingerprint !== overviewFingerprint) {
        this.setData({
          overviewFingerprint,
          ...profileOverviewState(display)
        });
      }
    } catch (_error) {
      // Public presence is best-effort; keep the safe offline fallback on failure.
    }
  },

  async loadOverview(requestId: number, overviewOwnerKey: string) {
    try {
      const overview = await getMyOverview();
      if (
        requestId !== latestOverviewRequest ||
        this.data.overviewOwnerKey !== overviewOwnerKey ||
        !this.data.loggedIn
      ) {
        return;
      }
      const display = profileOverviewDisplay(overview);
      const overviewFingerprint = profileOverviewFingerprint(display);
      if (this.data.overviewFingerprint !== overviewFingerprint) {
        this.setData({
          overviewFingerprint,
          ...profileOverviewState(display)
        });
      }
    } catch {
      // 静默刷新失败时保留已有概览，避免真实数字退回占位符造成闪动。
    }
  },

  onMemberTap() {
    if (!this.data.loggedIn) {
      openLoginPage("/pages/profile/profile");
      return;
    }
    wx.navigateTo({ url: ACCOUNT_ROUTES.profile });
  },

  onAllOrdersTap() {
    const url = buildOrderListUrl();
    if (this.requireLogin(url)) {
      wx.navigateTo({ url });
    }
  },

  onOrderShortcutTap(event: DatasetEvent) {
    const path = accountNavigationPath(event.currentTarget.dataset.path);
    if (path) {
      if (this.requireLogin(path)) {
        wx.navigateTo({ url: path });
      }
      return;
    }

    const group = parseOrderStatusGroup(event.currentTarget.dataset.group);
    const url = buildOrderListUrl(group);
    if (this.requireLogin(url)) {
      wx.navigateTo({ url });
    }
  },

  onAccountServiceTap(event: DatasetEvent) {
    const path = accountNavigationPath(event.currentTarget.dataset.path);
    if (path && this.requireLogin(path)) {
      wx.navigateTo({ url: path });
    }
  },

  onServiceItemTap(event: DatasetEvent) {
    if (event.currentTarget.dataset.kind === "phone") {
      void this.onContactPhoneTap();
      return;
    }
    if (event.currentTarget.dataset.kind === "public-route") {
      const path = event.currentTarget.dataset.path;
      if (path === COMPLIANCE_ROUTES.settings) {
        wx.navigateTo({ url: path });
      }
      return;
    }
    this.onAccountServiceTap(event);
  },

  async onContactPhoneTap() {
    if (this.data.contactLoading) {
      return;
    }
    this.setData({ contactLoading: true });
    try {
      const contact = await getPublicContact();
      const phoneNumber = normalizeContactPhone(contact.phone);
      if (!phoneNumber) {
        wx.showToast({ title: "联系电话暂不可用", icon: "none" });
        return;
      }
      wx.makePhoneCall({
        phoneNumber,
        fail: (result) => {
          if (!result.errMsg.toLowerCase().includes("cancel")) {
            wx.showToast({ title: "暂时无法发起拨号", icon: "none" });
          }
        }
      });
    } catch (_error) {
      wx.showToast({ title: "联系电话获取失败", icon: "none" });
    } finally {
      this.setData({ contactLoading: false });
    }
  },

  requireLogin(redirect: string): boolean {
    if (this.data.loggedIn) {
      return true;
    }
    openLoginPage(redirect);
    return false;
  }
});
