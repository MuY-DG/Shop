export interface ApiResponse<T> {
  code: number;
  msg: string;
  data: T;
}

export interface AppUserProfile {
  userId: number;
  openidMasked: string;
  phoneAuthorized: boolean;
  phoneNumberMasked: string | null;
}

export interface AppSessionResponse {
  token: string;
  refreshToken: string;
  expiresIn: number;
  user: AppUserProfile;
}

export type RequestBody = string | WechatMiniprogram.IAnyObject | ArrayBuffer;

export interface RequestOptions<TBody extends RequestBody = WechatMiniprogram.IAnyObject> {
  url: string;
  method?: "GET" | "POST" | "PUT" | "DELETE";
  data?: TBody;
  auth?: boolean;
  recoverAuth?: boolean;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

export interface ProductCategory {
  id: number;
  parentId: number;
  name: string;
  icon: string;
  sortOrder: number;
  status: "ENABLED" | "DISABLED";
}

export type HomeBannerJumpType = "NONE" | "PRODUCT" | "CATEGORY" | "COUPON" | "APP_PATH" | "URL";

export interface HomeBanner {
  id: number;
  title: string;
  subtitle: string;
  imageUrl: string;
  jumpType: HomeBannerJumpType;
  jumpTargetId: number | null;
  jumpPath: string | null;
}

export type EvidenceUploadPurpose = "AFTER_SALE_IMAGE" | "REFUND_EVIDENCE";

export interface StorageFileUploadResponse {
  id: number;
  purpose: EvidenceUploadPurpose;
  assetCategoryId: number | null;
  visibility: string;
  provider: string;
  originalFilename: string;
  contentType: string;
  extension: string;
  sizeBytes: number;
  sha256?: string | null;
  width?: number | null;
  height?: number | null;
  status: string;
  uploadedByType: string;
  uploadedById?: number | null;
  url?: string | null;
  publicUrl?: string | null;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string | null;
}

export interface ProductListItem {
  id: number;
  categoryId: number;
  title: string;
  subtitle: string;
  mainImage: string;
  sellingPoints: string[];
  minPriceCent?: number;
  maxPriceCent?: number;
  totalStock: number;
}

export interface ProductImage {
  id: number;
  url: string;
  sortOrder: number;
}

export interface ProductSku {
  id: number;
  skuCode: string;
  specJson: string;
  specText: string;
  priceCent: number;
  originalPriceCent: number;
  stockAvailable: number;
  weightGram: number;
  image: string;
  status: "ENABLED" | "DISABLED";
}

export interface ProductDetail {
  id: number;
  categoryId: number;
  categoryName: string;
  title: string;
  subtitle: string;
  mainImage: string;
  sellingPoints: string[];
  detailHtml: string;
  images: ProductImage[];
  skus: ProductSku[];
}

export interface CartItem {
  id: number;
  skuId: number;
  spuId: number;
  productTitle: string;
  productSubtitle: string;
  mainImage: string;
  skuImage: string;
  displayImage: string;
  specText: string;
  priceCent: number;
  originalPriceCent: number;
  quantity: number;
  lineAmountCent: number;
  stockAvailable: number;
  skuStatus: "ENABLED" | "DISABLED" | null;
  spuStatus: "DRAFT" | "ON_SALE" | "OFF_SALE" | null;
  available: boolean;
  unavailableReason: "SKU_UNAVAILABLE" | "PRODUCT_UNAVAILABLE" | "STOCK_SHORTAGE" | null;
  createdAt: string;
  updatedAt: string;
}

export interface CartListResponse {
  items: CartItem[];
  totalQuantity: number;
  totalAmountCent: number;
  unavailableCount: number;
}

export interface ClaimableCoupon {
  templateId: number;
  name: string;
  description: string;
  couponType: "NO_THRESHOLD" | "MIN_SPEND";
  thresholdCent: number;
  discountCent: number;
  validStartAt: string;
  validEndAt: string;
  claimedCount: number;
  perUserLimit: number;
  claimable: boolean;
  unavailableReason: string | null;
}

export interface UserCoupon {
  userCouponId: number;
  templateId: number;
  name: string;
  couponType: "NO_THRESHOLD" | "MIN_SPEND";
  thresholdCent: number;
  discountCent: number;
  scopeType: "ALL" | "PRODUCT" | "CATEGORY";
  status: "CLAIMED" | "LOCKED" | "USED" | "EXPIRED";
  validStartAt: string;
  validEndAt: string;
  claimedAt: string;
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
  unavailableReason: string | null;
  validEndAt: string;
}

export interface AvailableCouponResponse {
  cartAmountCent: number;
  bestUserCouponId: number | null;
  bestDiscountCent: number;
  payableAmountCent: number;
  coupons: AvailableCouponItem[];
}

export type OrderStatus =
  | "CREATED"
  | "PAYING"
  | "PAID"
  | "SHIPPED"
  | "COMPLETED"
  | "CLOSED"
  | "REFUNDING"
  | "REFUNDED";

export interface PaymentPrepayResponse {
  paymentOrderId?: number;
  outTradeNo?: string;
  timeStamp: string;
  nonceStr: string;
  package: string;
  signType: string;
  paySign: string;
  expiresAt?: string;
}

export interface PaymentCancelResponse {
  orderId: number;
  status: OrderStatus;
}

export interface PaymentSyncResponse {
  orderId: number;
  status: OrderStatus;
  transactionId: string;
}

export type AfterSaleType = "REFUND_ONLY" | "RETURN_REFUND";

export type AfterSaleStatus =
  | "REQUESTED"
  | "APPROVED"
  | "REJECTED"
  | "REFUNDING"
  | "REFUNDED"
  | "REFUND_FAILED";

export interface AfterSaleApplyPayload {
  afterSaleType: AfterSaleType;
  reason: string;
  requestedAmountCent: number;
  description: string;
  evidenceFileIds: number[];
}

export interface AfterSaleEvidenceFile {
  fileId: number;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  visibility: string;
  purpose: EvidenceUploadPurpose;
  status: string;
}

export interface RefundOrder {
  id: number;
  afterSaleId: number;
  orderId: number;
  paymentOrderId: number;
  outRefundNo: string;
  refundId: string | null;
  refundAmountCent: number;
  status: string;
  callbackStatus: string;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  requestedAt: string;
  successAt: string | null;
}

export interface AfterSaleResponse {
  id: number;
  orderId: number;
  orderNo: string;
  userId: number;
  afterSaleType: AfterSaleType;
  status: AfterSaleStatus;
  reason: string;
  description: string;
  requestedAmountCent: number;
  approvedAmountCent: number | null;
  auditNote: string | null;
  reviewedBy: number | null;
  reviewedAt: string | null;
  createdAt: string;
  evidenceFileIds: number[];
  evidenceFiles: AfterSaleEvidenceFile[];
  refundOrder: RefundOrder | null;
}

export interface OrderPreviewItem {
  cartItemId: number;
  skuId: number;
  spuId: number;
  productTitle: string;
  productSubtitle: string;
  mainImage: string;
  skuImage: string;
  displayImage: string;
  skuCode: string;
  specText: string;
  originalPriceCent: number;
  unitPriceCent: number;
  quantity: number;
  lineOriginalAmountCent: number;
  lineAmountCent: number;
}

export interface OrderPreviewResponse {
  items: OrderPreviewItem[];
  productOriginalAmountCent: number;
  productAmountCent: number;
  userCouponId: number | null;
  couponName: string | null;
  couponDiscountCent: number;
  freightCent: number;
  payableAmountCent: number;
}

export interface OrderSubmitResponse {
  orderId: number;
  orderNo: string;
  status: OrderStatus;
  payableAmountCent: number;
  couponDiscountCent: number;
  createdAt: string;
}

export interface OrderSummary {
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

export interface OrderItem {
  orderItemId: number;
  skuId: number;
  spuId: number;
  productTitle: string;
  productSubtitle: string;
  mainImage: string;
  skuImage: string;
  displayImage: string;
  skuCode: string;
  specText: string;
  originalPriceCent: number;
  unitPriceCent: number;
  quantity: number;
  lineOriginalAmountCent: number;
  lineAmountCent: number;
}

export interface OrderDetail {
  orderId: number;
  orderNo: string;
  status: OrderStatus;
  source: string;
  productOriginalAmountCent: number;
  productAmountCent: number;
  userCouponId: number | null;
  couponName: string | null;
  couponDiscountCent: number;
  freightCent: number;
  payableAmountCent: number;
  paidAmountCent: number;
  receiverName: string | null;
  receiverPhone: string | null;
  receiverAddress: string | null;
  paymentTransactionId: string | null;
  merchantTradeNo: string | null;
  closeReason: string | null;
  closedAt: string | null;
  createdAt: string;
  items: OrderItem[];
}
