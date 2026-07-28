import {
  buildDirectBuyUrl,
  resolveAddressSelection
} from "../../../features/checkout";
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
import {
  buildMyProductReviewViews,
  buildProductReviewSummaryView,
  buildPublicProductReviewViews,
  buildRatingStars,
  buildReviewableOrderItemViews,
  normalizeRating,
  normalizeReviewContent,
  type MyProductReviewView,
  type ProductReviewSummaryView,
  type PublicProductReviewView,
  type RatingStarView,
  type ReviewableOrderItemView
} from "../../../features/product-review";
import { getAddresses } from "../../../services/address";
import { addCartItem } from "../../../services/cart";
import {
  createProductReview,
  deleteProductReview,
  getMyProductReviews,
  getProductDetail,
  getProductReviewEligibility,
  getProductReviews,
  updateProductReview
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

type InfoSheet = "guarantee" | "freight" | "wholesale";
type ActiveSheet = "" | "purchase" | "address" | InfoSheet | "reviews" | "reviewManage";

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
      orderItemId?: number | string;
      rating?: number | string;
      reviewId?: number | string;
    };
  };
}

interface TextareaEvent {
  detail: {
    value: string;
  };
}

interface SwitchEvent {
  detail: {
    value: boolean;
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
let latestReviewManagementRequest = 0;
let sheetCloseTimer: ReturnType<typeof setTimeout> | null = null;

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
    reviewManagementLoading: false,
    reviewManagementErrorText: "",
    reviewOrderItems: [] as ReviewableOrderItemView[],
    myReviews: [] as MyProductReviewView[],
    reviewFormMode: "create" as "create" | "update",
    reviewEditingId: 0,
    reviewSelectedOrderItemId: 0,
    reviewRating: 5,
    reviewFormStars: buildRatingStars(5) as RatingStarView[],
    reviewContent: "",
    reviewAnonymous: false,
    reviewSubmitting: false,
    reviewDeletingId: 0,
    favorited: false,
    favoriteLoading: false,
    activeSheet: "" as ActiveSheet,
    sheetClosing: false,
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
    latestDetailRequest += 1;
    latestAddressRequest += 1;
    latestReviewPreviewRequest += 1;
    latestReviewPageRequest += 1;
    latestReviewManagementRequest += 1;
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
        benefitItems: [],
        specificationGroups: [],
        specificationImageMode: "list",
        ...EMPTY_SELECTION,
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
    if (!this.data.selectedSkuId) {
      wx.showToast({ title: "暂无可售规格", icon: "none" });
      return;
    }
    if (!this.requireLogin()) {
      return;
    }
    const purchaseMode = event.currentTarget.dataset.mode === "CART" ? "CART" : "BUY";
    this.setData({
      activeSheet: "purchase",
      sheetClosing: false,
      purchaseMode,
      purchaseActionText: purchaseMode === "CART" ? "加入购物车" : "立即购买"
    });
  },

