import type { OperationDrilldownTarget } from './operations-state'

const todoTargets: Record<string, OperationDrilldownTarget | undefined> = {
  unpaidOrders: { path: '/trade/orders', query: { statusGroup: 'UNPAID' } },
  toShipOrders: { path: '/trade/orders', query: { statusGroup: 'TO_SHIP' } },
  pendingAfterSales: {
    path: '/trade/after-sales',
    query: { statusGroup: 'PENDING_REVIEW' }
  },
  failedRefunds: {
    path: '/trade/after-sales',
    query: { statusGroup: 'REFUND_FAILED' }
  },
  waitingConversations: { path: '/customer-service', query: { status: 'WAITING' } }
}

const normalizedText = (value: string): string | undefined => {
  const normalized = value.trim()
  return normalized || undefined
}

export const todoDrilldown = (key: string): OperationDrilldownTarget | undefined => todoTargets[key]

export const productDrilldown = (productId: string): OperationDrilldownTarget | undefined => {
  const id = normalizedText(productId)
  const numericId = Number(id)
  if (!id || !/^\d+$/.test(id) || !Number.isSafeInteger(numericId) || numericId <= 0) {
    return undefined
  }
  return { path: '/product/spu', query: { mode: 'edit', id } }
}

export const recentOrderDrilldown = (orderNo: string): OperationDrilldownTarget | undefined => {
  const normalizedOrderNo = normalizedText(orderNo)
  if (!normalizedOrderNo) return undefined
  return { path: '/trade/orders', query: { orderNo: normalizedOrderNo } }
}
