import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AvailableCouponResponse,
  CheckoutSelection
} from "../types/checkout";
import type {
  ClaimableCoupon,
  UserCoupon,
  UserCouponStatus
} from "../types/coupon";
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

export function getClaimableCoupons(): Promise<ClaimableCoupon[]> {
  return request<ClaimableCoupon[]>({
    url: API_ENDPOINTS.coupons.claimable,
    method: "GET"
  });
}

export function claimCoupon(templateId: number): Promise<UserCoupon> {
  return request<UserCoupon>({
    url: API_ENDPOINTS.coupons.claim(templateId),
    method: "POST"
  });
}

export function getMyCoupons(status?: UserCouponStatus): Promise<UserCoupon[]> {
  return request<UserCoupon[]>({
    url: API_ENDPOINTS.coupons.mine,
    method: "GET",
    data: status ? { status } : undefined
  });
}
