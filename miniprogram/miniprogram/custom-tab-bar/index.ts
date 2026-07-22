interface TabItem {
  pagePath: string;
  text: string;
  iconPath: string;
  activeIconPath: string;
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
  {
    pagePath: "/pages/index/index",
    text: "首页",
    iconPath: "/assets/icons/tab-home.svg",
    activeIconPath: "/assets/icons/tab-home-active.svg",
    badge: ""
  },
  {
    pagePath: "/pages/category/category",
    text: "分类",
    iconPath: "/assets/icons/tab-category.svg",
    activeIconPath: "/assets/icons/tab-category-active.svg",
    badge: ""
  },
  {
    pagePath: "/pages/cart/cart",
    text: "购物车",
    iconPath: "/assets/icons/tab-cart.svg",
    activeIconPath: "/assets/icons/tab-cart-active.svg",
    badge: ""
  },
  {
    pagePath: "/pages/profile/profile",
    text: "我的",
    iconPath: "/assets/icons/tab-profile.svg",
    activeIconPath: "/assets/icons/tab-profile-active.svg",
    badge: ""
  }
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
