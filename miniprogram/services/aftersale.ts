import type { AfterSaleApplyPayload, AfterSaleResponse } from "../types/api";
import { request } from "../utils/request";

export function applyAfterSale(
  orderId: number,
  payload: AfterSaleApplyPayload
): Promise<AfterSaleResponse> {
  return request<AfterSaleResponse>({
    url: `/app/orders/${orderId}/after-sales`,
    method: "POST",
    data: payload
  });
}

export function getOrderAfterSales(orderId: number): Promise<AfterSaleResponse[]> {
  return request<AfterSaleResponse[]>({
    url: `/app/orders/${orderId}/after-sales`
  });
}
