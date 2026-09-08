export function orderItemAfterSaleStatusText(status: string): string {
  return (
    (
      {
        REQUESTED: '等待商家审核',
        APPROVED: '退款处理中',
        REFUNDING: '退款处理中',
        WAITING_RETURN: '待寄回商品',
        RETURNING: '退货运输中',
        WAITING_INSPECTION: '待商家验收',
        REFUND_FAILED: '退款待处理',
        REFUNDED: '退款成功'
      } as Record<string, string>
    )[status] || '售后处理中'
  )
}

export function buildOrderItemAfterSaleView(item: {
  quantity: number
  afterSale?: Api.Order.ItemAfterSale | null
}) {
  const sale = item.afterSale
  const amount = sale?.refundedAmountCent
  const refunded = typeof amount === 'number' && Number.isSafeInteger(amount) && amount > 0
  let label = ''
  if (refunded && sale) {
    label = sale.fullyRefunded
      ? '已退款'
      : sale.refundedQuantity > 0 && sale.refundedQuantity < item.quantity
        ? `已退款 ${sale.refundedQuantity}/${item.quantity} 件`
        : '部分退款'
  }
  const records = Array.isArray(sale?.records) ? sale.records : []
  const active = records.find((record) => record.status !== 'REFUNDED')
  return {
    refundText: refunded ? `${label} · ¥${(amount / 100).toFixed(2)}` : '',
    activeText: active ? `${active.quantity} 件${orderItemAfterSaleStatusText(active.status)}` : '',
    records
  }
}
