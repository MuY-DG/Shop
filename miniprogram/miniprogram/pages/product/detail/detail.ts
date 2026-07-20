import {
  buildGalleryImages,
  buildParameterViews,
  buildSpecificationPreviewUrls,
  buildSkuSpecificationGroups,
  findDefaultSku,
  formatMoney,
  parsePositiveId,
  resolvePurchaseSelection,
  resolveSkuSpecificationSelection,
  type GalleryImageView,
  type ProductParameterView,
  type PurchaseSelectionView,
  type SkuSpecificationGroupView,
  type WholesaleTierView
} from "../../../features/product-catalog";
import { addCartItem } from "../../../services/cart";
import { getProductDetail } from "../../../services/product";
import type {
  ProductDetail,
  ProductFreightTemplate,
  ProductSku
} from "../../../types/product";
import { isApiError } from "../../../utils/api-error";

interface PageOptions {
  id?: string;
}

type InfoSheet = "guarantee" | "freight" | "wholesale";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
      mode?: "CART" | "BUY";
      quantity?: number | string;
      sheet?: InfoSheet;
      specName?: string;
      specValue?: string;
      imageUrl?: string;
    };
  };
}

interface BenefitItemView {
  key: string;
  text: string;
  iconUrl: string;
  sheet: "" | InfoSheet;
  interactive: boolean;
}

