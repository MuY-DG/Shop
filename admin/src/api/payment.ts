import request from '@/utils/http'

export function uploadPaymentSecretFile(file: File) {
  const formData = new FormData()
  formData.append('file', file)

  return request.post<Api.Storage.Asset>({
    url: '/admin/pay/configs/secret-files',
    data: formData,
    showSuccessMessage: true
  })
}

export function fetchEffectivePaymentConfig() {
  return request.get<Api.Payment.EffectiveConfig>({
    url: '/admin/pay/configs/effective'
  })
}

export function fetchEnvironmentPaymentConfig() {
  return request.get<Api.Payment.EnvironmentConfig>({
    url: '/admin/pay/configs/environment',
    showErrorMessage: false
  })
}

export function fetchPaymentConfigSource() {
  return request.get<Api.Payment.ConfigSourceSetting>({
    url: '/admin/pay/configs/source'
  })
}

export function updatePaymentConfigSource(
  data: Api.Payment.ConfigSourceForm,
  showSuccessMessage = true
) {
  return request.put<Api.Payment.ConfigSourceSetting>({
    url: '/admin/pay/configs/source',
    data,
    showSuccessMessage
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
