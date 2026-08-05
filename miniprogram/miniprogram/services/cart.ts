import { API_ENDPOINTS } from "../constants/api-endpoints";
import { CartItemsCache } from "../features/cart-items-cache";
import type {
  AddCartItemRequest,
  CartItemResponse,
  CartListResponse,
  DeleteCartItemsRequest,
  UpdateCartQuantityRequest
} from "../types/cart";
import { request } from "../utils/request";
import { getSessionState } from "./session";

const CART_ITEMS_CACHE_TTL_MS = 30_000;
const cartItemsCache = new CartItemsCache<CartListResponse>(
  CART_ITEMS_CACHE_TTL_MS
);

export interface GetCartItemsOptions {
  preferCache?: boolean;
}

function cartOwnerKey(): string {
  const session = getSessionState();
  return session.user?.userId || session.refreshToken || session.accessToken || "guest";
}

function invalidateCartItemsCache(): void {
  cartItemsCache.invalidate();
}

export function getCartItems(
  options: GetCartItemsOptions = {}
): Promise<CartListResponse> {
  return cartItemsCache.get(
    cartOwnerKey(),
    () => request<CartListResponse>({
      url: API_ENDPOINTS.cart.items,
      method: "GET"
    }),
    options.preferCache === true
  );
}

export async function addCartItem(
  data: AddCartItemRequest
): Promise<CartItemResponse> {
  const item = await request<CartItemResponse, AddCartItemRequest>({
    url: API_ENDPOINTS.cart.items,
    method: "POST",
    data
  });
  invalidateCartItemsCache();
  return item;
}

export async function updateCartItemQuantity(
  cartItemId: number,
  data: UpdateCartQuantityRequest
): Promise<CartItemResponse> {
  const item = await request<CartItemResponse, UpdateCartQuantityRequest>({
    url: API_ENDPOINTS.cart.quantity(cartItemId),
    method: "PUT",
    data
  });
  invalidateCartItemsCache();
  return item;
}

export async function deleteCartItem(cartItemId: number): Promise<void> {
  await request<void>({
    url: API_ENDPOINTS.cart.item(cartItemId),
    method: "DELETE",
    expectData: false
  });
  invalidateCartItemsCache();
}

export async function deleteCartItems(cartItemIds: number[]): Promise<void> {
  await request<void, DeleteCartItemsRequest>({
    url: API_ENDPOINTS.cart.batchDelete,
    method: "DELETE",
    data: { cartItemIds },
    expectData: false
  });
  invalidateCartItemsCache();
}

export async function clearCart(): Promise<void> {
  await request<void>({
    url: API_ENDPOINTS.cart.items,
    method: "DELETE",
    expectData: false
  });
  invalidateCartItemsCache();
}
