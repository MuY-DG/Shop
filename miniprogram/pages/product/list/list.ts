import type { ProductCategory, ProductListItem } from "../../../types/api";
import { formatPrice, getProductCategories, getProductList } from "../../../services/product";

const PAGE_SIZE = 10;

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface CategoryTab {
  id: number;
  name: string;
  selected: boolean;
}

interface ProductCardView extends ProductListItem {
  priceText: string;
  stockText: string;
  soldOut: boolean;
}

function parsePositiveNumber(value: string | undefined): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function toCategoryTabs(categories: ProductCategory[], activeCategoryId: number): CategoryTab[] {
  return [
    {
      id: 0,
      name: "全部",
      selected: activeCategoryId === 0
    },
    ...categories.map((category) => ({
      id: category.id,
      name: category.name,
      selected: category.id === activeCategoryId
    }))
  ];
}

function toProductCard(product: ProductListItem): ProductCardView {
  const priceText = product.minPriceCent === product.maxPriceCent
    ? formatPrice(product.minPriceCent)
    : `${formatPrice(product.minPriceCent)} - ${formatPrice(product.maxPriceCent)}`;

  return {
    ...product,
    priceText,
    stockText: product.totalStock > 0 ? `库存 ${product.totalStock}` : "暂时售罄",
    soldOut: product.totalStock <= 0
  };
}

Page({
  data: {
    categories: [] as ProductCategory[],
    categoryTabs: [] as CategoryTab[],
    activeCategoryId: 0,
    products: [] as ProductCardView[],
    current: 1,
    total: 0,
    loading: false,
    loadingMore: false,
    errorText: ""
  },
  async onLoad(options: Record<string, string | undefined>) {
    const activeCategoryId = parsePositiveNumber(options.categoryId);
    this.setData({
      activeCategoryId
    });

    await Promise.all([
      this.loadCategories(),
      this.loadFirstPage()
    ]);
  },
  async loadCategories() {
    try {
      const categories = await getProductCategories();
      this.setData({
        categories,
        categoryTabs: toCategoryTabs(categories, this.data.activeCategoryId)
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "分类加载失败"
      });
    }
  },
  async loadFirstPage() {
    this.setData({
      loading: true,
      errorText: ""
    });

    try {
      const result = await getProductList({
        current: 1,
        size: PAGE_SIZE,
        categoryId: this.data.activeCategoryId || undefined
      });

      this.setData({
        products: result.records.map(toProductCard),
        current: result.current,
        total: result.total
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "商品加载失败"
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  },
  async onCategoryTap(event: DatasetEvent) {
    const categoryId = Number(event.currentTarget.dataset.id);
    if (!Number.isFinite(categoryId) || categoryId < 0 || categoryId === this.data.activeCategoryId) {
      return;
    }

    this.setData({
      activeCategoryId: categoryId,
      categoryTabs: toCategoryTabs(this.data.categories, categoryId),
      products: [],
      current: 1,
      total: 0
    });
    await this.loadFirstPage();
  },
  async onPullDownRefresh() {
    await Promise.all([
      this.loadCategories(),
      this.loadFirstPage()
    ]);
    wx.stopPullDownRefresh();
  },
  async onReachBottom() {
    if (this.data.loading || this.data.loadingMore || this.data.products.length >= this.data.total) {
      return;
    }

    this.setData({
      loadingMore: true,
      errorText: ""
    });

    try {
      const result = await getProductList({
        current: this.data.current + 1,
        size: PAGE_SIZE,
        categoryId: this.data.activeCategoryId || undefined
      });

      this.setData({
        products: this.data.products.concat(result.records.map(toProductCard)),
        current: result.current,
        total: result.total
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "加载更多失败"
      });
    } finally {
      this.setData({
        loadingMore: false
      });
    }
  },
  onProductTap(event: DatasetEvent) {
    const productId = Number(event.currentTarget.dataset.id);
    if (!Number.isFinite(productId) || productId <= 0) {
      return;
    }

    wx.navigateTo({
      url: `/pages/product/detail/detail?id=${productId}`
    });
  }
});
