import {
  AFTER_SALE_REASONS,
  afterSaleItemRefundCeilingCent,
  afterSaleItemSelectableQuantity,
  afterSaleTypeText,
  buildAfterSaleApplyPayload,
  buildAfterSaleDetailUrl,
  createAfterSaleRequestKey,
  positiveAfterSaleId
} from '../../../features/after-sale'
import { positiveOrderId } from '../../../features/order-center'
import { formatMoney } from '../../../features/product-catalog'
import {
  applyAfterSale,
  getAfterSaleEligibility,
  quoteAfterSale,
  uploadAfterSaleEvidence
} from '../../../services/after-sale'
import type {
  AfterSaleEligibilityItem,
  AfterSaleEligibilityResponse,
  AfterSaleQuoteResponse,
  AfterSaleType
} from '../../../types/after-sale'
import { isApiError } from '../../../utils/api-error'

interface InputEvent { detail: { value: string } }
interface DatasetEvent {
  currentTarget: { dataset: { index?: number | string; reason?: string; type?: string } }
}
interface AmountInputEvent {
  detail: { value: string }
  currentTarget: { dataset: { index?: number | string } }
}
interface SelectedEvidence { fileId: number; tempFilePath: string; originalFilename: string; sizeBytes: number }
interface LocalImage { tempFilePath: string; size: number }
interface SelectableItem extends AfterSaleEligibilityItem {
  selectableQuantity: number
  selected: boolean
  quantity: number
  maxAmountCent: number
  maxAmountText: string
  amountText: string
}

const MAX_EVIDENCE_COUNT = 3
const MAX_EVIDENCE_SIZE = 5 * 1024 * 1024
let latestLoadRequest = 0
let latestQuoteRequest = 0

function actionError(error: unknown, fallback: string): string {
  return isApiError(error) ? error.message : error instanceof Error ? error.message : fallback
}

function parseAmountCentText(text: string): number {
  const value = Number.parseFloat(String(text).replace(/[^\d.]/g, ''))
  if (!Number.isFinite(value) || value <= 0) return 0
  return Math.round(value * 100)
}

function chooseEvidenceImages(count: number): Promise<LocalImage[]> {
  return new Promise((resolve, reject) => {
    wx.chooseMedia({
      count,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      sizeType: ['compressed'],
      success: (result) => resolve(result.tempFiles
        .map((file) => ({ tempFilePath: file.tempFilePath, size: Number(file.size) || 0 }))
        .filter((file) => Boolean(file.tempFilePath))),
      fail: (error) => error.errMsg.includes('cancel')
        ? resolve([])
        : reject(new Error(error.errMsg || '选择图片失败'))
    })
  })
}

function confirmSubmit(type: AfterSaleType, amountText: string): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title: '确认提交售后申请',
      content: `${afterSaleTypeText(type)}的退款金额为 ${amountText}，商家审核通过后原路退回。`,
      confirmText: '确认提交',
      confirmColor: '#B72B22',
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    })
  })
}

