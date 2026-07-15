import type { ProductDetail, ProductSku } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import { addCartItem } from "../../../services/cart";
import { formatPrice, getProductDetail } from "../../../services/product";
import {
  buildProductCommand,
  clampQuantity
} from "../../../features/checkout";

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface GalleryImage {
  id: number;
  url: string;
}

interface SkuView extends ProductSku {
  priceText: string;
  stockText: string;
  selected: boolean;
  disabled: boolean;
}

function parsePositiveNumber(value: string | undefined): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function toGalleryImages(detail: ProductDetail): GalleryImage[] {
  const images = detail.images.length > 0
    ? detail.images
    : [{ id: detail.id, url: detail.mainImage, sortOrder: 0 }];

  return images
    .filter((image) => image.url)
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .map((image) => ({
      id: image.id,
      url: image.url
    }));
}

function findDefaultSku(skus: ProductSku[]): ProductSku | undefined {
  return skus.find((sku) => sku.status === "ENABLED" && sku.stockAvailable > 0);
}

function toSkuViews(skus: ProductSku[], selectedSkuId: number): SkuView[] {
  return skus.map((sku) => {
    const disabled = sku.status !== "ENABLED" || sku.stockAvailable <= 0;
    return {
      ...sku,
      priceText: formatPrice(sku.priceCent),
      stockText: sku.stockAvailable > 0 ? `库存 ${sku.stockAvailable}` : "售罄",
      selected: sku.id === selectedSkuId,
      disabled
    };
  });
}

Page({
  data: {
    detail: null as ProductDetail | null,
    galleryImages: [] as GalleryImage[],
    skuViews: [] as SkuView[],
    selectedSkuId: 0,
    selectedQuantity: 1,
    selectedQuantityMax: 0,
    selectedPriceText: "",
    selectedStockText: "",
    addingCart: false,
    buying: false,
    loading: false,
    errorText: ""
  },
  async onLoad(options: Record<string, string | undefined>) {
    const productId = parsePositiveNumber(options.id);
    if (!productId) {
      this.setData({
        errorText: "商品不存在"
      });
      return;
    }

    await this.loadDetail(productId);
  },
  async loadDetail(productId: number) {
    this.setData({
      loading: true,
      errorText: ""
    });

    try {
      const detail = await getProductDetail(productId);
      const selectedSku = findDefaultSku(detail.skus);
      const selectedSkuId = selectedSku ? selectedSku.id : 0;
      const skuViews = toSkuViews(detail.skus, selectedSkuId);

      this.setData({
        detail,
        galleryImages: toGalleryImages(detail),
        skuViews,
        selectedSkuId,
        selectedQuantity: 1,
        selectedQuantityMax: selectedSku
          ? Math.min(999, selectedSku.stockAvailable)
          : 0,
        selectedPriceText: selectedSku ? formatPrice(selectedSku.priceCent) : "暂无价格",
        selectedStockText: selectedSku ? `库存 ${selectedSku.stockAvailable}` : "暂无可售规格"
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "商品详情加载失败"
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  },
  onSkuTap(event: DatasetEvent) {
    const skuId = Number(event.currentTarget.dataset.id);
    if (!Number.isFinite(skuId) || skuId <= 0 || !this.data.detail) {
      return;
    }

    const selectedSku = this.data.detail.skus.find((sku) => sku.id === skuId);
    if (!selectedSku) {
      return;
    }
    if (selectedSku.status !== "ENABLED") {
      wx.showToast({ title: "该规格已下架", icon: "none" });
      return;
    }
    if (selectedSku.stockAvailable <= 0) {
      wx.showToast({ title: "该规格已售罄", icon: "none" });
      return;
    }

    this.setData({
      selectedSkuId: selectedSku.id,
      selectedQuantity: clampQuantity(
        this.data.selectedQuantity,
        selectedSku.stockAvailable
      ),
      selectedQuantityMax: Math.min(999, selectedSku.stockAvailable),
      skuViews: toSkuViews(this.data.detail.skus, selectedSku.id),
      selectedPriceText: formatPrice(selectedSku.priceCent),
      selectedStockText: `库存 ${selectedSku.stockAvailable}`
    });
  },
  onQuantityMinus() {
    const selectedSku = this.getSelectedSku();
    if (!selectedSku) {
      return;
    }
    this.setData({
      selectedQuantity: clampQuantity(
        this.data.selectedQuantity - 1,
        selectedSku.stockAvailable
      )
    });
  },
  onQuantityPlus() {
    const selectedSku = this.getSelectedSku();
    if (!selectedSku) {
      return;
    }
    this.setData({
      selectedQuantity: clampQuantity(
        this.data.selectedQuantity + 1,
        selectedSku.stockAvailable
      )
    });
  },
  async onAddCartTap() {
    if (this.data.addingCart) {
      return;
    }

    const command = buildProductCommand(
      "ADD_TO_CART",
      this.getSelectedSku(),
      this.data.selectedQuantity
    );
    if (command.type === "ERROR") {
      wx.showToast({ title: command.message, icon: "none" });
      return;
    }
    if (command.type !== "ADD_TO_CART") {
      return;
    }

    this.setData({
      addingCart: true
    });

    try {
      await ensureAppLogin();
      await addCartItem(command.payload);
      wx.showToast({
        title: "已加入购物车",
        icon: "success"
      });
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "加入失败",
        icon: "none"
      });
    } finally {
      this.setData({
        addingCart: false
      });
    }
  },
  async onBuyNowTap() {
    if (this.data.buying) {
      return;
    }
    const command = buildProductCommand(
      "DIRECT_BUY",
      this.getSelectedSku(),
      this.data.selectedQuantity
    );
    if (command.type === "ERROR") {
      wx.showToast({ title: command.message, icon: "none" });
      return;
    }
    if (command.type !== "DIRECT_BUY") {
      return;
    }

    this.setData({ buying: true });
    try {
      await ensureAppLogin();
      await wx.navigateTo({ url: command.url });
    } catch (error) {
      wx.showToast({
        title: error instanceof Error ? error.message : "暂时无法购买",
        icon: "none"
      });
    } finally {
      this.setData({ buying: false });
    }
  },
  onCustomerServiceTap() {
    if (!this.data.detail) {
      return;
    }
    wx.navigateTo({
      url: `/pages/customer-service/chat/chat?context_type=PRODUCT&context_id=${this.data.detail.id}`
    });
  },
  getSelectedSku(): ProductSku | undefined {
    if (!this.data.detail || this.data.selectedSkuId <= 0) {
      return undefined;
    }
    return this.data.detail.skus.find(
      (sku) => sku.id === this.data.selectedSkuId
    );
  }
});
