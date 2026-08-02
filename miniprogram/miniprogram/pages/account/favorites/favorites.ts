import {
  buildFavoriteProductViews,
  type AccountProductView
} from "../../../features/account-center";
import {
  getFavorites,
  removeFavorite
} from "../../../services/product-preference";
import { parsePositiveId } from "../../../features/product-catalog";
import { isApiError } from "../../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
      index?: number | string;
    };
  };
}

const PAGE_SIZE = 10;
let latestRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function confirmRemove(): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title: "取消收藏",
      content: "确定从收藏中移除该商品吗？",
      confirmText: "移除",
      confirmColor: "#B72B22",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    });
  });
}

Page({
  data: {
    items: [] as AccountProductView[],
    current: 1,
    total: 0,
    hasMore: false,
    loading: true,
    loaded: false,
    loadingMore: false,
    errorText: "",
    actionSpuId: 0
  },

  onLoad() {
    void this.refresh();
  },

  onUnload() {
    latestRequest += 1;
  },

  async onPullDownRefresh() {
    await this.refresh();
    wx.stopPullDownRefresh();
  },

  onReachBottom() {
    void this.loadMore();
  },

  onRetry() {
    void this.refresh();
  },

  async refresh() {
    const requestId = ++latestRequest;
    this.setData({ loading: true, loadingMore: false, errorText: "" });
    try {
      const response = await getFavorites(1, PAGE_SIZE);
      if (requestId !== latestRequest) {
        return;
      }
      this.setData({
        items: buildFavoriteProductViews(response.records),
        current: response.current,
        total: response.total,
        hasMore: response.current * response.size < response.total,
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({
          loading: false,
          loaded: this.data.items.length > 0,
          errorText: actionError(error, "收藏加载失败，请稍后重试")
        });
      }
    }
  },

  async loadMore() {
    if (!this.data.hasMore || this.data.loading || this.data.loadingMore) {
      return;
    }
    const requestId = ++latestRequest;
    this.setData({ loadingMore: true });
    try {
      const response = await getFavorites(this.data.current + 1, PAGE_SIZE);
      if (requestId !== latestRequest) {
        return;
      }
      this.setData({
        items: [...this.data.items, ...buildFavoriteProductViews(response.records)],
        current: response.current,
        total: response.total,
        hasMore: response.current * response.size < response.total,
        loadingMore: false
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({ loadingMore: false });
        wx.showToast({
          title: actionError(error, "更多收藏加载失败"),
          icon: "none"
        });
      }
    }
  },

  onProductTap(event: DatasetEvent) {
    const spuId = parsePositiveId(event.currentTarget.dataset.id);
    const product = this.data.items.find((item) => item.spuId === spuId);
    if (!product) {
      return;
    }
    if (!product.available) {
      wx.showToast({ title: "商品已下架", icon: "none" });
      return;
    }
    wx.navigateTo({ url: product.navigationPath });
  },

  onImageError(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isSafeInteger(index) || index < 0 || index >= this.data.items.length) {
      return;
    }
    this.setData({
      items: this.data.items.map((item, itemIndex) => (
        itemIndex === index ? { ...item, hasImage: false } : item
      ))
    });
  },

  onRemoveTap(event: DatasetEvent) {
    const spuId = parsePositiveId(event.currentTarget.dataset.id);
    if (spuId) {
      void this.remove(spuId);
    }
  },

  async remove(spuId: number) {
    if (this.data.actionSpuId || !await confirmRemove()) {
      return;
    }
    this.setData({ actionSpuId: spuId });
    try {
      await removeFavorite(spuId);
      this.setData({
        items: this.data.items.filter((item) => item.spuId !== spuId),
        current: 1,
        total: Math.max(0, this.data.total - 1),
        hasMore: false,
        actionSpuId: 0
      });
      wx.showToast({ title: "已取消收藏", icon: "success" });
      await this.refresh();
    } catch (error) {
      this.setData({ actionSpuId: 0 });
      wx.showToast({
        title: actionError(error, "取消收藏失败"),
        icon: "none"
      });
    }
  }
});
