import request from '@/utils/http'

export function fetchDataCleanupConfig() {
  return request.get<Api.DataCleanup.Config>({
    url: '/admin/data-cleanup/config'
  })
}

export function updateDataCleanupConfig(data: Api.DataCleanup.ConfigForm) {
  return request.put<Api.DataCleanup.Config>({
    url: '/admin/data-cleanup/config',
    data,
    showSuccessMessage: true
  })
}
