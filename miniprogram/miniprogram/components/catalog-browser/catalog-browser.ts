import {
  buildCatalogProductCards,
  buildCatalogParameterFilterGroups,
  buildCategoryTabs,
  buildProductListQuery,
  findDefaultSku,
  normalizeProductKeyword,
  parsePositiveId,
  type CatalogParameterFilterGroupView,
  type CatalogProductCardView,
  type CategoryTabView
} from "../../features/product-catalog";
import { addCartItem } from "../../services/cart";
import {
  getProductCategories,
  getProductDetail,
  getProductFilterFacets,
  getProductList
} from "../../services/product";
import { getSessionState } from "../../services/session";
import type {
  ProductCategory,
  ProductFilterGroup,
  ProductListItem,
  ProductListSort
} from "../../types/product";
import { isApiError } from "../../utils/api-error";
import { openLoginPage } from "../../utils/login-navigation";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
      key?: string;
      value?: string;
      sort?: string;
    };
  };
}

interface ProductSelectEvent {
  detail: {
    spuId?: number | string;
  };
}

const requestIds = new WeakMap<object, number>();
const facetRequestIds = new WeakMap<object, number>();

type ParameterSelections = Record<string, string>;

function hasParameterSelections(selections: ParameterSelections): boolean {
  return Object.values(selections).some(Boolean);
}

function nextRequestId(instance: object): number {
  const requestId = (requestIds.get(instance) ?? 0) + 1;
  requestIds.set(instance, requestId);
  return requestId;
}

function isCurrentRequest(instance: object, requestId: number): boolean {
  return requestIds.get(instance) === requestId;
}

function nextFacetRequestId(instance: object): number {
  const requestId = (facetRequestIds.get(instance) ?? 0) + 1;
  facetRequestIds.set(instance, requestId);
  return requestId;
}

