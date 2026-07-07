import type { ProductCategory, ProductListItem } from "../../types/api";
import { formatProductPriceRange, getProductCategories, getProductList } from "../../services/product";
import { request } from "../../utils/request";

interface HealthStatus {
  status: string;
  service: string;
}

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface CategoryView extends ProductCategory {
  iconText: string;
  iconUrl: string;
}

interface ProductCardView extends ProductListItem {
  priceText: string;
  stockText: string;
  soldOut: boolean;
}

function toCategoryView(category: ProductCategory): CategoryView {
  const icon = category.icon || "";
  return {
    ...category,
    iconText: icon.startsWith("http") ? category.name.charAt(0) : icon || category.name.charAt(0),
    iconUrl: icon.startsWith("http") ? icon : ""
  };
}

function toProductCard(product: ProductListItem): ProductCardView {
  return {
    ...product,
    priceText: formatProductPriceRange(product),
    stockText: product.totalStock > 0 ? `库存 ${product.totalStock}` : "暂时售罄",
    soldOut: product.totalStock <= 0
  };
}

Page({
  data: {
    healthText: "后端诊断: 正在连接...",
    categories: [] as CategoryView[],
    products: [] as ProductCardView[],
    productErrorText: "",
    loadingProducts: false
  },
  async onLoad() {
    this.loadHealth();
    this.loadProductPreview();
  },
  async loadHealth() {
    try {
      const health = await request<HealthStatus>({ url: "/app/health", auth: false });
      this.setData({
        healthText: `后端诊断: ${health.service} ${health.status}`
      });
    } catch (error) {
      this.setData({
        healthText: `后端诊断: ${error instanceof Error ? error.message : "后端暂不可用"}`
      });
    }
  },
  async loadProductPreview() {
    this.setData({
      loadingProducts: true,
      productErrorText: ""
    });

    try {
      const [categories, productPage] = await Promise.all([
        getProductCategories(),
        getProductList({ current: 1, size: 6 })
      ]);

      this.setData({
        categories: categories.map(toCategoryView),
        products: productPage.records.map(toProductCard)
      });
    } catch (error) {
      this.setData({
        productErrorText: error instanceof Error ? error.message : "商品暂不可用"
      });
    } finally {
      this.setData({
        loadingProducts: false
      });
    }
  },
  onCategoryTap(event: DatasetEvent) {
    const categoryId = Number(event.currentTarget.dataset.id);
    if (!Number.isFinite(categoryId) || categoryId <= 0) {
      return;
    }

    wx.reLaunch({
      url: `/pages/product/list/list?categoryId=${categoryId}`
    });
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