Page({
  data: {
    orderId: 0,
    requestKey: '',
    eligibility: null as AfterSaleEligibilityResponse | null,
    items: [] as SelectableItem[],
    availableTypes: [] as Array<{ value: AfterSaleType; text: string }>,
    selectedType: 'REFUND_ONLY' as AfterSaleType,
    quote: null as AfterSaleQuoteResponse | null,
    quoteAmountText: '',
    reasons: AFTER_SALE_REASONS,
    selectedReason: AFTER_SALE_REASONS[0],
    description: '',
    evidenceFiles: [] as SelectedEvidence[],
    blockedAfterSaleId: 0,
    loading: true,
    loaded: false,
    errorText: '',
    quoting: false,
    uploading: false,
    submitting: false
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderId = positiveOrderId(query.order_id)
    if (!orderId) {
      this.setData({ loading: false, errorText: '订单参数无效' })
      return
    }
    this.setData({ orderId, requestKey: createAfterSaleRequestKey(orderId) })
    void this.loadEligibility()
  },

  onUnload() {
    latestLoadRequest += 1
    latestQuoteRequest += 1
  },

  onRetry() { void this.loadEligibility() },

  async loadEligibility() {
    const loadId = ++latestLoadRequest
    this.setData({ loading: true, errorText: '' })
    try {
      const eligibility = await getAfterSaleEligibility(this.data.orderId)
      if (loadId !== latestLoadRequest) return
      const blockedAfterSaleId = positiveAfterSaleId(eligibility.activeAfterSaleId)
      const selectedType = eligibility.availableTypes[0] || 'REFUND_ONLY'
      const items = eligibility.items.map((item) => {
        const selectableQuantity = afterSaleItemSelectableQuantity(item, selectedType)
        const maxAmountCent = afterSaleItemRefundCeilingCent(
          item.paidAmountBasisCent, item.purchasedQuantity, item.refundedQuantity, selectableQuantity
        )
        return {
          ...item,
          selectableQuantity,
          selected: selectableQuantity > 0,
          quantity: selectableQuantity,
          maxAmountCent,
          maxAmountText: `¥${formatMoney(maxAmountCent)}`,
          amountText: formatMoney(maxAmountCent)
        }
      })
      this.setData({
        eligibility,
        items,
        blockedAfterSaleId,
        selectedType,
        availableTypes: eligibility.availableTypes.map((value) => ({
          value,
          text: afterSaleTypeText(value)
        })),
        loading: false,
        loaded: true,
        errorText: blockedAfterSaleId
          ? ''
          : eligibility.availableTypes.length
            ? ''
            : '当前订单暂无可申请售后的商品'
      })
      if (!blockedAfterSaleId && eligibility.availableTypes.length) await this.refreshQuote()
    } catch (error) {
      if (loadId === latestLoadRequest) {
        this.setData({
          loading: false,
          loaded: this.data.eligibility !== null,
          errorText: actionError(error, '售后资格加载失败，请稍后重试')
        })
      }
    }
  },

  /** 读取输入框文本，归一化每件商品的申报金额（非法回退上限，超限截断） */
  normalizeSelectedAmounts(): Array<{ orderItemId: number; quantity: number; requestedAmountCent: number }> {
    const updates: Record<string, string> = {}
    let clamped = false
    const items = this.data.items
      .map((item, index) => ({ item, index }))
      .filter(({ item }) => item.selected && item.quantity > 0)
      .map(({ item, index }) => {
        const raw = parseAmountCentText(item.amountText)
        let cents = raw > 0 ? raw : item.maxAmountCent
        if (cents > item.maxAmountCent) {
          cents = item.maxAmountCent
          clamped = true
        }
        if (raw !== cents) updates[`items[${index}].amountText`] = formatMoney(cents)
        return { orderItemId: item.orderItemId, quantity: item.quantity, requestedAmountCent: cents }
      })
    if (Object.keys(updates).length) this.setData(updates)
    if (clamped) wx.showToast({ title: '退款金额不能超过可退上限，已调整', icon: 'none' })
    return items
  },

  async refreshQuote() {
    const quoteId = ++latestQuoteRequest
    const items = this.normalizeSelectedAmounts()
    if (!items.length) {
      this.setData({ quote: null, quoteAmountText: '', quoting: false })
      return
    }
    this.setData({ quoting: true, quote: null, quoteAmountText: '' })
    try {
      const quote = await quoteAfterSale(this.data.orderId, {
        afterSaleType: this.data.selectedType,
        items
      })
      if (quoteId !== latestQuoteRequest) return
      this.setData({ quote, quoteAmountText: `¥${formatMoney(quote.requestedAmountCent)}` })
    } catch (error) {
      if (quoteId === latestQuoteRequest) {
        wx.showToast({ title: actionError(error, '退款报价失败'), icon: 'none' })
      }
    } finally {
      if (quoteId === latestQuoteRequest) this.setData({ quoting: false })
    }
  },

  onTypeTap(event: DatasetEvent) {
    const type = String(event.currentTarget.dataset.type || '') as AfterSaleType
    if (!this.data.eligibility?.availableTypes.includes(type) || this.data.submitting) return
    const items = this.data.items.map((item) => {
      const selectableQuantity = afterSaleItemSelectableQuantity(item, type)
      const quantity = Math.min(item.quantity || selectableQuantity, selectableQuantity)
      const maxAmountCent = afterSaleItemRefundCeilingCent(
        item.paidAmountBasisCent, item.purchasedQuantity, item.refundedQuantity, quantity
      )
      return {
        ...item,
        selectableQuantity,
        quantity,
        selected: item.selected && quantity > 0,
        maxAmountCent,
        maxAmountText: `¥${formatMoney(maxAmountCent)}`,
        amountText: formatMoney(maxAmountCent)
      }
    })
    this.setData({ selectedType: type, items }, () => void this.refreshQuote())
  },

  onItemToggle(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index)
    const item = this.data.items[index]
    if (!item || item.selectableQuantity <= 0 || this.data.submitting) return
    this.setData(
      { [`items[${index}].selected`]: !item.selected },
      () => void this.refreshQuote()
    )
  },

  applyQuantityChange(index: number, quantity: number) {
    const item = this.data.items[index]
    if (!item) return
    const maxAmountCent = afterSaleItemRefundCeilingCent(
      item.paidAmountBasisCent, item.purchasedQuantity, item.refundedQuantity, quantity
    )
    this.setData({
      [`items[${index}].quantity`]: quantity,
      [`items[${index}].maxAmountCent`]: maxAmountCent,
      [`items[${index}].maxAmountText`]: `¥${formatMoney(maxAmountCent)}`,
      [`items[${index}].amountText`]: formatMoney(maxAmountCent)
    }, () => void this.refreshQuote())
  },

  onQuantityMinus(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index)
    const item = this.data.items[index]
    if (!item || !item.selected || item.quantity <= 1 || this.data.submitting) return
    this.applyQuantityChange(index, item.quantity - 1)
  },

  onQuantityPlus(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index)
    const item = this.data.items[index]
    if (!item || !item.selected || item.quantity >= item.selectableQuantity || this.data.submitting) return
    this.applyQuantityChange(index, item.quantity + 1)
  },

  onItemAmountInput(event: AmountInputEvent) {
    const index = Number(event.currentTarget.dataset.index)
    const item = this.data.items[index]
    if (!item || !item.selected || this.data.submitting) return
    this.setData({ [`items[${index}].amountText`]: event.detail.value })
  },

  onItemAmountBlur() {
    if (!this.data.submitting) void this.refreshQuote()
  },

  onReasonTap(event: DatasetEvent) {
    const reason = String(event.currentTarget.dataset.reason || '')
    if (AFTER_SALE_REASONS.includes(reason) && !this.data.submitting) this.setData({ selectedReason: reason })
  },

  onDescriptionInput(event: InputEvent) { this.setData({ description: event.detail.value }) },

  async onChooseEvidenceTap() {
    if (this.data.uploading || this.data.submitting || this.data.blockedAfterSaleId) return
    const remaining = MAX_EVIDENCE_COUNT - this.data.evidenceFiles.length
    if (remaining <= 0) return
    try {
      const selected = await chooseEvidenceImages(remaining)
      const accepted = selected.filter((file) => file.size > 0 && file.size <= MAX_EVIDENCE_SIZE)
      if (accepted.length < selected.length) wx.showToast({ title: '单张图片不能超过 5MB', icon: 'none' })
      if (!accepted.length) return
      this.setData({ uploading: true })
      const evidenceFiles = this.data.evidenceFiles.slice()
      for (const file of accepted) {
        const uploaded = await uploadAfterSaleEvidence(this.data.orderId, file.tempFilePath)
        evidenceFiles.push({
          fileId: uploaded.id,
          tempFilePath: file.tempFilePath,
          originalFilename: uploaded.originalFilename,
          sizeBytes: uploaded.sizeBytes
        })
        this.setData({ evidenceFiles })
      }
    } catch (error) {
      wx.showToast({ title: actionError(error, '凭证上传失败，请稍后重试'), icon: 'none' })
    } finally {
      this.setData({ uploading: false })
    }
  },

  onPreviewEvidenceTap(event: DatasetEvent) {
    const current = this.data.evidenceFiles[Number(event.currentTarget.dataset.index)]?.tempFilePath
    if (current) wx.previewImage({ current, urls: this.data.evidenceFiles.map((file) => file.tempFilePath) })
  },

  onRemoveEvidenceTap(event: DatasetEvent) {
    if (this.data.uploading || this.data.submitting) return
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isSafeInteger(index) || index < 0 || index >= this.data.evidenceFiles.length) return
    const evidenceFiles = this.data.evidenceFiles.slice()
    evidenceFiles.splice(index, 1)
    this.setData({ evidenceFiles })
  },

  onBlockedAfterSaleTap() {
    if (this.data.blockedAfterSaleId) wx.redirectTo({ url: buildAfterSaleDetailUrl(this.data.blockedAfterSaleId) })
  },

  async onSubmitTap() {
    if (!this.data.quote || this.data.quoting || this.data.uploading || this.data.submitting) return
    const normalized = this.normalizeSelectedAmounts()
    const quotedAmounts = new Map(
      this.data.quote.items.map((item) => [item.orderItemId, item.requestedAmountCent])
    )
    const amountsDrifted = normalized.length !== this.data.quote.items.length
      || normalized.some((item) => quotedAmounts.get(item.orderItemId) !== item.requestedAmountCent)
    if (amountsDrifted) {
      wx.showToast({ title: '退款金额已变化，请重新确认', icon: 'none' })
      await this.refreshQuote()
      return
    }
    this.setData({ submitting: true })
    if (!await confirmSubmit(this.data.selectedType, this.data.quoteAmountText)) {
      this.setData({ submitting: false })
      return
    }
    let payload
    try {
      payload = buildAfterSaleApplyPayload({
        requestKey: this.data.requestKey,
        quote: this.data.quote,
        items: normalized,
        reason: this.data.selectedReason,
        description: this.data.description,
        evidenceFileIds: this.data.evidenceFiles.map((file) => file.fileId)
      })
    } catch (error) {
      this.setData({ submitting: false })
      wx.showToast({ title: actionError(error, '申请内容不完整'), icon: 'none' })
      return
    }
    try {
      const result = await applyAfterSale(this.data.orderId, payload)
      wx.showToast({ title: '申请已提交', icon: 'success' })
      wx.redirectTo({
        url: buildAfterSaleDetailUrl(result.id),
        fail: () => this.setData({ submitting: false })
      })
    } catch (error) {
      this.setData({ submitting: false })
      wx.showToast({ title: actionError(error, '申请提交失败，请稍后重试'), icon: 'none' })
      await this.refreshQuote()
    }
  }
})
