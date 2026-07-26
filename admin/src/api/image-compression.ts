import request from '@/utils/http'

export function fetchImageCompressionConfig() {
  return request.get<Api.ImageCompression.Config>({
    url: '/admin/image-compression/config'
  })
}

export function updateImageCompressionConfig(data: Api.ImageCompression.ConfigForm) {
  return request.put<Api.ImageCompression.Config>({
    url: '/admin/image-compression/config',
    data,
    showSuccessMessage: true
  })
}

export function refreshImageCompressionQuota() {
  return request.post<Api.ImageCompression.Config>({
    url: '/admin/image-compression/config/refresh',
    showSuccessMessage: true
  })
}
