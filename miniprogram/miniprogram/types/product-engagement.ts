import type { PageResult } from "./api";

export interface ProductReview {
  id: number;
  spuId: number;
  productTitle: string;
  orderItemId: number;
  skuSpecText: string;
  rating: number;
  content: string;
  anonymous: boolean;
  reviewerName: string;
  verifiedPurchase: boolean;
  createdAt: string;
  updatedAt: string;
  images: ProductReviewImage[];
}

export interface ProductReviewImage {
  fileId: number;
  url: string;
  sortOrder: number;
}

export interface ProductReviewImageUploadResponse {
  id: number;
  scope: "LIBRARY";
  mediaKind: "IMAGE";
  visibility: "PUBLIC";
  originalFilename: string;
  sizeBytes: number;
  status: "ACTIVE";
  url?: string;
  publicUrl?: string;
  expiresAt?: string;
}

export interface ProductReviewSummary {
  reviewCount: number;
  averageRating: number;
  goodReviewCount: number;
  imageReviewCount: number;
  criticalReviewCount: number;
}

export type ProductReviewFilter = "ALL" | "WITH_IMAGES" | "GOOD" | "CRITICAL";
export type ProductReviewSort = "RECOMMENDED" | "LATEST";

export interface ProductReviewListQuery {
  filter?: ProductReviewFilter;
  sort?: ProductReviewSort;
  specText?: string;
}

export interface PublicProductReview {
  id: number;
  skuSpecText: string;
  rating: number;
  content: string;
  anonymous: boolean;
  reviewerName: string;
  verifiedPurchase: boolean;
  createdAt: string;
  updatedAt: string;
  images: ProductReviewImage[];
}

export interface ProductReviewPage {
  summary: ProductReviewSummary;
  page: PageResult<PublicProductReview>;
}

export interface ReviewableOrderItem {
  orderItemId: number;
  orderId: number;
  orderNo: string;
  skuId: number;
  skuSpecText: string;
  completedAt: string;
}

export interface ProductReviewEligibility {
  orderItems: ReviewableOrderItem[];
}

export interface ProductReviewCreateRequest extends WechatMiniprogram.IAnyObject {
  orderItemId: number;
  rating: number;
  content?: string;
  anonymous?: boolean;
  imageFileIds?: number[];
}

export interface ProductFavoriteStatus {
  spuId: number;
  favorited: boolean;
}

export interface DeleteFavoriteItemsRequest {
  spuIds: number[];
}

export interface ProductFavoriteItem {
  spuId: number;
  title: string;
  subtitle?: string;
  mainImage?: string;
  minPriceCent?: number;
  maxPriceCent?: number;
  available: boolean;
  favoritedAt: string;
}

export interface ProductBrowseRecord {
  spuId: number;
  lastViewedAt: string;
  viewCount: number;
}

export interface DeleteBrowseHistoryItemsRequest {
  spuIds: number[];
}

export interface ProductBrowseHistoryItem {
  spuId: number;
  title: string;
  subtitle?: string;
  mainImage?: string;
  minPriceCent?: number;
  maxPriceCent?: number;
  available: boolean;
  firstViewedAt: string;
  lastViewedAt: string;
  viewCount: number;
}

export type ProductFavoritePage = PageResult<ProductFavoriteItem>;
export interface ProductBrowseHistoryPage {
  records: ProductBrowseHistoryItem[];
  current: number;
  size: number;
  hasMore: boolean;
}
