import type {
  AfterSaleAction,
  AfterSaleApplyRequest,
  AfterSaleItemRequest,
  AfterSaleQuoteResponse,
  AfterSaleResponse,
  AfterSaleStatus,
  AfterSaleType
} from '../types/after-sale'
import type { OrderStatus } from '../types/order'
import { formatMoney } from './product-catalog'
import { formatLocalDateTime } from '../utils/date-time'

export const AFTER_SALE_REASONS = Object.freeze([
  '不想要了',
  '商品存在问题',
  '发货或物流问题',
  '收到的商品与描述不符',
  '其他原因'
])

export type AfterSaleStatusTone = 'brand' | 'warning' | 'success' | 'danger' | 'muted'
export type AfterSaleProgressState = 'pending' | 'current' | 'done' | 'error'

export interface AfterSaleProgressStep {
  label: string
  state: AfterSaleProgressState
}

export interface AfterSaleView extends AfterSaleResponse {
  typeText: string
  statusText: string
  statusTone: AfterSaleStatusTone
  statusDescription: string
  requestedAmountText: string
  approvedAmountText: string
  refundAmountText: string
  createdAtText: string
  reviewedAtText: string
  refundedAtText: string
  evidenceCountText: string
  evidenceNames: string[]
  progressSteps: AfterSaleProgressStep[]
  returnAddressText: string
  returnShipmentText: string
  canCancel: boolean
  canSubmitReturnShipment: boolean
  canUpdateReturnShipment: boolean
}

function cleanText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function moneyText(value: unknown): string {
  return `¥${formatMoney(value) || '0.00'}`
}

export function afterSaleTypeText(type: AfterSaleType): string {
  return type === 'RETURN_REFUND' ? '退货退款' : '仅退款'
}

export function afterSaleStatusText(status: AfterSaleStatus): string {
  const labels: Record<AfterSaleStatus, string> = {
    REQUESTED: '待商家审核',
    APPROVED: '审核已通过',
    WAITING_RETURN: '待寄回商品',
    RETURNING: '退货运输中',
    WAITING_INSPECTION: '待商家验收',
    REJECTED: '申请未通过',
    RETURN_REJECTED: '退货验收未通过',
    CANCELLED: '申请已取消',
    REFUNDING: '退款处理中',
    REFUNDED: '退款已完成',
    REFUND_FAILED: '退款处理异常'
  }
  return labels[status]
}

function afterSaleStatusTone(status: AfterSaleStatus): AfterSaleStatusTone {
  if (status === 'REFUNDED') return 'success'
  if (status === 'REJECTED' || status === 'RETURN_REJECTED' || status === 'CANCELLED') return 'muted'
  if (status === 'REFUND_FAILED') return 'danger'
  if (status === 'REQUESTED' || status === 'WAITING_RETURN' || status === 'WAITING_INSPECTION') {
    return 'warning'
  }
  return 'brand'
}

function afterSaleStatusDescription(status: AfterSaleStatus): string {
  const descriptions: Record<AfterSaleStatus, string> = {
    REQUESTED: '申请已提交，商家审核后会更新处理结果',
    APPROVED: '申请已通过，系统正在准备发起原路退款',
    WAITING_RETURN: '商家已提供退货地址，请在截止时间前寄回并填写物流',
    RETURNING: '退货物流已提交，商家收货后会进行验收',
    WAITING_INSPECTION: '商家已收到退货，正在核对商品情况',
    REJECTED: '商家未通过本次申请，可查看审核说明',
    RETURN_REJECTED: '退回商品未通过验收，可查看商家说明',
    CANCELLED: '本次售后已取消',
    REFUNDING: '退款已提交微信，到账时间以微信支付通知为准',
    REFUNDED: '退款已原路退回，具体到账时间以支付渠道为准',
    REFUND_FAILED: '退款暂未完成，商家正在核查处理，无需重复申请'
  }
  return descriptions[status]
}

