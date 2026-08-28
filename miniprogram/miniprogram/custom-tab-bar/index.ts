import { getCartItems } from "../services/cart";
import { getSessionState } from "../services/session";

interface TabItem {
  pagePath: string;
  text: string;
  icon: "home" | "category" | "cart" | "profile";
  iconPath: string;
  selectedIconPath: string;
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
    iconPath: "/assets/icons/tab-home.svg",
    selectedIconPath: "/assets/icons/tab-home-active.svg",
    badge: "",
    ariaLabel: "首页"
  },
  {
    pagePath: "/pages/category/category",
    text: "分类",
    icon: "category",
    iconPath: "/assets/icons/tab-category.svg",
    selectedIconPath: "/assets/icons/tab-category-active.svg",
    badge: "",
    ariaLabel: "分类"
  },
  {
    pagePath: "/pages/cart/cart",
    text: "购物车",
    icon: "cart",
    iconPath: "/assets/icons/tab-cart.svg",
    selectedIconPath: "/assets/icons/tab-cart-active.svg",
    badge: "",
    ariaLabel: "购物车"
  },
  {
    pagePath: "/pages/profile/profile",
    text: "我的",
    icon: "profile",
    iconPath: "/assets/icons/tab-profile.svg",
    selectedIconPath: "/assets/icons/tab-profile-active.svg",
    badge: "",
    ariaLabel: "我的"
  }
];

let latestCartCountRequest = 0;

interface TabBarPlatformView {
  isAndroid: boolean;
  itemOffsetRpx: number;
}

function resolveTabBarPlatformView(): TabBarPlatformView {
  try {
    const systemInfo = wx.getSystemInfoSync();
    const isAndroid = systemInfo.platform === "android";
    if (isAndroid) {
      return { isAndroid: true, itemOffsetRpx: 34 };
    }
    const windowWidth = Number(systemInfo.windowWidth);
    const screenHeight = Number(systemInfo.screenHeight);
    const safeAreaBottom = Number(systemInfo.safeArea?.bottom);
    const bottomInsetPx = Number.isFinite(screenHeight) && Number.isFinite(safeAreaBottom)
      ? Math.max(0, screenHeight - safeAreaBottom)
      : 0;
    const bottomInsetRpx = Number.isFinite(windowWidth) && windowWidth > 0
      ? bottomInsetPx * 750 / windowWidth
      : 0;
    return {
      isAndroid: false,
      itemOffsetRpx: Math.min(44, Math.round(bottomInsetRpx / 2))
    };
  } catch {
    return { isAndroid: false, itemOffsetRpx: 0 };
  }
}

const TAB_BAR_PLATFORM_VIEW = resolveTabBarPlatformView();

Component({
  options: {
    styleIsolation: "isolated"
  },

  data: {
    selected: -1,
    hidden: true,
    ...TAB_BAR_PLATFORM_VIEW,
    list: TAB_ITEMS
  },

  methods: {
    syncSelection(selected: number) {
      if (Number.isSafeInteger(selected) && selected >= 0 && selected < this.data.list.length) {
        this.setData({ selected, hidden: false });
      }
    },

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
        const cart = await getCartItems({ preferCache: true });
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
