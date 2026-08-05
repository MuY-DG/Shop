import {
  buildHistoryProductViews,
  groupHistoryProductViews,
  type HistoryProductGroup,
  type HistoryProductView
} from "../../../features/account-center";
import {
  findDefaultSku,
  parsePositiveId
} from "../../../features/product-catalog";
import { addCartItem } from "../../../services/cart";
import {
  clearBrowseHistory,
  deleteBrowseHistoryItems,
  getBrowseHistory
} from "../../../services/product-preference";
import { getProductDetail } from "../../../services/product";
import { getSessionState } from "../../../services/session";
import { isApiError } from "../../../utils/api-error";
import { openLoginPage } from "../../../utils/login-navigation";
import { refreshCustomTabBarCartCount } from "../../../utils/tab-bar";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
    };
  };
}

interface HistoryCollection {
  items: HistoryProductView[];
  groups: HistoryProductGroup[];
  selectedIds: number[];
  allSelected: boolean;
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
      confirmColor: "#FF172B",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    });
  });
}

function historyCollection(
  items: HistoryProductView[],
  selectedIds: number[] = []
): HistoryCollection {
  const requestedIds = new Set(selectedIds);
  const normalizedSelectedIds = items
    .filter((item) => requestedIds.has(item.spuId))
    .map((item) => item.spuId);
  const selectedIdSet = new Set(normalizedSelectedIds);
  const selectedItems = items.map((item) => ({
    ...item,
    selected: selectedIdSet.has(item.spuId)
  }));
  return {
    items: selectedItems,
    groups: groupHistoryProductViews(selectedItems),
    selectedIds: normalizedSelectedIds,
    allSelected: selectedItems.length > 0
      && normalizedSelectedIds.length === selectedItems.length
  };
}

