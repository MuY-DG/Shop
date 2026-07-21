import type {
  ProductReview,
  ProductReviewSummary,
  PublicProductReview,
  ReviewableOrderItem
} from "../types/product-engagement";

export interface RatingStarView {
  value: number;
  filled: boolean;
}

export interface ProductReviewSummaryView {
  reviewCount: number;
  reviewCountText: string;
  averageRating: number;
  averageRatingText: string;
  goodRateText: string;
  hasReviews: boolean;
}

export interface PublicProductReviewView {
  id: number;
  reviewerName: string;
  reviewerInitial: string;
  rating: number;
  stars: RatingStarView[];
  content: string;
  hasContent: boolean;
  skuSpecText: string;
  verifiedPurchase: boolean;
  createdAtText: string;
}

export interface MyProductReviewView extends PublicProductReviewView {
  orderItemId: number;
  anonymous: boolean;
}

export interface ReviewableOrderItemView extends ReviewableOrderItem {
  label: string;
  completedAtText: string;
}

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function nonNegativeInteger(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : 0;
}

export function normalizeRating(value: unknown, fallback = 5): number {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) {
    return fallback;
  }
  return Math.min(5, Math.max(1, Math.round(numeric)));
}

export function buildRatingStars(rating: unknown): RatingStarView[] {
  const numeric = Number(rating);
  const normalized = Number.isFinite(numeric)
    ? Math.min(5, Math.max(0, Math.round(numeric)))
    : 0;
  return [1, 2, 3, 4, 5].map((value) => ({
    value,
    filled: value <= normalized
  }));
}

export function formatReviewDate(value: unknown): string {
  const text = cleanText(value);
  const matched = /^(\d{4})-(\d{2})-(\d{2})/.exec(text);
  return matched ? `${matched[1]}-${matched[2]}-${matched[3]}` : "";
}

export function buildProductReviewSummaryView(
  summary?: ProductReviewSummary | null
): ProductReviewSummaryView {
  const reviewCount = nonNegativeInteger(summary?.reviewCount);
  const goodReviewCount = Math.min(
    reviewCount,
    nonNegativeInteger(summary?.goodReviewCount)
  );
  const rawAverage = Number(summary?.averageRating);
  const averageRating = Number.isFinite(rawAverage)
    ? Math.min(5, Math.max(0, rawAverage))
    : 0;
  return {
    reviewCount,
    reviewCountText: reviewCount ? `${reviewCount} 条评价` : "暂无评价",
    averageRating,
    averageRatingText: averageRating.toFixed(1),
    goodRateText: reviewCount
      ? `${Math.round((goodReviewCount / reviewCount) * 100)}% 好评`
      : "期待首条评价",
    hasReviews: reviewCount > 0
  };
}

export function buildPublicProductReviewViews(
  reviews: PublicProductReview[]
): PublicProductReviewView[] {
  return (Array.isArray(reviews) ? reviews : [])
    .filter((review) => Number.isSafeInteger(review?.id) && review.id > 0)
    .map((review) => {
      const reviewerName = cleanText(review.reviewerName) || "匿名用户";
      const content = cleanText(review.content);
      const rating = normalizeRating(review.rating);
      return {
        id: review.id,
        reviewerName,
        reviewerInitial: reviewerName.slice(0, 1) || "用",
        rating,
        stars: buildRatingStars(rating),
        content,
        hasContent: Boolean(content),
        skuSpecText: cleanText(review.skuSpecText),
        verifiedPurchase: review.verifiedPurchase === true,
        createdAtText: formatReviewDate(review.createdAt)
      };
    });
}

export function buildMyProductReviewViews(
  reviews: ProductReview[]
): MyProductReviewView[] {
  return (Array.isArray(reviews) ? reviews : [])
    .map((review) => {
      const publicView = buildPublicProductReviewViews([review])[0];
      return publicView && Number.isSafeInteger(review.orderItemId) && review.orderItemId > 0
        ? {
          ...publicView,
          orderItemId: review.orderItemId,
          anonymous: review.anonymous === true
        }
        : undefined;
    })
    .filter((review): review is MyProductReviewView => Boolean(review));
}

export function buildReviewableOrderItemViews(
  orderItems: ReviewableOrderItem[]
): ReviewableOrderItemView[] {
  return (Array.isArray(orderItems) ? orderItems : [])
    .filter((item) => Number.isSafeInteger(item?.orderItemId) && item.orderItemId > 0)
    .map((item) => {
      const skuSpecText = cleanText(item.skuSpecText) || "默认规格";
      return {
        ...item,
        skuSpecText,
        label: skuSpecText,
        completedAtText: formatReviewDate(item.completedAt)
      };
    });
}

export function normalizeReviewContent(value: unknown): string {
  return cleanText(value).slice(0, 1000);
}