function progressSteps(type: AfterSaleType, status: AfterSaleStatus): AfterSaleProgressStep[] {
  const labels = type === 'RETURN_REFUND'
    ? ['提交申请', '商家审核', '寄回商品', '商家验收', '退款到账']
    : ['提交申请', '商家审核', '退款到账']
  const indexByStatus: Record<AfterSaleStatus, number> = type === 'RETURN_REFUND'
    ? {
        REQUESTED: 2,
        APPROVED: 5,
        WAITING_RETURN: 3,
        RETURNING: 3,
        WAITING_INSPECTION: 4,
        REJECTED: 2,
        RETURN_REJECTED: 4,
        CANCELLED: 2,
        REFUNDING: 5,
        REFUNDED: 5,
        REFUND_FAILED: 5
      }
    : {
        REQUESTED: 2,
        APPROVED: 3,
        WAITING_RETURN: 1,
        RETURNING: 1,
        WAITING_INSPECTION: 1,
        REJECTED: 2,
        RETURN_REJECTED: 1,
        CANCELLED: 2,
        REFUNDING: 3,
        REFUNDED: 3,
        REFUND_FAILED: 3
      }
  const current = indexByStatus[status]
  const error = ['REJECTED', 'RETURN_REJECTED', 'REFUND_FAILED'].includes(status)
  const cancelled = status === 'CANCELLED'
  return labels.map((label, index) => {
    const step = index + 1
    let state: AfterSaleProgressState = step < current ? 'done' : step === current ? 'current' : 'pending'
    if ((error || cancelled) && step === current) state = error ? 'error' : 'current'
    if (status === 'REFUNDED') state = 'done'
    return { label, state }
  })
}

export function isActiveAfterSale(status: AfterSaleStatus): boolean {
  return [
    'REQUESTED',
    'APPROVED',
    'WAITING_RETURN',
    'RETURNING',
    'WAITING_INSPECTION',
    'REFUNDING',
    'REFUND_FAILED'
  ].includes(status)
}

export function canApplyAfterSale(
  orderStatus: OrderStatus,
  latestAfterSale?: AfterSaleResponse
): boolean {
  const eligibleOrder = orderStatus === 'PAID' || orderStatus === 'PARTIALLY_SHIPPED'
    || orderStatus === 'SHIPPED' || orderStatus === 'COMPLETED'
  return eligibleOrder && (!latestAfterSale || !isActiveAfterSale(latestAfterSale.status))
}

function hasAction(record: AfterSaleResponse, action: AfterSaleAction): boolean {
  return Array.isArray(record.allowedActions) && record.allowedActions.includes(action)
}

export function buildAfterSaleView(record: AfterSaleResponse): AfterSaleView {
  const evidenceFiles = Array.isArray(record.evidenceFiles) ? record.evidenceFiles : []
  const approvedAmount = record.approvedAmountCent ?? 0
  const refundAmount = record.refundOrder?.refundAmountCent ?? approvedAmount
  const returnInfo = record.returnInfo
  const region = [returnInfo?.province, returnInfo?.city, returnInfo?.district]
    .map(cleanText)
    .filter(Boolean)
    .join('')
  return {
    ...record,
    description: cleanText(record.description),
    auditNote: cleanText(record.auditNote),
    items: Array.isArray(record.items) ? record.items : [],
    allowedActions: Array.isArray(record.allowedActions) ? record.allowedActions : [],
    evidenceFiles,
    evidenceFileIds: Array.isArray(record.evidenceFileIds) ? record.evidenceFileIds : [],
    typeText: afterSaleTypeText(record.afterSaleType),
    statusText: afterSaleStatusText(record.status),
    statusTone: afterSaleStatusTone(record.status),
    statusDescription: afterSaleStatusDescription(record.status),
    requestedAmountText: moneyText(record.requestedAmountCent),
    approvedAmountText: approvedAmount > 0 ? moneyText(approvedAmount) : '',
    refundAmountText: refundAmount > 0 ? moneyText(refundAmount) : '',
    createdAtText: formatLocalDateTime(record.createdAt),
    reviewedAtText: formatLocalDateTime(record.reviewedAt),
    refundedAtText: formatLocalDateTime(record.refundOrder?.successAt),
    evidenceCountText: evidenceFiles.length ? `${evidenceFiles.length} 张` : '未上传',
    evidenceNames: evidenceFiles.map((file) => cleanText(file.originalFilename) || '售后凭证'),
    progressSteps: progressSteps(record.afterSaleType, record.status),
    returnAddressText: returnInfo
      ? `${cleanText(returnInfo.contactName)} ${cleanText(returnInfo.contactPhone)} ${region}${cleanText(returnInfo.detailAddress)}`.trim()
      : '',
    returnShipmentText: returnInfo?.trackingNo
      ? `${cleanText(returnInfo.deliveryCompanyName) || cleanText(returnInfo.deliveryCompanyCode)} ${returnInfo.trackingNo}`.trim()
      : '',
    canCancel: hasAction(record, 'CANCEL'),
    canSubmitReturnShipment: hasAction(record, 'SUBMIT_RETURN_SHIPMENT'),
    canUpdateReturnShipment: hasAction(record, 'UPDATE_RETURN_SHIPMENT')
  }
}

