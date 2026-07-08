import type {
  AfterSaleResponse,
  AfterSaleStatus,
  AfterSaleType,
  OrderDetail,
  OrderItem,
  OrderStatus,
  PaymentPrepayResponse
} from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import { getOrderAfterSales } from "../../../services/aftersale";
import { cancelOrder, getOrderDetail, payOrder, syncOrderPayment } from "../../../services/order";
import { formatPrice } from "../../../services/product";

type PaymentSignType = NonNullable<WechatMiniprogram.RequestPaymentOption["signType"]>;

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

interface AfterSaleView extends AfterSaleResponse {
  typeText: string;
  statusText: string;
  requestedAmountText: string;
  refundAmountText: string;
  auditNoteText: string;
  createdAtText: string;
}

interface OrderDetailPageData {
  loading: boolean;
  paymentOperating: boolean;
  cancelOperating: boolean;
  errorText: string;
  orderId: number;
  detail: OrderDetailView | null;
  latestAfterSale: AfterSaleView | null;
  canPay: boolean;
  canCancel: boolean;
  canApplyAfterSale: boolean;
  hasActiveAfterSale: boolean;
  showActionBar: boolean;
}

const ACTIVE_AFTER_SALE_STATUSES: AfterSaleStatus[] = [
  "REQUESTED",
  "APPROVED",
  "REFUNDING",
  "REFUND_FAILED"
];

function parsePositiveNumber(value: string | undefined): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function getStatusText(status: OrderStatus): string {
  if (status === "CREATED") {
    return "待支付";
  }
  if (status === "PAYING") {
    return "支付中";
  }
  if (status === "PAID") {
    return "已支付";
  }
  if (status === "SHIPPED") {
    return "已发货";
  }
  if (status === "COMPLETED") {
    return "已完成";
  }
  if (status === "CLOSED") {
    return "已关闭";
  }
  if (status === "REFUNDING") {
    return "退款中";
  }
  if (status === "REFUNDED") {
    return "已退款";
  }
  const exhaustiveStatus: never = status;
  return exhaustiveStatus;
}

function getAfterSaleTypeText(type: AfterSaleType): string {
  if (type === "REFUND_ONLY") {
    return "仅退款";
  }
  if (type === "RETURN_REFUND") {
    return "退货退款";
  }
  const exhaustiveType: never = type;
  return exhaustiveType;
}

function getAfterSaleStatusText(status: AfterSaleStatus): string {
  if (status === "REQUESTED") {
    return "待审核";
  }
  if (status === "APPROVED") {
    return "已同意";
  }
  if (status === "REJECTED") {
    return "已拒绝";
  }
  if (status === "REFUNDING") {
    return "退款中";
  }
  if (status === "REFUNDED") {
    return "已退款";
  }
  if (status === "REFUND_FAILED") {
    return "退款失败";
  }
  const exhaustiveStatus: never = status;
  return exhaustiveStatus;
}

function formatDateTime(value: string | null): string {
  return value ? value.replace("T", " ").slice(0, 16) : "";
}

function toErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function toPaymentSignType(value: string): PaymentSignType {
  if (value === "MD5" || value === "HMAC-SHA256" || value === "RSA") {
    return value;
  }
  return "RSA";
}

function requestWechatPayment(payment: PaymentPrepayResponse): Promise<void> {
  return new Promise((resolve, reject) => {
    wx.requestPayment({
      timeStamp: payment.timeStamp,
      nonceStr: payment.nonceStr,
      package: payment.package,
      signType: toPaymentSignType(payment.signType),
      paySign: payment.paySign,
      success: () => {
        resolve();
      },
      fail: (error) => {
        reject(new Error(error.errMsg || "支付未完成"));
      }
    });
  });
}

function confirmDialog(title: string, content: string): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title,
      content,
      confirmText: "确认",
      cancelText: "再想想",
      success: (result) => {
        resolve(result.confirm);
      },
      fail: () => {
        resolve(false);
      }
    });
  });
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

function toAfterSaleView(afterSale: AfterSaleResponse): AfterSaleView {
  const refundAmountCent =
    afterSale.refundOrder?.refundAmountCent ??
    afterSale.approvedAmountCent ??
    afterSale.requestedAmountCent;

  return {
    ...afterSale,
    typeText: getAfterSaleTypeText(afterSale.afterSaleType),
    statusText: getAfterSaleStatusText(afterSale.status),
    requestedAmountText: formatPrice(afterSale.requestedAmountCent),
    refundAmountText: formatPrice(refundAmountCent),
    auditNoteText: afterSale.auditNote || "暂无审核备注",
    createdAtText: formatDateTime(afterSale.createdAt)
  };
}

