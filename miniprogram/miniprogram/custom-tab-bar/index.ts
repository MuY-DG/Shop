import { getCartItems } from "../services/cart";
import { getSessionState } from "../services/session";

interface TabItem {
  pagePath: string;
  text: string;
  icon: "home" | "category" | "cart" | "profile";
  badge: string;
  ariaLabel: string;
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
    icon: "home",
    badge: "",
    ariaLabel: "首页"
  },
  {
    pagePath: "/pages/category/category",
    text: "分类",
    icon: "category",
    badge: "",
    ariaLabel: "分类"
  },
  {
    pagePath: "/pages/cart/cart",
    text: "购物车",
    icon: "cart",
    badge: "",
    ariaLabel: "购物车"
  },
  {
    pagePath: "/pages/profile/profile",
    text: "我的",
    icon: "profile",
    badge: "",
    ariaLabel: "我的"
  }
];

let latestCartCountRequest = 0;

Component({
  options: {
    styleIsolation: "isolated"
  },

  data: {
    selected: 0,
    hidden: false,
    list: TAB_ITEMS
  },

  methods: {
    setHidden(hidden: boolean) {
      this.setData({ hidden: Boolean(hidden) });
    },

    setCartCount(count: number) {
      const safeCount = Number.isSafeInteger(count) && count > 0 ? count : 0;
      const badge = safeCount > 99 ? "99+" : safeCount ? String(safeCount) : "";
      const list = (this.data.list as TabItem[]).map((item) => (
        item.icon === "cart"
          ? {
              ...item,
              badge,
              ariaLabel: safeCount ? `购物车，${badge}件商品` : "购物车"
            }
          : item
      ));
      this.setData({ list });
    },

    async refreshCartCount() {
      const requestId = ++latestCartCountRequest;
      const session = getSessionState();
      if (!session.user || (!session.accessToken && !session.refreshToken)) {
        this.setCartCount(0);
        return;
      }
      try {
        const cart = await getCartItems();
        if (requestId === latestCartCountRequest) {
          this.setCartCount(cart.totalQuantity);
        }
      } catch {
        const currentSession = getSessionState();
        if (
          requestId === latestCartCountRequest &&
          (!currentSession.user ||
            (!currentSession.accessToken && !currentSession.refreshToken))
        ) {
          this.setCartCount(0);
        }
      }
    },

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
