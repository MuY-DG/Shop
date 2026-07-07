import type {
  OrderDetail,
  OrderPreviewResponse,
  OrderStatus,
  OrderSubmitResponse,
  OrderSummary,
  PageResult
} from "../types/api";
import { request } from "../utils/request";

export function previewOrder(payload: {
  cartItemIds: number[];
  userCouponId?: number | null;
}): Promise<OrderPreviewResponse> {
  return request<OrderPreviewResponse>({
    url: "/app/orders/preview",
    method: "POST",
    data: payload
  });
}

export function submitOrder(payload: {
  cartItemIds: number[];
  userCouponId?: number | null;
  idempotencyKey: string;
}): Promise<OrderSubmitResponse> {
  return request<OrderSubmitResponse>({
    url: "/app/orders",
    method: "POST",
    data: payload
  });
}

export function getOrders(params: {
  current: number;
  size: number;
  status?: OrderStatus;
}): Promise<PageResult<OrderSummary>> {
  const query = [
    `current=${params.current}`,
    `size=${params.size}`,
    params.status ? `status=${params.status}` : ""
  ].filter(Boolean).join("&");

  return request<PageResult<OrderSummary>>({
    url: `/app/orders?${query}`
  });
}

export function getOrderDetail(orderId: number): Promise<OrderDetail> {
  return request<OrderDetail>({
    url: `/app/orders/${orderId}`
  });
}
