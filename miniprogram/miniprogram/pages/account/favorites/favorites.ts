import {
  buildFavoriteProductViews,
  type FavoriteProductView
} from "../../../features/account-center";
import {
  findDefaultSku,
  parsePositiveId
} from "../../../features/product-catalog";
import { addCartItem } from "../../../services/cart";
import {
  getFavorites,
  removeFavorites
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

interface ProductCardEvent {
  detail: {
    spuId?: number | string;
  };
}

interface FavoriteCollection {
  items: FavoriteProductView[];
  selectedIds: number[];
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

function favoriteCollection(
  items: FavoriteProductView[],
  selectedIds: number[] = []
): FavoriteCollection {
  const requestedIds = new Set(selectedIds);
  const normalizedSelectedIds = items
    .filter((item) => requestedIds.has(item.spuId))
    .map((item) => item.spuId);
  const selectedIdSet = new Set(normalizedSelectedIds);
  return {
    items: items.map((item) => ({
      ...item,
      selected: selectedIdSet.has(item.spuId)
    })),
    selectedIds: normalizedSelectedIds
  };
}

Page({
  data: {
    items: [] as FavoriteProductView[],
    current: 1,
    total: 0,
    hasMore: false,
    loading: true,
    loaded: false,
    loadingMore: false,
    errorText: "",
    managing: false,
    selectedIds: [] as number[],
    addingSpuId: 0,
    deleting: false
  },

  onLoad() {
    void this.refresh();
  },

  onUnload() {
    latestRequest += 1;
  },

  async onPullDownRefresh() {
    if (!this.data.deleting) {
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
      const response = await getFavorites(1, PAGE_SIZE);
      if (requestId !== latestRequest) {
        return;
      }
      const sourceItems = buildFavoriteProductViews(response.records);
      const managing = this.data.managing && sourceItems.length > 0;
      this.setData({
        ...favoriteCollection(
          sourceItems,
          managing ? this.data.selectedIds : []
        ),
        current: response.current,
        total: response.total,
        hasMore: response.current * response.size < response.total,
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
          errorText: actionError(error, "收藏加载失败，请稍后重试")
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
    ) {
      return;
    }
    const requestId = ++latestRequest;
    this.setData({ loadingMore: true });
    try {
      const response = await getFavorites(this.data.current + 1, PAGE_SIZE);
      if (requestId !== latestRequest) {
        return;
      }
      const items = [
        ...this.data.items,
        ...buildFavoriteProductViews(response.records)
      ];
      this.setData({
        ...favoriteCollection(items, this.data.selectedIds),
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

  onManageToggle() {
    if (this.data.deleting || this.data.addingSpuId) {
      return;
    }
    this.setData({
      managing: !this.data.managing,
      ...favoriteCollection(this.data.items)
    });
  },

  onProductSelect(event: ProductCardEvent) {
    const spuId = parsePositiveId(event.detail.spuId);
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
    if (!this.data.managing || this.data.deleting) {
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
    this.setData(favoriteCollection(this.data.items, selectedIds));
  },

  onProductAdd(event: ProductCardEvent) {
    const spuId = parsePositiveId(event.detail.spuId);
    const product = this.data.items.find((item) => item.spuId === spuId);
    if (
      !product?.available
      || this.data.managing
      || this.data.addingSpuId
      || this.data.deleting
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

  onCancelFavoritesTap() {
    void this.cancelSelectedFavorites();
  },

  async cancelSelectedFavorites() {
    if (this.data.deleting) {
      return;
    }
    const selectedSpuIds = Array.from(new Set(this.data.selectedIds))
      .filter((spuId) => Number.isSafeInteger(spuId) && spuId > 0);
    if (!selectedSpuIds.length) {
      wx.showToast({ title: "请选择要取消收藏的商品", icon: "none" });
      return;
    }
    if (!await confirmAction(
      "取消收藏",
      `确定取消收藏这 ${selectedSpuIds.length} 件商品吗？`,
      "取消收藏"
    )) {
      return;
    }
    latestRequest += 1;
    this.setData({ deleting: true, loadingMore: false });
    try {
      await removeFavorites(selectedSpuIds);
      const removedIds = new Set(selectedSpuIds);
      const items = this.data.items.filter((item) => !removedIds.has(item.spuId));
      this.setData({
        ...favoriteCollection(items),
        total: Math.max(0, this.data.total - selectedSpuIds.length),
        managing: items.length > 0
      });
      await this.refresh();
      wx.showToast({ title: "取消收藏成功", icon: "success" });
    } catch (error) {
      await this.refresh();
      wx.showToast({
        title: actionError(error, "取消收藏失败，请重试"),
        icon: "none"
      });
    } finally {
      this.setData({ deleting: false });
    }
  }
});
