import { formatMoney } from "./product-catalog";
import { formatLocalDateTime } from "../utils/date-time";
import type {
  AppOrderDetailResponse,
  OrderItemResponse,
  OrderStatus,
  OrderStatusGroup,
  OrderSummaryItemResponse,
  OrderSummaryResponse
} from "../types/order";
import {
  buildAfterSaleView,
  canApplyAfterSale,
  isActiveAfterSale,
  type AfterSaleView
} from "./after-sale";

export interface PageOperationGuard {
  mount(): number;
  unmount(pageToken: number): void;
  begin(pageToken: number): number;
  isCurrent(pageToken: number, operationToken: number): boolean;
}

export function createPageOperationGuard(): PageOperationGuard {
  let nextPageToken = 0;
  let nextOperationToken = 0;
  const activePages = new Set<number>();
  const currentOperations = new Map<number, number>();

  return {
    mount(): number {
      const pageToken = ++nextPageToken;
      activePages.add(pageToken);
      return pageToken;
    },

    unmount(pageToken: number): void {
      activePages.delete(pageToken);
      currentOperations.delete(pageToken);
    },

    begin(pageToken: number): number {
      if (!activePages.has(pageToken)) {
        return 0;
      }
      const operationToken = ++nextOperationToken;
      currentOperations.set(pageToken, operationToken);
      return operationToken;
    },

    isCurrent(pageToken: number, operationToken: number): boolean {
      return pageToken > 0
        && operationToken > 0
        && activePages.has(pageToken)
        && currentOperations.get(pageToken) === operationToken;
    }
  };
}

export interface OrderStatusTab {
  value: OrderStatusGroup;
  label: string;
}

export const ORDER_STATUS_TABS: readonly OrderStatusTab[] = Object.freeze([
  { value: "ALL", label: "全部" },
  { value: "UNPAID", label: "待付款" },
  { value: "TO_SHIP", label: "待发货" },
  { value: "TO_RECEIVE", label: "待收货" },
  { value: "TO_REVIEW", label: "待评价" },
  { value: "COMPLETED", label: "已完成" },
  { value: "CANCELLED", label: "已取消" }
]);

interface OrderActions {
  canPay: boolean;
  canCancel: boolean;
  canSyncPayment: boolean;
  canConfirmReceipt: boolean;
  canDelete: boolean;
  canRebuy: boolean;
  paymentActionText: string;
}

interface OrderSummaryActions {
  canPay: boolean;
  canCancel: boolean;
  canModify: boolean;
  canDelete: boolean;
  canRebuy: boolean;
  canReview: boolean;
  hasActions: boolean;
  paymentActionText: string;
}

export interface OrderSummaryItemView extends OrderSummaryItemResponse {
  titleText: string;
  specificationText: string;
  imageUrl: string;
  hasImage: boolean;
  unitPriceText: string;
  quantityText: string;
}

export interface OrderSummaryView extends Omit<OrderSummaryResponse, "items">, OrderSummaryActions {
  items: OrderSummaryItemView[];
  statusText: string;
  statusTone: string;
  amountText: string;
  createdAtText: string;
  itemCountText: string;
}

export interface OrderItemView extends OrderItemResponse {
  imageUrl: string;
  hasImage: boolean;
  unitPriceText: string;
  lineAmountText: string;
  wholesaleText: string;
}

export interface OrderDetailView extends AppOrderDetailResponse, OrderActions {
  items: OrderItemView[];
  statusText: string;
  statusTone: string;
  statusDescription: string;
  productAmountText: string;
  wholesaleDiscountCent: number;
  wholesaleDiscountText: string;
  hasWholesaleDiscount: boolean;
  couponDiscountText: string;
  hasCouponDiscount: boolean;
  freightText: string;
  payableAmountText: string;
  paidAmountText: string;
  createdAtText: string;
  paidAtText: string;
  shippedAtText: string;
  completedAtText: string;
  hasAfterSale: boolean;
  canApplyAfterSale: boolean;
  afterSaleActionText: string;
  latestAfterSaleView?: AfterSaleView;
}

function money(cent: unknown): string {
  return `¥${formatMoney(cent) || "0.00"}`;
}

