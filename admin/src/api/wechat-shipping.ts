import request from '@/utils/http'

export function fetchWechatShippingCapability() {
  return request.get<Api.Order.WechatShippingCapability>({
    url: '/admin/wechat-shipping/capability',
    showErrorMessage: false
  })
}

export function fetchWechatShippingRuntime() {
  return request.get<Api.Order.WechatShippingRuntime>({
    url: '/admin/wechat-shipping/runtime'
  })
}

export function updateWechatShippingRuntime(data: Api.Order.WechatShippingRuntimeUpdate) {
  return request.put<Api.Order.WechatShippingRuntime>({
    url: '/admin/wechat-shipping/runtime',
    data,
    showErrorMessage: false
  })
}

export function fetchWechatShippingCarriers() {
  return request.get<Api.Order.WechatDeliveryCompany[]>({
    url: '/admin/wechat-shipping/carriers'
  })
}

export function syncWechatShippingCarriers() {
  return request.post<Api.Order.WechatDeliveryCompany[]>({
    url: '/admin/wechat-shipping/carriers/sync'
  })
}
