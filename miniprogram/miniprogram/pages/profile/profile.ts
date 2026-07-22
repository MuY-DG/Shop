import {
  ACCOUNT_ROUTES,
  accountNavigationPath
} from "../../features/account-center";
import { buildOrderListUrl, parseOrderStatusGroup } from "../../features/order-center";
import { getSessionState } from "../../services/session";
import { openLoginPage } from "../../utils/login-navigation";
import { syncCustomTabBar } from "../../utils/tab-bar";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      group?: string;
      path?: string;
    };
  };
}

Page({
  data: {
    loggedIn: false,
    nickname: "点击登录",
    avatarUrl: "/assets/images/zaoxiangji-login-emblem.png",
    avatarMode: "aspectFit",
    memberCopy: "登录后查看订单、优惠券与收货地址",
    orderShortcuts: [{ group: "UNPAID", label: "待付款", icon: "付" },
      { group: "TO_SHIP", label: "待发货", icon: "备" },
      { group: "TO_RECEIVE", label: "待收货", icon: "收" },
      { group: "COMPLETED", label: "已完成", icon: "✓" }],
    accountServices: [
      { label: "收货地址", icon: "址", path: ACCOUNT_ROUTES.addresses },
      { label: "优惠券", icon: "券", path: ACCOUNT_ROUTES.coupons },
      { label: "我的收藏", icon: "藏", path: ACCOUNT_ROUTES.favorites },
      { label: "浏览记录", icon: "览", path: ACCOUNT_ROUTES.history }
    ]
  },

  onShow() {
    syncCustomTabBar(this, 3);
    const session = getSessionState();
    const loggedIn = Boolean(
      session.user && (session.accessToken || session.refreshToken)
    );
    this.setData({
      loggedIn,
      nickname: loggedIn && session.user?.nickname
        ? session.user.nickname
        : "点击登录",
      avatarUrl: loggedIn && session.user?.avatarUrl
        ? session.user.avatarUrl
        : "/assets/images/zaoxiangji-login-emblem.png",
      avatarMode: loggedIn && session.user?.avatarUrl ? "aspectFill" : "aspectFit",
      memberCopy: loggedIn
        ? session.user?.phoneNumberMasked
          ? `已绑定手机 ${session.user.phoneNumberMasked}`
          : "欢迎回来，会员服务已为你开启"
        : "登录后查看订单、优惠券与收货地址"
    });
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

  requireLogin(redirect: string): boolean {
    if (this.data.loggedIn) {
      return true;
    }
    openLoginPage(redirect);
    return false;
  }
});
