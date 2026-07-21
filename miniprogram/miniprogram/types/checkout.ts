export interface AddressResponse {
  id: string;
  receiverName: string;
  receiverPhone: string;
  province: string;
  city: string;
  district: string;
  detailAddress: string;
  isDefault: boolean;
  formattedAddress: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface AddressUpsertRequest {
  receiverName: string;
  receiverPhone: string;
  province: string;
  city: string;
  district: string;
  detailAddress: string;
  isDefault: boolean;
}

export type CheckoutSelection =
  | {
      source: "CART";
      cartItemIds: number[];
    }
  | {
      source: "DIRECT";
      skuId: number;
      quantity: number;
    };

export type OrderPreviewRequest = CheckoutSelection & {
  addressId?: string;
  userCouponId?: number;
};

export type OrderSubmitRequest = CheckoutSelection & {
  addressId: string;
  userCouponId?: number;
  idempotencyKey: string;
};

export interface OrderPreviewItem {
  cartItemId?: number;
  skuId: number;
  spuId: number;
  productTitle: string;
  productSubtitle?: string;
  mainImage?: string;
  skuImage?: string;
  displayImage?: string;
  skuCode: string;
  specText?: string;
  originalPriceCent: number;
  unitPriceCent: number;
  retailUnitPriceCent: number;
  wholesaleTierMinQuantity?: number;
  quantity: number;
  lineOriginalAmountCent: number;
  lineAmountCent: number;
}

export interface OrderPreviewResponse {
  items: OrderPreviewItem[];
  productOriginalAmountCent: number;
  productAmountCent: number;
  userCouponId?: number;
  couponName?: string;
  couponDiscountCent: number;
  freightCent: number;
  payableAmountCent: number;
}

export interface OrderSubmitResponse {
  orderId: number | string;
  orderNo: string;
  status: string;
  payableAmountCent: number;
  couponDiscountCent: number;
  createdAt: string;
}

export interface AvailableCouponItem {
  userCouponId: number;
  templateId: number;
  name: string;
  couponType: "NO_THRESHOLD" | "MIN_SPEND";
  thresholdCent: number;
  discountCent: number;
  discountAmountCent: number;
  available: boolean;
  unavailableReason?: string;
  validEndAt: string;
}

export interface AvailableCouponResponse {
  cartAmountCent: number;
  bestUserCouponId?: number;
  bestDiscountCent: number;
  payableAmountCent: number;
  coupons: AvailableCouponItem[];
}
