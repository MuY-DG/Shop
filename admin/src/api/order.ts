import request from '@/utils/http'

export function fetchOrders(params: Api.Order.OrderSearchParams) {
  return request.get<Api.Order.OrderList>({
    url: '/admin/orders',
    params
  })
}

export function fetchOrderStatusCounts(params: Api.Order.OrderSearchParams) {
  return request.get<Api.Order.OrderStatusCounts>({
    url: '/admin/orders/status-counts',
    params
  })
}

export function fetchOrderDetail(orderId: number) {
  return request.get<Api.Order.OrderDetail>({
    url: `/admin/orders/${orderId}`
  })
}

export function fetchOrderStatusLogs(orderId: number) {
  return request.get<Api.Order.OrderStatusLog[]>({
    url: `/admin/orders/${orderId}/status-logs`
  })
}

export function closeOrder(orderId: number) {
  return request.post<Record<string, unknown>>({
    url: `/admin/orders/${orderId}/close`,
    showSuccessMessage: true
  })
}

export function shipOrder(orderId: number, data: Api.Order.ShipOrderForm) {
  return request.post<Api.Order.Shipment>({
    url: `/admin/orders/${orderId}/ship`,
    data
  })
}

export function retryOrderShippingUpload(orderId: number) {
  return request.post<Api.Order.Shipment>({
    url: `/admin/orders/${orderId}/shipping/retry-wechat-upload`
  })
}

export function retryShipmentShippingUpload(orderId: number, shipmentId: number) {
  return request.post<Api.Order.Shipment>({
    url: `/admin/orders/${orderId}/shipments/${shipmentId}/retry-wechat-upload`
  })
}

export function fetchOrderShipmentTracking(orderId: number) {
  return request.get<Api.Order.ShipmentTracking>({
    url: `/admin/orders/${orderId}/shipping/tracking`
  })
}

export function fetchShipmentTracking(orderId: number, shipmentId: number) {
  return request.get<Api.Order.ShipmentTracking>({
    url: `/admin/orders/${orderId}/shipments/${shipmentId}/tracking`
  })
}

export function syncOrderShipmentTracking(orderId: number) {
  return request.post<Api.Order.ShipmentTracking>({
    url: `/admin/orders/${orderId}/shipping/tracking/sync`
  })
}

export function syncShipmentTracking(orderId: number, shipmentId: number) {
  return request.post<Api.Order.ShipmentTracking>({
    url: `/admin/orders/${orderId}/shipments/${shipmentId}/tracking/sync`
  })
}
