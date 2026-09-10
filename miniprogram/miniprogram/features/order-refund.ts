import type { OrderItemAfterSaleResponse } from '../types/order'

function money(cent: number): string { return `¥${(cent / 100).toFixed(2)}` }

export function orderItemAfterSaleStatusText(status: string): string {
  return ({
    REQUESTED: '等待商家审核', APPROVED: '退款处理中', REFUNDING: '退款处理中',
    WAITING_RETURN: '待寄回商品', RETURNING: '退货运输中', WAITING_INSPECTION: '待商家验收',
    REFUND_FAILED: '退款待处理', REFUNDED: '退款成功'
  } as Record<string, string>)[status] || '售后处理中'
}

export function buildOrderItemAfterSaleView(item: { quantity: number; afterSale?: OrderItemAfterSaleResponse | null }) {
  const sale = item.afterSale
  const amount = sale?.refundedAmountCent
  const refunded = typeof amount === 'number' && Number.isSafeInteger(amount) && amount > 0
  let label = ''
  if (refunded && sale) {
    label = sale.fullyRefunded ? '已退款'
      : sale.refundedQuantity > 0 && sale.refundedQuantity < item.quantity
        ? `已退款 ${sale.refundedQuantity}/${item.quantity} 件` : '部分退款'
  }
  const records = Array.isArray(sale?.records) ? sale.records : []
  const active = records.find((record) => record.status !== 'REFUNDED')
  return {
    itemRefundText: refunded ? `${label} · ${money(amount)}` : '',
    itemAfterSaleText: active ? `${active.quantity} 件${orderItemAfterSaleStatusText(active.status)}` : '',
    afterSaleRecords: records.filter((record) => record.appVisible)
      .filter((record, index, visible) => visible.findIndex((other) => (other.status === 'REFUNDED') === (record.status === 'REFUNDED')) === index)
  }
}

export function buildOrderRefundSummary(order: { paidAmountCent: number; refundedAmountCent?: number | null }) {
  const amount = order.refundedAmountCent
  if (typeof amount !== 'number' || !Number.isSafeInteger(amount) || amount <= 0
      || !Number.isSafeInteger(order.paidAmountCent) || order.paidAmountCent <= 0) return ''
  return amount >= order.paidAmountCent ? '退款成功' : `部分退款 · 已退 ${money(amount)}`
}
