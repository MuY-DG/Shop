import {
  buildCatalogProductCards,
  buildCategoryTabs,
  buildProductListQuery,
  normalizeProductKeyword,
  parsePositiveId,
  type CatalogProductCardView,
  type CategoryTabView
} from "../../features/product-catalog";
import {
  getProductCategories,
  getProductList
} from "../../services/product";
import type { ProductCategory } from "../../types/product";
import { isApiError } from "../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
    };
  };
}

interface InputEvent {
  detail: {
    value: string;
  };
}

interface ProductSelectEvent {
  detail: {
    spuId?: number | string;
  };
}

const requestIds = new WeakMap<object, number>();

function nextRequestId(instance: object): number {
  const requestId = (requestIds.get(instance) ?? 0) + 1;
  requestIds.set(instance, requestId);
  return requestId;
}

function isCurrentRequest(instance: object, requestId: number): boolean {
  return requestIds.get(instance) === requestId;
}

function productErrorMessage(error: unknown, fallback: string): string {
  if (!isApiError(error)) {
    return error instanceof Error ? error.message : fallback;
  }
  switch (error.kind) {
    case "NETWORK":
      return "网络连接失败，请检查网络后重试";
    case "RATE_LIMIT":
      return "请求有点频繁，请稍后再试";
    case "SERVER":
      return "商品服务暂时不可用，请稍后重试";
    case "PROTOCOL":
      return "商品数据格式异常，请稍后重试";
    default:
      return error.message || fallback;
  }
}

Component({
  options: {
    styleIsolation: "isolated"
  },

  properties: {
    initialCategoryId: {
      type: Number,
      value: 0
    },
    initialKeyword: {
      type: String,
      value: ""
    },
    tabPage: {
      type: Boolean,
      value: false
    }
  },

  data: {
    categories: [] as ProductCategory[],
    categoryTabs: [] as CategoryTabView[],
    activeCategoryId: 0,
    searchInput: "",
    activeKeyword: "",
    products: [] as CatalogProductCardView[],
    current: 1,
    total: 0,
    loading: true,
    loadingMore: false,
    loaded: false,
    errorText: ""
  },

  lifetimes: {
    attached() {
      const activeCategoryId = parsePositiveId(this.data.initialCategoryId);
      const activeKeyword = normalizeProductKeyword(this.data.initialKeyword);
      this.setData({
        activeCategoryId,
        searchInput: activeKeyword,
        activeKeyword,
        categoryTabs: buildCategoryTabs([], activeCategoryId)
      });
      void Promise.all([this.loadCategories(), this.loadFirstPage()]);
    },

    detached() {
      nextRequestId(this);
    }
  },

  methods: {
    async refresh() {
      await Promise.all([this.loadCategories(true), this.loadFirstPage(true)]);
    },

    async loadCategories(silent = false) {
      try {
        const categories = await getProductCategories();
        this.setData({
          categories,
          categoryTabs: buildCategoryTabs(categories, this.data.activeCategoryId)
        });
      } catch (error) {
        if (!silent) {
          wx.showToast({
            title: productErrorMessage(error, "分类加载失败"),
            icon: "none"
          });
        }
      }
    },

    async loadFirstPage(preserveContent = false) {
      const requestId = nextRequestId(this);
      const keepCurrentContent = preserveContent && this.data.loaded;
      this.setData({
        loading: true,
        loadingMore: false,
        errorText: keepCurrentContent ? this.data.errorText : ""
      });
      try {
        const result = await getProductList(buildProductListQuery(
          this.data.activeCategoryId,
          this.data.activeKeyword
        ));
        if (!isCurrentRequest(this, requestId)) {
          return;
        }
        this.setData({
          products: buildCatalogProductCards(result.records),
          current: parsePositiveId(result.current) || 1,
          total: Math.max(0, Number(result.total) || 0),
          loading: false,
          loaded: true,
          errorText: ""
        });
      } catch (error) {
        if (!isCurrentRequest(this, requestId)) {
          return;
        }
        const message = productErrorMessage(error, "商品加载失败，请稍后重试");
        if (keepCurrentContent) {
          this.setData({ loading: false });
          wx.showToast({ title: "刷新失败，已保留当前商品", icon: "none" });
        } else {
          this.setData({
            products: [],
            current: 1,
            total: 0,
            loading: false,
            loaded: false,
            errorText: message
          });
        }
      }
    },

    async loadMore() {
      if (
        this.data.loading ||
        this.data.loadingMore ||
        this.data.products.length >= this.data.total
      ) {
        return;
      }
      const requestId = nextRequestId(this);
      this.setData({ loadingMore: true, errorText: "" });
      try {
        const result = await getProductList(buildProductListQuery(
          this.data.activeCategoryId,
          this.data.activeKeyword,
          this.data.current + 1
        ));
        if (!isCurrentRequest(this, requestId)) {
          return;
        }
        this.setData({
          products: this.data.products.concat(buildCatalogProductCards(result.records)),
          current: parsePositiveId(result.current) || this.data.current + 1,
          total: Math.max(0, Number(result.total) || this.data.total),
          loadingMore: false
        });
      } catch (error) {
        if (!isCurrentRequest(this, requestId)) {
          return;
        }
        this.setData({
          loadingMore: false,
          errorText: productErrorMessage(error, "加载更多失败，请稍后重试")
        });
      }
    },

    onRetry() {
      if (this.data.products.length) {
        void this.loadMore();
        return;
      }
      void this.loadFirstPage();
    },

    onEmptyAction() {
      if (this.data.activeKeyword) {
        this.onSearchClear();
        return;
      }
      this.onRetry();
    },

    onCategoryTap(event: DatasetEvent) {
      const categoryId = Number(event.currentTarget.dataset.id);
      if (
        !Number.isSafeInteger(categoryId) ||
        categoryId < 0 ||
        categoryId === this.data.activeCategoryId ||
        this.data.loading ||
        this.data.loadingMore
      ) {
        return;
      }
      this.setData({
        activeCategoryId: categoryId,
        categoryTabs: buildCategoryTabs(this.data.categories, categoryId),
        products: [],
        current: 1,
        total: 0,
        loaded: false,
        errorText: ""
      });
      void this.loadFirstPage();
    },

    onSearchInput(event: InputEvent) {
      this.setData({ searchInput: event.detail.value });
    },

    onSearchConfirm(event: InputEvent) {
      this.submitSearch(event.detail.value);
    },

    onSearchTap() {
      this.submitSearch(this.data.searchInput);
    },

    onSearchClear() {
      if (!this.data.searchInput && !this.data.activeKeyword) {
        return;
      }
      this.submitSearch("");
    },

    submitSearch(value: unknown) {
      if (this.data.loading || this.data.loadingMore) {
        return;
      }
      const keyword = normalizeProductKeyword(value);
      this.setData({
        searchInput: keyword,
        activeKeyword: keyword,
        products: [],
        current: 1,
        total: 0,
        loaded: false,
        errorText: ""
      });
      void this.loadFirstPage();
    },

    onProductSelect(event: ProductSelectEvent) {
      const spuId = parsePositiveId(event.detail.spuId);
      if (spuId) {
        this.triggerEvent("productselect", { spuId });
      }
    }
  }
});
