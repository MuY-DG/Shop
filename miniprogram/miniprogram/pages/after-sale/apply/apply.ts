import {
  AFTER_SALE_REASONS, afterSaleItemSelectableQuantity, afterSaleTypeText,
  buildAfterSaleApplyPayload, buildAfterSaleDetailUrl, buildAfterSaleResultUrl,
  createAfterSaleRequestKey, positiveAfterSaleId
} from '../../../features/after-sale'
import { positiveOrderId } from '../../../features/order-center'
import { formatMoney } from '../../../features/product-catalog'
import { applyAfterSale, getAfterSaleEligibility, quoteAfterSale, uploadAfterSaleEvidence } from '../../../services/after-sale'
import type { AfterSaleEligibilityResponse, AfterSaleQuoteResponse, AfterSaleType } from '../../../types/after-sale'
import { isApiError } from '../../../utils/api-error'
import {
  MAX_EVIDENCE_COUNT, defaultAfterSaleType, evidenceValidationError, parseRefundAmount,
  refundAmountInputWidth, selectableItems, updateItemQuantity, type SelectableItem
} from './model'

interface InputEvent { detail: { value: string } }
interface DatasetEvent { currentTarget: { dataset: { index?: number | string } } }
interface AmountInputEvent extends InputEvent, DatasetEvent {}
interface LocalEvidence { tempFilePath: string; size: number; type: 'image' | 'video'; thumbnail: string }
interface SelectedEvidence extends LocalEvidence { fileId: number; originalFilename: string }

function actionError(error: unknown, fallback: string): string {
  return isApiError(error) ? error.message : error instanceof Error ? error.message : fallback
}

function chooseEvidenceMedia(count: number): Promise<LocalEvidence[]> {
  return new Promise((resolve, reject) => {
    wx.chooseMedia({
      count, mediaType: ['mix'], sourceType: ['album', 'camera'],
      sizeType: ['compressed'], maxDuration: 60,
      success: (result) => resolve(result.tempFiles.filter((file) => Boolean(file.tempFilePath)).map((file) => ({
        tempFilePath: file.tempFilePath, size: Number(file.size) || 0,
        type: file.fileType === 'video' || result.type === 'video' ? 'video' : 'image',
        thumbnail: file.thumbTempFilePath || ''
      }))),
      fail: (error) => error.errMsg.includes('cancel') ? resolve([]) : reject(new Error(error.errMsg || '选择凭证失败'))
    })
  })
}

function confirmSubmit(type: AfterSaleType, amountText: string, quantity: number): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title: '确认提交售后申请',
      content: `共 ${quantity} 件商品，申请${afterSaleTypeText(type)} ${amountText}。${type === 'RETURN_REFUND' ? '审核通过后需按指引寄回商品，商家验收后退款。' : '商家审核通过后，款项将原路退回。'}`,
      confirmText: '确认提交', confirmColor: '#B72B22',
      success: (result) => resolve(result.confirm), fail: () => resolve(false)
    })
  })
}

