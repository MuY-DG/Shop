import type { PageResult } from "./api";

export type ProductParameterCardRole = "HIGHLIGHT" | "META";
export type ProductParameterCardRenderer = "TEXT" | "PILL" | "LEVEL" | "SPICE";
export type ProductSkuStatus = "ENABLED" | "DISABLED";
export type ProductFreightChargeMode = "FREE" | "FIXED";
export type ProductSaleState = "AVAILABLE" | "SOLD_OUT";
export type ProductBadgeTone = "RED" | "ORANGE" | "GREEN" | "NEUTRAL";
export type ProductListSort =
  | "COMPREHENSIVE"
  | "SALES_DESC"
  | "PRICE_ASC"
  | "PRICE_DESC";

export interface ProductCategory {
  id: number;
  parentId: number;
  name: string;
  icon?: string;
  iconFileId?: number;
  sortOrder: number;
  status: string;
}

export interface ProductParameterOptionValue {
  optionCode: string;
  optionLabel: string;
  displayLevel?: number;
}

export interface ProductParameterValue {
  parameterId: number;
  parameterCode: string;
  parameterName: string;
  valueType: string;
  unit?: string;
  displayText: string;
  cardRole: ProductParameterCardRole;
  cardRenderer: ProductParameterCardRenderer;
  cardPriority: number;
  selectedOptions: ProductParameterOptionValue[];
}

export interface ProductFilterOption {
  optionCode: string;
  optionLabel: string;
  displayLevel?: number;
  productCount: number;
}

export interface ProductFilterGroup {
  parameterId: number;
  parameterCode: string;
  parameterName: string;
  valueType: string;
  options: ProductFilterOption[];
}

export interface ProductFreightTemplate {
  id: number;
  name: string;
  chargeMode: ProductFreightChargeMode;
  fixedAmountCent: number;
}

export interface ProductGuaranteeService {
  id: number;
  termsName: string;
  contentDescription: string;
  icon: string;
  iconFileId?: number;
  sortOrder: number;
}

export interface ProductListItem {
  id: number;
  categoryId: number;
  title: string;
  subtitle?: string;
  mainImage?: string;
  sellingPoints: string[];
  minPriceCent?: number;
  maxPriceCent?: number;
  displaySales: number;
  saleState: ProductSaleState;
  badgeText?: string;
  badgeTone?: ProductBadgeTone;
  parameters: ProductParameterValue[];
}

export interface ProductListQuery {
  current: number;
  size: number;
  categoryId?: number;
  keyword?: string;
  sort?: ProductListSort;
  parameterFilters?: Record<string, string>;
}

export type ProductListResult = PageResult<ProductListItem>;

export interface ProductImage {
  id?: number;
  url: string;
  fileId?: number;
  sortOrder: number;
}

export interface WholesaleTier {
  minQuantity: number;
  unitPriceCent: number;
}

export interface ProductSku {
  id: number;
  skuCode: string;
  specJson: string;
  specText: string;
  priceCent: number;
  originalPriceCent?: number;
  saleState: ProductSaleState;
  weightGram?: number;
  image?: string;
  imageFileId?: number;
  status: ProductSkuStatus;
  wholesaleTiers: WholesaleTier[];
}

export interface ProductDetail {
  id: number;
  categoryId: number;
  categoryName: string;
  title: string;
  subtitle?: string;
  mainImage?: string;
  mainImageFileId?: number;
  salesCount: number;
  saleState: ProductSaleState;
  sellingPoints: string[];
  detailHtml?: string;
  images: ProductImage[];
  skus: ProductSku[];
  parameters: ProductParameterValue[];
  freightTemplate: ProductFreightTemplate;
  guaranteeServices: ProductGuaranteeService[];
  reviewSummary?: ProductReviewSummary;
}

export interface ProductReviewSummary {
  reviewCount: number;
  averageRating: number;
  goodReviewCount: number;
  imageReviewCount: number;
  criticalReviewCount: number;
}
