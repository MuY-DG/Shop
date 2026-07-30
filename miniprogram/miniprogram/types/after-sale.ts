import type { PageResult } from "./api";

export type AfterSaleType = "REFUND_ONLY" | "RETURN_REFUND";

export type AfterSaleStatus =
  | "REQUESTED"
  | "APPROVED"
  | "REJECTED"
  | "REFUNDING"
  | "REFUNDED"
  | "REFUND_FAILED";

export interface AfterSaleApplyRequest {
  afterSaleType: "REFUND_ONLY";
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
  scope: "ATTACHMENT";
  mediaKind: "IMAGE";
  visibility: "PRIVATE";
  status: string;
  accessMode?: "SIGNED_URL" | "AUTHENTICATED_BLOB";
  accessUrl?: string;
  accessExpiresAt?: string;
}

export interface RefundOrderResponse {
  id: number;
  afterSaleId: number;
  orderId: number;
  paymentOrderId: number;
  outRefundNo: string;
  refundId?: string;
  refundAmountCent: number;
  status: string;
  callbackStatus: string;
  lastErrorCode?: string;
  lastErrorMessage?: string;
  requestedAt: string;
  successAt?: string;
}

export interface AfterSaleResponse {
  id: number;
  afterSaleNo: string;
  orderId: number;
  orderNo: string;
  userId: string;
  userNickname?: string;
  afterSaleType: AfterSaleType;
  status: AfterSaleStatus;
  reason: string;
  description?: string;
  requestedAmountCent: number;
  approvedAmountCent?: number;
  auditNote?: string;
  reviewedBy?: number;
  reviewedAt?: string;
  createdAt: string;
  evidenceFileIds: number[];
  evidenceFiles: AfterSaleEvidenceFile[];
  refundOrder?: RefundOrderResponse;
}

export type AfterSalePage = PageResult<AfterSaleResponse>;

export interface StorageAssetUploadResponse {
  id: number;
  scope: "ATTACHMENT";
  mediaKind: "IMAGE";
  visibility: "PRIVATE";
  provider: string;
  originalFilename: string;
  contentType: string;
  extension: string;
  sizeBytes: number;
  status: "ACTIVE";
  uploadedByType: "APP";
  uploadedById?: string;
  createdAt: string;
  updatedAt: string;
  expiresAt?: string;
}
