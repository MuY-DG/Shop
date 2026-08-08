import { buildCartCheckoutUrl } from "../../../features/checkout";
import {
  buildAfterSaleApplyUrl,
  buildAfterSaleDetailUrl
} from "../../../features/after-sale";
import {
  buildOrderDetailView,
  buildOrderListUrl,
  buildOrderModifyUrl,
  copyOrderNo,
  filterRebuyableOrderItems,
  formatPaymentCountdown,
  positiveOrderId,
  REBUY_NO_AVAILABLE_ITEMS_MESSAGE,
  rebuyFailureMessage,
  rebuyPartialMessage,
  type OrderDetailView
} from "../../../features/order-center";
import { buildCustomerServiceUrl } from "../../../features/customer-service";
import {
  executeOrderPayment,
  recoverOrderPayment
} from "../../../features/order-payment";
import { confirmWechatOrderReceipt } from "../../../features/wechat-order-receipt";
import { addCartItem } from "../../../services/cart";
import {
  cancelOrder,
  confirmOrderReceipt,
  deleteOrder,
  getOrderDetail
} from "../../../services/order";
import { isApiError } from "../../../utils/api-error";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
    };
  };
}

let latestDetailRequest = 0;
let countdownTimer: ReturnType<typeof setInterval> | null = null;
let countdownDeadlineMs = 0;

function countdownDisplay(value: number): {
  countdownText: string;
  countdownHours: string;
  countdownMinutes: string;
  countdownSeconds: string;
} {
  const countdownText = formatPaymentCountdown(value);
  const [countdownHours, countdownMinutes, countdownSeconds] = countdownText.split(":");
  return {
    countdownText,
    countdownHours,
    countdownMinutes,
    countdownSeconds
  };
}

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function confirmAction(title: string, content: string, confirmText: string): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title,
      content,
      confirmText,
      confirmColor: "#B72B22",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    });
  });
}

