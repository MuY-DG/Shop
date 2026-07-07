import type { OrderDetail, OrderItem, OrderStatus } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import { getOrderDetail } from "../../../services/order";
import { formatPrice } from "../../../services/product";

interface OrderItemView extends OrderItem {
  imageUrl: string;
  unitPriceText: string;
  originalPriceText: string;
  lineAmountText: string;
  lineOriginalAmountText: string;
}

interface OrderDetailView extends OrderDetail {
  items: OrderItemView[];
  statusText: string;
  createdAtText: string;
  closedAtText: string;
  productOriginalAmountText: string;
  productAmountText: string;
  couponDiscountText: string;
  freightText: string;
  payableAmountText: string;
  paidAmountText: string;
}

interface OrderDetailPageData {
  loading: boolean;
  errorText: string;
  orderId: number;
  detail: OrderDetailView | null;
}

function parsePositiveNumber(value: string | undefined): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function getStatusText(status: OrderStatus): string {
  if (status === "CREATED") {
    return "待支付";
  }
  if (status === "PAID") {
    return "已支付";
  }
  if (status === "CLOSED") {
    return "已关闭";
  }
  if (status === "REFUNDED") {
    return "已退款";
  }
  const exhaustiveStatus: never = status;
  return exhaustiveStatus;
}

function formatDateTime(value: string | null): string {
  return value ? value.replace("T", " ").slice(0, 16) : "";
}

function toItemView(item: OrderItem): OrderItemView {
  return {
    ...item,
    imageUrl: item.displayImage || item.skuImage || item.mainImage,
    unitPriceText: formatPrice(item.unitPriceCent),
    originalPriceText: formatPrice(item.originalPriceCent),
    lineAmountText: formatPrice(item.lineAmountCent),
    lineOriginalAmountText: formatPrice(item.lineOriginalAmountCent)
  };
}

function toDetailView(detail: OrderDetail): OrderDetailView {
  return {
    ...detail,
    items: detail.items.map(toItemView),
    statusText: getStatusText(detail.status),
    createdAtText: formatDateTime(detail.createdAt),
    closedAtText: formatDateTime(detail.closedAt),
    productOriginalAmountText: formatPrice(detail.productOriginalAmountCent),
    productAmountText: formatPrice(detail.productAmountCent),
    couponDiscountText: formatPrice(detail.couponDiscountCent),
    freightText: formatPrice(detail.freightCent),
    payableAmountText: formatPrice(detail.payableAmountCent),
    paidAmountText: formatPrice(detail.paidAmountCent)
  };
}

Page<OrderDetailPageData, WechatMiniprogram.Page.CustomOption>({
  data: {
    loading: false,
    errorText: "",
    orderId: 0,
    detail: null as OrderDetailView | null
  },
  async onLoad(query: Record<string, string | undefined>) {
    const orderId = parsePositiveNumber(query.order_id);
    this.setData({
      orderId
    });

    if (!orderId) {
      this.setData({
        errorText: "订单不存在"
      });
      return;
    }

    await this.loadDetail();
  },
  async onPullDownRefresh() {
    await this.loadDetail();
    wx.stopPullDownRefresh();
  },
  async loadDetail() {
    if (!this.data.orderId) {
      return;
    }

    this.setData({
      loading: true,
      errorText: ""
    });

    try {
      await ensureAppLogin();
      const detail = await getOrderDetail(this.data.orderId);
      this.setData({
        detail: toDetailView(detail)
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "订单详情加载失败",
        detail: null
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  }
});
