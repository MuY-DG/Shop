import {
  buildAfterSaleView,
  positiveAfterSaleId,
  type AfterSaleView
} from "../../../features/after-sale";
import { buildOrderDetailUrl } from "../../../features/order-center";
import { getAfterSaleDetail } from "../../../services/after-sale";
import { isApiError } from "../../../utils/api-error";

let latestDetailRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

Page({
  data: {
    afterSaleId: 0,
    detail: null as AfterSaleView | null,
    loading: true,
    loaded: false,
    errorText: ""
  },

  onLoad(query: Record<string, string | undefined>) {
    const afterSaleId = positiveAfterSaleId(query.after_sale_id);
    if (!afterSaleId) {
      this.setData({ loading: false, errorText: "售后参数无效" });
      return;
    }
    this.setData({ afterSaleId });
    void this.loadDetail();
  },

  onShow() {
    if (this.data.loaded && !this.data.loading) {
      void this.loadDetail();
    }
  },

  onUnload() {
    latestDetailRequest += 1;
  },

  async onPullDownRefresh() {
    await this.loadDetail();
    wx.stopPullDownRefresh();
  },

  onRetry() {
    void this.loadDetail();
  },

  async loadDetail() {
    if (!this.data.afterSaleId) {
      return;
    }
    const requestId = ++latestDetailRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const detail = buildAfterSaleView(await getAfterSaleDetail(this.data.afterSaleId));
      if (requestId !== latestDetailRequest) {
        return;
      }
      this.setData({
        detail,
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestDetailRequest) {
        this.setData({
          loading: false,
          loaded: this.data.detail !== null,
          errorText: actionError(error, "售后详情加载失败，请稍后重试")
        });
      }
    }
  },

  onOrderTap() {
    const orderId = this.data.detail?.orderId;
    if (orderId) {
      wx.navigateTo({ url: buildOrderDetailUrl(orderId) });
    }
  }
});
