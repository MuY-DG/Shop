import request from '@/utils/http'

export function fetchEffectivePaymentConfig() {
  return request.get<Api.Payment.EffectiveConfig>({
    url: '/admin/pay/configs/effective'
  })
}

export function fetchPaymentConfigs(params: Api.Payment.ConfigSearchParams) {
  return request.get<Api.Payment.ConfigList>({
    url: '/admin/pay/configs',
    params
  })
}

export function createPaymentConfig(data: Api.Payment.ConfigForm) {
  return request.post<Api.Payment.Config>({
    url: '/admin/pay/configs',
    data,
    showSuccessMessage: true
  })
}

export function updatePaymentConfig(configId: number, data: Api.Payment.ConfigForm) {
  return request.put<Api.Payment.Config>({
    url: `/admin/pay/configs/${configId}`,
    data,
    showSuccessMessage: true
  })
}

export function enablePaymentConfig(configId: number) {
  return request.post<Api.Payment.Config>({
    url: `/admin/pay/configs/${configId}/enable`,
    showSuccessMessage: true
  })
}
