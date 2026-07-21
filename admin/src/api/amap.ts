import request from '@/utils/http'

export function fetchAmapConfig() {
  return request.get<Api.Amap.Config>({
    url: '/admin/amap/config'
  })
}

export function updateAmapConfig(data: Api.Amap.ConfigForm) {
  return request.put<Api.Amap.Config>({
    url: '/admin/amap/config',
    data,
    showSuccessMessage: true
  })
}
