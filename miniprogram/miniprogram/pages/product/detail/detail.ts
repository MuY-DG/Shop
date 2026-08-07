import {
  buildDirectBuyUrl,
  resolveAddressSelection
} from "../../../features/checkout";
import { buildCustomerServiceUrl } from "../../../features/customer-service";
import {
  buildGalleryImages,
  buildParameterViews,
  buildSpecificationPreviewUrls,
  buildVisibleSkuSpecificationGroups,
  displaySpecText,
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
import {
  buildProductReviewSummaryView,
  buildPublicProductReviewViews,
  type ProductReviewSummaryView,
  type PublicProductReviewView
} from "../../../features/product-review";
import { getAddresses } from "../../../services/address";
import { addCartItem } from "../../../services/cart";
import { cartAddErrorMessage } from "../../../features/cart-feedback";
import {
  normalizeQuantityInput,
  stockQuantityCorrectedMessage
} from "../../../features/quantity";
import {
  getProductDetail,
  getProductReviews
} from "../../../services/product";
import {
  addFavorite,
  getFavoriteStatus,
  recordProductBrowse,
  removeFavorite
} from "../../../services/product-preference";
import { getSessionState } from "../../../services/session";
import type { AddressResponse } from "../../../types/checkout";
import type {
  ProductReviewFilter,
  ProductReviewSort
} from "../../../types/product-engagement";
import type {
  ProductDetail,
  ProductFreightTemplate,
  ProductSku
} from "../../../types/product";
import { isApiError } from "../../../utils/api-error";
import { openLoginPage } from "../../../utils/login-navigation";
import { enableNativeShareMenu } from "../../../utils/share";

interface PageOptions {
  id?: string;
}

type InfoSheet = "parameters" | "guarantee" | "freight" | "wholesale";
type ActiveSheet = "" | "address" | InfoSheet | "reviews";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
      mode?: "CART" | "BUY";
      quantity?: number | string;
      sheet?: InfoSheet;
      specName?: string;
      specValue?: string;
      reviewFilter?: ProductReviewFilter;
      reviewSpecText?: string;
      imageUrl?: string;
      reviewId?: number | string;
    };
  };
}

interface QuantityInputEvent {
  detail: {
    value: string;
  };
}

interface ParameterViewGroups {
  weightParameter: ProductParameterView | null;
  spiceParameter: ProductParameterView | null;
  otherParameterViews: ProductParameterView[];
}

interface ReviewSpecOptionView {
  value: string;
  label: string;
}

interface ReviewContentRect {
  height?: number;
  dataset?: {
    reviewId?: number | string;
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
  wholesaleApplied: false,
  wholesaleHint: "",
  wholesaleTiers: []
};

const EMPTY_REVIEW_SUMMARY = buildProductReviewSummaryView();
const REVIEW_PREVIEW_SIZE = 2;
const REVIEW_PAGE_SIZE = 10;
const SHEET_EXIT_DURATION_MS = 340;

let latestDetailRequest = 0;
let latestAddressRequest = 0;
let latestReviewPreviewRequest = 0;
let latestReviewPageRequest = 0;
let sheetCloseTimer: ReturnType<typeof setTimeout> | null = null;
let purchaseSheetCloseTimer: ReturnType<typeof setTimeout> | null = null;

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
    summary: `${cleanText(template.name)}｜${chargeText}`,
    chargeText
  };
}

