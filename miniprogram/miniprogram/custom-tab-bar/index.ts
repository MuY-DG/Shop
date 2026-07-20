interface TabItem {
  pagePath: string;
  text: string;
  icon: "home" | "category" | "message" | "cart" | "profile";
  badge: string;
}

interface TabTapEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
    };
  };
}

const TAB_ITEMS: TabItem[] = [
  { pagePath: "/pages/index/index", text: "首页", icon: "home", badge: "" },
  { pagePath: "/pages/category/category", text: "分类", icon: "category", badge: "" },
  { pagePath: "/pages/message/message", text: "消息", icon: "message", badge: "" },
  { pagePath: "/pages/cart/cart", text: "购物车", icon: "cart", badge: "" },
  { pagePath: "/pages/profile/profile", text: "我的", icon: "profile", badge: "" }
];

Component({
  options: {
    styleIsolation: "isolated"
  },

  data: {
    selected: 0,
    list: TAB_ITEMS
  },

  methods: {
    onTabTap(event: TabTapEvent) {
      const index = Number(event.currentTarget.dataset.index);
      if (
        !Number.isSafeInteger(index) ||
        index < 0 ||
        index >= this.data.list.length ||
        index === this.data.selected
      ) {
        return;
      }
      const item = this.data.list[index] as TabItem | undefined;
      if (!item) {
        return;
      }
      wx.switchTab({ url: item.pagePath });
    }
  }
});
