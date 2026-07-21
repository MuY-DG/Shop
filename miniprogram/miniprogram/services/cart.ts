import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AddCartItemRequest,
  CartItemResponse,
  CartListResponse,
  UpdateCartQuantityRequest
} from "../types/cart";
import { request } from "../utils/request";

export function getCartItems(): Promise<CartListResponse> {
  return request<CartListResponse>({
    url: API_ENDPOINTS.cart.items,
    method: "GET"
  });
}

export function addCartItem(data: AddCartItemRequest): Promise<CartItemResponse> {
  return request<CartItemResponse, AddCartItemRequest>({
    url: API_ENDPOINTS.cart.items,
    method: "POST",
    data
  });
}

export function updateCartItemQuantity(
  cartItemId: number,
  data: UpdateCartQuantityRequest
): Promise<CartItemResponse> {
  return request<CartItemResponse, UpdateCartQuantityRequest>({
    url: API_ENDPOINTS.cart.quantity(cartItemId),
    method: "PUT",
    data
  });
}

export function deleteCartItem(cartItemId: number): Promise<void> {
  return request<void>({
    url: API_ENDPOINTS.cart.item(cartItemId),
    method: "DELETE",
    expectData: false
  });
}

export function clearCart(): Promise<void> {
  return request<void>({
    url: API_ENDPOINTS.cart.items,
    method: "DELETE",
    expectData: false
  });
}
