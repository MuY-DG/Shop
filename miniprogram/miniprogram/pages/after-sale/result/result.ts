import { buildAfterSaleView, buildAfterSaleDetailUrl, positiveAfterSaleId, type AfterSaleView } from '../../../features/after-sale'
import { buildOrderDetailUrl } from '../../../features/order-center'
import { getAfterSaleDetail } from '../../../services/after-sale'

Page({
  _request: 0,
  data: { afterSaleId: 0, detail: null as AfterSaleView | null, loading: true, errorText: '' },
  onLoad(query: Record<string, string | undefined>) {
    this.setData({ afterSaleId: positiveAfterSaleId(query.after_sale_id) })
    void this.loadResult()
  },
  onUnload() { this._request += 1 },
  onRetry() { void this.loadResult() },
  async loadResult() {
    if (!this.data.afterSaleId) {
      this.setData({ loading: false, errorText: '售后参数无效' })
      return
    }
    const request = ++this._request
    this.setData({ loading: true, errorText: '' })
    try {
      const detail = buildAfterSaleView(await getAfterSaleDetail(this.data.afterSaleId))
      if (request !== this._request) return
      // The result page always verifies success against the server.
      if (detail.status !== 'REFUNDED') {
        wx.redirectTo({
          url: `${buildAfterSaleDetailUrl(detail.id)}&follow_refund=1`,
          fail: () => this.setData({ loading: false, errorText: '退款仍在处理中，点击重新查看进度' })
        })
        return
      }
      this.setData({ detail, loading: false })
    } catch {
      if (request === this._request) this.setData({ loading: false, errorText: '退款结果加载失败，点击重试' })
    }
  },
  onDetailTap() {
    if (this.data.detail) wx.redirectTo({ url: buildAfterSaleDetailUrl(this.data.detail.id) })
  },
  onOrderTap() {
    if (this.data.detail) wx.redirectTo({ url: buildOrderDetailUrl(this.data.detail.orderId) })
  }
})
