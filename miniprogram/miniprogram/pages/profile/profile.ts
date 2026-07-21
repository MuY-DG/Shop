import {
  ACCOUNT_ROUTES,
  accountNavigationPath
} from "../../features/account-center";
import { buildOrderListUrl, parseOrderStatusGroup } from "../../features/order-center";
import { getSessionState } from "../../services/session";
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
    nickname: "灶香集会员",
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
    const user = getSessionState().user;
    if (user?.nickname) {
      this.setData({ nickname: user.nickname });
    }
  },

  onAllOrdersTap() {
    wx.navigateTo({ url: buildOrderListUrl() });
  },

  onOrderShortcutTap(event: DatasetEvent) {
    const group = parseOrderStatusGroup(event.currentTarget.dataset.group);
    wx.navigateTo({ url: buildOrderListUrl(group) });
  },

  onAccountServiceTap(event: DatasetEvent) {
    const path = accountNavigationPath(event.currentTarget.dataset.path);
    if (path) {
      wx.navigateTo({ url: path });
    }
  }
});
