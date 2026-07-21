import { buildOrderListUrl, parseOrderStatusGroup } from "../../features/order-center";
import { getSessionState } from "../../services/session";
import { syncCustomTabBar } from "../../utils/tab-bar";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      group?: string;
    };
  };
}

Page({
  data: {
    nickname: "灶香集会员",
    orderShortcuts: [{ group: "UNPAID", label: "待付款", icon: "付" },
      { group: "TO_SHIP", label: "待发货", icon: "备" },
      { group: "TO_RECEIVE", label: "待收货", icon: "收" },
      { group: "COMPLETED", label: "已完成", icon: "✓" }]
  },

  onShow() {
    syncCustomTabBar(this, 4);
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
  }
});
