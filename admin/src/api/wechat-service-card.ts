import request from '@/utils/http'

const baseUrl = '/admin/wechat-service-cards'

export function fetchWechatServiceCardStatus() {
  return request.get<Api.WechatServiceCard.Status>({
    url: `${baseUrl}/status`
  })
}

export function fetchWechatServiceCardDeliveries(params: Api.WechatServiceCard.DeliveryQuery) {
  return request.get<Api.WechatServiceCard.DeliveryList>({
    url: `${baseUrl}/deliveries`,
    params
  })
}

export function updateWechatServiceCardRuntime(data: Api.WechatServiceCard.RuntimeUpdate) {
  return request.put<Api.WechatServiceCard.Status>({
    url: `${baseUrl}/runtime`,
    data,
    showSuccessMessage: true
  })
}

export function fetchWechatServiceCardConfig() {
  return request.get<Api.WechatServiceCard.Config>({
    url: `${baseUrl}/config`
  })
}

export function updateWechatServiceCardConfig(data: Api.WechatServiceCard.ConfigUpdate) {
  return request.put<Api.WechatServiceCard.Config>({
    url: `${baseUrl}/config`,
    data,
    showSuccessMessage: true
  })
}
