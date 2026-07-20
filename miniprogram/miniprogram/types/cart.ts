export interface AddCartItemRequest {
  skuId: number;
  quantity: number;
}

export interface CartItemResponse {
  id: number;
  skuId: number;
  quantity: number;
}
