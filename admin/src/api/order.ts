import request from '@/utils/http'

export function fetchOrders(params: Api.Order.OrderSearchParams) {
  return request.get<Api.Order.OrderList>({
    url: '/admin/orders',
    params
  })
}

export function fetchOrderDetail(orderId: number) {
  return request.get<Api.Order.OrderDetail>({
    url: `/admin/orders/${orderId}`
  })
}

export function closeOrder(orderId: number) {
  return request.post<Record<string, unknown>>({
    url: `/admin/orders/${orderId}/close`,
    showSuccessMessage: true
  })
}
