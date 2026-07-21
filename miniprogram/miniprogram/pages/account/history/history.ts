import {
  buildHistoryProductViews,
  type AccountProductView
} from "../../../features/account-center";
import { parsePositiveId } from "../../../features/product-catalog";
import {
  clearBrowseHistory,
  deleteBrowseHistoryItem,
  getBrowseHistory
} from "../../../services/product-preference";
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

function confirmAction(title: string, content: string, confirmText: string): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title,
      content,
      confirmText,
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
    clearing: false,
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
    this.setData({ loading: true, errorText: "" });
    try {
      const response = await getBrowseHistory(1, PAGE_SIZE);
      if (requestId !== latestRequest) {
        return;
      }
      this.setData({
        items: buildHistoryProductViews(response.records),
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
          errorText: actionError(error, "浏览记录加载失败，请稍后重试")
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
      const response = await getBrowseHistory(this.data.current + 1, PAGE_SIZE);
      if (requestId !== latestRequest) {
        return;
      }
      this.setData({
        items: [...this.data.items, ...buildHistoryProductViews(response.records)],
        current: response.current,
        total: response.total,
        hasMore: response.current * response.size < response.total,
        loadingMore: false
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({ loadingMore: false });
        wx.showToast({
          title: actionError(error, "更多浏览记录加载失败"),
          icon: "none"
        });
      }
    }
  },

  onProductTap(event: DatasetEvent) {
    const spuId = parsePositiveId(event.currentTarget.dataset.id);
    const product = this.data.items.find((item) => item.spuId === spuId);
    if (product?.available) {
      wx.navigateTo({ url: product.navigationPath });
    }
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

  onDeleteTap(event: DatasetEvent) {
    const spuId = parsePositiveId(event.currentTarget.dataset.id);
    if (spuId) {
      void this.remove(spuId);
    }
  },

  async remove(spuId: number) {
    if (
      this.data.actionSpuId ||
      !await confirmAction("删除记录", "确定删除该条浏览记录吗？", "删除")
    ) {
      return;
    }
    this.setData({ actionSpuId: spuId });
    try {
      await deleteBrowseHistoryItem(spuId);
      this.setData({
        items: this.data.items.filter((item) => item.spuId !== spuId),
        total: Math.max(0, this.data.total - 1),
        actionSpuId: 0
      });
      wx.showToast({ title: "记录已删除", icon: "success" });
    } catch (error) {
      this.setData({ actionSpuId: 0 });
      wx.showToast({
        title: actionError(error, "删除失败，请重试"),
        icon: "none"
      });
    }
  },

  onClearTap() {
    void this.clearAll();
  },

  async clearAll() {
    if (
      this.data.clearing ||
      !this.data.items.length ||
      !await confirmAction("清空记录", "清空后无法恢复，是否继续？", "清空")
    ) {
      return;
    }
    this.setData({ clearing: true });
    try {
      await clearBrowseHistory();
      this.setData({
        items: [],
        total: 0,
        hasMore: false,
        clearing: false
      });
      wx.showToast({ title: "浏览记录已清空", icon: "success" });
    } catch (error) {
      this.setData({ clearing: false });
      wx.showToast({
        title: actionError(error, "清空失败，请重试"),
        icon: "none"
      });
    }
  }
});
