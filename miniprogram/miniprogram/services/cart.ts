import { API_ENDPOINTS } from "../constants/api-endpoints";
import type { AddCartItemRequest, CartItemResponse } from "../types/cart";
import { request } from "../utils/request";

export function addCartItem(data: AddCartItemRequest): Promise<CartItemResponse> {
  return request<CartItemResponse, AddCartItemRequest>({
    url: API_ENDPOINTS.cart.items,
    method: "POST",
    data
  });
}
