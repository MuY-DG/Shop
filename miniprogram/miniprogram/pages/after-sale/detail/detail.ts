import {
  buildAfterSaleView,
  positiveAfterSaleId,
  type AfterSaleView
} from '../../../features/after-sale'
import { buildOrderDetailUrl } from '../../../features/order-center'
import { formatMoney } from '../../../features/product-catalog'
import {
  cancelAfterSale,
  getAfterSaleDetail,
  submitReturnShipment
} from '../../../services/after-sale'
import { isApiError } from '../../../utils/api-error'

interface PickerEvent { detail: { value: string | number } }
interface InputEvent { detail: { value: string } }
interface DisplayItem {
  id: number
  productTitle: string
  specText?: string
  image?: string
  requestedQuantity: number
  approvedQuantityText: string
  requestedAmountText: string
  approvedAmountText: string
  restockQuantityText: string
}

const SHIPMENT_COMPANIES = Object.freeze([
  { code: 'SF', name: '顺丰速运' },
  { code: 'ZTO', name: '中通快递' },
  { code: 'YTO', name: '圆通速递' },
  { code: 'STO', name: '申通快递' },
  { code: 'YUNDA', name: '韵达快递' },
  { code: 'JD', name: '京东物流' },
  { code: 'EMS', name: '中国邮政 EMS' }
])

let latestDetailRequest = 0

function actionError(error: unknown, fallback: string): string {
  return isApiError(error) ? error.message : error instanceof Error ? error.message : fallback
}

function confirmAction(title: string, content: string, confirmText: string): Promise<boolean> {
  return new Promise((resolve) => wx.showModal({
    title,
    content,
    confirmText,
    confirmColor: '#B72B22',
    success: (result) => resolve(result.confirm),
    fail: () => resolve(false)
  }))
}

function displayItems(detail: AfterSaleView): DisplayItem[] {
  return detail.items.map((item) => ({
    id: item.id,
    productTitle: item.productTitle,
    specText: item.specText,
    image: item.image,
    requestedQuantity: item.requestedQuantity,
    approvedQuantityText: item.approvedQuantity == null ? '待审核' : `${item.approvedQuantity} 件`,
    requestedAmountText: `¥${formatMoney(item.requestedAmountCent)}`,
    approvedAmountText: item.approvedAmountCent == null ? '' : `¥${formatMoney(item.approvedAmountCent)}`,
    restockQuantityText: item.restockQuantity == null ? '' : `${item.restockQuantity} 件`
  }))
}

Page({
  data: {
    afterSaleId: 0,
    detail: null as AfterSaleView | null,
    displayItems: [] as DisplayItem[],
    shipmentCompanies: SHIPMENT_COMPANIES,
    shipmentCompanyNames: SHIPMENT_COMPANIES.map((item) => item.name),
    shipmentCompanyIndex: 0,
    trackingNo: '',
    loading: true,
    loaded: false,
    operating: false,
    errorText: ''
  },

  onLoad(query: Record<string, string | undefined>) {
    const afterSaleId = positiveAfterSaleId(query.after_sale_id)
    if (!afterSaleId) {
      this.setData({ loading: false, errorText: '售后参数无效' })
      return
    }
    this.setData({ afterSaleId })
    void this.loadDetail()
  },

  onShow() {
    if (this.data.loaded && !this.data.loading) void this.loadDetail()
  },

  onUnload() { latestDetailRequest += 1 },

  async onPullDownRefresh() {
    await this.loadDetail()
    wx.stopPullDownRefresh()
  },

  onRetry() { void this.loadDetail() },

  async loadDetail() {
    if (!this.data.afterSaleId) return
    const requestId = ++latestDetailRequest
    this.setData({ loading: true, errorText: '' })
    try {
      const detail = buildAfterSaleView(await getAfterSaleDetail(this.data.afterSaleId))
      if (requestId !== latestDetailRequest) return
      const companyIndex = Math.max(0, SHIPMENT_COMPANIES.findIndex((company) =>
        company.code === detail.returnInfo?.deliveryCompanyCode
      ))
      this.setData({
        detail,
        displayItems: displayItems(detail),
        shipmentCompanyIndex: companyIndex,
        trackingNo: detail.returnInfo?.trackingNo || '',
        loading: false,
        loaded: true,
        errorText: ''
      })
    } catch (error) {
      if (requestId === latestDetailRequest) {
        this.setData({
          loading: false,
          loaded: this.data.detail !== null,
          errorText: actionError(error, '售后详情加载失败，请稍后重试')
        })
      }
    }
  },

  onOrderTap() {
    const orderId = this.data.detail?.orderId
    if (orderId) wx.navigateTo({ url: buildOrderDetailUrl(orderId) })
  },

  onShipmentCompanyChange(event: PickerEvent) {
    this.setData({ shipmentCompanyIndex: Number(event.detail.value) || 0 })
  },

  onTrackingNoInput(event: InputEvent) { this.setData({ trackingNo: event.detail.value }) },

  async onCancelTap() {
    const detail = this.data.detail
    if (!detail?.canCancel || this.data.operating) return
    if (!await confirmAction('取消售后申请', '取消后本次售后将终止，是否继续？', '确认取消')) return
    this.setData({ operating: true })
    try {
      const next = buildAfterSaleView(await cancelAfterSale(detail.id))
      this.setData({ detail: next, displayItems: displayItems(next) })
      wx.showToast({ title: '售后已取消', icon: 'success' })
    } catch (error) {
      wx.showToast({ title: actionError(error, '取消失败'), icon: 'none' })
    } finally {
      this.setData({ operating: false })
    }
  },

  async onSubmitShipmentTap() {
    const detail = this.data.detail
    if (!detail || (!detail.canSubmitReturnShipment && !detail.canUpdateReturnShipment) || this.data.operating) return
    const company = SHIPMENT_COMPANIES[this.data.shipmentCompanyIndex]
    const trackingNo = this.data.trackingNo.trim()
    if (!company || !trackingNo) {
      wx.showToast({ title: '请选择快递公司并填写物流单号', icon: 'none' })
      return
    }
    if (!await confirmAction(
      detail.canUpdateReturnShipment ? '修正退货物流' : '提交退货物流',
      `${company.name} ${trackingNo}，请确认单号无误。`,
      '确认提交'
    )) return
    this.setData({ operating: true })
    try {
      const next = buildAfterSaleView(await submitReturnShipment(detail.id, {
        deliveryCompanyCode: company.code,
        deliveryCompanyName: company.name,
        trackingNo
      }))
      this.setData({ detail: next, displayItems: displayItems(next) })
      wx.showToast({ title: '退货物流已提交', icon: 'success' })
    } catch (error) {
      wx.showToast({ title: actionError(error, '物流提交失败'), icon: 'none' })
    } finally {
      this.setData({ operating: false })
    }
  }
})
