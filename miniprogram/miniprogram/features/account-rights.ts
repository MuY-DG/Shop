import type {
  AccountRightsAuditResponse,
  AccountRightsRequestResponse,
  AccountRightsRequestStatus,
  AccountRightsRequestType
} from "../types/account-rights";

export const ACCOUNT_RIGHTS_ROUTE = "/pages/account/rights/rights";

export interface AccountRightsRequestView extends AccountRightsRequestResponse {
  typeText: string;
  statusText: string;
  createdAtText: string;
  canWithdraw: boolean;
  retainedDataCategoriesText: string;
}

export interface AccountRightsAuditView extends AccountRightsAuditResponse {
  transitionText: string;
  createdAtText: string;
}

export function accountRightsTypeText(type: AccountRightsRequestType): string {
  switch (type) {
    case "ACCOUNT_CANCELLATION":
      return "注销账户";
    case "PERSONAL_INFORMATION_DELETION":
      return "删除个人信息";
    case "ACCESS_COPY":
      return "查阅/复制个人信息";
    case "CORRECTION":
      return "更正个人信息";
  }
}

export function accountRightsStatusText(status: AccountRightsRequestStatus): string {
  switch (status) {
    case "PENDING":
      return "待处理";
    case "IN_REVIEW":
      return "审核中";
    case "APPROVED":
      return "已批准";
    case "REJECTED":
      return "已拒绝";
    case "WITHDRAWN":
      return "已撤回";
    case "COMPLETED":
      return "已完成";
  }
}

function formatDateTime(value: string | undefined): string {
  const parsed = value ? new Date(value) : null;
  return parsed && Number.isFinite(parsed.getTime())
    ? parsed.toLocaleString()
    : "";
}

export function buildAccountRightsRequestView(
  request: AccountRightsRequestResponse
): AccountRightsRequestView {
  return {
    ...request,
    retainedDataCategories: Array.isArray(request.retainedDataCategories)
      ? request.retainedDataCategories
      : [],
    typeText: accountRightsTypeText(request.requestType),
    statusText: accountRightsStatusText(request.status),
    createdAtText: formatDateTime(request.createdAt),
    canWithdraw: request.status === "PENDING",
    retainedDataCategoriesText: (Array.isArray(request.retainedDataCategories)
      ? request.retainedDataCategories
      : []).join("、")
  };
}

export function buildAccountRightsAuditView(
  audit: AccountRightsAuditResponse
): AccountRightsAuditView {
  return {
    ...audit,
    retainedDataCategories: Array.isArray(audit.retainedDataCategories)
      ? audit.retainedDataCategories
      : [],
    transitionText: `${audit.fromStatus ? accountRightsStatusText(audit.fromStatus) : "发起"} → ${accountRightsStatusText(audit.toStatus)}`,
    createdAtText: formatDateTime(audit.createdAt)
  };
}

export function normalizeAccountRightsNote(value: string): string {
  return value.trim().replace(/\s+/g, " ");
}

export function validateAccountRightsNote(value: string): string | undefined {
  const note = normalizeAccountRightsNote(value);
  return note.length > 1000 ? "补充说明最多 1000 个字" : undefined;
}
