export type HomeBannerJumpType =
  | "NONE"
  | "PRODUCT"
  | "CATEGORY"
  | "COUPON"
  | "APP_PATH"
  | "URL";

export type ProductBadgeTone = "RED" | "ORANGE" | "GREEN" | "NEUTRAL";
export type HomeSectionPresentation = "FEATURED" | "COMPACT";
export type ProductSaleState = "AVAILABLE" | "SOLD_OUT";

export interface HomeBanner {
  id: number;
  title: string;
  subtitle?: string;
  imageUrl: string;
  jumpType: HomeBannerJumpType;
  jumpTargetId?: number;
  jumpPath?: string;
}

export interface HomeCategory {
  id: number;
  categoryId: number;
  name: string;
  imageUrl?: string;
  path: string;
}

export interface HomeProductPrice {
  minPriceCent?: number;
  maxPriceCent?: number;
  /** 最低价 SKU 对应的原价；存在且高于现价时展示为划线价。 */
  originalPriceCent?: number;
  minOriginalPriceCent?: number;
}

export interface HomeProductBadge {
  text: string;
  source: string;
  tone: ProductBadgeTone;
}

export interface HomeProductFeature {
  code: string;
  name: string;
  displayText: string;
  renderer: string;
  level?: number;
}

export interface HomeWholesaleSummary {
  available: boolean;
  label: string;
}

export interface HomeProduct {
  placementId: number;
  spuId: number;
  title: string;
  subtitle?: string;
  imageUrl?: string;
  price: HomeProductPrice;
  badge?: HomeProductBadge;
  highlights: HomeProductFeature[];
  metaFacts: HomeProductFeature[];
  wholesaleSummary?: HomeWholesaleSummary;
  displaySales: number;
  saleState: ProductSaleState;
  path: string;
}

export interface HomeProductSection {
  code: string;
  presentation: HomeSectionPresentation;
  products: HomeProduct[];
}

export interface HomeResponse {
  schemaVersion: number;
  banners: HomeBanner[];
  categories: HomeCategory[];
  productSections: HomeProductSection[];
}
