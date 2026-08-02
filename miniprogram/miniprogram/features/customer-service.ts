import type {
  CustomerServiceContextType,
  CustomerServiceConversationStatus
} from "../types/customer-service";
import { parseApiDateTime } from "../utils/date-time";

export const CUSTOMER_SERVICE_ROUTE = "/pages/customer-service/chat/chat";

export interface CustomerServiceEntryContext {
  contextType: CustomerServiceContextType;
  contextId?: number;
}

export type CustomerServiceHistoryLoadPhase = "idle" | "loading" | "restoring";

export class CustomerServiceHistoryLoadGate {
  private nextGestureId = 0;
  private armedGestureId = 0;
  private loadPhase: CustomerServiceHistoryLoadPhase = "idle";
  private latestPositionPending = false;

  get phase(): CustomerServiceHistoryLoadPhase {
    return this.loadPhase;
  }

  armGesture(canLoad: boolean): void {
    this.nextGestureId += 1;
    this.armedGestureId = canLoad && this.loadPhase === "idle"
      ? this.nextGestureId
      : 0;
  }

  consumeGesture(canLoad: boolean): boolean {
    if (!canLoad || this.loadPhase !== "idle" || !this.armedGestureId) {
      return false;
    }
    this.armedGestureId = 0;
    this.loadPhase = "loading";
    return true;
  }

  cancelGesture(): void {
    this.armedGestureId = 0;
  }

  beginManualLoad(canLoad: boolean): boolean {
    this.armedGestureId = 0;
    if (!canLoad || this.loadPhase !== "idle") {
      return false;
    }
    this.loadPhase = "loading";
    return true;
  }

  markRestoring(): void {
    if (this.loadPhase === "loading") {
      this.loadPhase = "restoring";
    }
  }

  finish(): void {
    this.armedGestureId = 0;
    this.loadPhase = "idle";
  }

  deferLatestPosition(): void {
    this.latestPositionPending = true;
  }

  takeDeferredLatestPosition(): boolean {
    if (this.loadPhase !== "idle" || !this.latestPositionPending) {
      return false;
    }
    this.latestPositionPending = false;
    return true;
  }

  cancelDeferredLatestPosition(): void {
    this.latestPositionPending = false;
  }

  reset(): void {
    this.nextGestureId = 0;
    this.latestPositionPending = false;
    this.finish();
  }
}

interface CustomerServiceTimedMessage {
  consultationNo: number;
  createdAt: string;
}

export function parseCustomerServiceDate(value: unknown): Date | null {
  return parseApiDateTime(value);
}

export function shouldShowCustomerServiceMessageTime(
  message: CustomerServiceTimedMessage,
  previous?: CustomerServiceTimedMessage
): boolean {
  if (!previous || message.consultationNo !== previous.consultationNo) {
    return true;
  }
  const currentDate = parseCustomerServiceDate(message.createdAt);
  const previousDate = parseCustomerServiceDate(previous.createdAt);
  return Boolean(
    currentDate &&
    previousDate &&
    currentDate.getTime() - previousDate.getTime() >= 5 * 60 * 1000
  );
}

export function isPersistedCustomerServiceMessageId(messageId: unknown): messageId is number {
  return typeof messageId === "number" && Number.isSafeInteger(messageId) && messageId > 0;
}

export function preserveCustomerServiceHistoryScrollTop(
  currentScrollTop: number,
  anchorTopBefore: number,
  anchorTopAfter: number
): number {
  if (
    !Number.isFinite(currentScrollTop) ||
    !Number.isFinite(anchorTopBefore) ||
    !Number.isFinite(anchorTopAfter)
  ) {
    return Math.max(0, Number.isFinite(currentScrollTop) ? currentScrollTop : 0);
  }
  return Math.max(0, currentScrollTop + anchorTopAfter - anchorTopBefore);
}

export function customerServiceBottomScrollTop(
  scrollHeight: number,
  viewportHeight: number
): number | null {
  if (
    !Number.isFinite(scrollHeight) ||
    !Number.isFinite(viewportHeight) ||
    scrollHeight < 0 ||
    viewportHeight <= 0
  ) {
    return null;
  }
  return Math.max(0, scrollHeight - viewportHeight);
}

export function isCustomerServiceBottomScrollSettled(
  scrollTop: number,
  targetScrollTop: number,
  tolerance: number
): boolean {
  return Boolean(
    Number.isFinite(scrollTop) &&
    Number.isFinite(targetScrollTop) &&
    Number.isFinite(tolerance) &&
    tolerance >= 0 &&
    Math.abs(scrollTop - targetScrollTop) <= tolerance
  );
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

export function shouldShowCustomerServiceCommonQuestions(
  status: CustomerServiceConversationStatus,
  questionCount: number,
  hasPendingUserMessage: boolean
): boolean {
  return status === "DRAFT" && questionCount > 0 && !hasPendingUserMessage;
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
