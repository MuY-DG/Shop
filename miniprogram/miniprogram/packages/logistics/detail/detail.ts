import {
  buildOrderDetailView,
  copyTrackingNo,
  positiveOrderId,
  type OrderDetailView,
  type OrderShipmentView
} from "../../../features/order-center";
import {
  buildOrderDeliverySummary,
  buildOrderTrackingView,
  LOGISTICS_UNAVAILABLE_MESSAGE,
  openOrderLogistics,
  type OrderDeliverySummary,
  type OrderTrackingView
} from "../../../features/order-logistics";
import {
  getOrderDetail,
  getShipmentWaybillToken,
  syncShipmentTracking
} from "../../../services/order";
import { isApiError } from "../../../utils/api-error";

Page({
  data: {
    orderId: 0,
    requestedShipmentId: 0,
    detail: null as OrderDetailView | null,
    activeShipment: null as OrderShipmentView | null,
    deliverySummary: null as OrderDeliverySummary | null,
    trackingView: null as OrderTrackingView | null,
    loading: true,
    loaded: false,
    errorText: "",
    trackingLoading: false,
    trackingErrorText: "",
    logisticsOpening: false
  },
  detailRequest: 0,
  trackingRequest: 0,
  disposed: false,

  onLoad(query: Record<string, string | undefined>) {
    const orderId = positiveOrderId(query.order_id);
    if (!orderId) {
      this.setData({ loading: false, errorText: "订单参数无效" });
      return;
    }
    this.setData({
      orderId,
      requestedShipmentId: positiveOrderId(query.shipment_id) || 0
    });
    void this.refreshDetail();
  },

  onShow() {
    if (this.data.loaded) void this.refreshDetail();
  },

  onUnload() {
    this.disposed = true;
    this.detailRequest += 1;
    this.trackingRequest += 1;
  },

  async refreshDetail() {
    if (!this.data.orderId || this.disposed) return;
    const requestId = ++this.detailRequest;
    this.trackingRequest += 1;
    this.setData({ loading: true, errorText: "", trackingLoading: false });
    try {
      const response = await getOrderDetail(this.data.orderId);
      if (requestId !== this.detailRequest || this.disposed) return;
      const detail = buildOrderDetailView(response);
      const preferredId =
        this.data.activeShipment?.shipmentId || this.data.requestedShipmentId;
      const activeShipment =
        detail.shipmentViews.find((item) => item.shipmentId === preferredId) ||
        detail.shipmentView ||
        null;
      this.setData({
        detail,
        activeShipment,
        loading: false,
        loaded: true,
        trackingView: null,
        trackingErrorText: "",
        deliverySummary: buildOrderDeliverySummary(
          detail,
          activeShipment || undefined
        )
      });
      if (activeShipment) void this.refreshTracking();
    } catch (error) {
      if (requestId !== this.detailRequest || this.disposed) return;
      this.setData({
        loading: false,
        errorText: isApiError(error)
          ? error.message
          : "物流信息加载失败，请重试"
      });
    }
  },

  onRetry() {
    void this.refreshDetail();
  },

  onShipmentTap(event: WechatMiniprogram.TouchEvent) {
    const shipmentId = positiveOrderId(event.currentTarget.dataset.shipmentId);
    const detail = this.data.detail;
    const activeShipment = detail?.shipmentViews.find(
      (item) => item.shipmentId === shipmentId
    );
    if (
      !detail ||
      !activeShipment ||
      activeShipment.shipmentId === this.data.activeShipment?.shipmentId
    )
      return;
    this.setData({
      activeShipment,
      trackingView: null,
      trackingErrorText: "",
      deliverySummary: buildOrderDeliverySummary(detail, activeShipment)
    });
    void this.refreshTracking();
  },

  onRefreshTracking() {
    if (!this.data.trackingLoading) void this.refreshTracking();
  },

  async refreshTracking() {
    const { detail, activeShipment } = this.data;
    if (!detail || !activeShipment || this.disposed) return;
    const requestId = ++this.trackingRequest;
    this.setData({ trackingLoading: true, trackingErrorText: "" });
    try {
      const response = await syncShipmentTracking(
        detail.orderId,
        activeShipment.shipmentId
      );
      if (requestId !== this.trackingRequest || this.disposed) return;
      this.setData({
        trackingView: buildOrderTrackingView(response),
        trackingLoading: false,
        deliverySummary: buildOrderDeliverySummary(
          detail,
          activeShipment,
          response
        )
      });
    } catch {
      if (requestId !== this.trackingRequest || this.disposed) return;
      this.setData({
        trackingLoading: false,
        trackingErrorText: "物流更新失败，请稍后重试"
      });
    }
  },

  onCopyTrackingNoTap() {
    copyTrackingNo(this.data.activeShipment?.trackingNo);
  },

  async onOpenLogisticsTap() {
    const { detail, activeShipment } = this.data;
    if (
      !detail ||
      !activeShipment?.canOpenTracking ||
      this.data.logisticsOpening ||
      this.disposed
    )
      return;
    this.setData({ logisticsOpening: true });
    try {
      const opened = await openOrderLogistics({
        requestWaybillToken: () =>
          getShipmentWaybillToken(detail.orderId, activeShipment.shipmentId)
      });
      if (!opened && !this.disposed)
        wx.showToast({ title: LOGISTICS_UNAVAILABLE_MESSAGE, icon: "none" });
    } finally {
      if (!this.disposed) this.setData({ logisticsOpening: false });
    }
  }
});
