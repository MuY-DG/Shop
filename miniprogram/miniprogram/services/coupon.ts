import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AvailableCouponResponse,
  CheckoutSelection
} from "../types/checkout";
import { request } from "../utils/request";

export function getAvailableCoupons(
  data: CheckoutSelection
): Promise<AvailableCouponResponse> {
  return request<AvailableCouponResponse, CheckoutSelection>({
    url: API_ENDPOINTS.coupons.available,
    method: "POST",
    data
  });
}
