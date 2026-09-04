import request from '@/utils/http'

export function fetchStorageConfig() {
  return request.get<Api.Storage.Config>({
    url: '/admin/storage/config'
  })
}

export function fetchStorageBuckets(data: Api.Storage.BucketListRequest) {
  return request.post<Api.Storage.BucketOption[]>({
    url: '/admin/storage/config/buckets',
    data
  })
}

export function fetchStorageDomains(data: Api.Storage.DomainListRequest) {
  return request.post<Api.Storage.DomainOption[]>({
    url: '/admin/storage/config/domains',
    data
  })
}

export function updateStorageConfig(data: Api.Storage.ConfigForm) {
  return request.put<Api.Storage.Config>({
    url: '/admin/storage/config',
    data,
    showSuccessMessage: false
  })
}
