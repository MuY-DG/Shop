import type { PageResult } from './api'

export type AfterSaleType = 'REFUND_ONLY' | 'RETURN_REFUND'

export type AfterSaleStatus =
  | 'REQUESTED'
  | 'APPROVED'
  | 'WAITING_RETURN'
  | 'RETURNING'
  | 'WAITING_INSPECTION'
  | 'REJECTED'
  | 'RETURN_REJECTED'
  | 'CANCELLED'
  | 'REFUNDING'
  | 'REFUNDED'
  | 'REFUND_FAILED'

export type AfterSaleStatusGroup = 'PROCESSING' | 'COMPLETED'

export type AfterSaleAction =
  | 'CANCEL'
  | 'SUBMIT_RETURN_SHIPMENT'
  | 'UPDATE_RETURN_SHIPMENT'

export interface AfterSaleItemRequest {
  orderItemId: number
  quantity: number
  /** 用户自报的按件退款金额（分）；缺省表示按服务端分摊上限全额申报 */
  requestedAmountCent?: number
}

export interface AfterSaleQuoteRequest {
  afterSaleType: AfterSaleType
  items: AfterSaleItemRequest[]
}

export interface AfterSaleQuoteItem {
  orderItemId: number
  quantity: number
  requestedAmountCent: number
}

export interface AfterSaleQuoteResponse {
  orderId: number
  afterSaleType: AfterSaleType
  requestedAmountCent: number
  quoteDigest: string
  items: AfterSaleQuoteItem[]
}

export interface AfterSaleApplyRequest extends AfterSaleQuoteRequest {
  requestKey: string
  quoteDigest: string
  reason: string
  requestedAmountCent: number
  description: string
  evidenceFileIds: number[]
}

export interface AfterSaleEligibilityItem {
  orderItemId: number
  skuId: number
  productTitle: string
  specText?: string
  image?: string
  purchasedQuantity: number
  refundedQuantity: number
  availableQuantity: number
  paidAmountBasisCent: number
}

export interface AfterSaleEligibilityResponse {
  orderId: number
  orderNo: string
  orderStatus: string
  activeAfterSaleId?: number
  paidAmountCent: number
  refundedAmountCent: number
  remainingRefundableAmountCent: number
  availableTypes: AfterSaleType[]
  items: AfterSaleEligibilityItem[]
}

export interface AfterSaleEvidenceFile {
  fileId: number
  originalFilename: string
  contentType: string
  sizeBytes: number
  scope: 'ATTACHMENT'
  mediaKind: 'IMAGE'
  visibility: 'PRIVATE'
  status: string
  accessMode?: 'SIGNED_URL' | 'AUTHENTICATED_BLOB'
  accessUrl?: string
  accessExpiresAt?: string
}

export interface RefundOrderResponse {
  id: number
  afterSaleId: number
  orderId: number
  paymentOrderId: number
  outRefundNo: string
  refundId?: string
  refundAmountCent: number
  status: string
  callbackStatus: string
  lastErrorCode?: string
  lastErrorMessage?: string
  requestedAt: string
  successAt?: string
}

export interface AfterSaleItemResponse {
  id: number
  orderItemId: number
  skuId: number
  productTitle: string
  specText?: string
  image?: string
  requestedQuantity: number
  approvedQuantity?: number
  requestedAmountCent: number
  approvedAmountCent?: number
  restockQuantity?: number
}

export interface AfterSaleReturnResponse {
  returnAddressId?: number
  contactName?: string
  contactPhone?: string
  province?: string
  city?: string
  district?: string
  detailAddress?: string
  deliveryCompanyCode?: string
  deliveryCompanyName?: string
  trackingNo?: string
  returnDeadlineAt?: string
  userShippedAt?: string
  merchantReceivedAt?: string
  inspectionResult?: string
  inspectionNote?: string
  inspectedAt?: string
}

export interface AfterSaleResponse {
  id: number
  afterSaleNo: string
  orderId: number
  orderNo: string
  userId: string
  userNickname?: string
  afterSaleType: AfterSaleType
  status: AfterSaleStatus
  reason: string
  description?: string
  requestedAmountCent: number
  approvedAmountCent?: number
  auditNote?: string
  reviewedBy?: number
  reviewedAt?: string
  createdAt: string
  evidenceFileIds: number[]
  evidenceFiles: AfterSaleEvidenceFile[]
  refundOrder?: RefundOrderResponse
  items: AfterSaleItemResponse[]
  returnInfo?: AfterSaleReturnResponse
  allowedActions: AfterSaleAction[]
}

export type AfterSalePage = PageResult<AfterSaleResponse>

export interface ReturnShipmentRequest {
  deliveryCompanyCode: string
  deliveryCompanyName: string
  trackingNo: string
}

export interface StorageAssetUploadResponse {
  id: number
  scope: 'ATTACHMENT'
  mediaKind: 'IMAGE'
  visibility: 'PRIVATE'
  provider: string
  originalFilename: string
  contentType: string
  extension: string
  sizeBytes: number
  status: 'ACTIVE'
  uploadedByType: 'APP'
  uploadedById?: string
  createdAt: string
  updatedAt: string
  expiresAt?: string
}
