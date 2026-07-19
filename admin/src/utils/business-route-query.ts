type RouteQueryValue = string | null | (string | null)[] | undefined

const firstQueryValue = (value: RouteQueryValue): string | undefined => {
  const candidate = Array.isArray(value) ? value[0] : value
  return typeof candidate === 'string' && candidate.trim() ? candidate.trim() : undefined
}

const orderStatusGroups = new Set<Api.Order.AdminOrderStatusGroup>([
  'ALL',
  'UNPAID',
  'TO_SHIP',
  'TO_RECEIVE',
  'COMPLETED',
  'CLOSED',
  'REFUNDING',
  'REFUNDED'
])

const afterSaleStatusGroups = new Set<Api.AfterSale.AdminAfterSaleStatusGroup>([
  'ALL',
  'PENDING_REVIEW',
  'REFUNDING',
  'REFUNDED',
  'REJECTED',
  'REFUND_FAILED'
])

export type CustomerServiceStatusFilter =
  | 'ALL'
  | Exclude<Api.CustomerService.ConversationStatus, 'DRAFT'>

const customerServiceStatuses = new Set<CustomerServiceStatusFilter>([
  'ALL',
  'WAITING',
  'ACTIVE',
  'CLOSED'
])

export function orderStatusGroupFromQuery(
  value: RouteQueryValue
): Api.Order.AdminOrderStatusGroup | undefined {
  const candidate = firstQueryValue(value) as Api.Order.AdminOrderStatusGroup | undefined
  return candidate && orderStatusGroups.has(candidate) ? candidate : undefined
}

export function afterSaleStatusGroupFromQuery(
  value: RouteQueryValue
): Api.AfterSale.AdminAfterSaleStatusGroup | undefined {
  const candidate = firstQueryValue(value) as Api.AfterSale.AdminAfterSaleStatusGroup | undefined
  return candidate && afterSaleStatusGroups.has(candidate) ? candidate : undefined
}

export function customerServiceStatusFromQuery(
  value: RouteQueryValue
): CustomerServiceStatusFilter | undefined {
  const candidate = firstQueryValue(value) as CustomerServiceStatusFilter | undefined
  return candidate && customerServiceStatuses.has(candidate) ? candidate : undefined
}
