import request from '@/utils/http'

export function fetchWechatExpressConfig() {
  return request.get<Api.Waybill.WechatExpressConfig>({
    url: '/admin/logistics/wechat-express/config'
  })
}

export function updateWechatExpressConfig(data: Api.Waybill.WechatExpressConfigUpdate) {
  return request.put<Api.Waybill.WechatExpressConfig>({
    url: '/admin/logistics/wechat-express/config',
    data,
    showErrorMessage: false
  })
}

export function fetchElectronicWaybillContext(orderId: number) {
  return request.get<Api.Waybill.Context>({
    url: `/admin/orders/${orderId}/waybills/context`
  })
}

export function fetchElectronicWaybills(orderId: number) {
  return request.get<Api.Waybill.Attempt[]>({
    url: `/admin/orders/${orderId}/waybills`
  })
}

export function createElectronicWaybill(orderId: number, data: Api.Waybill.CreateRequest) {
  return request.post<Api.Waybill.Attempt>({
    url: `/admin/orders/${orderId}/waybills`,
    data
  })
}

export function refreshElectronicWaybill(orderId: number, waybillRecordId: number) {
  return request.post<Api.Waybill.Attempt>({
    url: `/admin/orders/${orderId}/waybills/${waybillRecordId}/refresh`
  })
}

export function cancelElectronicWaybill(orderId: number, waybillRecordId: number) {
  return request.post<Api.Waybill.Attempt>({
    url: `/admin/orders/${orderId}/waybills/${waybillRecordId}/cancel`
  })
}

export function fetchElectronicWaybillPrint(
  orderId: number,
  waybillRecordId: number,
  printType: Api.Waybill.PrintType
) {
  return request.get<Blob>({
    url: `/admin/orders/${orderId}/waybills/${waybillRecordId}/print`,
    params: { printType },
    responseType: 'blob'
  })
}

export function simulateElectronicWaybillEvent(
  orderId: number,
  waybillRecordId: number,
  data: Api.Waybill.SandboxEventRequest
) {
  return request.post<Api.Waybill.Attempt>({
    url: `/admin/orders/${orderId}/waybills/${waybillRecordId}/sandbox-events`,
    data
  })
}

export function confirmElectronicWaybillShipment(orderId: number, waybillRecordId: number) {
  return request.post<Api.Order.Shipment>({
    url: `/admin/orders/${orderId}/waybills/${waybillRecordId}/confirm-shipment`
  })
}

export function retryWaybillRegistration(orderId: number) {
  return request.post<Api.Order.Shipment>({
    url: `/admin/orders/${orderId}/shipping/retry-waybill-registration`
  })
}

export function retryShipmentWaybillRegistration(orderId: number, shipmentId: number) {
  return request.post<Api.Order.Shipment>({
    url: `/admin/orders/${orderId}/shipments/${shipmentId}/retry-waybill-registration`
  })
}
