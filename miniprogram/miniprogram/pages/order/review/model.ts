import {
  buildRatingStars,
  normalizeRating,
  type RatingStarView
} from "../../../features/product-review";
import type { OrderItemResponse } from "../../../types/order";

export type OrderReviewSourceItem = OrderItemResponse;

export interface OrderReviewDraftImage {
  fileId: number;
  tempFilePath: string;
}

export interface OrderReviewItemView extends OrderReviewSourceItem {
  imageUrl: string;
  hasImage: boolean;
  specTextDisplay: string;
  rating: number;
  stars: RatingStarView[];
  content: string;
  anonymous: boolean;
  reviewImages: OrderReviewDraftImage[];
}

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function positiveInteger(value: unknown): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

export function parseReviewOrderId(value: unknown): number {
  if (typeof value === "string" && !/^\d+$/.test(value.trim())) {
    return 0;
  }
  return positiveInteger(value);
}

export function isReviewableOrderStatus(status: unknown): boolean {
  return status === "COMPLETED";
}

export function buildPendingOrderReviewItems(
  items: readonly OrderReviewSourceItem[]
): OrderReviewItemView[] {
  return (Array.isArray(items) ? items : [])
    .filter((item) => (
      item.reviewable === true &&
      item.reviewed !== true &&
      positiveInteger(item.orderItemId) > 0 &&
      positiveInteger(item.spuId) > 0
    ))
    .map((item) => {
      const imageUrl = cleanText(item.displayImage)
        || cleanText(item.skuImage)
        || cleanText(item.mainImage);
      const rating = normalizeRating(5);
      return {
        ...item,
        productTitle: cleanText(item.productTitle) || "订单商品",
        imageUrl,
        hasImage: Boolean(imageUrl),
        specTextDisplay: cleanText(item.specText),
        rating,
        stars: buildRatingStars(rating),
        content: "",
        anonymous: false,
        reviewImages: []
      };
    });
}

export function updateOrderReviewDraft(
  items: readonly OrderReviewItemView[],
  orderItemId: number,
  patch: Partial<Pick<
    OrderReviewItemView,
    "rating" | "content" | "anonymous" | "hasImage" | "reviewImages"
  >>
): OrderReviewItemView[] {
  return items.map((item) => {
    if (item.orderItemId !== orderItemId) {
      return item;
    }
    const rating = patch.rating === undefined
      ? item.rating
      : normalizeRating(patch.rating);
    return {
      ...item,
      ...patch,
      rating,
      stars: buildRatingStars(rating),
      content: patch.content === undefined
        ? item.content
        : String(patch.content).slice(0, 1000)
    };
  });
}

export function reviewProgressText(completed: number, total: number): string {
  const safeTotal = Math.max(0, Math.floor(Number(total) || 0));
  const safeCompleted = Math.min(
    safeTotal,
    Math.max(0, Math.floor(Number(completed) || 0))
  );
  return safeTotal ? `已完成 ${safeCompleted}/${safeTotal}` : "评价已完成";
}
