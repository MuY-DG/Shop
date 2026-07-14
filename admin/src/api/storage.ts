import request from '@/utils/http'

export function fetchStorageConfig() {
  return request.get<Api.Storage.Config>({
    url: '/admin/storage/config'
  })
}

export function updateStorageConfig(data: Api.Storage.ConfigForm) {
  return request.put<Api.Storage.Config>({
    url: '/admin/storage/config',
    data,
    showSuccessMessage: true
  })
}
