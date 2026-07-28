import {
  buildHomeViewModel,
  normalizeHomePath,
  type HomeBannerView,
  type HomeCategoryView,
  type HomeProductCardView
} from "../../features/home";
import { findDefaultSku, parsePositiveId } from "../../features/product-catalog";
import { addCartItem } from "../../services/cart";
import { getHome } from "../../services/home";
import { getProductDetail } from "../../services/product";
import { getSessionState } from "../../services/session";
import { isApiError } from "../../utils/api-error";
import { openLoginPage } from "../../utils/login-navigation";
import {
  DEFAULT_SHARE_TITLE,
  enableNativeShareMenu
} from "../../utils/share";
import {
  refreshCustomTabBarCartCount,
  syncCustomTabBar
} from "../../utils/tab-bar";

interface BusinessPathEvent {
  detail: {
    path?: string;
  };
}

interface ProductAddEvent {
  detail: {
    spuId?: number | string;
    title?: string;
  };
}

interface IndexedComponentEvent {
  detail: {
    index?: number | string;
  };
}

let latestHomeRequest = 0;

function homeErrorMessage(error: unknown): string {
  if (!isApiError(error)) {
    return error instanceof Error && error.message.startsWith("暂不支持首页")
      ? error.message
      : "首页数据暂时不可用，请稍后重试";
  }
  switch (error.kind) {
    case "NETWORK":
      return "网络连接失败，请检查网络后重试";
    case "RATE_LIMIT":
      return "请求有点频繁，请稍后再试";
    case "SERVER":
      return "服务暂时开小差，请稍后重试";
    case "PROTOCOL":
      return "首页数据格式异常，请稍后重试";
    default:
      return error.message || "首页加载失败，请稍后重试";
  }
}

function eventIndex(event: IndexedComponentEvent, length: number): number | undefined {
  const index = Number(event.detail.index);
  return Number.isInteger(index) && index >= 0 && index < length
    ? index
    : undefined;
}

Page({
  data: {
    loading: true,
    loaded: false,
    errorText: "",
    banners: [] as HomeBannerView[],
    categories: [] as HomeCategoryView[],
    featuredProducts: [] as HomeProductCardView[],
    compactProducts: [] as HomeProductCardView[],
    hasContent: false,
    addingSpuId: 0
  },

  onLoad() {
    enableNativeShareMenu();
    void this.loadHome();
  },

  onShow() {
    syncCustomTabBar(this, 0);
  },

  onUnload() {
    latestHomeRequest += 1;
  },

  onPullDownRefresh() {
    void this.loadHome(true);
  },

  onShareAppMessage() {
    return {
      title: DEFAULT_SHARE_TITLE,
      path: "/pages/index/index"
    };
  },

  onShareTimeline() {
    return {
      title: DEFAULT_SHARE_TITLE
    };
  },

  async loadHome(preserveContent = false) {
    const requestId = ++latestHomeRequest;
    const keepCurrentContent = preserveContent && this.data.loaded;
    this.setData({
      loading: true,
      errorText: keepCurrentContent ? this.data.errorText : ""
    });

    try {
      const response = await getHome();
      const viewModel = buildHomeViewModel(response);
      if (requestId !== latestHomeRequest) {
        return;
      }
      this.setData({
        ...viewModel,
        loaded: true,
        loading: false,
        errorText: ""
      });
    } catch (error) {
      if (requestId !== latestHomeRequest) {
        return;
      }
      const message = homeErrorMessage(error);
      if (keepCurrentContent) {
        this.setData({ loading: false });
        wx.showToast({
          title: "刷新失败，已保留当前内容",
          icon: "none"
        });
      } else {
        this.setData({
          loading: false,
          loaded: false,
          errorText: message
        });
      }
    } finally {
      if (requestId === latestHomeRequest) {
        wx.stopPullDownRefresh();
      }
    }
  },

  onRetry() {
    void this.loadHome();
  },

  onBusinessPathSelect(event: BusinessPathEvent) {
    this.openBusinessPath(event.detail.path);
  },

  onProductAdd(event: ProductAddEvent) {
    const spuId = parsePositiveId(event.detail.spuId);
    if (!spuId || this.data.addingSpuId) {
      return;
    }
    const session = getSessionState();
    if (!session.user || (!session.accessToken && !session.refreshToken)) {
      openLoginPage("/pages/index/index");
      return;
    }
    void this.addProductToCart(spuId);
  },

  async addProductToCart(spuId: number) {
    this.setData({ addingSpuId: spuId });
    try {
      const detail = await getProductDetail(spuId);
      const sku = findDefaultSku(detail.skus);
      if (!sku) {
        wx.showToast({ title: "该商品暂无可售规格", icon: "none" });
        return;
      }
      await addCartItem({ skuId: sku.id, quantity: 1 });
      void refreshCustomTabBarCartCount(this);
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch (error) {
      if (isApiError(error) && error.kind === "AUTH") {
        openLoginPage("/pages/index/index");
        return;
      }
      wx.showToast({
        title: isApiError(error)
          ? error.message
          : "加入购物车失败，请稍后重试",
        icon: "none"
      });
    } finally {
      if (this.data.addingSpuId === spuId) {
        this.setData({ addingSpuId: 0 });
      }
    }
  },

  openBusinessPath(path: unknown) {
    const safePath = normalizeHomePath(path);
    if (!safePath) {
      return;
    }
    wx.navigateTo({
      url: safePath,
      fail: () => {
        wx.showToast({
          title: "相关页面正在建设中",
          icon: "none"
        });
      }
    });
  },

  onBannerImageError(event: IndexedComponentEvent) {
    const index = eventIndex(event, this.data.banners.length);
    if (index === undefined) {
      return;
    }
    const banners = this.data.banners.map((banner, itemIndex) => (
      itemIndex === index ? { ...banner, hasImage: false } : banner
    ));
    this.setData({ banners });
  },

  onCategoryImageError(event: IndexedComponentEvent) {
    const index = eventIndex(event, this.data.categories.length);
    if (index === undefined) {
      return;
    }
    const categories = this.data.categories.map((category, itemIndex) => (
      itemIndex === index ? { ...category, hasImage: false } : category
    ));
    this.setData({ categories });
  }
});
