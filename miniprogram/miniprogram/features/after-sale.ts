import type {
  AfterSaleApplyRequest,
  AfterSaleResponse,
  AfterSaleStatus,
  AfterSaleType
} from "../types/after-sale";
import type { OrderStatus } from "../types/order";
import { formatMoney } from "./product-catalog";

export const AFTER_SALE_REASONS = Object.freeze([
  "不想要了",
  "商品存在问题",
  "发货或物流问题",
  "收到的商品与描述不符",
  "其他原因"
]);

export type AfterSaleStatusTone = "brand" | "warning" | "success" | "danger" | "muted";
export type AfterSaleProgressState = "pending" | "current" | "done" | "error";

export interface AfterSaleProgressStep {
  label: string;
  state: AfterSaleProgressState;
}

export interface AfterSaleView extends AfterSaleResponse {
  typeText: string;
  statusText: string;
  statusTone: AfterSaleStatusTone;
  statusDescription: string;
  requestedAmountText: string;
  approvedAmountText: string;
  refundAmountText: string;
  createdAtText: string;
  reviewedAtText: string;
  refundedAtText: string;
  evidenceCountText: string;
  evidenceNames: string[];
  progressSteps: AfterSaleProgressStep[];
}

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function dateTimeText(value: unknown): string {
  const text = cleanText(value);
  return text ? text.replace("T", " ").slice(0, 16) : "";
}

function moneyText(value: unknown): string {
  return `¥${formatMoney(value) || "0.00"}`;
}

export function afterSaleTypeText(type: AfterSaleType): string {
  return type === "RETURN_REFUND" ? "退货退款" : "整单仅退款";
}

export function afterSaleStatusText(status: AfterSaleStatus): string {
  switch (status) {
    case "REQUESTED":
      return "待商家审核";
    case "APPROVED":
      return "审核已通过";
    case "REJECTED":
      return "申请未通过";
    case "REFUNDING":
      return "退款处理中";
    case "REFUNDED":
      return "退款已完成";
    case "REFUND_FAILED":
      return "退款处理异常";
  }
}

function afterSaleStatusTone(status: AfterSaleStatus): AfterSaleStatusTone {
  switch (status) {
    case "REQUESTED":
      return "warning";
    case "APPROVED":
    case "REFUNDING":
      return "brand";
    case "REFUNDED":
      return "success";
    case "REJECTED":
      return "muted";
    case "REFUND_FAILED":
      return "danger";
  }
}

function afterSaleStatusDescription(status: AfterSaleStatus): string {
  switch (status) {
    case "REQUESTED":
      return "申请已提交，商家审核后会更新处理结果";
    case "APPROVED":
      return "申请已通过，正在准备发起原路退款";
    case "REJECTED":
      return "商家未通过本次申请，可查看审核说明";
    case "REFUNDING":
      return "退款已提交微信，到账时间以微信支付通知为准";
    case "REFUNDED":
      return "退款已原路退回，具体到账时间以支付渠道为准";
    case "REFUND_FAILED":
      return "退款暂未完成，商家正在核查处理，无需重复申请";
  }
}

function progressSteps(status: AfterSaleStatus): AfterSaleProgressStep[] {
  const states: Record<AfterSaleStatus, AfterSaleProgressState[]> = {
    REQUESTED: ["done", "current", "pending"],
    APPROVED: ["done", "done", "current"],
    REJECTED: ["done", "error", "pending"],
    REFUNDING: ["done", "done", "current"],
    REFUNDED: ["done", "done", "done"],
    REFUND_FAILED: ["done", "done", "error"]
  };
  return ["提交申请", "商家审核", "退款到账"].map((label, index) => ({
    label,
    state: states[status][index] || "pending"
  }));
}

export function isActiveAfterSale(status: AfterSaleStatus): boolean {
  return status === "REQUESTED" ||
    status === "APPROVED" ||
    status === "REFUNDING" ||
    status === "REFUND_FAILED";
}

