import type {
  ProductReviewSummary,
  PublicProductReview,
  ProductReviewImage,
  ReviewableOrderItem
} from "../types/product-engagement";
import { formatLocalDate } from "../utils/date-time";

export interface RatingStarView {
  value: number;
  filled: boolean;
}

export interface ProductReviewSummaryView {
  reviewCount: number;
  goodReviewCount: number;
  imageReviewCount: number;
  criticalReviewCount: number;
  reviewCountText: string;
  reviewCountPlusText: string;
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
  ratingLabel: string;
  stars: RatingStarView[];
  content: string;
  hasContent: boolean;
  contentCollapsible: boolean;
  contentExpanded: boolean;
  skuSpecText: string;
  purchaseSpecText: string;
  verifiedPurchase: boolean;
  createdAtText: string;
  images: ProductReviewImage[];
  hasImages: boolean;
  previewImageUrl: string;
  imageCount: number;
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

function reviewImages(value: unknown): ProductReviewImage[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((image): image is ProductReviewImage => Boolean(
      image &&
      Number.isSafeInteger(image.fileId) &&
      image.fileId > 0 &&
      cleanText(image.url)
    ))
    .map((image) => ({
      fileId: image.fileId,
      url: cleanText(image.url),
      sortOrder: nonNegativeInteger(image.sortOrder)
    }))
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .slice(0, 6);
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
  return formatLocalDate(value);
}

export function buildProductReviewSummaryView(
  summary?: ProductReviewSummary | null
): ProductReviewSummaryView {
  const reviewCount = nonNegativeInteger(summary?.reviewCount);
  const goodReviewCount = Math.min(
    reviewCount,
    nonNegativeInteger(summary?.goodReviewCount)
  );
  const imageReviewCount = Math.min(
    reviewCount,
    nonNegativeInteger(summary?.imageReviewCount)
  );
  const criticalReviewCount = Math.min(
    reviewCount,
    nonNegativeInteger(summary?.criticalReviewCount)
  );
  const rawAverage = Number(summary?.averageRating);
  const averageRating = Number.isFinite(rawAverage)
    ? Math.min(5, Math.max(0, rawAverage))
    : 0;
  return {
    reviewCount,
    goodReviewCount,
    imageReviewCount,
    criticalReviewCount,
    reviewCountText: reviewCount ? `${reviewCount} 条评价` : "暂无评价",
    reviewCountPlusText: reviewCount ? `${reviewCount}+` : "0",
    averageRating,
    averageRatingText: averageRating.toFixed(1),
    goodRateText: reviewCount
      ? `好评率${Math.round((goodReviewCount / reviewCount) * 100)}%`
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
      const images = reviewImages(review.images);
      const skuSpecText = cleanText(review.skuSpecText);
      return {
        id: review.id,
        reviewerName,
        reviewerInitial: reviewerName.slice(0, 1) || "用",
        rating,
        ratingLabel: rating >= 4 ? "超赞" : rating === 3 ? "还不错" : "",
        stars: buildRatingStars(rating),
        content,
        hasContent: Boolean(content),
        contentCollapsible: false,
        contentExpanded: false,
        skuSpecText,
        purchaseSpecText: skuSpecText || "默认规格",
        verifiedPurchase: review.verifiedPurchase === true,
        createdAtText: formatReviewDate(review.createdAt),
        images,
        hasImages: images.length > 0,
        previewImageUrl: images[0]?.url ?? "",
        imageCount: images.length
      };
    });
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
