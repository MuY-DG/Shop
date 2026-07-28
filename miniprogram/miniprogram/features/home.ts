import type {
  HomeBanner,
  HomeCategory,
  HomeProduct,
  HomeProductSection,
  HomeResponse,
  ProductBadgeTone
} from "../types/home";
import {
  adaptProductFact,
  type ProductFactTone,
  type ProductFactView
} from "./product-facts";

export const SUPPORTED_HOME_SCHEMA_VERSION = 3;

export type ProductTagTone = ProductFactTone;

export interface HomeBannerView {
  id: number;
  title: string;
  subtitle: string;
  imageUrl: string;
  hasImage: boolean;
  navigationPath: string;
  ariaLabel: string;
}

export interface HomeCategoryView {
  id: number;
  categoryId: number;
  name: string;
  imageUrl: string;
  hasImage: boolean;
  placeholder: string;
  navigationPath: string;
}

export type ProductFeatureView = ProductFactView;

export interface HomeProductCardView {
  placementId: number;
  spuId: number;
  title: string;
  subtitle: string;
  imageUrl: string;
  hasImage: boolean;
  placeholder: string;
  priceText: string;
  hasPrice: boolean;
  priceIntegerText: string;
  priceDecimalText: string;
  rangePriceIntegerText: string;
  rangePriceDecimalText: string;
  hasPriceRange: boolean;
  priceSuffixText: string;
  originalPriceText: string;
  hasOriginalPrice: boolean;
  badgeText: string;
  badgeTone: ProductTagTone;
  soldOut: boolean;
  features: ProductFeatureView[];
  wholesaleText: string;
  salesText: string;
  navigationPath: string;
}

export interface HomePageViewModel {
  schemaVersion: number;
  banners: HomeBannerView[];
  categories: HomeCategoryView[];
  featuredProducts: HomeProductCardView[];
  compactProducts: HomeProductCardView[];
  hasContent: boolean;
}

const SAFE_PRODUCT_PATH = /^\/pages\/product\/detail\/detail\?id=[1-9]\d{0,18}$/;
const SAFE_CATEGORY_PATH = /^\/pages\/product\/list\/list\?categoryId=[1-9]\d{0,18}$/;

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function positiveInteger(value: unknown): number | undefined {
  return typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value > 0
    ? value
    : undefined;
}

function nonNegativeInteger(value: unknown): number | undefined {
  return typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value >= 0
    ? value
    : undefined;
}

export function normalizeHomePath(value: unknown): string {
  const path = text(value);
  if (!path || path.length > 256) {
    return "";
  }
  return SAFE_PRODUCT_PATH.test(path) || SAFE_CATEGORY_PATH.test(path)
    ? path
    : "";
}

export function formatPriceRange(
  minPriceCent: unknown,
  maxPriceCent: unknown
): string {
  const min = nonNegativeInteger(minPriceCent);
  const max = nonNegativeInteger(maxPriceCent);
  if (min === undefined && max === undefined) {
    return "";
  }
  const format = (cent: number): string => (cent / 100).toFixed(2);
  if (min !== undefined && max === undefined) {
    return `${format(min)} 起`;
  }
  if (min === undefined && max !== undefined) {
    return format(max);
  }
  if (min === undefined || max === undefined || max < min) {
    return "";
  }
  return min === max ? format(min) : `${format(min)}–${format(max)}`;
}

function badgeTone(tone: ProductBadgeTone | undefined): ProductTagTone {
  switch (tone) {
    case "RED":
      return "brand";
    case "ORANGE":
      return "orange";
    case "GREEN":
      return "success";
    case "NEUTRAL":
    default:
      return "neutral";
  }
}

function productFeatures(product: HomeProduct): ProductFeatureView[] {
  const source = [
    ...(Array.isArray(product.highlights) ? product.highlights : []),
    ...(Array.isArray(product.metaFacts) ? product.metaFacts : [])
  ];
  const seen = new Set<string>();
  const features: ProductFeatureView[] = [];
  source.forEach((feature) => {
    const displayText = text(feature?.displayText);
    if (!displayText || seen.has(displayText) || features.length >= 3) {
      return;
    }
    seen.add(displayText);
    const adapted = adaptProductFact(feature);
    if (adapted) {
      features.push(adapted);
    }
  });
  return features;
}

interface PriceParts {
  integerText: string;
  decimalText: string;
}

function priceParts(cent: number | undefined): PriceParts {
  if (cent === undefined) {
    return { integerText: "", decimalText: "" };
  }
  const formatted = (cent / 100).toFixed(2);
  const [integerText, fraction = "00"] = formatted.split(".");
  return {
    integerText,
    decimalText: `.${fraction}`
  };
}

function originalPriceText(product: HomeProduct, currentMin: number | undefined): string {
  const original = nonNegativeInteger(
    product.price?.originalPriceCent ?? product.price?.minOriginalPriceCent
  );
  if (original === undefined || original === 0 || (currentMin !== undefined && original <= currentMin)) {
    return "";
  }
  return (original / 100).toFixed(2);
}

function productPath(product: HomeProduct): string {
  const fromServer = normalizeHomePath(product.path);
  if (fromServer) {
    return fromServer;
  }
  const spuId = positiveInteger(product.spuId);
  return spuId ? `/pages/product/detail/detail?id=${spuId}` : "";
}

