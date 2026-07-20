import {
  buildHomeViewModel,
  normalizeHomePath,
  type HomeBannerView,
  type HomeCategoryView,
  type HomeProductCardView
} from "../../features/home";
import { getHome } from "../../services/home";
import { isApiError } from "../../utils/api-error";
import { syncCustomTabBar } from "../../utils/tab-bar";

interface BusinessPathEvent {
  detail: {
    path?: string;
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
    hasContent: false
  },

  onLoad() {
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