function guaranteeSummary(detail: ProductDetail): string {
  return detail.guaranteeServices
    .map((service) => cleanText(service.termsName))
    .filter(Boolean)
    .join("｜");
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

function wholesaleSummary(tiers: WholesaleTierView[]): string {
  return tiers
    .slice(0, 2)
    .map((tier) => `${tier.minQuantity}件起 ¥${tier.priceText}/件`)
    .join(" · ");
}

function buildReviewSpecOptions(
  skus: ProductSku[],
  specType: ProductDetail["specType"]
): ReviewSpecOptionView[] {
  if (specType === "SINGLE") {
    return [];
  }
  const values = new Set<string>();
  for (const sku of skus) {
    const value = cleanText(sku.specText);
    if (value) {
      values.add(value);
    }
  }
  return [...values].map((value) => ({
    value,
    label: displaySpecText(value)
  }));
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
    specificationGroups: [] as SkuSpecificationGroupView[],
    specificationImageMode: "list" as "list" | "image",
    ...EMPTY_SELECTION,
    selectedSkuName: "",
    purchaseImageUrl: "",
    guaranteeSummary: "",
    freightSummary: "",
    freightChargeText: "",
    wholesaleSummary: "",
    addresses: [] as AddressResponse[],
    selectedAddress: null as AddressResponse | null,
    addressLoading: false,
    addressLoaded: false,
    addressErrorText: "",
    reviewSummary: EMPTY_REVIEW_SUMMARY as ProductReviewSummaryView,
    reviewPreview: [] as PublicProductReviewView[],
    reviewPreviewLoading: false,
    reviewPreviewErrorText: "",
    reviewRecords: [] as PublicProductReviewView[],
    reviewCurrent: 0,
    reviewTotal: 0,
    reviewHasMore: false,
    reviewLoading: false,
    reviewErrorText: "",
    reviewFilter: "ALL" as ProductReviewFilter,
    reviewSort: "RECOMMENDED" as ProductReviewSort,
    reviewSpecText: "",
    reviewSpecLabel: "",
    reviewSpecDraftText: "",
    reviewSpecSheetOpen: false,
    reviewSpecOptions: [] as ReviewSpecOptionView[],
    favorited: false,
    favoriteLoading: false,
    activeSheet: "" as ActiveSheet,
    sheetClosing: false,
    purchaseSheetOpen: false,
    purchaseSheetClosing: false,
    purchaseMode: "BUY" as "CART" | "BUY",
    purchaseActionText: "立即购买",
    confirmLoading: false,
    loading: true,
    loaded: false,
    errorText: ""
  },

  onLoad(options: PageOptions) {
    enableNativeShareMenu();
    const productId = parsePositiveId(options.id);
    if (!productId) {
      this.setData({
        loading: false,
        loaded: false,
        errorText: "商品参数无效，请返回后重试"
      });
      return;
    }
    this.setData({ productId, favorited: false });
    void this.loadDetail();
  },

  onShow() {
    const session = getSessionState();
    if (session.user && (session.accessToken || session.refreshToken)) {
      void this.loadAddresses();
      return;
    }
    latestAddressRequest += 1;
    this.setData({
      addresses: [],
      selectedAddress: null,
      addressLoading: false,
      addressLoaded: false,
      addressErrorText: ""
    });
  },

  onUnload() {
    if (sheetCloseTimer !== null) {
      clearTimeout(sheetCloseTimer);
      sheetCloseTimer = null;
    }
    if (purchaseSheetCloseTimer !== null) {
      clearTimeout(purchaseSheetCloseTimer);
      purchaseSheetCloseTimer = null;
    }
    latestDetailRequest += 1;
    latestAddressRequest += 1;
    latestReviewPreviewRequest += 1;
    latestReviewPageRequest += 1;
  },

  onShareAppMessage() {
    const detail = this.data.detail;
    return {
      title: detail?.title || "灶香集好物",
      path: `/pages/product/detail/detail?id=${this.data.productId}`,
      imageUrl: detail?.mainImage || this.data.galleryImages[0]?.url || ""
    };
  },

  onShareTimeline() {
    const detail = this.data.detail;
    return {
      title: detail?.title || "灶香集好物",
      query: `id=${this.data.productId}`,
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
        specType: detail.specType === "MULTI" ? "MULTI" : "SINGLE",
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
        specificationGroups: buildVisibleSkuSpecificationGroups(
          normalizedDetail.specType,
          normalizedDetail.skus,
          selection.selectedSkuId
        ),
        specificationImageMode: "list",
        ...selection,
        selectedSkuName: displaySpecText(selectedSku?.specText),
        purchaseImageUrl: cleanText(selectedSku?.image) || cleanText(normalizedDetail.mainImage),
        guaranteeSummary: guaranteeSummary(normalizedDetail),
        freightSummary: freight.summary,
        freightChargeText: freight.chargeText,
        wholesaleSummary: wholesaleSummary(selection.wholesaleTiers),
        reviewSpecOptions: buildReviewSpecOptions(normalizedDetail.skus, normalizedDetail.specType),
        reviewSpecText: "",
        reviewSpecLabel: "",
        reviewSpecDraftText: "",
        reviewSpecSheetOpen: false,
        reviewSummary: buildProductReviewSummaryView(normalizedDetail.reviewSummary),
        reviewPreview: [],
        reviewPreviewLoading: true,
        reviewPreviewErrorText: "",
        loading: false,
        loaded: true,
        errorText: ""
      });
      void this.refreshProductEngagement();
      void this.loadReviewPreview();
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
        specificationGroups: [],
        specificationImageMode: "list",
        ...EMPTY_SELECTION,
        guaranteeSummary: "",
        freightSummary: "",
        freightChargeText: "",
        wholesaleSummary: "",
        reviewSummary: EMPTY_REVIEW_SUMMARY,
        reviewPreview: [],
        reviewPreviewLoading: false,
        reviewPreviewErrorText: "",
        reviewRecords: [],
        reviewCurrent: 0,
        reviewTotal: 0,
        reviewHasMore: false,
        reviewLoading: false,
        reviewErrorText: "",
        reviewFilter: "ALL",
        reviewSort: "RECOMMENDED",
        reviewSpecText: "",
        reviewSpecLabel: "",
        reviewSpecDraftText: "",
        reviewSpecSheetOpen: false,
        reviewSpecOptions: [],
        loading: false,
        loaded: false,
        errorText: detailErrorMessage(error)
      });
    }
  },

  onRetry() {
    void this.loadDetail();
  },

  async refreshProductEngagement() {
    const productId = this.data.productId;
    if (!productId) {
      return;
    }
    const [favoriteResult] = await Promise.all([
      getFavoriteStatus(productId).catch(() => null),
      recordProductBrowse(productId).catch(() => null)
    ]);
    if (favoriteResult && productId === this.data.productId) {
      this.setData({ favorited: favoriteResult.favorited });
    }
  },

  onInfoSheetOpen(event: DatasetEvent) {
    const sheet = event.currentTarget.dataset.sheet;
    if (sheet) {
      this.setData({ activeSheet: sheet, sheetClosing: false });
    }
  },

  onOpenPurchase(event: DatasetEvent) {
    if (this.data.purchaseSheetOpen || this.data.purchaseSheetClosing) {
      return;
    }
    if (!this.data.selectedSkuId) {
      wx.showToast({ title: "暂无可售规格", icon: "none" });
      return;
    }
    if (!this.requireLogin()) {
      return;
    }
    const purchaseMode = event.currentTarget.dataset.mode === "CART" ? "CART" : "BUY";
    this.setData({
      purchaseSheetOpen: true,
      purchaseSheetClosing: false,
      purchaseMode,
      purchaseActionText: purchaseMode === "CART" ? "加入购物车" : "立即购买"
    });
  },

  onClosePurchaseSheet() {
    if (!this.data.confirmLoading) {
      this.animatePurchaseSheetClose();
    }
  },

  onCloseSheet() {
    if (this.data.reviewSpecSheetOpen) {
      this.setData({ reviewSpecSheetOpen: false });
      return;
    }
    if (!this.data.confirmLoading) {
      this.animateSheetClose();
    }
  },

  animateSheetClose(afterClose?: () => void) {
    if (!this.data.activeSheet || this.data.sheetClosing) {
      return;
    }
    this.setData({ sheetClosing: true });
    if (sheetCloseTimer !== null) {
      clearTimeout(sheetCloseTimer);
    }
    sheetCloseTimer = setTimeout(() => {
      sheetCloseTimer = null;
      this.setData({
        activeSheet: "",
        sheetClosing: false,
        reviewSpecSheetOpen: false
      }, afterClose);
    }, SHEET_EXIT_DURATION_MS);
  },

  animatePurchaseSheetClose(afterClose?: () => void) {
    if (!this.data.purchaseSheetOpen || this.data.purchaseSheetClosing) {
      return;
    }
    this.setData({ purchaseSheetClosing: true });
    if (purchaseSheetCloseTimer !== null) {
      clearTimeout(purchaseSheetCloseTimer);
    }
    purchaseSheetCloseTimer = setTimeout(() => {
      purchaseSheetCloseTimer = null;
      this.setData({
        purchaseSheetOpen: false,
        purchaseSheetClosing: false
      }, afterClose);
    }, SHEET_EXIT_DURATION_MS);
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
    if (!sku) {
      return;
    }
    if (this.data.quantity >= this.data.quantityMax) {
      wx.showToast({ title: "商品已达最大可购买数", icon: "none" });
      return;
    }
    this.applySelection(sku, this.data.quantity + 1);
  },

  onQuantityInputCommit(event: QuantityInputEvent) {
    const sku = this.selectedSku();
    if (!sku) {
      return;
    }
    const result = normalizeQuantityInput(
      event.detail.value,
      this.data.quantity,
      this.data.quantityMax
    );
    if (result.quantity > 0) {
      this.applySelection(sku, result.quantity);
    }
    if (result.exceededStock) {
      wx.showToast({
        title: stockQuantityCorrectedMessage(this.data.quantityMax),
        icon: "none"
      });
    }
  },

  async onPurchaseConfirm() {
    if (this.data.confirmLoading || !this.data.selectedSkuId) {
      return;
    }
    if (this.data.purchaseMode === "BUY") {
      this.animatePurchaseSheetClose(() => {
        try {
          wx.navigateTo({
            url: buildDirectBuyUrl(this.data.selectedSkuId, this.data.quantity)
          });
        } catch (error) {
          wx.showToast({
            title: error instanceof Error ? error.message : "结算参数无效",
            icon: "none"
          });
        }
      });
      return;
    }
    this.setData({ confirmLoading: true });
    try {
      await addCartItem({
        skuId: this.data.selectedSkuId,
        quantity: this.data.quantity
      });
      this.setData({ confirmLoading: false });
      this.animatePurchaseSheetClose();
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch (error) {
      this.setData({ confirmLoading: false });
      wx.showToast({
        title: cartAddErrorMessage(error, "加入购物车失败，请稍后重试"),
        icon: "none"
      });
    }
  },

  async onFavoriteToggle() {
    if (this.data.favoriteLoading || !this.data.productId) {
      return;
    }
    if (!this.requireLogin()) {
      return;
    }
    const favorited = !this.data.favorited;
    this.setData({ favoriteLoading: true });
    try {
      if (favorited) {
        await addFavorite(this.data.productId);
      } else {
        await removeFavorite(this.data.productId);
      }
      this.setData({ favorited, favoriteLoading: false });
      wx.showToast({ title: favorited ? "已收藏" : "已取消收藏", icon: "none" });
    } catch (error) {
      this.setData({ favoriteLoading: false });
      wx.showToast({
        title: isApiError(error) ? error.message : "收藏状态保存失败",
        icon: "none"
      });
    }
  },

  onGoToCart() {
    wx.navigateTo({
      url: "/pages/cart/standalone/standalone",
      fail: () => {
        wx.switchTab({ url: "/pages/cart/cart" });
      }
    });
  },

  onCustomerServiceTap() {
    const url = buildCustomerServiceUrl("PRODUCT", this.data.productId);
    const session = getSessionState();
    if (!session.user || (!session.accessToken && !session.refreshToken)) {
      openLoginPage(url);
      return;
    }
    wx.navigateTo({ url });
  },

  onAddressTap() {
    if (!this.requireLogin()) {
      return;
    }
    this.setData({ activeSheet: "address", sheetClosing: false });
    if (!this.data.addressLoading) {
      void this.loadAddresses();
    }
  },

  async loadAddresses() {
    const requestId = ++latestAddressRequest;
    this.setData({ addressLoading: true, addressErrorText: "" });
    try {
      const addresses = await getAddresses();
      if (requestId !== latestAddressRequest) {
        return;
      }
      this.setData({
        addresses,
        selectedAddress: resolveAddressSelection(addresses, this.data.selectedAddress),
        addressLoading: false,
        addressLoaded: true,
        addressErrorText: ""
      });
    } catch (error) {
      if (requestId !== latestAddressRequest) {
        return;
      }
      this.setData({
        addressLoading: false,
        addressLoaded: this.data.addresses.length > 0,
        addressErrorText: isApiError(error)
          ? error.message
          : "收货地址加载失败，请稍后重试"
      });
    }
  },

  onAddressRetry() {
    if (!this.data.addressLoading) {
      void this.loadAddresses();
    }
  },

  onAddressSelect(event: DatasetEvent) {
    const addressId = String(event.currentTarget.dataset.id || "");
    const selectedAddress = this.data.addresses.find((address) => address.id === addressId);
    if (selectedAddress) {
      this.setData({ selectedAddress });
      this.animateSheetClose();
    }
  },

  onAddAddress() {
    if (!this.requireLogin()) {
      return;
    }
    wx.navigateTo({ url: "/pages/account/address/edit/edit" });
  },

  async loadReviewPreview() {
    const productId = this.data.productId;
    if (!productId) {
      return;
    }
    const requestId = ++latestReviewPreviewRequest;
    this.setData({ reviewPreviewLoading: true, reviewPreviewErrorText: "" });
    try {
      const result = await getProductReviews(productId, 1, REVIEW_PREVIEW_SIZE);
      if (requestId !== latestReviewPreviewRequest || productId !== this.data.productId) {
        return;
      }
      this.setData({
        reviewSummary: buildProductReviewSummaryView(result.summary),
        reviewPreview: buildPublicProductReviewViews(result.page.records),
        reviewPreviewLoading: false,
        reviewPreviewErrorText: ""
      });
    } catch {
      if (requestId !== latestReviewPreviewRequest || productId !== this.data.productId) {
        return;
      }
      this.setData({
        reviewPreviewLoading: false,
        reviewPreviewErrorText: "评价暂时无法加载"
      });
    }
  },

  onReviewPreviewRetry() {
    void this.loadReviewPreview();
  },

  onReviewImagePreview(event: DatasetEvent) {
    const reviewId = parsePositiveId(event.currentTarget.dataset.reviewId);
    const current = cleanText(event.currentTarget.dataset.imageUrl);
    if (!reviewId || !current) {
      return;
    }
    const review = [
      ...this.data.reviewPreview,
      ...this.data.reviewRecords
    ].find((item) => item.id === reviewId);
    const urls = review?.images.map((image) => image.url).filter(Boolean) ?? [];
    wx.previewImage({
      current,
      urls: urls.includes(current) ? urls : [current]
    });
  },

  onReviewContentToggle(event: DatasetEvent) {
    const reviewId = parsePositiveId(event.currentTarget.dataset.reviewId);
    if (!reviewId) {
      return;
    }
    this.setData({
      reviewRecords: this.data.reviewRecords.map((review) => review.id === reviewId
        ? { ...review, contentExpanded: !review.contentExpanded }
        : review)
    });
  },

  measureReviewContentOverflow() {
    wx.nextTick(() => {
      const query = wx.createSelectorQuery().in(this);
      query.selectAll(".review-item__content-measure").boundingClientRect();
      query.exec((results) => {
        const rects = (Array.isArray(results?.[0]) ? results[0] : []) as ReviewContentRect[];
        if (!rects.length) {
          return;
        }
        const fiveLineHeight = (wx.getWindowInfo().windowWidth / 750) * 39 * 5;
        const overflowById = new Map<number, boolean>();
        rects.forEach((rect) => {
          const reviewId = parsePositiveId(rect.dataset?.reviewId);
          if (reviewId) {
            overflowById.set(reviewId, Number(rect.height) > fiveLineHeight + 1);
          }
        });
        if (!overflowById.size) {
          return;
        }
        this.setData({
          reviewRecords: this.data.reviewRecords.map((review) => {
            const contentCollapsible = overflowById.get(review.id) ?? false;
            return {
              ...review,
              contentCollapsible,
              contentExpanded: contentCollapsible && review.contentExpanded
            };
          })
        });
      });
    });
  },

  onReviewListOpen() {
    this.setData({
      activeSheet: "reviews",
      sheetClosing: false,
      reviewSpecSheetOpen: false
    });
    void this.loadReviewPage(true);
  },

  onReviewFilterSelect(event: DatasetEvent) {
    const reviewFilter = event.currentTarget.dataset.reviewFilter;
    if (
      !reviewFilter ||
      !["ALL", "WITH_IMAGES", "GOOD", "CRITICAL"].includes(reviewFilter) ||
      reviewFilter === this.data.reviewFilter
    ) {
      return;
    }
    this.setData({ reviewFilter });
    void this.loadReviewPage(true);
  },

  onReviewSortTap() {
    const reviewSort: ProductReviewSort = this.data.reviewSort === "LATEST"
      ? "RECOMMENDED"
      : "LATEST";
    this.setData({ reviewSort });
    void this.loadReviewPage(true);
  },

  onReviewSpecOpen() {
    this.setData({
      reviewSpecDraftText: this.data.reviewSpecText,
      reviewSpecSheetOpen: true
    });
  },

  onReviewSpecClose() {
    this.setData({ reviewSpecSheetOpen: false });
  },

  onReviewSpecSelect(event: DatasetEvent) {
    this.setData({
      reviewSpecDraftText: cleanText(event.currentTarget.dataset.reviewSpecText)
    });
  },

  onReviewSpecReset() {
    this.setData({ reviewSpecDraftText: "" });
  },

  onReviewSpecConfirm() {
    const reviewSpecText = this.data.reviewSpecDraftText;
    const changed = reviewSpecText !== this.data.reviewSpecText;
    const reviewSpecLabel = this.data.reviewSpecOptions.find(
      (option) => option.value === reviewSpecText
    )?.label ?? "";
    this.setData({
      reviewSpecText,
      reviewSpecLabel,
      reviewSpecSheetOpen: false
    });
    if (changed) {
      void this.loadReviewPage(true);
    }
  },

  onReviewListRetry() {
    void this.loadReviewPage(true);
  },

  onReviewLoadMore() {
    if (this.data.reviewHasMore && !this.data.reviewLoading) {
      void this.loadReviewPage(false);
    }
  },

  async loadReviewPage(reset: boolean) {
    const productId = this.data.productId;
    if (!productId || (!reset && (this.data.reviewLoading || !this.data.reviewHasMore))) {
      return;
    }
    const requestId = ++latestReviewPageRequest;
    const current = reset ? 1 : this.data.reviewCurrent + 1;
    this.setData({
      reviewLoading: true,
      reviewErrorText: "",
      ...(reset ? {
        reviewRecords: [],
        reviewCurrent: 0,
        reviewTotal: 0,
        reviewHasMore: false
      } : {})
    });
    try {
      const result = await getProductReviews(productId, current, REVIEW_PAGE_SIZE, {
        filter: this.data.reviewFilter,
        sort: this.data.reviewSort,
        ...(this.data.reviewSpecText ? { specText: this.data.reviewSpecText } : {})
      });
      if (requestId !== latestReviewPageRequest || productId !== this.data.productId) {
        return;
      }
      const incoming = buildPublicProductReviewViews(result.page.records);
      const reviewRecords = reset
        ? incoming
        : [...this.data.reviewRecords, ...incoming];
      this.setData({
        reviewSummary: buildProductReviewSummaryView(result.summary),
        reviewRecords,
        reviewCurrent: result.page.current,
        reviewTotal: result.page.total,
        reviewHasMore: reviewRecords.length < result.page.total,
        reviewLoading: false,
        reviewErrorText: ""
      }, () => this.measureReviewContentOverflow());
    } catch {
      if (requestId !== latestReviewPageRequest || productId !== this.data.productId) {
        return;
      }
      this.setData({
        reviewLoading: false,
        reviewErrorText: "评价加载失败，请点击重试"
      });
    }
  },

  requireLogin(): boolean {
    const session = getSessionState();
    if (session.user && (session.accessToken || session.refreshToken)) {
      return true;
    }
    const redirect = this.data.productId
      ? `/pages/product/detail/detail?id=${this.data.productId}`
      : "/pages/index/index";
    openLoginPage(redirect);
    return false;
  },

  applySelection(sku: ProductSku, quantity: number) {
    const selection = resolvePurchaseSelection(sku, quantity);
    const fallbackImage = this.data.detail?.mainImage ?? "";
    this.setData({
      ...selection,
      specificationGroups: this.data.detail
        ? buildVisibleSkuSpecificationGroups(
            this.data.detail.specType,
            this.data.detail.skus,
            selection.selectedSkuId
          )
        : [],
      selectedSkuName: displaySpecText(sku.specText),
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