function isCurrentFacetRequest(instance: object, requestId: number): boolean {
  return facetRequestIds.get(instance) === requestId;
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
    activeKeyword: "",
    sourceProducts: [] as ProductListItem[],
    products: [] as CatalogProductCardView[],
    sortMode: "COMPREHENSIVE" as ProductListSort,
    viewMode: "grid" as "grid" | "list",
    filterVisible: false,
    hasFilters: false,
    selectedParameterValues: {} as ParameterSelections,
    draftParameterValues: {} as ParameterSelections,
    draftCategoryTabs: [] as CategoryTabView[],
    draftCategoryId: 0,
    filterFacets: [] as ProductFilterGroup[],
    parameterFilterGroups: [] as CatalogParameterFilterGroupView[],
    current: 1,
    total: 0,
    loading: true,
    loadingMore: false,
    loaded: false,
    errorText: "",
    addingSpuId: 0
  },

  lifetimes: {
    attached() {
      const activeCategoryId = parsePositiveId(this.data.initialCategoryId);
      const activeKeyword = normalizeProductKeyword(this.data.initialKeyword);
      this.setData({
        activeCategoryId,
        activeKeyword,
        categoryTabs: buildCategoryTabs([], activeCategoryId)
      });
      void Promise.all([
        this.loadCategories(),
        this.loadFilterFacets(activeCategoryId, {}),
        this.loadFirstPage()
      ]);
    },

    detached() {
      nextRequestId(this);
      nextFacetRequestId(this);
    }
  },

  methods: {
    async refresh() {
      await Promise.all([
        this.loadCategories(true),
        this.loadFilterFacets(
          this.data.activeCategoryId,
          this.data.selectedParameterValues,
          true
        ),
        this.loadFirstPage(true)
      ]);
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

    async loadFilterFacets(
      categoryId: number,
      selections: ParameterSelections,
      silent = false
    ) {
      const requestId = nextFacetRequestId(this);
      try {
        const filterFacets = await getProductFilterFacets({
          ...(categoryId ? { categoryId } : {}),
          ...(this.data.activeKeyword ? { keyword: this.data.activeKeyword } : {})
        });
        if (!isCurrentFacetRequest(this, requestId)) {
          return;
        }
        this.setData({
          filterFacets: Array.isArray(filterFacets) ? filterFacets : [],
          parameterFilterGroups: buildCatalogParameterFilterGroups(
            filterFacets,
            selections
          )
        });
      } catch (error) {
        if (!isCurrentFacetRequest(this, requestId)) {
          return;
        }
        this.setData({
          filterFacets: [],
          parameterFilterGroups: []
        });
        if (!silent) {
          wx.showToast({
            title: productErrorMessage(error, "筛选项加载失败"),
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
          this.data.activeKeyword,
          1,
          this.data.sortMode,
          this.data.selectedParameterValues
        ));
        if (!isCurrentRequest(this, requestId)) {
          return;
        }
        const sourceProducts = Array.isArray(result.records) ? result.records : [];
        this.setData({
          sourceProducts,
          products: buildCatalogProductCards(sourceProducts),
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
            sourceProducts: [],
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
        this.data.sourceProducts.length >= this.data.total
      ) {
        return;
      }
      const requestId = nextRequestId(this);
      this.setData({ loadingMore: true, errorText: "" });
      try {
        const result = await getProductList(buildProductListQuery(
          this.data.activeCategoryId,
          this.data.activeKeyword,
          this.data.current + 1,
          this.data.sortMode,
          this.data.selectedParameterValues
        ));
        if (!isCurrentRequest(this, requestId)) {
          return;
        }
        const sourceProducts = this.data.sourceProducts.concat(
          Array.isArray(result.records) ? result.records : []
        );
        this.setData({
          sourceProducts,
          products: buildCatalogProductCards(sourceProducts),
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
      if (this.data.sourceProducts.length) {
        void this.loadMore();
        return;
      }
      void this.loadFirstPage();
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
        selectedParameterValues: {},
        draftParameterValues: {},
        filterFacets: [],
        parameterFilterGroups: [],
        hasFilters: Boolean(categoryId),
        sourceProducts: [],
        products: [],
        current: 1,
        total: 0,
        loaded: false,
        errorText: ""
      });
      void Promise.all([
        this.loadFilterFacets(categoryId, {}),
        this.loadFirstPage()
      ]);
    },

    onSortTap(event: DatasetEvent) {
      if (this.data.loading || this.data.loadingMore) {
        return;
      }
      const requestedSort = event.currentTarget.dataset.sort;
      let sortMode: ProductListSort;
      if (requestedSort === "PRICE") {
        sortMode = this.data.sortMode === "PRICE_ASC" ? "PRICE_DESC" : "PRICE_ASC";
      } else if (
        requestedSort === "COMPREHENSIVE" ||
        requestedSort === "SALES_DESC"
      ) {
        sortMode = requestedSort;
      } else {
        return;
      }
      if (sortMode === this.data.sortMode) {
        return;
      }
      this.setData({
        sortMode,
        sourceProducts: [],
        products: [],
        current: 1,
        total: 0,
        loaded: false,
        errorText: ""
      });
      void this.loadFirstPage();
    },

    onViewToggle() {
      this.setData({
        viewMode: this.data.viewMode === "grid" ? "list" : "grid"
      });
    },

    onFilterOpen() {
      const draftParameterValues = { ...this.data.selectedParameterValues };
      this.setData({
        filterVisible: true,
        draftCategoryId: this.data.activeCategoryId,
        draftCategoryTabs: buildCategoryTabs(
          this.data.categories,
          this.data.activeCategoryId
        ),
        draftParameterValues,
        parameterFilterGroups: buildCatalogParameterFilterGroups(
          this.data.filterFacets,
          draftParameterValues
        )
      });
      this.triggerEvent("filtervisibilitychange", { visible: true });
      void this.loadFilterFacets(
        this.data.activeCategoryId,
        draftParameterValues,
        true
      );
    },

    onFilterClose() {
      this.setData({ filterVisible: false });
      this.triggerEvent("filtervisibilitychange", { visible: false });
    },

    onFilterCategoryTap(event: DatasetEvent) {
      const categoryId = Number(event.currentTarget.dataset.id);
      if (!Number.isSafeInteger(categoryId) || categoryId < 0) {
        return;
      }
      this.setData({
        draftCategoryId: categoryId,
        draftCategoryTabs: buildCategoryTabs(this.data.categories, categoryId),
        draftParameterValues: {},
        filterFacets: [],
        parameterFilterGroups: []
      });
      void this.loadFilterFacets(categoryId, {});
    },

    onFilterOptionTap(event: DatasetEvent) {
      const key = typeof event.currentTarget.dataset.key === "string"
        ? event.currentTarget.dataset.key
        : "";
      const value = typeof event.currentTarget.dataset.value === "string"
        ? event.currentTarget.dataset.value
        : "";
      if (!key || !value) {
        return;
      }
      const option = this.data.parameterFilterGroups
        .find((group) => group.key === key)
        ?.options.find((candidate) => candidate.value === value);
      if (!option || option.disabled) {
        return;
      }
      const draftParameterValues = { ...this.data.draftParameterValues };
      if (draftParameterValues[key] === value) {
        delete draftParameterValues[key];
      } else {
        draftParameterValues[key] = value;
      }
      this.setData({
        draftParameterValues,
        parameterFilterGroups: buildCatalogParameterFilterGroups(
          this.data.filterFacets,
          draftParameterValues
        )
      });
    },

    onFilterReset() {
      this.setData({
        draftCategoryId: 0,
        draftCategoryTabs: buildCategoryTabs(this.data.categories, 0),
        draftParameterValues: {},
        filterFacets: [],
        parameterFilterGroups: []
      });
      void this.loadFilterFacets(0, {});
    },

    onFilterApply() {
      const selectedParameterValues = { ...this.data.draftParameterValues };
      const hasFilters = Boolean(this.data.draftCategoryId) ||
        hasParameterSelections(selectedParameterValues);
      this.triggerEvent("filtervisibilitychange", { visible: false });
      this.setData({
        filterVisible: false,
        activeCategoryId: this.data.draftCategoryId,
        categoryTabs: buildCategoryTabs(
          this.data.categories,
          this.data.draftCategoryId
        ),
        selectedParameterValues,
        hasFilters,
        parameterFilterGroups: buildCatalogParameterFilterGroups(
          this.data.filterFacets,
          selectedParameterValues
        ),
        sourceProducts: [],
        products: [],
        current: 1,
        total: 0,
        loaded: false,
        errorText: ""
      });
      void this.loadFirstPage();
    },

    noop() {
      // Intentionally stops mask taps and scroll gestures from reaching the page.
    },

    onProductSelect(event: ProductSelectEvent) {
      const spuId = parsePositiveId(event.detail.spuId);
      if (spuId) {
        this.triggerEvent("productselect", { spuId });
      }
    },

    onProductAdd(event: ProductSelectEvent) {
      const spuId = parsePositiveId(event.detail.spuId);
      if (!spuId || this.data.addingSpuId) {
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
        this.triggerEvent("cartchange");
        wx.showToast({ title: "已加入购物车", icon: "success" });
      } catch (error) {
        if (isApiError(error) && error.kind === "AUTH") {
          openLoginPage();
          return;
        }
        wx.showToast({
          title: productErrorMessage(error, "加入购物车失败，请稍后重试"),
          icon: "none"
        });
      } finally {
        if (this.data.addingSpuId === spuId) {
          this.setData({ addingSpuId: 0 });
        }
      }
    }
  }
});
