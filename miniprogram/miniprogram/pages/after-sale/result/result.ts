import {
  buildAfterSaleView, buildAfterSaleDetailUrl, positiveAfterSaleId,
  shouldPollAfterSale, type AfterSaleView
} from '../../../features/after-sale'
import { buildOrderDetailUrl } from '../../../features/order-center'
import { getAfterSaleDetail } from '../../../services/after-sale'

Page({
  _request: 0,
  _visible: false,
  _pollCount: 0,
  _pollTimer: null as ReturnType<typeof setTimeout> | null,
  data: {
    afterSaleId: 0,
    detail: null as AfterSaleView | null,
    loading: true,
    errorText: '',
    phase: 'processing',
    processingTitle: '正在查询退款进度'
  },
  onLoad(query: Record<string, string | undefined>) {
    this._visible = true
    this.setData({ afterSaleId: positiveAfterSaleId(query.after_sale_id) })
    void this.loadResult()
  },
  onShow() {
    this._visible = true
    if (this.data.afterSaleId && !this.data.loading) void this.loadResult()
  },
  onHide() { this.stopPolling() },
  onUnload() { this.stopPolling() },
  stopPolling() {
    this._visible = false
    this._request += 1
    if (this._pollTimer) clearTimeout(this._pollTimer)
    this._pollTimer = null
    this.setData({ loading: false })
  },
  scheduleRefresh() {
    if (this._pollTimer) clearTimeout(this._pollTimer)
    this._pollTimer = null
    if (!this._visible || !this.data.detail || !shouldPollAfterSale(this.data.detail.status)) return
    if (this.data.detail.status === 'REQUESTED' && !this.data.detail.automaticReviewPending) return
    this._pollTimer = setTimeout(() => {
      this._pollTimer = null
      void this.loadResult()
    }, this._pollCount++ < 20 ? 3000 : 10000)
  },
  onRetry() { void this.loadResult() },
  async loadResult() {
    if (!this.data.afterSaleId) {
      this.setData({ loading: false, errorText: '售后参数无效' })
      return
    }
    if (this._pollTimer) clearTimeout(this._pollTimer)
    this._pollTimer = null
    const request = ++this._request
    this.setData({ loading: true })
    try {
      const detail = buildAfterSaleView(await getAfterSaleDetail(this.data.afterSaleId))
      if (request !== this._request || !this._visible) return
      const awaitingReview = detail.status === 'REQUESTED' && !detail.automaticReviewPending
      const processing = !awaitingReview && ['REQUESTED', 'APPROVED', 'REFUNDING'].includes(detail.status)
      this.setData({
        detail,
        loading: false,
        errorText: '',
        phase: detail.status === 'REFUNDED' ? 'success' : awaitingReview ? 'review' : processing ? 'processing' : 'attention',
        processingTitle: detail.status === 'REQUESTED' ? '正在审核' : '退款处理中'
      })
      // Return shipments require the address/form available on the detail page.
      if (['WAITING_RETURN', 'RETURNING', 'WAITING_INSPECTION'].includes(detail.status)) {
        wx.redirectTo({
          url: buildAfterSaleDetailUrl(detail.id),
          fail: () => {
            if (request === this._request && this._visible) {
              this.setData({ errorText: '请点击退款详情，查看退货进度' })
              this.scheduleRefresh()
            }
          }
        })
        return
      }
      this.scheduleRefresh()
    } catch {
      if (request === this._request && this._visible) {
        this.setData({ loading: false, errorText: '退款进度暂时无法更新，点击重试' })
        this.scheduleRefresh()
      }
    }
  },
  onDetailTap() {
    if (this.data.detail) wx.redirectTo({ url: buildAfterSaleDetailUrl(this.data.detail.id) })
  },
  onOrderTap() {
    if (this.data.detail) wx.redirectTo({ url: buildOrderDetailUrl(this.data.detail.orderId) })
  }
})
