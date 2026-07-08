import type { HomeBanner, ProductCategory, ProductListItem } from "../../types/api";
import { getHomeBanners } from "../../services/home";
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

const TAB_PAGE_PATHS = new Set([
  "/pages/home/home",
  "/pages/product/list/list",
  "/pages/cart/cart",
  "/pages/profile/profile"
]);

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

function normalizeAppPath(path: string): string {
  const trimmed = path.trim();
  if (!trimmed) {
    return "";
  }

  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

function toPagePath(path: string): string {
  return normalizeAppPath(path).split("?")[0] || "";
}

function isTabPath(path: string): boolean {
  return TAB_PAGE_PATHS.has(toPagePath(path));
}

Page({
  data: {
    healthText: "后端诊断: 正在连接...",
    banners: [] as HomeBanner[],
    categories: [] as CategoryView[],
    products: [] as ProductCardView[],
    productErrorText: "",
    loadingProducts: false
  },
  async onLoad() {
    this.loadHealth();
    void this.loadBanners();
    void this.loadCategories();
    void this.loadProducts();
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
  async loadBanners() {
    this.setData({
      banners: []
    });

    try {
      const banners = await getHomeBanners();
      this.setData({
        banners
      });
    } catch {
      this.setData({
        banners: []
      });
    }
  },
  async loadCategories() {
    try {
      const categories = await getProductCategories();
      this.setData({
        categories: categories.map(toCategoryView)
      });
    } catch {
      this.setData({
        categories: []
      });
    }
  },
  async loadProducts() {
    this.setData({
      loadingProducts: true,
      productErrorText: ""
    });

    try {
      const products = await getProductList({ current: 1, size: 6 });
      this.setData({
        products: products.records.map(toProductCard)
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
  },
  onBannerTap(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isFinite(index) || index < 0) {
      return;
    }

    const banner = (this.data.banners as HomeBanner[])[index];
    if (!banner) {
      return;
    }

    switch (banner.jumpType) {
      case "PRODUCT": {
        const productId = Number(banner.jumpTargetId);
        if (!Number.isFinite(productId) || productId <= 0) {
          return;
        }
        wx.navigateTo({
          url: `/pages/product/detail/detail?id=${productId}`
        });
        return;
      }
      case "CATEGORY": {
        const categoryId = Number(banner.jumpTargetId);
        if (!Number.isFinite(categoryId) || categoryId <= 0) {
          return;
        }
        wx.reLaunch({
          url: `/pages/product/list/list?categoryId=${categoryId}`
        });
        return;
      }
      case "APP_PATH": {
        const jumpPath = normalizeAppPath(banner.jumpPath || "");
        if (!jumpPath) {
          return;
        }

        if (isTabPath(jumpPath)) {
          if (jumpPath.includes("?")) {
            wx.reLaunch({
              url: jumpPath
            });
            return;
          }

          wx.switchTab({
            url: toPagePath(jumpPath)
          });
          return;
        }

        wx.navigateTo({
          url: jumpPath
        });
        return;
      }
      case "NONE":
      case "URL":
      case "COUPON":
      default:
        return;
    }
  }
});
