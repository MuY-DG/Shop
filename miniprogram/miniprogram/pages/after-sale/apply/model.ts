import { afterSaleItemRefundCeilingCent, afterSaleItemSelectableQuantity } from '../../../features/after-sale'
import { displaySpecText, formatMoney } from '../../../features/product-catalog'
import type { AfterSaleEligibilityItem, AfterSaleEligibilityResponse, AfterSaleType } from '../../../types/after-sale'

export const MAX_EVIDENCE_COUNT = 4
export const MAX_IMAGE_SIZE = 5 * 1024 * 1024
export const MAX_VIDEO_SIZE = 50 * 1024 * 1024

export interface SelectableItem extends AfterSaleEligibilityItem {
  selectableQuantity: number
  selected: boolean
  selectionWanted: boolean
  quantity: number
  maxAmountCent: number
  maxAmountText: string
  amountText: string
  amountInputWidth: number
  amountError: string
}

export function defaultAfterSaleType(eligibility: AfterSaleEligibilityResponse): AfterSaleType {
  const available = eligibility.items.filter((item) => item.availableQuantity > 0)
  // 部分发货时优先仅退款，避免默认排除尚未发出的商品；全部发出后优先退货退款。
  const allShipped = available.length > 0
    && available.every((item) => item.returnableQuantity >= item.availableQuantity)
  const preferred = allShipped ? 'RETURN_REFUND' : 'REFUND_ONLY'
  return eligibility.availableTypes.includes(preferred)
    ? preferred : eligibility.availableTypes[0] || 'REFUND_ONLY'
}

export function updateItemQuantity(item: SelectableItem, quantity: number): SelectableItem {
  const maxAmountCent = afterSaleItemRefundCeilingCent(
    item.paidAmountBasisCent, item.purchasedQuantity, item.refundedQuantity, quantity
  )
  return { ...item, quantity, maxAmountCent, maxAmountText: `¥${formatMoney(maxAmountCent)}`,
    amountText: formatMoney(maxAmountCent), amountInputWidth: refundAmountInputWidth(formatMoney(maxAmountCent)), amountError: '' }
}

export function refundAmountInputWidth(text: string): number {
  return Math.min(220, Math.max(70, text.length * 20 + 4))
}

export function selectableItems(eligibility: AfterSaleEligibilityResponse, type: AfterSaleType): SelectableItem[] {
  return eligibility.items.map((item) => {
    const selectableQuantity = afterSaleItemSelectableQuantity(item, type)
    return updateItemQuantity({ ...item, specText: displaySpecText(item.specText), selectableQuantity,
      selected: selectableQuantity > 0, selectionWanted: true, quantity: 0, maxAmountCent: 0, maxAmountText: '',
      amountText: '', amountInputWidth: 70, amountError: '' }, selectableQuantity)
  })
}

/** 金额严格按分解析，不能把空值、零或非法文本静默变成全额退款。 */
export function parseRefundAmount(text: string): number | null {
  const value = text.trim()
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) return null
  const [yuan, fraction = ''] = value.split('.')
  const cents = Number(yuan) * 100 + Number(fraction.padEnd(2, '0'))
  return Number.isSafeInteger(cents) && cents > 0 ? cents : null
}

export function evidenceValidationError(type: 'image' | 'video', size: number): string {
  if (!Number.isSafeInteger(size) || size <= 0) return '文件为空或已失效，请重新选择'
  if (type === 'image' && size > MAX_IMAGE_SIZE) return '单张图片不能超过 5MB'
  if (type === 'video' && size > MAX_VIDEO_SIZE) return '单个视频不能超过 50MB'
  return ''
}
