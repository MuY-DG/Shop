import type { PageResult } from "./api";

export type ProductParameterCardRole = "HIGHLIGHT" | "META";
export type ProductParameterCardRenderer = "TEXT" | "PILL" | "LEVEL" | "SPICE";
export type ProductSkuStatus = "ENABLED" | "DISABLED";
export type ProductFreightChargeMode = "FREE" | "FIXED";

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
  totalStock: number;
  parameters: ProductParameterValue[];
}

export interface ProductListQuery {
  current: number;
  size: number;
  categoryId?: number;
  keyword?: string;
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
  stockAvailable: number;
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
}
