export type OrderStatus =
  | "CREATED"
  | "PAYING"
  | "PAID"
  | "SHIPPED"
  | "COMPLETED"
  | "CLOSED"
  | "REFUNDING"
  | "REFUNDED";

export type OrderStatusGroup =
  | "ALL"
  | "UNPAID"
  | "TO_SHIP"
  | "TO_RECEIVE"
  | "COMPLETED";

export interface OrderListQuery {
  current: number;
  size: number;
  statusGroup: OrderStatusGroup;
}

export interface OrderSummaryResponse {
  orderId: number;
  orderNo: string;
  status: OrderStatus;
  productAmountCent: number;
  couponDiscountCent: number;
  freightCent: number;
  payableAmountCent: number;
  paidAmountCent: number;
  productTitle: string;
  itemCount: number;
  createdAt: string;
}

export interface OrderItemResponse {
  orderItemId: number;
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

export interface AppOrderDetailResponse {
  orderId: number;
  orderNo: string;
  status: OrderStatus;
  source: "CART" | "DIRECT";
  productOriginalAmountCent: number;
  productAmountCent: number;
  userCouponId?: number;
  couponName?: string;
  couponDiscountCent: number;
  freightCent: number;
  payableAmountCent: number;
  paidAmountCent: number;
  receiverName: string;
  receiverPhone: string;
  receiverAddress: string;
  paymentTransactionId?: string;
  merchantTradeNo?: string;
  paymentStatus?: string;
  outTradeNo?: string;
  transactionId?: string;
  paidAt?: string;
  closeReason?: string;
  closedAt?: string;
  createdAt: string;
  shippedAt?: string;
  completedAt?: string;
  refundingAt?: string;
  refundedAt?: string;
  shipment?: unknown;
  latestAfterSale?: unknown;
  items: OrderItemResponse[];
}

export interface OrderReceiptResponse {
  orderId: number;
  status: OrderStatus;
  completedAt: string;
}

export interface WechatPaymentParamsResponse {
  timeStamp: string;
  nonceStr: string;
  package: string;
  signType: string;
  paySign: string;
}

export interface PaymentCancelResponse {
  orderId: number;
  status: OrderStatus;
}

export interface PaymentSyncResponse {
  orderId: number;
  status: OrderStatus;
  transactionId?: string;
}