export function canApplyAfterSale(
  orderStatus: OrderStatus,
  latestAfterSale?: AfterSaleResponse
): boolean {
  const eligibleOrder = orderStatus === "PAID" ||
    orderStatus === "SHIPPED" ||
    orderStatus === "COMPLETED";
  return eligibleOrder && (!latestAfterSale || !isActiveAfterSale(latestAfterSale.status));
}

export function buildAfterSaleView(record: AfterSaleResponse): AfterSaleView {
  const evidenceFiles = Array.isArray(record.evidenceFiles) ? record.evidenceFiles : [];
  const approvedAmount = record.approvedAmountCent ?? 0;
  const refundAmount = record.refundOrder?.refundAmountCent ?? approvedAmount;
  return {
    ...record,
    description: cleanText(record.description),
    auditNote: cleanText(record.auditNote),
    evidenceFiles,
    evidenceFileIds: Array.isArray(record.evidenceFileIds) ? record.evidenceFileIds : [],
    typeText: afterSaleTypeText(record.afterSaleType),
    statusText: afterSaleStatusText(record.status),
    statusTone: afterSaleStatusTone(record.status),
    statusDescription: afterSaleStatusDescription(record.status),
    requestedAmountText: moneyText(record.requestedAmountCent),
    approvedAmountText: approvedAmount > 0 ? moneyText(approvedAmount) : "",
    refundAmountText: refundAmount > 0 ? moneyText(refundAmount) : "",
    createdAtText: dateTimeText(record.createdAt),
    reviewedAtText: dateTimeText(record.reviewedAt),
    refundedAtText: dateTimeText(record.refundOrder?.successAt),
    evidenceCountText: evidenceFiles.length ? `${evidenceFiles.length} 张` : "未上传",
    evidenceNames: evidenceFiles.map((file) => cleanText(file.originalFilename) || "售后凭证"),
    progressSteps: progressSteps(record.status)
  };
}

export function buildAfterSaleApplyPayload(input: {
  reason: unknown;
  requestedAmountCent: unknown;
  description?: unknown;
  evidenceFileIds?: unknown[];
}): AfterSaleApplyRequest {
  const reason = cleanText(input.reason).slice(0, 128);
  const amount = Number(input.requestedAmountCent);
  if (!reason) {
    throw new Error("请选择售后原因");
  }
  if (!Number.isSafeInteger(amount) || amount <= 0) {
    throw new Error("退款金额无效，请返回订单重试");
  }
  const evidenceFileIds = Array.from(new Set(
    (Array.isArray(input.evidenceFileIds) ? input.evidenceFileIds : [])
      .map(Number)
      .filter((id) => Number.isSafeInteger(id) && id > 0)
  )).slice(0, 3);
  return {
    afterSaleType: "REFUND_ONLY",
    reason,
    requestedAmountCent: amount,
    description: cleanText(input.description).slice(0, 500),
    evidenceFileIds
  };
}

export function positiveAfterSaleId(value: unknown): number {
  const text = typeof value === "string" ? value.trim() : value;
  if (typeof text === "string" && !/^\d+$/.test(text)) {
    return 0;
  }
  const id = Number(text);
  return Number.isSafeInteger(id) && id > 0 ? id : 0;
}

export function buildAfterSaleListUrl(): string {
  return "/pages/after-sale/list/list";
}

export function buildAfterSaleApplyUrl(orderId: number): string {
  const id = positiveAfterSaleId(orderId);
  if (!id) {
    throw new Error("订单参数无效");
  }
  return `/pages/after-sale/apply/apply?order_id=${id}`;
}

export function buildAfterSaleDetailUrl(afterSaleId: number): string {
  const id = positiveAfterSaleId(afterSaleId);
  if (!id) {
    throw new Error("售后参数无效");
  }
  return `/pages/after-sale/detail/detail?after_sale_id=${id}`;
}
