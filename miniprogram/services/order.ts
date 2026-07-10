import type {
  OrderDetail,
  OrderPreviewRequest,
  OrderPreviewResponse,
  OrderStatus,
  OrderSubmitRequest,
  OrderSubmitResponse,
  OrderSummary,
  PageResult,
  PaymentCancelResponse,
  PaymentPrepayResponse,
  PaymentSyncResponse
} from "../types/api";
import { request } from "../utils/request";

export function previewOrder(payload: OrderPreviewRequest): Promise<OrderPreviewResponse> {
  return request<OrderPreviewResponse>({
    url: "/app/orders/preview",
    method: "POST",
    data: payload
  });
}

export function submitOrder(payload: OrderSubmitRequest): Promise<OrderSubmitResponse> {
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

export function payOrder(orderId: number): Promise<PaymentPrepayResponse> {
  return request<PaymentPrepayResponse>({
    url: `/app/orders/${orderId}/pay`,
    method: "POST"
  });
}

export function cancelOrder(orderId: number): Promise<PaymentCancelResponse> {
  return request<PaymentCancelResponse>({
    url: `/app/orders/${orderId}/cancel`,
    method: "POST"
  });
}

export function syncOrderPayment(orderId: number): Promise<PaymentSyncResponse> {
  return request<PaymentSyncResponse>({
    url: `/app/orders/${orderId}/payment/sync`,
    method: "POST"
  });
}
