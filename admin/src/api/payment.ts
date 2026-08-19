import request from '@/utils/http'

export function fetchEffectivePaymentConfig() {
  return request.get<Api.Payment.EffectiveConfigState>({
    url: '/admin/pay/configs/effective'
  })
}

export function fetchPaymentConfigs(params: Api.Payment.ConfigSearchParams) {
  return request.get<Api.Payment.ConfigList>({
    url: '/admin/pay/configs',
    params
  })
}

export function createPaymentConfig(data: Api.Payment.ConfigForm, showSuccessMessage = true) {
  return request.post<Api.Payment.Config>({
    url: '/admin/pay/configs',
    data,
    showSuccessMessage
  })
}

export function importLegacyPaymentSecretFiles(configId: number, showSuccessMessage = true) {
  return request.post<Api.Payment.Config>({
    url: `/admin/pay/configs/${configId}/import-legacy-secret-files`,
    showSuccessMessage
  })
}

export function updatePaymentConfig(
  configId: number,
  data: Api.Payment.ConfigForm,
  showSuccessMessage = true
) {
  return request.put<Api.Payment.Config>({
    url: `/admin/pay/configs/${configId}`,
    data,
    showSuccessMessage
  })
}

export function enablePaymentConfig(configId: number, showSuccessMessage = true) {
  return request.post<Api.Payment.Config>({
    url: `/admin/pay/configs/${configId}/enable`,
    showSuccessMessage
  })
}

export function deletePaymentConfig(configId: number, showSuccessMessage = true) {
  return request.del<void>({
    url: `/admin/pay/configs/${configId}`,
    showSuccessMessage
  })
}