Page({
  data: {
    orderId: 0, requestKey: '', eligibility: null as AfterSaleEligibilityResponse | null,
    items: [] as SelectableItem[], isBatch: false, selectedCount: 0, selectedQuantity: 0, allSelected: false,
    availableTypes: [] as Array<{ value: AfterSaleType; text: string }>,
    selectedType: 'REFUND_ONLY' as AfterSaleType, selectedTypeText: '',
    quote: null as AfterSaleQuoteResponse | null, quoteAmountText: '', quoteError: '',
    selectedReason: '', description: '', evidenceFiles: [] as SelectedEvidence[],
    editingIndex: -1, blockedAfterSaleId: 0, loading: true, loaded: false, errorText: '',
    quoting: false, uploading: false, submitting: false
  },
  _loadRequest: 0,
  _quoteRequest: 0,
  _disposed: false,

  onLoad(query: Record<string, string | undefined>) {
    const orderId = positiveOrderId(query.order_id)
    if (!orderId) { this.setData({ loading: false, errorText: '订单参数无效' }); return }
    this.setData({ orderId, requestKey: createAfterSaleRequestKey(orderId) })
    void this.loadEligibility()
  },
  onUnload() { this._disposed = true; this._loadRequest += 1; this._quoteRequest += 1 },
  onRetry() { void this.loadEligibility() },

  async loadEligibility() {
    const loadId = ++this._loadRequest
    this.setData({ loading: true, errorText: '' })
    try {
      const eligibility = await getAfterSaleEligibility(this.data.orderId)
      if (loadId !== this._loadRequest) return
      const blockedAfterSaleId = positiveAfterSaleId(eligibility.activeAfterSaleId)
      const selectedType = defaultAfterSaleType(eligibility)
      const items = selectableItems(eligibility, selectedType)
      this.setData({
        eligibility, items, isBatch: items.length > 1, blockedAfterSaleId, selectedType,
        selectedTypeText: afterSaleTypeText(selectedType),
        availableTypes: eligibility.availableTypes.map((value) => ({ value, text: afterSaleTypeText(value) })),
        loading: false, loaded: true,
        errorText: blockedAfterSaleId || eligibility.availableTypes.length ? '' : '当前订单暂无可申请售后的商品'
      })
      if (!blockedAfterSaleId && eligibility.availableTypes.length) await this.refreshQuote()
    } catch (error) {
      if (loadId === this._loadRequest) this.setData({ loading: false, loaded: this.data.eligibility !== null,
        errorText: actionError(error, '售后资格加载失败，请稍后重试') })
    }
  },

  normalizeSelectedAmounts() {
    const items: Array<{ orderItemId: number; quantity: number; requestedAmountCent: number }> = []
    let invalid = false
    const next = this.data.items.map((item) => {
      if (!item.selected || item.quantity <= 0) return { ...item, amountError: '' }
      const cents = parseRefundAmount(item.amountText)
      const amountError = cents === null ? '请输入大于 0 的金额，最多两位小数'
        : cents > item.maxAmountCent ? `最多可输入 ${item.maxAmountText}` : ''
      if (amountError) invalid = true
      else items.push({ orderItemId: item.orderItemId, quantity: item.quantity, requestedAmountCent: cents! })
      const amountText = amountError ? item.amountText : formatMoney(cents!)
      return { ...item, amountError, amountText, amountInputWidth: refundAmountInputWidth(amountText) }
    })
    this.setData({ items: next })
    return invalid ? null : items
  },

  updateSelectionSummary() {
    const selected = this.data.items.filter((item) => item.selected && item.quantity > 0)
    this.setData({ selectedCount: selected.length,
      selectedQuantity: selected.reduce((total, item) => total + item.quantity, 0),
      allSelected: selected.length > 0 && selected.length === this.data.items.filter((item) => item.selectableQuantity > 0).length })
  },

  async refreshQuote() {
    const quoteId = ++this._quoteRequest
    this.updateSelectionSummary()
    const items = this.normalizeSelectedAmounts()
    this.setData({ quote: null, quoteAmountText: '', quoteError: '', quoting: false })
    if (!items || !items.length || this.data.blockedAfterSaleId) return
    this.setData({ quoting: true })
    try {
      const quote = await quoteAfterSale(this.data.orderId, { afterSaleType: this.data.selectedType, items })
      if (quoteId !== this._quoteRequest) return
      this.setData({ quote, quoteAmountText: `¥${formatMoney(quote.requestedAmountCent)}` })
    } catch (error) {
      if (quoteId === this._quoteRequest) this.setData({ quoteError: actionError(error, '退款金额核算失败，请重试') })
    } finally {
      if (quoteId === this._quoteRequest) this.setData({ quoting: false })
    }
  },
  onRetryQuote() { if (!this.data.submitting) void this.refreshQuote() },

  onChooseType() {
    if (this.data.submitting || !this.data.availableTypes.length) return
    wx.showActionSheet({
      itemList: this.data.availableTypes.map((type) => type.text),
      success: ({ tapIndex }) => {
        const type = this.data.availableTypes[tapIndex]?.value
        if (type && !this._disposed && !this.data.submitting) this.selectType(type)
      }
    })
  },
  selectType(type: AfterSaleType) {
    if (!this.data.eligibility?.availableTypes.includes(type) || type === this.data.selectedType) return
    const items = this.data.items.map((item) => {
      const selectableQuantity = afterSaleItemSelectableQuantity(item, type)
      const quantity = Math.min(item.quantity || selectableQuantity, selectableQuantity)
      return updateItemQuantity({ ...item, selectableQuantity,
        selected: item.selectionWanted && quantity > 0 }, quantity)
    })
    this.setData({ selectedType: type, selectedTypeText: afterSaleTypeText(type), items, editingIndex: -1 })
    void this.refreshQuote()
  },
  onItemToggle(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index)
    const item = this.data.items[index]
    if (!this.data.isBatch || !item || item.selectableQuantity <= 0 || this.data.submitting) return
    this.setData({ [`items[${index}].selected`]: !item.selected, [`items[${index}].selectionWanted`]: !item.selected })
    void this.refreshQuote()
  },
  onToggleAll() {
    if (this.data.submitting) return
    this.setData({ items: this.data.items.map((item) => item.selectableQuantity > 0
      ? { ...item, selected: !this.data.allSelected, selectionWanted: !this.data.allSelected } : item) })
    void this.refreshQuote()
  },
  applyQuantityChange(index: number, quantity: number) {
    const item = this.data.items[index]
    if (!item || quantity < 1 || quantity > item.selectableQuantity) return
    this.setData({ [`items[${index}]`]: updateItemQuantity(item, quantity) })
    void this.refreshQuote()
  },
  onQuantityMinus(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index), item = this.data.items[index]
    if (!item?.selected || this.data.submitting) return
    this.applyQuantityChange(index, item.quantity - 1)
  },
  onQuantityPlus(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index), item = this.data.items[index]
    if (!item?.selected || this.data.submitting) return
    this.applyQuantityChange(index, item.quantity + 1)
  },
  onEditAmount(event: DatasetEvent) {
    if (!this.data.submitting) this.setData({ editingIndex: Number(event.currentTarget.dataset.index) })
  },
  onItemAmountInput(event: AmountInputEvent) {
    const index = Number(event.currentTarget.dataset.index)
    if (!this.data.items[index]?.selected || this.data.submitting) return
    ++this._quoteRequest
    this.setData({ [`items[${index}].amountText`]: event.detail.value, [`items[${index}].amountError`]: '',
      [`items[${index}].amountInputWidth`]: refundAmountInputWidth(event.detail.value),
      quote: null, quoteAmountText: '', quoteError: '', quoting: false })
  },
  onItemAmountBlur() {
    this.setData({ editingIndex: -1 })
    if (!this.data.submitting) void this.refreshQuote()
  },
  onChooseReason() {
    if (this.data.submitting) return
    wx.showActionSheet({ itemList: [...AFTER_SALE_REASONS], success: ({ tapIndex }) => {
      const selectedReason = AFTER_SALE_REASONS[tapIndex]
      if (selectedReason && !this._disposed && !this.data.submitting) this.setData({ selectedReason })
    } })
  },
  onDescriptionInput(event: InputEvent) { if (!this.data.submitting) this.setData({ description: event.detail.value }) },

  async onChooseEvidenceTap() {
    if (this.data.uploading || this.data.submitting || this.data.blockedAfterSaleId) return
    const remaining = MAX_EVIDENCE_COUNT - this.data.evidenceFiles.length
    if (remaining <= 0) return
    this.setData({ uploading: true })
    try {
      const selected = (await chooseEvidenceMedia(remaining)).slice(0, remaining)
      if (this._disposed) return
      let validationError = ''
      const accepted = selected.filter((file) => {
        const error = evidenceValidationError(file.type, file.size)
        if (error) validationError = error
        return !error
      })
      if (validationError) wx.showToast({ title: validationError, icon: 'none' })
      for (const file of accepted) {
        const uploaded = await uploadAfterSaleEvidence(this.data.orderId, file.tempFilePath, file.type)
        if (this._disposed) return
        this.setData({ evidenceFiles: [...this.data.evidenceFiles, { ...file,
          fileId: uploaded.id, originalFilename: uploaded.originalFilename }] })
      }
    } catch (error) {
      if (!this._disposed) wx.showToast({ title: actionError(error, '凭证上传失败，请稍后重试'), icon: 'none' })
    } finally {
      if (!this._disposed) this.setData({ uploading: false })
    }
  },
  onPreviewEvidenceTap(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index)
    if (!this.data.evidenceFiles[index]) return
    wx.previewMedia({ current: index, sources: this.data.evidenceFiles.map((file) => ({
      url: file.tempFilePath, type: file.type, ...(file.thumbnail ? { poster: file.thumbnail } : {})
    })), fail: () => wx.showToast({ title: '凭证预览失败，请重试', icon: 'none' }) })
  },
  onRemoveEvidenceTap(event: DatasetEvent) {
    if (this.data.uploading || this.data.submitting) return
    const index = Number(event.currentTarget.dataset.index)
    if (!Number.isSafeInteger(index) || index < 0 || index >= this.data.evidenceFiles.length) return
    this.setData({ evidenceFiles: this.data.evidenceFiles.filter((_, position) => position !== index) })
  },
  onBlockedAfterSaleTap() {
    if (this.data.blockedAfterSaleId) wx.redirectTo({ url: buildAfterSaleDetailUrl(this.data.blockedAfterSaleId) })
  },

  async onSubmitTap() {
    if (this.data.quoting || this.data.uploading || this.data.submitting) return
    if (!this.data.selectedReason) { wx.showToast({ title: '请选择退款原因', icon: 'none' }); return }
    if (!this.data.quote) { await this.refreshQuote(); return }
    const normalized = this.normalizeSelectedAmounts()
    if (!normalized) return
    let payload
    try {
      payload = buildAfterSaleApplyPayload({ requestKey: this.data.requestKey, quote: this.data.quote,
        items: normalized, reason: this.data.selectedReason, description: this.data.description,
        evidenceFileIds: this.data.evidenceFiles.map((file) => file.fileId) })
      if (payload.afterSaleType !== this.data.selectedType) throw new Error('售后类型已变化，请重新确认')
    } catch (error) {
      wx.showToast({ title: actionError(error, '申请内容不完整'), icon: 'none' })
      await this.refreshQuote()
      return
    }
    this.setData({ submitting: true })
    if (!await confirmSubmit(this.data.selectedType, this.data.quoteAmountText, this.data.selectedQuantity)) {
      if (!this._disposed) this.setData({ submitting: false })
      return
    }
    if (this._disposed) return
    try {
      const result = await applyAfterSale(this.data.orderId, payload)
      if (this._disposed) return
      wx.redirectTo({ url: buildAfterSaleResultUrl(result.id), fail: () => this.setData({ submitting: false }) })
    } catch (error) {
      if (this._disposed) return
      this.setData({ submitting: false })
      wx.showToast({ title: actionError(error, '申请提交失败，请稍后重试'), icon: 'none' })
      await this.refreshQuote()
    }
  }
})
