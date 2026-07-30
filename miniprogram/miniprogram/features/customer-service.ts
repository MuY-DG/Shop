import type {
  CustomerServiceContextType,
  CustomerServiceConversationStatus
} from "../types/customer-service";

export const CUSTOMER_SERVICE_ROUTE = "/pages/customer-service/chat/chat";

export interface CustomerServiceEntryContext {
  contextType: CustomerServiceContextType;
  contextId?: number;
}

function positiveId(value: unknown): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

export function customerServiceEntryContext(
  contextType: unknown,
  contextId: unknown
): CustomerServiceEntryContext {
  const normalizedType = contextType === "PRODUCT" || contextType === "ORDER"
    ? contextType
    : "GENERAL";
  const normalizedId = positiveId(contextId);
  if (normalizedType === "GENERAL" || !normalizedId) {
    return { contextType: "GENERAL" };
  }
  return {
    contextType: normalizedType,
    contextId: normalizedId
  };
}

export function buildCustomerServiceUrl(
  contextType: CustomerServiceContextType = "GENERAL",
  contextId?: number
): string {
  const context = customerServiceEntryContext(contextType, contextId);
  if (context.contextType === "GENERAL") {
    return CUSTOMER_SERVICE_ROUTE;
  }
  return `${CUSTOMER_SERVICE_ROUTE}?contextType=${context.contextType}&contextId=${context.contextId}`;
}

export function customerServiceStatusHint(
  status: CustomerServiceConversationStatus,
  assignedAdminDisplayName?: string
): string {
  switch (status) {
    case "DRAFT":
      return "发送消息后，客服会尽快接待";
    case "WAITING":
      return "正在为你接入客服";
    case "ACTIVE":
      return assignedAdminDisplayName?.trim()
        ? `${assignedAdminDisplayName.trim()} 正在为你服务`
        : "客服正在为你服务";
    case "CLOSED":
      return "本次服务已结束，发送消息可再次咨询";
  }
}

export function formatCustomerServiceMoney(value: unknown): string {
  const cent = typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : 0;
  return `¥${(cent / 100).toFixed(2)}`;
}

export function customerServiceOrderStatusText(status: unknown): string {
  switch (status) {
    case "CREATED":
      return "待付款";
    case "PAYING":
      return "支付中";
    case "PAID":
      return "待发货";
    case "SHIPPED":
      return "待收货";
    case "COMPLETED":
      return "已完成";
    case "REFUNDING":
      return "退款中";
    case "REFUNDED":
      return "已退款";
    case "CLOSED":
      return "已关闭";
    default:
      return "订单";
  }
}

export function customerServicePriceRange(
  minimumCent: unknown,
  maximumCent: unknown
): string {
  const minimum = typeof minimumCent === "number" && minimumCent >= 0
    ? minimumCent
    : undefined;
  const maximum = typeof maximumCent === "number" && maximumCent >= 0
    ? maximumCent
    : undefined;
  if (minimum === undefined && maximum === undefined) {
    return "价格以商品详情为准";
  }
  const primary = minimum ?? maximum ?? 0;
  if (minimum !== undefined && maximum !== undefined && maximum > minimum) {
    return `${formatCustomerServiceMoney(minimum)}–${formatCustomerServiceMoney(maximum)}`;
  }
  return formatCustomerServiceMoney(primary);
}

export function customerServiceMessageId(): string {
  const random = Math.random().toString(36).slice(2, 12);
  return `app-${Date.now().toString(36)}-${random}`;
}