export function orderStatusText(status: OrderStatus): string {
  switch (status) {
    case "CREATED":
    case "PAYING":
      return "待付款";
    case "PAID":
      return "待发货";
    case "SHIPPED":
      return "待收货";
    case "COMPLETED":
      return "已完成";
    case "CLOSED":
      return "已取消";
    case "REFUNDING":
      return "退款中";
    case "REFUNDED":
      return "已退款";
  }
}

function orderStatusTone(status: OrderStatus): string {
  switch (status) {
    case "CREATED":
    case "PAYING":
      return "warning";
    case "PAID":
    case "SHIPPED":
      return "brand";
    case "COMPLETED":
      return "success";
    default:
      return "muted";
  }
}

function statusDescription(status: OrderStatus): string {
  switch (status) {
    case "CREATED":
    case "PAYING":
      return "请尽快完成支付，超时订单将自动取消";
    case "PAID":
      return "支付成功，正在等待商家发货";
    case "SHIPPED":
      return "商品已发出，收货后请确认完成";
    case "COMPLETED":
      return "订单已完成，感谢你的购买";
    case "CLOSED":
      return "订单已取消，库存与优惠券已释放";
    case "REFUNDING":
      return "退款正在处理中";
    case "REFUNDED":
      return "退款已经完成";
  }
}

function actions(status: OrderStatus): OrderActions {
  const canPay = status === "CREATED" || status === "PAYING";
  return {
    canPay,
    canCancel: canPay,
    canSyncPayment: status === "PAYING",
    canConfirmReceipt: status === "SHIPPED",
    canDelete: status === "CLOSED",
    canRebuy: status === "CLOSED",
    paymentActionText: status === "PAYING" ? "继续支付" : "立即支付"
  };
}

function summaryActions(
  status: OrderStatus,
  pendingReviewCount: number
): OrderSummaryActions {
  const canPay = status === "CREATED" || status === "PAYING";
  const canReview = status === "COMPLETED" && pendingReviewCount > 0;
  const canDelete = status === "COMPLETED" || status === "CLOSED";
  const canRebuy = status === "SHIPPED" || canDelete;
  return {
    canPay,
    canCancel: canPay,
    canModify: canPay,
    canDelete,
    canRebuy,
    canReview,
    hasActions: canPay || canDelete || canRebuy || canReview,
    paymentActionText: "去支付"
  };
}

export function formatPaymentCountdown(value: unknown): string {
  const parsed = Number(value);
  const totalSeconds = Number.isFinite(parsed)
    ? Math.max(0, Math.floor(parsed))
    : 0;
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const twoDigits = (part: number): string => String(part).padStart(2, "0");
  return `${twoDigits(hours)}时${twoDigits(minutes)}分${twoDigits(seconds)}秒`;
}

export function buildOrderSummaryView(order: OrderSummaryResponse): OrderSummaryView {
  const pendingReviewCount = Number.isSafeInteger(order.pendingReviewCount)
    ? Math.max(0, order.pendingReviewCount)
    : 0;
  const orderActions = summaryActions(order.status, pendingReviewCount);
  return {
    ...order,
    ...orderActions,
    pendingReviewCount,
    items: (Array.isArray(order.items) ? order.items : []).map(buildOrderSummaryItemView),
    statusText: orderActions.canReview ? "待评价" : orderStatusText(order.status),
    statusTone: orderActions.canReview ? "brand" : orderStatusTone(order.status),
    amountText: money(order.status === "PAID" || order.paidAmountCent > 0
      ? order.paidAmountCent
      : order.payableAmountCent),
    createdAtText: formatLocalDateTime(order.createdAt),
    itemCountText: `共 ${Math.max(0, order.itemCount)} 件商品`
  };
}

function buildOrderSummaryItemView(
  item: OrderSummaryItemResponse
): OrderSummaryItemView {
  const imageUrl = (item.displayImage || item.skuImage || item.mainImage || "").trim();
  const titleText = typeof item.productTitle === "string"
    ? item.productTitle.trim()
    : "";
  const specificationText = typeof item.specText === "string"
    ? item.specText.trim()
    : "";
  const quantity = Number.isSafeInteger(item.quantity)
    ? Math.max(0, item.quantity)
    : 0;
  return {
    ...item,
    titleText: titleText || "订单商品",
    specificationText,
    imageUrl,
    hasImage: Boolean(imageUrl),
    unitPriceText: money(item.unitPriceCent),
    quantity,
    quantityText: `共${quantity}件`
  };
}

