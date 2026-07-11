import request from '@/utils/http'

export function fetchWechatShippingCapability() {
  return request.get<Api.Order.WechatShippingCapability>({
    url: '/admin/wechat-shipping/capability'
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
