import type { AfterSaleResponse } from "./after-sale";

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
  | "TO_REVIEW"
  | "COMPLETED"
  | "CANCELLED";

export interface OrderListQuery {
  current: number;
  size: number;
  statusGroup: OrderStatusGroup;
  keyword?: string;
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
  items: OrderSummaryItemResponse[];
  pendingReviewCount: number;
  createdAt: string;
}

export interface OrderSummaryItemResponse {
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
  unitPriceCent: number;
  quantity: number;
  reviewed: boolean;
  reviewable: boolean;
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
  reviewed: boolean;
  reviewable: boolean;
}

export type LogisticsType = 1 | 2 | 3 | 4;
export type DeliveryMode = 1;
export type ShipmentSource = "MANUAL" | "WECHAT_WAYBILL";
export type WechatShippingProviderMode = "REAL" | "MOCK" | "DISABLED" | "UNKNOWN";
export type WechatShippingUploadStatus =
  | "PENDING"
  | "SKIPPED"
  | "UPLOADING"
  | "UPLOADED"
  | "FAILED"
  | "UNAVAILABLE"
  | "UNKNOWN";
export type WaybillRegistrationKind = "TRACE" | "FOLLOW";
export type WaybillRegistrationStatus =
  | "PENDING"
  | "REGISTERING"
  | "REGISTERED"
  | "FAILED"
  | "UNKNOWN"
  | "UNAVAILABLE"
  | "SKIPPED";

export interface AppOrderShipmentResponse {
  shipmentId: number;
  orderId: number;
  logisticsType: LogisticsType;
  deliveryMode: DeliveryMode;
  itemDesc: string;
  expressCompanyCode: string | null;
  expressCompanyName: string | null;
  trackingNo: string | null;
  shipmentSource: ShipmentSource;
  electronicWaybillId?: number;
  localShipmentStatus: string;
  wechatProviderMode: WechatShippingProviderMode;
  wechatUploadStatus: WechatShippingUploadStatus;
  wechatUploadMessage: string | null;
  waybillTrackingSupported: boolean;
  waybillRegistrationKind: WaybillRegistrationKind | null;
  waybillRegistrationStatus: WaybillRegistrationStatus | null;
  waybillRegistrationMessage: string | null;
  shippedAt: string;
  uploadTime: string | null;
  wechatUploadedAt: string | null;
}

export interface OrderWaybillTokenResponse {
  waybillToken: string;
}

export type WechatTrackingSyncStatus =
  | "NOT_REQUESTED"
  | "SYNCING"
  | "SYNCED"
  | "UNSUPPORTED"
  | "FAILED"
  | "UNKNOWN"
  | "UNAVAILABLE";

export type WechatLogisticsStatus =
  | "NOT_FOUND"
  | "PICKED_UP"
  | "IN_TRANSIT"
  | "OUT_FOR_DELIVERY"
  | "SIGNED"
  | "EXCEPTION"
  | "SIGNED_BY_OTHER";

export interface ShipmentTrackingEventResponse {
  actionTime: number;
  actionType: number;
  actionMessage: string;
}

export interface ShipmentTrackingResponse {
  shipmentId: number;
  orderId: number;
  carrierCode: string;
  carrierName: string;
  trackingNo: string;
  querySupported: boolean;
  querySyncStatus: WechatTrackingSyncStatus;
  logisticsStatus: WechatLogisticsStatus | null;
  logisticsStatusText: string | null;
  queryErrorCode: string | null;
  queryErrorMessage: string | null;
  pathSupported: boolean;
  pathSyncStatus: WechatTrackingSyncStatus;
  pathErrorCode: string | null;
  pathErrorMessage: string | null;
  officialViewAvailable: boolean;
  pathItems: ShipmentTrackingEventResponse[];
  lastAttemptAt: string | null;
  lastSyncedAt: string | null;
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
  paymentExpiresAt?: string;
  paymentRemainingSeconds?: number;
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
  shipment?: AppOrderShipmentResponse | null;
  latestAfterSale?: AfterSaleResponse;
  rebuyableOrderItemIds?: number[];
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