function isActiveAfterSale(afterSale: AfterSaleResponse): boolean {
  return ACTIVE_AFTER_SALE_STATUSES.includes(afterSale.status);
}

function canApplyAfterSale(detail: OrderDetail, hasActiveAfterSale: boolean): boolean {
  return (detail.status === "PAID" || detail.status === "SHIPPED") && !hasActiveAfterSale;
}

Page<OrderDetailPageData, WechatMiniprogram.Page.CustomOption>({
  data: {
    loading: false,
    paymentOperating: false,
    cancelOperating: false,
    errorText: "",
    orderId: 0,
    detail: null as OrderDetailView | null,
    latestAfterSale: null as AfterSaleView | null,
    canPay: false,
    canCancel: false,
    canApplyAfterSale: false,
    hasActiveAfterSale: false,
    showActionBar: false
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
      const [detail, afterSales] = await Promise.all([
        getOrderDetail(this.data.orderId),
        getOrderAfterSales(this.data.orderId)
      ]);
      const hasActiveAfterSale = afterSales.some(isActiveAfterSale);
      const canPay = detail.status === "CREATED" || detail.status === "PAYING";
      const canCancel = canPay;
      const canApply = canApplyAfterSale(detail, hasActiveAfterSale);

      this.setData({
        detail: toDetailView(detail),
        latestAfterSale: afterSales.length > 0 ? toAfterSaleView(afterSales[0]) : null,
        canPay,
        canCancel,
        canApplyAfterSale: canApply,
        hasActiveAfterSale,
        showActionBar: canPay || canCancel || canApply || hasActiveAfterSale
      });
    } catch (error) {
      this.setData({
        errorText: toErrorMessage(error, "订单详情加载失败"),
        detail: null,
        latestAfterSale: null,
        canPay: false,
        canCancel: false,
        canApplyAfterSale: false,
        hasActiveAfterSale: false,
        showActionBar: false
      });
    } finally {
      this.setData({
        loading: false
      });
    }
  },
  async onPayTap() {
    if (!this.data.orderId || !this.data.canPay || this.data.paymentOperating) {
      return;
    }

    this.setData({
      paymentOperating: true
    });

    try {
      await ensureAppLogin();
      const payment = await payOrder(this.data.orderId);

      try {
        await requestWechatPayment(payment);
      } catch {
        await this.loadDetail();
        wx.showToast({
          title: "支付未完成，已刷新状态",
          icon: "none"
        });
        return;
      }

      try {
        await syncOrderPayment(this.data.orderId);
      } catch (error) {
        wx.showToast({
          title: toErrorMessage(error, "支付同步失败"),
          icon: "none"
        });
      }

      await this.loadDetail();
    } catch (error) {
      wx.showToast({
        title: toErrorMessage(error, "发起支付失败"),
        icon: "none"
      });
      await this.loadDetail();
    } finally {
      this.setData({
        paymentOperating: false
      });
    }
  },
  async onCancelTap() {
    if (!this.data.orderId || !this.data.canCancel || this.data.cancelOperating) {
      return;
    }

    const confirmed = await confirmDialog("取消订单", "确认取消该订单？库存和优惠券将由后台释放。");
    if (!confirmed) {
      return;
    }

    this.setData({
      cancelOperating: true
    });

    try {
      await ensureAppLogin();
      await cancelOrder(this.data.orderId);
      await this.loadDetail();
      wx.showToast({
        title: "订单已取消",
        icon: "success"
      });
    } catch (error) {
      wx.showToast({
        title: toErrorMessage(error, "取消失败"),
        icon: "none"
      });
    } finally {
      this.setData({
        cancelOperating: false
      });
    }
  },
  onAfterSaleTap() {
    if (!this.data.orderId || !this.data.detail || !this.data.canApplyAfterSale) {
      return;
    }

    const maxAmountCent = this.data.detail.paidAmountCent || this.data.detail.payableAmountCent;
    wx.navigateTo({
      url: `/pages/aftersale/apply/apply?order_id=${this.data.orderId}&max_amount_cent=${maxAmountCent}`
    });
  }
});