function toProductView(product: HomeProduct): HomeProductCardView | undefined {
  const spuId = positiveInteger(product?.spuId);
  const title = text(product?.title);
  if (!spuId || !title) {
    return undefined;
  }
  const minPrice = nonNegativeInteger(product.price?.minPriceCent);
  const maxPrice = nonNegativeInteger(product.price?.maxPriceCent);
  const primaryPrice = minPrice ?? maxPrice;
  const priceText = primaryPrice === undefined ? "" : (primaryPrice / 100).toFixed(2);
  const primaryPriceParts = priceParts(primaryPrice);
  const strikePriceText = originalPriceText(product, primaryPrice);
  const imageUrl = text(product.imageUrl);
  const sales = nonNegativeInteger(product.displaySales) ?? 0;
  const soldOut = product.saleState === "SOLD_OUT";
  return {
    placementId: positiveInteger(product.placementId) ?? spuId,
    spuId,
    title,
    subtitle: text(product.subtitle),
    imageUrl,
    hasImage: Boolean(imageUrl),
    placeholder: title.slice(0, 1),
    priceText,
    hasPrice: Boolean(priceText),
    priceIntegerText: primaryPriceParts.integerText,
    priceDecimalText: primaryPriceParts.decimalText,
    rangePriceIntegerText: "",
    rangePriceDecimalText: "",
    hasPriceRange: false,
    priceSuffixText: "",
    originalPriceText: strikePriceText,
    hasOriginalPrice: Boolean(strikePriceText),
    badgeText: soldOut ? "暂时售罄" : text(product.badge?.text),
    badgeTone: soldOut ? "neutral" : badgeTone(product.badge?.tone),
    soldOut,
    features: productFeatures(product),
    wholesaleText: product.wholesaleSummary?.available
      ? text(product.wholesaleSummary.label)
      : "",
    salesText: `已售 ${sales}+`,
    navigationPath: productPath(product)
  };
}

function sectionProducts(
  section: HomeProductSection | undefined
): HomeProductCardView[] {
  if (!section || !Array.isArray(section.products)) {
    return [];
  }
  return section.products
    .map(toProductView)
    .filter((product): product is HomeProductCardView => Boolean(product));
}

function bannerPath(banner: HomeBanner): string {
  if (banner.jumpType === "PRODUCT") {
    const productId = positiveInteger(banner.jumpTargetId);
    return productId ? `/pages/product/detail/detail?id=${productId}` : "";
  }
  if (banner.jumpType === "CATEGORY") {
    const categoryId = positiveInteger(banner.jumpTargetId);
    return categoryId ? `/pages/product/list/list?categoryId=${categoryId}` : "";
  }
  if (banner.jumpType === "APP_PATH") {
    return normalizeHomePath(banner.jumpPath);
  }
  return "";
}

function toBannerView(banner: HomeBanner, index: number): HomeBannerView | undefined {
  const id = positiveInteger(banner?.id);
  const imageUrl = text(banner?.imageUrl);
  if (!id) {
    return undefined;
  }
  const title = text(banner.title);
  const subtitle = text(banner.subtitle);
  return {
    id,
    title,
    subtitle,
    imageUrl,
    hasImage: Boolean(imageUrl),
    navigationPath: bannerPath(banner),
    ariaLabel: title || subtitle || `首页活动 ${index + 1}`
  };
}

function categoryPath(category: HomeCategory): string {
  const fromServer = normalizeHomePath(category.path);
  if (fromServer) {
    return fromServer;
  }
  const categoryId = positiveInteger(category.categoryId);
  return categoryId ? `/pages/product/list/list?categoryId=${categoryId}` : "";
}

function toCategoryView(category: HomeCategory): HomeCategoryView | undefined {
  const categoryId = positiveInteger(category?.categoryId);
  const name = text(category?.name);
  if (!categoryId || !name) {
    return undefined;
  }
  const imageUrl = text(category.imageUrl);
  return {
    id: positiveInteger(category.id) ?? categoryId,
    categoryId,
    name,
    imageUrl,
    hasImage: Boolean(imageUrl),
    placeholder: name.slice(0, 1),
    navigationPath: categoryPath(category)
  };
}

function findSection(
  sections: HomeProductSection[],
  code: string,
  presentation: string
): HomeProductSection | undefined {
  return sections.find((section) => text(section?.code).toUpperCase() === code) ??
    sections.find((section) => text(section?.presentation).toUpperCase() === presentation);
}

export function buildHomeViewModel(response: HomeResponse): HomePageViewModel {
  if (response.schemaVersion !== SUPPORTED_HOME_SCHEMA_VERSION) {
    throw new Error(`暂不支持首页数据版本 ${String(response.schemaVersion)}`);
  }
  const banners = (Array.isArray(response.banners) ? response.banners : [])
    .map(toBannerView)
    .filter((banner): banner is HomeBannerView => Boolean(banner));
  const categories = (Array.isArray(response.categories) ? response.categories : [])
    .map(toCategoryView)
    .filter((category): category is HomeCategoryView => Boolean(category));
  const sections = Array.isArray(response.productSections)
    ? response.productSections
    : [];
  const featuredProducts = sectionProducts(findSection(sections, "HOT", "FEATURED"));
  const compactProducts = sectionProducts(findSection(sections, "RECOMMENDED", "COMPACT"));
  return {
    schemaVersion: response.schemaVersion,
    banners,
    categories,
    featuredProducts,
    compactProducts,
    hasContent: banners.length + categories.length + featuredProducts.length + compactProducts.length > 0
  };
}