function buildOrderItemView(item: OrderItemResponse): OrderItemView {
  const imageUrl = (item.displayImage || item.skuImage || item.mainImage || "").trim();
  return {
    ...item,
    imageUrl,
    hasImage: Boolean(imageUrl),
    unitPriceText: money(item.unitPriceCent),
    lineAmountText: money(item.lineAmountCent),
    wholesaleText: item.wholesaleTierMinQuantity
      ? `${item.wholesaleTierMinQuantity} 件起批发价`
      : ""
  };
}

export function buildOrderDetailView(order: AppOrderDetailResponse): OrderDetailView {
  const retailProductAmountCent = order.items.reduce(
    (total, item) => total + item.retailUnitPriceCent * item.quantity,
    0
  );
  const wholesaleDiscountCent = Math.max(
    retailProductAmountCent - order.productAmountCent,
    0
  );
  const latestAfterSaleView = order.latestAfterSale
    ? buildAfterSaleView(order.latestAfterSale)
    : undefined;
  const orderActions = actions(order.status);
  const fulfillmentBlocked = order.latestAfterSale
    ? isActiveAfterSale(order.latestAfterSale.status)
    : false;
  return {
    ...order,
    ...orderActions,
    items: order.items.map(buildOrderItemView),
    statusText: orderStatusText(order.status),
    statusTone: orderStatusTone(order.status),
    statusDescription: statusDescription(order.status),
    productAmountText: money(retailProductAmountCent),
    wholesaleDiscountCent,
    wholesaleDiscountText: money(wholesaleDiscountCent),
    hasWholesaleDiscount: wholesaleDiscountCent > 0,
    couponDiscountText: money(order.couponDiscountCent),
    hasCouponDiscount: order.couponDiscountCent > 0,
    freightText: money(order.freightCent),
    payableAmountText: money(order.payableAmountCent),
    paidAmountText: money(order.paidAmountCent),
    createdAtText: formatLocalDateTime(order.createdAt),
    paidAtText: formatLocalDateTime(order.paidAt),
    shippedAtText: formatLocalDateTime(order.shippedAt),
    completedAtText: formatLocalDateTime(order.completedAt),
    canConfirmReceipt: orderActions.canConfirmReceipt && !fulfillmentBlocked,
    hasAfterSale: Boolean(latestAfterSaleView),
    canApplyAfterSale: canApplyAfterSale(order.status, order.latestAfterSale),
    afterSaleActionText: latestAfterSaleView?.status === "REJECTED" ? "重新申请售后" : "申请售后",
    latestAfterSaleView
  };
}

export function parseOrderStatusGroup(value: unknown): OrderStatusGroup {
  const normalized = String(value || "ALL").toUpperCase();
  return ORDER_STATUS_TABS.some((tab) => tab.value === normalized)
    ? normalized as OrderStatusGroup
    : "ALL";
}

export function positiveOrderId(value: unknown): number {
  const text = typeof value === "string" ? value.trim() : value;
  if (typeof text === "string" && !/^\d+$/.test(text)) {
    return 0;
  }
  const parsed = Number(text);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

export function buildOrderListUrl(group: OrderStatusGroup = "ALL"): string {
  return `/pages/order/list/list?group=${group}`;
}

export function buildOrderDetailUrl(orderId: number): string {
  const normalized = positiveOrderId(orderId);
  if (!normalized) {
    throw new Error("订单参数无效");
  }
  return `/pages/order/detail/detail?order_id=${normalized}`;
}

export function buildOrderReviewUrl(orderId: number): string {
  const normalized = positiveOrderId(orderId);
  if (!normalized) {
    throw new Error("订单参数无效");
  }
  return `/pages/order/review/review?order_id=${normalized}`;
}

export function buildOrderModifyUrl(orderId: number): string {
  const normalized = positiveOrderId(orderId);
  if (!normalized) {
    throw new Error("订单参数无效");
  }
  return `/pages/order/modify/modify?order_id=${normalized}`;
}
