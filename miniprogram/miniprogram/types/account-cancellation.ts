export interface AccountCancellationEligibilityResponse {
  eligible: boolean;
  activeOrderCount: number;
  activePaymentCount: number;
  activeRefundCount: number;
  activeAfterSaleCount: number;
}

export interface AccountCancellationRequest {
  wechatCode: string;
  noticeVersion: string;
  noticeContentSha256: string;
  noticeAcknowledged: true;
  miniProgramEnv: "develop" | "trial" | "release";
}

export interface AccountCancellationResponse {
  cancellationId: string;
  completedAt: string;
}