Page({
  data: {
    items: [] as HistoryProductView[],
    groups: [] as HistoryProductGroup[],
    current: 1,
    hasMore: false,
    loading: true,
    loaded: false,
    loadingMore: false,
    errorText: "",
    managing: false,
    selectedIds: [] as number[],
    allSelected: false,
    addingSpuId: 0,
    deleting: false,
    clearing: false
  },

  onLoad() {
    void this.refresh();
  },

  onUnload() {
    latestRequest += 1;
  },

  async onPullDownRefresh() {
    if (!this.data.deleting && !this.data.clearing) {
      await this.refresh();
    }
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
      const response = await getBrowseHistory(1, PAGE_SIZE);
      if (requestId !== latestRequest) {
        return;
      }
      const sourceItems = buildHistoryProductViews(response.records);
      const managing = this.data.managing && sourceItems.length > 0;
      this.setData({
        ...historyCollection(
          sourceItems,
          managing ? this.data.selectedIds : []
        ),
        current: response.current,
        hasMore: response.hasMore,
        loading: false,
        loaded: true,
        managing,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({
          loading: false,
          loaded: this.data.items.length > 0,
          errorText: actionError(error, "足迹加载失败，请稍后重试")
        });
      }
    }
  },

  async loadMore() {
    if (
      !this.data.hasMore
      || this.data.loading
      || this.data.loadingMore
      || this.data.deleting
      || this.data.clearing
    ) {
      return;
    }
    const requestId = ++latestRequest;
    this.setData({ loadingMore: true });
    try {
      const response = await getBrowseHistory(this.data.current + 1, PAGE_SIZE);
      if (requestId !== latestRequest) {
        return;
      }
      const items = [
        ...this.data.items,
        ...buildHistoryProductViews(response.records)
      ];
      this.setData({
        ...historyCollection(items, this.data.selectedIds),
        current: response.current,
        hasMore: response.hasMore,
        loadingMore: false
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({ loadingMore: false });
        wx.showToast({
          title: actionError(error, "更多足迹加载失败"),
          icon: "none"
        });
      }
    }
  },

  onManageToggle() {
    if (this.data.deleting || this.data.clearing || this.data.addingSpuId) {
      return;
    }
    const managing = !this.data.managing;
    this.setData({
      managing,
      ...historyCollection(this.data.items)
    });
  },

  onProductTap(event: DatasetEvent) {
    const spuId = parsePositiveId(event.currentTarget.dataset.id);
    const product = this.data.items.find((item) => item.spuId === spuId);
    if (!product) {
      return;
    }
    if (this.data.managing) {
      this.toggleSelection(spuId);
      return;
    }
    if (!product.available) {
      wx.showToast({ title: "商品已下架", icon: "none" });
      return;
    }
    wx.navigateTo({ url: product.navigationPath });
  },

  onSelectionToggle(event: DatasetEvent) {
    if (!this.data.managing || this.data.deleting || this.data.clearing) {
      return;
    }
    const spuId = parsePositiveId(event.currentTarget.dataset.id);
    if (spuId) {
      this.toggleSelection(spuId);
    }
  },

  toggleSelection(spuId: number) {
    const selectedIds = this.data.selectedIds.includes(spuId)
      ? this.data.selectedIds.filter((id) => id !== spuId)
      : [...this.data.selectedIds, spuId];
    this.setData(historyCollection(this.data.items, selectedIds));
  },

  onSelectAllToggle() {
    if (!this.data.managing || this.data.deleting || this.data.clearing) {
      return;
    }
    const selectedIds = this.data.allSelected
      ? []
      : this.data.items.map((item) => item.spuId);
    this.setData(historyCollection(this.data.items, selectedIds));
  },

  onImageError(event: DatasetEvent) {
    const spuId = parsePositiveId(event.currentTarget.dataset.id);
    const product = this.data.items.find((item) => item.spuId === spuId);
    if (!product?.hasImage) {
      return;
    }
    const items = this.data.items.map((item) => (
      item.spuId === spuId ? { ...item, hasImage: false } : item
    ));
    this.setData(historyCollection(items, this.data.selectedIds));
  },

  onAddCartTap(event: DatasetEvent) {
    const spuId = parsePositiveId(event.currentTarget.dataset.id);
    const product = this.data.items.find((item) => item.spuId === spuId);
    if (
      !product?.available
      || this.data.managing
      || this.data.addingSpuId
      || this.data.deleting
      || this.data.clearing
    ) {
      return;
    }
    const session = getSessionState();
    if (!session.user || (!session.accessToken && !session.refreshToken)) {
      openLoginPage();
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
        openLoginPage();
        return;
      }
      wx.showToast({
        title: actionError(error, "加入购物车失败，请稍后重试"),
        icon: "none"
      });
    } finally {
      if (this.data.addingSpuId === spuId) {
        this.setData({ addingSpuId: 0 });
      }
    }
  },

  onBatchDeleteTap() {
    void this.deleteSelected();
  },

  async deleteSelected() {
    if (this.data.deleting || this.data.clearing) {
      return;
    }
    const selectedSpuIds = Array.from(new Set(this.data.selectedIds))
      .filter((spuId) => Number.isSafeInteger(spuId) && spuId > 0);
    if (!selectedSpuIds.length) {
      wx.showToast({ title: "请选择要删除的商品", icon: "none" });
      return;
    }
    latestRequest += 1;
    this.setData({ deleting: true, loadingMore: false });
    try {
      await deleteBrowseHistoryItems(selectedSpuIds);
      const deletedIds = new Set(selectedSpuIds);
      const items = this.data.items.filter((item) => !deletedIds.has(item.spuId));
      this.setData(historyCollection(items));
      await this.refresh();
      wx.showToast({ title: "删除成功", icon: "success" });
    } catch (error) {
      await this.refresh();
      wx.showToast({
        title: actionError(error, "删除失败，请重试"),
        icon: "none"
      });
    } finally {
      this.setData({ deleting: false });
    }
  },

  onClearTap() {
    void this.clearAll();
  },

  async clearAll() {
    if (
      this.data.clearing
      || this.data.deleting
      || !this.data.items.length
      || !await confirmAction("清空足迹", "清空后无法恢复，是否继续？", "清空")
    ) {
      return;
    }
    latestRequest += 1;
    this.setData({ clearing: true, loadingMore: false });
    try {
      await clearBrowseHistory();
      this.setData({
        ...historyCollection([]),
        current: 1,
        hasMore: false,
        managing: false
      });
      wx.showToast({ title: "清空成功", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "清空失败，请重试"),
        icon: "none"
      });
    } finally {
      this.setData({ clearing: false });
    }
  }
});
