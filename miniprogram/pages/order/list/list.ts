import type { OrderStatus, OrderSummary } from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import { getOrders } from "../../../services/order";
import { formatPrice } from "../../../services/product";

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface OrderSummaryView extends OrderSummary {
  statusText: string;
  productAmountText: string;
  couponDiscountText: string;
  freightText: string;
  payableAmountText: string;
  createdAtText: string;
}

interface OrderListPageData {
  loading: boolean;
  errorText: string;
  orders: OrderSummaryView[];
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
  return "已取消";
}

function formatDateTime(value: string): string {
  return value ? value.replace("T", " ").slice(0, 16) : "";
}

function toOrderSummaryView(order: OrderSummary): OrderSummaryView {
  return {
    ...order,
    statusText: getStatusText(order.status),
    productAmountText: formatPrice(order.productAmountCent),
    couponDiscountText: formatPrice(order.couponDiscountCent),
    freightText: formatPrice(order.freightCent),
    payableAmountText: formatPrice(order.payableAmountCent),
    createdAtText: formatDateTime(order.createdAt)
  };
}

Page<OrderListPageData, WechatMiniprogram.Page.CustomOption>({
  data: {
    loading: false,
    errorText: "",
    orders: [] as OrderSummaryView[]
  },
  async onShow() {
    await this.loadOrders();
  },
  async onPullDownRefresh() {
    await this.loadOrders();
    wx.stopPullDownRefresh();
  },
  async loadOrders() {
    this.setData({
      loading: true,
      errorText: ""
    });

    try {
      await ensureAppLogin();
      const response = await getOrders({
        current: 1,
        size: 20
      });
      this.setData({
        orders: response.records.map(toOrderSummaryView)
      });
    } catch (error) {
      this.setData({
        errorText: error instanceof Error ? error.message : "订单加载失败",
        orders: []
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  },
  onOrderTap(event: DatasetEvent) {
    const orderId = Number(event.currentTarget.dataset.id);
    if (!Number.isFinite(orderId) || orderId <= 0) {
      return;
    }

    wx.navigateTo({
      url: `/pages/order/detail/detail?order_id=${orderId}`
    });
  }
});
