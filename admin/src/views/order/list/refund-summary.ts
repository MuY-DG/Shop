interface OrderRefundAmounts {
  paidAmountCent: number
  refundedAmountCent?: number | null
}

/** 订单累计退款包含多次售后及已核实的外部退款，不使用单笔售后申请金额。 */
export function buildOrderRefundSummary(order: OrderRefundAmounts) {
  const refunded = order.refundedAmountCent
  if (
    typeof refunded !== 'number' ||
    !Number.isSafeInteger(refunded) ||
    refunded <= 0 ||
    !Number.isSafeInteger(order.paidAmountCent) ||
    order.paidAmountCent <= 0
  ) {
    return null
  }
  const fullyRefunded = refunded >= order.paidAmountCent
  return {
    text: fullyRefunded ? '全部退款' : '部分退款',
    type: fullyRefunded ? ('success' as const) : ('warning' as const),
    tone: fullyRefunded ? 'refunded' : 'refunding',
    amountCent: refunded
  }
}
