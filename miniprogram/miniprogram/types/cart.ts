export interface AddCartItemRequest {
  skuId: number;
  quantity: number;
}

export interface CartItemResponse {
  id: number;
  skuId: number;
  spuId: number;
  productTitle: string;
  productSubtitle?: string;
  mainImage?: string;
  skuImage?: string;
  displayImage?: string;
  specText?: string;
  priceCent: number;
  retailPriceCent: number;
  originalPriceCent?: number;
  wholesaleTierMinQuantity?: number;
  nextWholesaleTierMinQuantity?: number;
  nextWholesaleTierPriceCent?: number;
  nextWholesaleTierQuantityNeeded?: number;
  maxPurchaseQuantity?: number;
  quantity: number;
  lineAmountCent: number;
  skuStatus?: "ENABLED" | "DISABLED";
  spuStatus?: "DRAFT" | "ON_SALE" | "OFF_SALE";
  available: boolean;
  unavailableReason?: "SKU_UNAVAILABLE" | "PRODUCT_UNAVAILABLE" | "SOLD_OUT" | "STOCK_SHORTAGE";
  createdAt?: string;
  updatedAt?: string;
}

export interface CartListResponse {
  items: CartItemResponse[];
  totalQuantity: number;
  totalAmountCent: number;
  unavailableCount: number;
}

export interface UpdateCartQuantityRequest {
  quantity: number;
}

export interface DeleteCartItemsRequest {
  cartItemIds: number[];
}
