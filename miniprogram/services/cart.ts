import type { CartItem, CartListResponse } from "../types/api";
import { request } from "../utils/request";

export interface AddCartItemPayload {
  skuId: number;
  quantity: number;
  analyticsVisitorId?: string;
  analyticsSessionId?: string;
  analyticsEntryScene?: string;
}

export interface UpdateCartQuantityPayload {
  quantity: number;
}

export function getCartItems(): Promise<CartListResponse> {
  return request<CartListResponse>({
    url: "/app/cart/items"
  });
}

export function addCartItem(payload: AddCartItemPayload): Promise<CartItem> {
  return request<CartItem>({
    url: "/app/cart/items",
    method: "POST",
    data: payload
  });
}

export function updateCartItemQuantity(
  cartItemId: number,
  payload: UpdateCartQuantityPayload
): Promise<CartItem> {
  return request<CartItem>({
    url: `/app/cart/items/${cartItemId}/quantity`,
    method: "PUT",
    data: payload
  });
}

export function deleteCartItem(cartItemId: number): Promise<void> {
  return request<void>({
    url: `/app/cart/items/${cartItemId}`,
    method: "DELETE"
  });
}

export function clearCart(): Promise<void> {
  return request<void>({
    url: "/app/cart/items",
    method: "DELETE"
  });
}