function normalizeItems(items: AfterSaleItemRequest[]): AfterSaleItemRequest[] {
  return items
    .map((item) => ({
      orderItemId: Number(item.orderItemId),
      quantity: Number(item.quantity),
      requestedAmountCent: item.requestedAmountCent == null ? undefined : Number(item.requestedAmountCent)
    }))
    .filter((item) =>
      Number.isSafeInteger(item.orderItemId) && item.orderItemId > 0
      && Number.isSafeInteger(item.quantity) && item.quantity > 0
      && (item.requestedAmountCent === undefined
        || (Number.isSafeInteger(item.requestedAmountCent) && item.requestedAmountCent > 0)))
    .sort((a, b) => a.orderItemId - b.orderItemId)
}

function itemSignature(items: AfterSaleItemRequest[]): string {
  return normalizeItems(items)
    .map((item) => `${item.orderItemId}:${item.quantity}:${item.requestedAmountCent ?? ''}`)
    .join(',')
}

/**
 * 与服务端 AfterSaleAmountAllocator.tranche 一致的单件分摊上限：
 * 按件均摊订单实付（含优惠分摊），向上取整差值保证不超收。
 */
export function afterSaleItemRefundCeilingCent(
  paidAmountBasisCent: number,
  purchasedQuantity: number,
  refundedQuantity: number,
  requestedQuantity: number
): number {
  const basis = Math.trunc(Number(paidAmountBasisCent))
  const total = Math.trunc(Number(purchasedQuantity))
  const refunded = Math.trunc(Number(refundedQuantity))
  const requested = Math.trunc(Number(requestedQuantity))
  if (!(basis >= 0) || total <= 0 || refunded < 0 || requested <= 0 || refunded + requested > total) {
    return 0
  }
  const end = Math.floor(basis * (refunded + requested) / total)
  const start = Math.floor(basis * refunded / total)
  return Math.max(0, end - start)
}

export function buildAfterSaleApplyPayload(input: {
  requestKey: unknown
  quote: AfterSaleQuoteResponse | null | undefined
  reason: unknown
  description?: unknown
  evidenceFileIds?: unknown[]
  items: AfterSaleItemRequest[]
}): AfterSaleApplyRequest {
  const reason = cleanText(input.reason).slice(0, 128)
  const requestKey = cleanText(input.requestKey).slice(0, 64)
  const items = normalizeItems(input.items)
  if (!requestKey) throw new Error('申请标识无效，请重试')
  if (!reason) throw new Error('请选择售后原因')
  if (!input.quote?.quoteDigest || input.quote.requestedAmountCent <= 0) {
    throw new Error('请先获取服务端退款报价')
  }
  if (!items.length) throw new Error('请至少选择一件商品')
  if (itemSignature(items) !== itemSignature(input.quote.items)) {
    throw new Error('商品数量或金额已变化，请重新获取报价')
  }
  const evidenceFileIds = Array.from(new Set(
    (Array.isArray(input.evidenceFileIds) ? input.evidenceFileIds : [])
      .map(Number)
      .filter((id) => Number.isSafeInteger(id) && id > 0)
  )).slice(0, 3)
  return {
    requestKey,
    quoteDigest: input.quote.quoteDigest,
    afterSaleType: input.quote.afterSaleType,
    reason,
    requestedAmountCent: input.quote.requestedAmountCent,
    description: cleanText(input.description).slice(0, 500),
    evidenceFileIds,
    items
  }
}

export function createAfterSaleRequestKey(
  orderId: number,
  now = Date.now(),
  entropy = Math.random().toString(36).slice(2, 10)
): string {
  const id = positiveAfterSaleId(orderId)
  if (!id) throw new Error('订单参数无效')
  const suffix = cleanText(entropy).replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 16)
  if (!suffix) throw new Error('申请标识生成失败')
  return `as-${id}-${Math.max(0, Math.trunc(now))}-${suffix}`.slice(0, 64)
}

export function positiveAfterSaleId(value: unknown): number {
  const text = typeof value === 'string' ? value.trim() : value
  if (typeof text === 'string' && !/^\d+$/.test(text)) return 0
  const id = Number(text)
  return Number.isSafeInteger(id) && id > 0 ? id : 0
}

export function buildAfterSaleListUrl(): string {
  return '/pages/after-sale/list/list'
}

export function buildAfterSaleApplyUrl(orderId: number): string {
  const id = positiveAfterSaleId(orderId)
  if (!id) throw new Error('订单参数无效')
  return `/pages/after-sale/apply/apply?order_id=${id}`
}

export function buildAfterSaleDetailUrl(afterSaleId: number): string {
  const id = positiveAfterSaleId(afterSaleId)
  if (!id) throw new Error('售后参数无效')
  return `/pages/after-sale/detail/detail?after_sale_id=${id}`
}
