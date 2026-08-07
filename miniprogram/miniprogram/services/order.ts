import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  OrderPreviewRequest,
  OrderPreviewResponse,
  OrderSubmitRequest,
  OrderSubmitResponse
} from "../types/checkout";
import type { PageResult } from "../types/api";
import type {
  AppOrderDetailResponse,
  OrderListQuery,
  OrderReceiptResponse,
  OrderStatus,
  OrderSummaryResponse,
  PaymentCancelResponse,
  PaymentSyncResponse,
  WechatPaymentParamsResponse
} from "../types/order";
import { request } from "../utils/request";

export interface OrderReceiverUpdateRequest extends WechatMiniprogram.IAnyObject {
  addressId: string;
}

export interface OrderReceiverUpdateResponse {
  orderId: number;
  status: OrderStatus;
  receiverName: string;
  receiverPhone: string;
  receiverAddress: string;
  updatedAt: string;
}

export function previewOrder(data: OrderPreviewRequest): Promise<OrderPreviewResponse> {
  return request<OrderPreviewResponse, OrderPreviewRequest>({
    url: API_ENDPOINTS.orders.preview,
    method: "POST",
    data
  });
}

export function submitOrder(data: OrderSubmitRequest): Promise<OrderSubmitResponse> {
  return request<OrderSubmitResponse, OrderSubmitRequest>({
    url: API_ENDPOINTS.orders.submit,
    method: "POST",
    data
  });
}

export function getOrders(query: OrderListQuery): Promise<PageResult<OrderSummaryResponse>> {
  return request<PageResult<OrderSummaryResponse>>({
    url: API_ENDPOINTS.orders.list,
    method: "GET",
    data: {
      current: query.current,
      size: query.size,
      statusGroup: query.statusGroup,
      ...(query.keyword ? { keyword: query.keyword } : {})
    }
  });
}

export function getOrderDetail(orderId: number): Promise<AppOrderDetailResponse> {
  return request<AppOrderDetailResponse>({
    url: API_ENDPOINTS.orders.detail(orderId),
    method: "GET"
  });
}

export function updateOrderReceiver(
  orderId: number,
  addressId: string
): Promise<OrderReceiverUpdateResponse> {
  return request<OrderReceiverUpdateResponse, OrderReceiverUpdateRequest>({
    url: API_ENDPOINTS.orders.receiver(orderId),
    method: "PUT",
    data: { addressId }
  });
}

export function deleteOrder(orderId: number): Promise<void> {
  return request<void>({
    url: API_ENDPOINTS.orders.delete(orderId),
    method: "DELETE",
    expectData: false
  });
}

export function initiateOrderPayment(
  orderId: number
): Promise<WechatPaymentParamsResponse> {
  return request<WechatPaymentParamsResponse>({
    url: API_ENDPOINTS.orders.pay(orderId),
    method: "POST"
  });
}

export function cancelOrder(orderId: number): Promise<PaymentCancelResponse> {
  return request<PaymentCancelResponse>({
    url: API_ENDPOINTS.orders.cancel(orderId),
    method: "POST"
  });
}

export function syncOrderPayment(orderId: number): Promise<PaymentSyncResponse> {
  return request<PaymentSyncResponse>({
    url: API_ENDPOINTS.orders.paymentSync(orderId),
    method: "POST"
  });
}

export function confirmOrderReceipt(orderId: number): Promise<OrderReceiptResponse> {
  return request<OrderReceiptResponse>({
    url: API_ENDPOINTS.orders.confirmReceipt(orderId),
    method: "POST"
  });
}
