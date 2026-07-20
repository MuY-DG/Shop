import {
  buildGalleryImages,
  buildParameterViews,
  buildSkuOptions,
  findDefaultSku,
  parsePositiveId,
  resolvePurchaseSelection,
  type GalleryImageView,
  type ProductParameterView,
  type PurchaseSelectionView,
  type SkuOptionView
} from "../../../features/product-catalog";
import { getProductDetail } from "../../../services/product";
import type { ProductDetail, ProductSku } from "../../../types/product";
import { isApiError } from "../../../utils/api-error";

interface PageOptions {
  id?: string;
}

interface SkuSelectEvent {
  detail: {
    id?: number | string;
  };
}

const EMPTY_SELECTION: PurchaseSelectionView = {
  selectedSkuId: 0,
  quantity: 1,
  quantityMax: 0,
  priceText: "",
  priceIntegerText: "",
  priceDecimalText: "",
  originalPriceText: "",
  hasOriginalPrice: false,
  stockText: "暂无可售规格",
  wholesaleApplied: false,
  wholesaleHint: "",
  wholesaleTiers: []
};

let latestDetailRequest = 0;

function detailErrorMessage(error: unknown): string {
  if (!isApiError(error)) {
    return error instanceof Error ? error.message : "商品详情加载失败，请稍后重试";
  }
  switch (error.kind) {
    case "NETWORK":
      return "网络连接失败，请检查网络后重试";
    case "RATE_LIMIT":
      return "请求有点频繁，请稍后再试";
    case "SERVER":
      return "商品服务暂时不可用，请稍后重试";
    case "PROTOCOL":
      return "商品详情格式异常，请稍后重试";
    default:
      return error.message || "商品详情加载失败，请稍后重试";
  }
}

Page({
  data: {
    productId: 0,
    detail: null as ProductDetail | null,
    galleryImages: [] as GalleryImageView[],
    parameterViews: [] as ProductParameterView[],
    skuOptions: [] as SkuOptionView[],
    ...EMPTY_SELECTION,
    loading: true,
    loaded: false,
    errorText: ""
  },

  onLoad(options: PageOptions) {
    const productId = parsePositiveId(options.id);
    if (!productId) {
      this.setData({
        loading: false,
        loaded: false,
        errorText: "商品参数无效，请返回后重试"
      });
      return;
    }
    this.setData({ productId });
    void this.loadDetail();
  },

  onUnload() {
    latestDetailRequest += 1;
  },

  async onPullDownRefresh() {
    await this.loadDetail(true);
    wx.stopPullDownRefresh();
  },

  async loadDetail(preserveContent = false) {
    if (!this.data.productId) {
      return;
    }
    const requestId = ++latestDetailRequest;
    const keepCurrentContent = preserveContent && this.data.loaded;
    this.setData({
      loading: true,
      errorText: keepCurrentContent ? this.data.errorText : ""
    });
    try {
      const detail = await getProductDetail(this.data.productId);
      if (requestId !== latestDetailRequest) {
        return;
      }
      const selectedSku = findDefaultSku(detail.skus);
      const selection = resolvePurchaseSelection(selectedSku, 1);
      this.setData({
        detail,
        galleryImages: buildGalleryImages(detail),
        parameterViews: buildParameterViews(detail.parameters),
        skuOptions: buildSkuOptions(detail.skus, selection.selectedSkuId),
        ...selection,
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId !== latestDetailRequest) {
        return;
      }
      const message = detailErrorMessage(error);
      if (keepCurrentContent) {
        this.setData({ loading: false });
        wx.showToast({ title: "刷新失败，已保留当前详情", icon: "none" });
      } else {
        this.setData({
          detail: null,
          galleryImages: [],
          parameterViews: [],
          skuOptions: [],
          ...EMPTY_SELECTION,
          loading: false,
          loaded: false,
          errorText: message
        });
      }
    }
  },

  onRetry() {
    void this.loadDetail();
  },

  onSkuSelect(event: SkuSelectEvent) {
    const skuId = parsePositiveId(event.detail.id);
    if (!skuId || !this.data.detail) {
      return;
    }
    const sku = this.data.detail.skus.find((item) => item.id === skuId);
    if (!sku) {
      return;
    }
    if (sku.status !== "ENABLED") {
      wx.showToast({ title: "该规格已下架", icon: "none" });
      return;
    }
    if (sku.stockAvailable <= 0) {
      wx.showToast({ title: "该规格已售罄", icon: "none" });
      return;
    }
    this.applySelection(sku, this.data.quantity);
  },

  onQuantityMinus() {
    const sku = this.selectedSku();
    if (!sku || this.data.quantity <= 1) {
      return;
    }
    this.applySelection(sku, this.data.quantity - 1);
  },

  onQuantityPlus() {
    const sku = this.selectedSku();
    if (!sku || this.data.quantity >= this.data.quantityMax) {
      return;
    }
    this.applySelection(sku, this.data.quantity + 1);
  },

  applySelection(sku: ProductSku, quantity: number) {
    const selection = resolvePurchaseSelection(sku, quantity);
    this.setData({
      ...selection,
      skuOptions: buildSkuOptions(this.data.detail?.skus ?? [], selection.selectedSkuId)
    });
  },

  selectedSku(): ProductSku | undefined {
    if (!this.data.detail || !this.data.selectedSkuId) {
      return undefined;
    }
    return this.data.detail.skus.find((sku) => sku.id === this.data.selectedSkuId);
  }
});
