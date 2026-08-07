import { isApiError } from "../utils/api-error";

const STOCK_SHORTAGE_CODE = 200100;
const PRODUCT_UNAVAILABLE_CODE = 200001;
const SKU_UNAVAILABLE_CODE = 200002;

export function cartAddErrorMessage(error: unknown, fallback: string): string {
  if (!isApiError(error)) {
    return error instanceof Error ? error.message : fallback;
  }
  switch (error.code) {
    case STOCK_SHORTAGE_CODE:
    case 100400:
      return "商品已达最大可购买数";
    case PRODUCT_UNAVAILABLE_CODE:
      return "商品已下架";
    case SKU_UNAVAILABLE_CODE:
      return "该规格已下架";
    default:
      return error.message || fallback;
  }
}

export function isStockShortageError(error: unknown): boolean {
  return isApiError(error) && error.code === STOCK_SHORTAGE_CODE;
}
