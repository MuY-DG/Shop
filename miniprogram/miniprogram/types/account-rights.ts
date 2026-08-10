export type AccountRightsRequestType =
  | "ACCOUNT_CANCELLATION"
  | "PERSONAL_INFORMATION_DELETION"
  | "ACCESS_COPY"
  | "CORRECTION";

export type AccountRightsRequestStatus =
  | "PENDING"
  | "IN_REVIEW"
  | "APPROVED"
  | "REJECTED"
  | "WITHDRAWN"
  | "COMPLETED";

export interface AccountRightsSubmitRequest {
  requestType: AccountRightsRequestType;
  requestNote?: string;
  wechatCode?: string;
}

export interface AccountRightsWithdrawRequest {
  version: number;
}

export interface AccountRightsRequestResponse {
  id: string;
  userId: string;
  userNickname: string;
  userStatus: string;
  requestType: AccountRightsRequestType;
  status: AccountRightsRequestStatus;
  requestNote?: string;
  identityVerifiedAt?: string;
  reviewReason?: string;
  retentionExplanation?: string;
  retainedDataCategories: string[];
  reviewedBy?: string;
  reviewedAt?: string;
  approvedAt?: string;
  rejectedAt?: string;
  withdrawnAt?: string;
  completedAt?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AccountRightsAuditResponse {
  id: string;
  action: string;
  actorType: string;
  actorId?: string;
  fromStatus?: AccountRightsRequestStatus;
  toStatus: AccountRightsRequestStatus;
  reason?: string;
  retentionExplanation?: string;
  retainedDataCategories: string[];
  createdAt: string;
}

export interface AccountRightsDetailResponse {
  request: AccountRightsRequestResponse;
  audits: AccountRightsAuditResponse[];
}
