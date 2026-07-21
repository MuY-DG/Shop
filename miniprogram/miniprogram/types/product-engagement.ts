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
}

export interface ProductReviewSummary {
  reviewCount: number;
  averageRating: number;
  goodReviewCount: number;
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
}

export interface ProductReviewUpdateRequest extends WechatMiniprogram.IAnyObject {
  rating: number;
  content?: string;
  anonymous?: boolean;
}

export interface ProductFavoriteStatus {
  spuId: number;
  favorited: boolean;
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
export type ProductBrowseHistoryPage = PageResult<ProductBrowseHistoryItem>;
