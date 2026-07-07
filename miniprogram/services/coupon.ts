import type {
  AvailableCouponResponse,
  ClaimableCoupon,
  UserCoupon
} from "../types/api";
import { request } from "../utils/request";

export function getClaimableCoupons(): Promise<ClaimableCoupon[]> {
  return request<ClaimableCoupon[]>({
    url: "/app/coupons/claimable"
  });
}

export function claimCoupon(templateId: number): Promise<UserCoupon> {
  return request<UserCoupon>({
    url: `/app/coupons/templates/${templateId}/claim`,
    method: "POST"
  });
}

export function getMyCoupons(status?: UserCoupon["status"]): Promise<UserCoupon[]> {
  const query = status ? `?status=${status}` : "";

  return request<UserCoupon[]>({
    url: `/app/coupons/mine${query}`
  });
}

export function getAvailableCoupons(cartItemIds?: number[]): Promise<AvailableCouponResponse> {
  const query = cartItemIds && cartItemIds.length > 0
    ? `?cartItemIds=${cartItemIds.join(",")}`
    : "";

  return request<AvailableCouponResponse>({
    url: `/app/coupons/available${query}`
  });
}