  onCloseSheet() {
    if (
      !this.data.confirmLoading &&
      !this.data.reviewSubmitting &&
      !this.data.reviewDeletingId
    ) {
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
        sheetClosing: false
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
      this.animateSheetClose(() => {
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
      this.animateSheetClose();
      wx.showToast({ title: "已加入购物车", icon: "success" });
    } catch (error) {
      this.setData({ confirmLoading: false });
      wx.showToast({
        title: isApiError(error) ? error.message : "加入购物车失败，请稍后重试",
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

  onReviewListOpen() {
    this.setData({ activeSheet: "reviews", sheetClosing: false });
    void this.loadReviewPage(true);
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
      const result = await getProductReviews(productId, current, REVIEW_PAGE_SIZE);
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
      });
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

  onReviewManageOpen() {
    if (!this.requireLogin()) {
      return;
    }
    this.setData({ activeSheet: "reviewManage", sheetClosing: false });
    void this.loadReviewManagement();
  },

  onReviewManagementRetry() {
    void this.loadReviewManagement();
  },

  async loadReviewManagement() {
    const productId = this.data.productId;
    if (!productId) {
      return;
    }
    const requestId = ++latestReviewManagementRequest;
    this.setData({
      reviewManagementLoading: true,
      reviewManagementErrorText: ""
    });
    try {
      const [eligibility, mine] = await Promise.all([
        getProductReviewEligibility(productId),
        getMyProductReviews(1, 50)
      ]);
      if (requestId !== latestReviewManagementRequest || productId !== this.data.productId) {
        return;
      }
      const reviewOrderItems = buildReviewableOrderItemViews(eligibility.orderItems);
      const myReviews = buildMyProductReviewViews(
        mine.records.filter((review) => review.spuId === productId)
      );
      this.setData({
        reviewManagementLoading: false,
        reviewManagementErrorText: "",
        reviewOrderItems,
        myReviews,
        reviewFormMode: "create",
        reviewEditingId: 0,
        reviewSelectedOrderItemId: reviewOrderItems[0]?.orderItemId ?? 0,
        reviewRating: 5,
        reviewFormStars: buildRatingStars(5),
        reviewContent: "",
        reviewAnonymous: false
      });
    } catch (error) {
      if (requestId !== latestReviewManagementRequest || productId !== this.data.productId) {
        return;
      }
      this.setData({
        reviewManagementLoading: false,
        reviewManagementErrorText: this.reviewActionErrorMessage(
          error,
          "评价资格加载失败，请稍后重试"
        )
      });
    }
  },

  onReviewOrderSelect(event: DatasetEvent) {
    const orderItemId = parsePositiveId(event.currentTarget.dataset.orderItemId);
    if (this.data.reviewOrderItems.some((item) => item.orderItemId === orderItemId)) {
      this.setData({ reviewSelectedOrderItemId: orderItemId });
    }
  },

  onReviewRatingSelect(event: DatasetEvent) {
    const reviewRating = normalizeRating(event.currentTarget.dataset.rating);
    this.setData({
      reviewRating,
      reviewFormStars: buildRatingStars(reviewRating)
    });
  },

  onReviewContentInput(event: TextareaEvent) {
    this.setData({ reviewContent: event.detail.value.slice(0, 1000) });
  },

  onReviewAnonymousChange(event: SwitchEvent) {
    this.setData({ reviewAnonymous: event.detail.value });
  },

  onReviewEdit(event: DatasetEvent) {
    const reviewId = parsePositiveId(event.currentTarget.dataset.reviewId);
    const review = this.data.myReviews.find((item) => item.id === reviewId);
    if (!review) {
      return;
    }
    this.setData({
      reviewFormMode: "update",
      reviewEditingId: review.id,
      reviewRating: review.rating,
      reviewFormStars: buildRatingStars(review.rating),
      reviewContent: review.content,
      reviewAnonymous: review.anonymous
    });
  },

  onReviewEditCancel() {
    this.resetReviewForm();
  },

  async onReviewSubmit() {
    if (this.data.reviewSubmitting) {
      return;
    }
    const isUpdate = this.data.reviewFormMode === "update";
    if (!isUpdate && !this.data.reviewSelectedOrderItemId) {
      wx.showToast({ title: "暂无可评价订单", icon: "none" });
      return;
    }
    this.setData({ reviewSubmitting: true });
    try {
      const payload = {
        rating: this.data.reviewRating,
        content: normalizeReviewContent(this.data.reviewContent),
        anonymous: this.data.reviewAnonymous
      };
      if (isUpdate) {
        await updateProductReview(this.data.reviewEditingId, payload);
      } else {
        await createProductReview(this.data.productId, {
          orderItemId: this.data.reviewSelectedOrderItemId,
          ...payload
        });
      }
      this.setData({ reviewSubmitting: false });
      wx.showToast({ title: isUpdate ? "评价已更新" : "评价已发布", icon: "success" });
      await Promise.all([
        this.loadReviewPreview(),
        this.loadReviewManagement()
      ]);
    } catch (error) {
      this.setData({ reviewSubmitting: false });
      wx.showToast({
        title: this.reviewActionErrorMessage(error, "评价保存失败，请稍后重试"),
        icon: "none"
      });
    }
  },

  onReviewDelete(event: DatasetEvent) {
    const reviewId = parsePositiveId(event.currentTarget.dataset.reviewId);
    if (!reviewId || this.data.reviewDeletingId || this.data.reviewSubmitting) {
      return;
    }
    wx.showModal({
      title: "删除评价",
      content: "删除后该订单可重新评价，确定继续吗？",
      confirmText: "删除",
      confirmColor: "#B72B22",
      success: (result) => {
        if (result.confirm) {
          void this.deleteOwnedReview(reviewId);
        }
      }
    });
  },

  async deleteOwnedReview(reviewId: number) {
    this.setData({ reviewDeletingId: reviewId });
    try {
      await deleteProductReview(reviewId);
      this.setData({ reviewDeletingId: 0 });
      wx.showToast({ title: "评价已删除", icon: "success" });
      await Promise.all([
        this.loadReviewPreview(),
        this.loadReviewManagement()
      ]);
    } catch (error) {
      this.setData({ reviewDeletingId: 0 });
      wx.showToast({
        title: this.reviewActionErrorMessage(error, "评价删除失败，请稍后重试"),
        icon: "none"
      });
    }
  },

  resetReviewForm() {
    this.setData({
      reviewFormMode: "create",
      reviewEditingId: 0,
      reviewSelectedOrderItemId: this.data.reviewOrderItems[0]?.orderItemId ?? 0,
      reviewRating: 5,
      reviewFormStars: buildRatingStars(5),
      reviewContent: "",
      reviewAnonymous: false
    });
  },

  reviewActionErrorMessage(error: unknown, fallback: string): string {
    if (!isApiError(error)) {
      return fallback;
    }
    if (error.code === 200201) {
      return "仅已完成订单可以评价";
    }
    if (error.code === 200202) {
      return "该订单商品已经评价过了";
    }
    if (error.code === 200200) {
      return "评价不存在或已被删除";
    }
    return error.message || fallback;
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
