export type CouponType = "NO_THRESHOLD" | "MIN_SPEND";
export type UserCouponStatus = "CLAIMED" | "LOCKED" | "USED" | "EXPIRED";

export interface ClaimableCoupon {
  templateId: number;
  name: string;
  description?: string;
  couponType: CouponType;
  thresholdCent: number;
  discountCent: number;
  validStartAt: string;
  validEndAt: string;
  claimedCount: number;
  perUserLimit: number;
  claimable: boolean;
  unavailableReason?: string;
}

export interface UserCoupon {
  userCouponId: number;
  templateId: number;
  name: string;
  couponType: CouponType;
  thresholdCent: number;
  discountCent: number;
  scopeType: string;
  status: UserCouponStatus;
  validStartAt: string;
  validEndAt: string;
  claimedAt: string;
}