interface ParameterViewGroups {
  weightParameter: ProductParameterView | null;
  spiceParameter: ProductParameterView | null;
  otherParameterViews: ProductParameterView[];
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

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

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

function freightCopy(template: ProductFreightTemplate | undefined): {
  summary: string;
  chargeText: string;
} {
  if (!template || !cleanText(template.name)) {
    return { summary: "", chargeText: "" };
  }
  const chargeText = template.chargeMode === "FREE"
    ? "免运费"
    : `固定运费 ¥${formatMoney(template.fixedAmountCent) || "0.00"}`;
  return {
    summary: `${cleanText(template.name)} · ${chargeText}`,
    chargeText
  };
}

function parameterViewGroups(parameters: ProductParameterView[]): ParameterViewGroups {
  const weightParameter = parameters.find((parameter) => parameter.fact.kind === "weight") ?? null;
  const spiceParameter = parameters.find((parameter) => parameter.fact.kind === "spice") ?? null;
  return {
    weightParameter,
    spiceParameter,
    otherParameterViews: parameters.filter((parameter) => (
      parameter !== weightParameter && parameter !== spiceParameter
    ))
  };
}

function buildBenefitItems(
  detail: ProductDetail,
  freightSummary: string
): BenefitItemView[] {
  const guaranteeItems = detail.guaranteeServices
    .map((service) => ({
      key: `guarantee-${service.id}`,
      text: cleanText(service.termsName),
      iconUrl: cleanText(service.icon),
      sheet: "guarantee" as const,
      interactive: true
    }))
    .filter((item) => item.text);
  const freightItems: BenefitItemView[] = freightSummary
    ? [{
      key: "freight",
      text: freightSummary,
      iconUrl: "/assets/icons/local-shipping-outline-rounded.svg",
      sheet: "freight",
      interactive: true
    }]
    : [];
  const sellingPointItems: BenefitItemView[] = detail.sellingPoints
    .map((point, index) => ({
      key: `selling-point-${index}`,
      text: cleanText(point),
      iconUrl: "",
      sheet: "" as const,
      interactive: false
    }))
    .filter((item) => item.text);
  return [...guaranteeItems, ...freightItems, ...sellingPointItems];
}

function wholesaleSummary(tiers: WholesaleTierView[]): string {
  return tiers
    .slice(0, 2)
    .map((tier) => `${tier.minQuantity}件起 ¥${tier.priceText}/件`)
    .join(" · ");
}

function favoriteStorageKey(productId: number): string {
  return `zaoxiangji:favorite:product:${productId}`;
}

function cachePreviewImage(url: string): Promise<string> {
  return new Promise((resolve) => {
    wx.getImageInfo({
      src: url,
      success: (result) => resolve(cleanText(result.path) || url),
      fail: () => resolve(url)
    });
  });
}

async function openImagePreview(
  currentUrl: string,
  specificationGroups: SkuSpecificationGroupView[]
): Promise<void> {
  const current = cleanText(currentUrl);
  if (!current) {
    return;
  }
  const specificationUrls = buildSpecificationPreviewUrls(specificationGroups, current);
  const urls = specificationUrls.includes(current) ? specificationUrls : [current];
  wx.showLoading({ title: "图片加载中", mask: true });
  const cachedUrls = await Promise.all(urls.map(cachePreviewImage));
  wx.hideLoading();
  wx.previewImage({
    current: cachedUrls[0] ?? current,
    urls: cachedUrls.length ? cachedUrls : urls
  });
}

Page({
  data: {
    productId: 0,
    detail: null as ProductDetail | null,
    galleryImages: [] as GalleryImageView[],
    parameterViews: [] as ProductParameterView[],
    weightParameter: null as ProductParameterView | null,
    spiceParameter: null as ProductParameterView | null,
    otherParameterViews: [] as ProductParameterView[],
    benefitItems: [] as BenefitItemView[],
    specificationGroups: [] as SkuSpecificationGroupView[],
    specificationImageMode: "list" as "list" | "image",
    ...EMPTY_SELECTION,
    selectedSkuName: "",
    purchaseImageUrl: "",
    freightSummary: "",
    freightChargeText: "",
    wholesaleSummary: "",
    addressText: "",
    favorited: false,
    activeSheet: "" as "" | "purchase" | "guarantee" | "freight" | "wholesale",
    purchaseMode: "BUY" as "CART" | "BUY",
    purchaseActionText: "立即购买",
    confirmLoading: false,
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
    let favorited = false;
    try {
      favorited = wx.getStorageSync(favoriteStorageKey(productId)) === true;
    } catch {
      favorited = false;
    }
    this.setData({ productId, favorited });
    void this.loadDetail();
  },

  onUnload() {
    latestDetailRequest += 1;
  },

  onShareAppMessage() {
    const detail = this.data.detail;
    return {
      title: detail?.title || "灶香集好物",
      path: `/pages/product/detail/detail?id=${this.data.productId}`,
      imageUrl: detail?.mainImage || this.data.galleryImages[0]?.url || ""
    };
  },

  async loadDetail() {
    if (!this.data.productId) {
      return;
    }
    const requestId = ++latestDetailRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const detail = await getProductDetail(this.data.productId);
      if (requestId !== latestDetailRequest) {
        return;
      }
      const normalizedDetail: ProductDetail = {
        ...detail,
        salesCount: Number.isSafeInteger(detail.salesCount) && detail.salesCount >= 0
          ? detail.salesCount
          : 0,
        sellingPoints: Array.isArray(detail.sellingPoints) ? detail.sellingPoints : [],
        images: Array.isArray(detail.images) ? detail.images : [],
        skus: Array.isArray(detail.skus) ? detail.skus : [],
        parameters: Array.isArray(detail.parameters) ? detail.parameters : [],
        guaranteeServices: Array.isArray(detail.guaranteeServices)
          ? detail.guaranteeServices
          : []
      };
      const selectedSku = findDefaultSku(normalizedDetail.skus);
      const selection = resolvePurchaseSelection(selectedSku, 1);
      const freight = freightCopy(normalizedDetail.freightTemplate);
      const parameterViews = buildParameterViews(normalizedDetail.parameters);
      const parameterGroups = parameterViewGroups(parameterViews);
      this.setData({
        detail: normalizedDetail,
        galleryImages: buildGalleryImages(normalizedDetail),
        parameterViews,
        ...parameterGroups,
        benefitItems: buildBenefitItems(normalizedDetail, freight.summary),
        specificationGroups: buildSkuSpecificationGroups(
          normalizedDetail.skus,
          selection.selectedSkuId
        ),
        specificationImageMode: "list",
        ...selection,
        selectedSkuName: cleanText(selectedSku?.specText) || "默认规格",
        purchaseImageUrl: cleanText(selectedSku?.image) || cleanText(normalizedDetail.mainImage),
        freightSummary: freight.summary,
        freightChargeText: freight.chargeText,
        wholesaleSummary: wholesaleSummary(selection.wholesaleTiers),
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId !== latestDetailRequest) {
        return;
      }
      this.setData({
        detail: null,
        galleryImages: [],
        parameterViews: [],
        weightParameter: null,
        spiceParameter: null,
        otherParameterViews: [],
        benefitItems: [],
        specificationGroups: [],
        specificationImageMode: "list",
        ...EMPTY_SELECTION,
        loading: false,
        loaded: false,
        errorText: detailErrorMessage(error)
      });
    }
  },

  onRetry() {
    void this.loadDetail();
  },

  onInfoSheetOpen(event: DatasetEvent) {
    const sheet = event.currentTarget.dataset.sheet;
    if (sheet) {
      this.setData({ activeSheet: sheet });
    }
  },

  onOpenPurchase(event: DatasetEvent) {
    if (!this.data.selectedSkuId) {
      wx.showToast({ title: "暂无可售规格", icon: "none" });
      return;
    }
    const purchaseMode = event.currentTarget.dataset.mode === "CART" ? "CART" : "BUY";
    this.setData({
      activeSheet: "purchase",
      purchaseMode,
      purchaseActionText: purchaseMode === "CART" ? "加入购物车" : "立即购买"
    });
  },

  onCloseSheet() {
    if (!this.data.confirmLoading) {
      this.setData({ activeSheet: "" });
    }
  },

  onPreventMove() {},

  onSheetSpecificationSelect(event: DatasetEvent) {
    if (!this.data.detail) {
      return;
    }
    const sku = resolveSkuSpecificationSelection(
      this.data.detail.skus,
      this.data.selectedSkuId,
      event.currentTarget.dataset.specName,
      event.currentTarget.dataset.specValue
    );
    if (!sku) {
      wx.showToast({ title: "该规格组合暂不可选", icon: "none" });
      return;
    }
    if (sku.id === this.data.selectedSkuId) {
      return;
    }
    this.applySelection(sku, 1);
  },

  onSpecificationImageModeToggle() {
    this.setData({
      specificationImageMode: this.data.specificationImageMode === "list" ? "image" : "list"
    });
  },

  onPreviewPurchaseImage() {
    openImagePreview(
      this.data.purchaseImageUrl,
      this.data.specificationGroups
    );
  },

  onPreviewSpecificationImage(event: DatasetEvent) {
    openImagePreview(
      cleanText(event.currentTarget.dataset.imageUrl),
      this.data.specificationGroups
    );
  },

  onWholesaleQuantityTap(event: DatasetEvent) {
    const quantity = parsePositiveId(event.currentTarget.dataset.quantity);
    const sku = this.selectedSku();
    if (!quantity || !sku) {
      return;
    }
    if (quantity > this.data.quantityMax) {
      wx.showToast({ title: "当前可购买数量不足以使用该档批发价", icon: "none" });
      return;
    }
    this.applySelection(sku, quantity);
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

  async onPurchaseConfirm() {
    if (this.data.confirmLoading || !this.data.selectedSkuId) {
      return;
    }
    if (this.data.purchaseMode === "BUY") {
      this.setData({ activeSheet: "" });
      wx.showToast({ title: `已选择 ${this.data.quantity} 件，结算页即将开放`, icon: "none" });
      return;
    }
    this.setData({ confirmLoading: true });
    try {
      await addCartItem({
        skuId: this.data.selectedSkuId,
        quantity: this.data.quantity
      });
      this.setData({ activeSheet: "", confirmLoading: false });
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch (error) {
      this.setData({ confirmLoading: false });
      wx.showToast({
        title: isApiError(error) ? error.message : "加入购物车失败，请稍后重试",
        icon: "none"
      });
    }
  },

  onFavoriteToggle() {
    const favorited = !this.data.favorited;
    try {
      wx.setStorageSync(favoriteStorageKey(this.data.productId), favorited);
      this.setData({ favorited });
      wx.showToast({ title: favorited ? "已收藏" : "已取消收藏", icon: "none" });
    } catch {
      wx.showToast({ title: "收藏状态保存失败", icon: "none" });
    }
  },

  onGoToCart() {
    wx.switchTab({ url: "/pages/cart/cart" });
  },

  onChooseAddress() {
    wx.chooseAddress({
      success: (address) => {
        const region = [address.provinceName, address.cityName, address.countyName]
          .map(cleanText)
          .filter(Boolean)
          .join(" ");
        this.setData({
          addressText: [region, cleanText(address.detailInfo)].filter(Boolean).join(" ")
        });
      },
      fail: (error) => {
        if (!cleanText(error.errMsg).includes("cancel")) {
          wx.showToast({ title: "地址选择失败，请稍后重试", icon: "none" });
        }
      }
    });
  },

  applySelection(sku: ProductSku, quantity: number) {
    const selection = resolvePurchaseSelection(sku, quantity);
    const fallbackImage = this.data.detail?.mainImage ?? "";
    this.setData({
      ...selection,
      specificationGroups: buildSkuSpecificationGroups(
        this.data.detail?.skus ?? [],
        selection.selectedSkuId
      ),
      selectedSkuName: cleanText(sku.specText) || "默认规格",
      purchaseImageUrl: cleanText(sku.image) || cleanText(fallbackImage),
      wholesaleSummary: wholesaleSummary(selection.wholesaleTiers)
    });
  },

  selectedSku(): ProductSku | undefined {
    if (!this.data.detail || !this.data.selectedSkuId) {
      return undefined;
    }
    return this.data.detail.skus.find((sku) => sku.id === this.data.selectedSkuId);
  }
});