Page({
  data: {
    orderId: 0,
    detail: null as OrderDetailView | null,
    orderInfoExpanded: true,
    countdownText: "",
    countdownHours: "",
    countdownMinutes: "",
    countdownSeconds: "",
    hasCountdown: false,
    paymentExpired: false,
    expiryAttempted: false,
    countdownConfirmed: false,
    loading: true,
    loaded: false,
    errorText: "",
    actionType: ""
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderId = positiveOrderId(query.order_id);
    if (!orderId) {
      this.setData({
        loading: false,
        errorText: "订单参数无效"
      });
      return;
    }
    this.setData({ orderId });
    void this.refreshDetail();
  },

  onShow() {
    if (this.data.loaded && this.data.detail?.status === "PAYING" && !this.data.actionType) {
      void this.recoverPayment();
    } else if (this.data.loaded && !this.data.actionType) {
      if (this.data.detail?.canPay && countdownDeadlineMs > 0) {
        this.startCountdownTimer();
      }
      void this.refreshDetail();
    }
  },

  onHide() {
    this.stopCountdownTimer();
  },

  onUnload() {
    latestDetailRequest += 1;
    this.stopCountdownTimer();
    countdownDeadlineMs = 0;
  },

  onRetry() {
    void this.refreshDetail();
  },

  async refreshDetail() {
    if (!this.data.orderId) {
      return;
    }
    const requestId = ++latestDetailRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const response = await getOrderDetail(this.data.orderId);
      if (requestId !== latestDetailRequest) {
        return;
      }
      const detail = buildOrderDetailView(response);
      this.setData({
        detail,
        loading: false,
        loaded: true,
        errorText: ""
      });
      this.configureCountdown(detail);
    } catch (error) {
      if (requestId !== latestDetailRequest) {
        return;
      }
      this.setData({
        loading: false,
        loaded: this.data.detail !== null,
        errorText: actionError(error, "订单详情加载失败")
      });
    }
  },

  configureCountdown(detail: OrderDetailView) {
    this.stopCountdownTimer();
    if (!detail.canPay) {
      countdownDeadlineMs = 0;
      this.setData({
        countdownText: "",
        countdownHours: "",
        countdownMinutes: "",
        countdownSeconds: "",
        hasCountdown: false,
        paymentExpired: false,
        expiryAttempted: false,
        countdownConfirmed: false
      });
      return;
    }
    const value = Number(detail.paymentRemainingSeconds);
    if (!Number.isFinite(value)) {
      countdownDeadlineMs = 0;
      this.setData({
        countdownText: "",
        countdownHours: "",
        countdownMinutes: "",
        countdownSeconds: "",
        hasCountdown: false,
        paymentExpired: false,
        countdownConfirmed: false
      });
      return;
    }
    const remainingSeconds = Math.max(0, Math.floor(value));
    countdownDeadlineMs = Date.now() + remainingSeconds * 1000;
    this.setData({
      ...countdownDisplay(remainingSeconds),
      hasCountdown: true,
      paymentExpired: remainingSeconds === 0,
      expiryAttempted: remainingSeconds > 0 ? false : this.data.expiryAttempted,
      countdownConfirmed: remainingSeconds > 0 || this.data.countdownConfirmed
    });
    this.startCountdownTimer();
  },

  startCountdownTimer() {
    this.stopCountdownTimer();
    this.updateCountdown();
    if (countdownDeadlineMs > Date.now()) {
      countdownTimer = setInterval(() => this.updateCountdown(), 1000);
    }
  },

  stopCountdownTimer() {
    if (countdownTimer !== null) {
      clearInterval(countdownTimer);
      countdownTimer = null;
    }
  },

  updateCountdown() {
    if (!this.data.detail?.canPay || countdownDeadlineMs <= 0) {
      this.stopCountdownTimer();
      return;
    }
    const remainingSeconds = Math.max(
      0,
      Math.ceil((countdownDeadlineMs - Date.now()) / 1000)
    );
    this.setData({
      ...countdownDisplay(remainingSeconds),
      hasCountdown: true,
      paymentExpired: remainingSeconds === 0
    });
    if (remainingSeconds === 0) {
      this.stopCountdownTimer();
      if (
        this.data.countdownConfirmed &&
        !this.data.expiryAttempted &&
        !this.data.actionType
      ) {
        this.setData({ expiryAttempted: true });
        void this.expireCurrentOrder();
      }
    }
  },

  async expireCurrentOrder() {
    if (!this.data.orderId || this.data.actionType || !this.data.detail?.canPay) {
      return;
    }
    this.setData({ actionType: "expire" });
    try {
      await cancelOrder(this.data.orderId);
    } catch {
      // 服务端超时任务仍会关闭订单；刷新用于识别支付恰好成功的竞争结果。
    } finally {
      this.setData({ actionType: "" });
      await this.refreshDetail();
    }
  },

  onPayTap() {
    void this.payOrder();
  },

  async payOrder() {
    if (
      !this.data.orderId ||
      this.data.actionType ||
      this.data.paymentExpired ||
      !this.data.detail?.canPay
    ) {
      return;
    }
    this.setData({ actionType: "pay" });
    let paid = false;
    try {
      const outcome = await executeOrderPayment(this.data.orderId);
      paid = outcome === "PAID";
      if (paid) {
        wx.showToast({ title: "支付成功", icon: "success" });
      } else if (outcome === "PENDING") {
        wx.showToast({ title: "正在确认支付结果", icon: "none" });
      } else {
        wx.showToast({ title: "已取消支付", icon: "none" });
      }
    } catch (error) {
      wx.showToast({
        title: actionError(error, "支付未完成，请稍后重试"),
        icon: "none"
      });
    } finally {
      this.setData({ actionType: "" });
      if (paid) {
        this.redirectPaymentSuccess();
      } else {
        await this.refreshDetail();
      }
    }
  },

  async recoverPayment() {
    if (!this.data.orderId || this.data.actionType) {
      return;
    }
    this.setData({ actionType: "sync" });
    let paid = false;
    try {
      const response = await recoverOrderPayment(this.data.orderId);
      paid = response.status === "PAID";
      if (paid) {
        wx.showToast({ title: "支付成功", icon: "success" });
      }
    } catch {
      // 页面仍会读取服务端订单状态，不向用户暴露支付查询实现细节。
    } finally {
      this.setData({ actionType: "" });
      if (paid) {
        this.redirectPaymentSuccess();
      } else {
        await this.refreshDetail();
      }
    }
  },

  redirectPaymentSuccess() {
    const detail = this.data.detail;
    if (!detail) {
      void this.refreshDetail();
      return;
    }
    const query = [
      `order_id=${encodeURIComponent(String(detail.orderId))}`,
      `order_no=${encodeURIComponent(detail.orderNo)}`,
      `amount=${encodeURIComponent(String(detail.payableAmountCent))}`,
      "payment_status=PAID"
    ].join("&");
    wx.redirectTo({
      url: `/pages/order/created/created?${query}`,
      fail: () => void this.refreshDetail()
    });
  },

  onCancelTap() {
    void this.cancelCurrentOrder();
  },

  onModifyTap() {
    const detail = this.data.detail;
    if (!detail?.canModifyReceiver || this.data.actionType) {
      return;
    }
    wx.navigateTo({ url: buildOrderModifyUrl(detail.orderId) });
  },

  onCopyOrderNoTap() {
    copyOrderNo(this.data.detail?.orderNo);
  },

  onOrderInfoToggle() {
    this.setData({ orderInfoExpanded: !this.data.orderInfoExpanded });
  },

  onCustomerServiceTap() {
    const detail = this.data.detail;
    if (!detail || this.data.actionType) {
      return;
    }
    wx.navigateTo({ url: buildCustomerServiceUrl("ORDER", detail.orderId) });
  },

  async cancelCurrentOrder() {
    if (
      !this.data.orderId ||
      this.data.actionType ||
      !this.data.detail?.canCancel ||
      !await confirmAction("取消订单", "取消后库存和优惠券会释放，订单不能恢复。", "确认取消")
    ) {
      return;
    }
    this.setData({ actionType: "cancel" });
    try {
      await cancelOrder(this.data.orderId);
      wx.showToast({ title: "订单已取消", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "订单取消失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionType: "" });
      await this.refreshDetail();
    }
  },

  onDeleteTap() {
    void this.deleteCurrentOrder();
  },

  async deleteCurrentOrder() {
    if (
      !this.data.orderId ||
      this.data.actionType ||
      !this.data.detail?.canDelete ||
      !await confirmAction("删除订单", "删除后将不再在订单列表中显示。", "确认删除")
    ) {
      return;
    }
    this.setData({ actionType: "delete" });
    try {
      await deleteOrder(this.data.orderId);
      wx.showToast({ title: "订单已删除", icon: "success" });
      wx.redirectTo({
        url: buildOrderListUrl("ALL"),
        fail: () => {
          this.setData({ actionType: "" });
          wx.switchTab({ url: "/pages/profile/profile" });
        }
      });
    } catch (error) {
      this.setData({ actionType: "" });
      wx.showToast({
        title: actionError(error, "订单删除失败"),
        icon: "none"
      });
    }
  },

  onRebuyTap() {
    void this.rebuyCurrentOrder();
  },

  async rebuyCurrentOrder() {
    const detail = this.data.detail;
    if (!detail?.canRebuy || this.data.actionType) {
      return;
    }
    this.setData({ actionType: "rebuy" });
    const cartItemIds: number[] = [];
    let firstError: unknown = null;
    const rebuyItems = filterRebuyableOrderItems(
      detail.items,
      detail.rebuyableOrderItemIds
    );
    for (const item of rebuyItems) {
      try {
        const cartItem = await addCartItem({
          skuId: item.skuId,
          quantity: item.quantity
        });
        cartItemIds.push(cartItem.id);
      } catch (error) {
        firstError ??= error;
      }
    }
    if (!cartItemIds.length) {
      this.setData({ actionType: "" });
      wx.showToast({
        title: rebuyItems.length
          ? rebuyFailureMessage(firstError)
          : REBUY_NO_AVAILABLE_ITEMS_MESSAGE,
        icon: "none"
      });
      return;
    }
    this.setData({ actionType: "" });
    if (cartItemIds.length < detail.items.length) {
      wx.showToast({
        title: rebuyPartialMessage(cartItemIds.length, detail.items.length),
        icon: "none",
        duration: 2500
      });
    }
    wx.redirectTo({
      url: buildCartCheckoutUrl(cartItemIds),
      fail: () => wx.switchTab({ url: "/pages/cart/cart" })
    });
  },

  onConfirmTap() {
    void this.confirmCurrentOrder();
  },

  async confirmCurrentOrder() {
    if (
      !this.data.orderId ||
      this.data.actionType
    ) {
      return;
    }
    this.setData({ actionType: "confirm" });
    try {
      const transactionId = this.data.detail?.transactionId
        || this.data.detail?.paymentTransactionId
        || "";
      const componentResult = await confirmWechatOrderReceipt({
        transactionId,
        confirmLocalReceipt: () => confirmOrderReceipt(this.data.orderId)
      });
      if (componentResult.outcome === "CANCELLED") {
        return;
      }
      if (componentResult.outcome === "FAILED") {
        wx.showToast({
          title: componentResult.message || "微信确认收货失败",
          icon: "none"
        });
        return;
      }
      wx.showToast({ title: "已确认收货", icon: "success" });
    } catch (error) {
      wx.showToast({
        title: actionError(error, "确认收货失败"),
        icon: "none"
      });
    } finally {
      this.setData({ actionType: "" });
      await this.refreshDetail();
    }
  },

  onApplyAfterSaleTap() {
    const detail = this.data.detail;
    if (!detail?.canApplyAfterSale || this.data.actionType) {
      return;
    }
    wx.navigateTo({ url: buildAfterSaleApplyUrl(detail.orderId) });
  },

  onAfterSaleDetailTap() {
    const afterSaleId = this.data.detail?.latestAfterSaleView?.id;
    if (!afterSaleId || this.data.actionType) {
      return;
    }
    wx.navigateTo({ url: buildAfterSaleDetailUrl(afterSaleId) });
  },

  onItemImageError(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isSafeInteger(index) || index < 0 || !this.data.detail) {
      return;
    }
    this.setData({
      detail: {
        ...this.data.detail,
        items: this.data.detail.items.map((item, itemIndex) => (
          itemIndex === index ? { ...item, hasImage: false } : item
        ))
      }
    });
  }
});
