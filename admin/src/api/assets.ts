import request from '@/utils/http'

export function uploadAsset(data: Api.Storage.AssetUploadPayload) {
  const formData = new FormData()
  formData.append('file', data.file)

  if (typeof data.folderId === 'number') {
    formData.append('folderId', String(data.folderId))
  }

  return request.post<Api.Storage.Asset>({
    url: '/admin/assets/upload',
    data: formData,
    showSuccessMessage: true
  })
}

export function fetchAssets(params: Api.Storage.AssetQueryParams) {
  return request.get<Api.Storage.AssetList>({
    url: '/admin/assets',
    params
  })
}

export function fetchAssetDetail(assetId: number) {
  return request.get<Api.Storage.Asset>({
    url: `/admin/assets/${assetId}`
  })
}

export function moveAsset(assetId: number, data: Api.Storage.AssetMovePayload) {
  return request.post<void>({
    url: `/admin/assets/${assetId}/move`,
    data,
    showSuccessMessage: true
  })
}

export function deleteAsset(assetId: number) {
  return request.del<void>({
    url: `/admin/assets/${assetId}`,
    showSuccessMessage: true
  })
}

export function fetchAssetFolders() {
  return request.get<Api.Storage.AssetFolder[]>({
    url: '/admin/asset-folders'
  })
}

export function createAssetFolder(data: Api.Storage.AssetFolderForm) {
  return request.post<Api.Storage.AssetFolder>({
    url: '/admin/asset-folders',
    data,
    showSuccessMessage: true
  })
}

export function updateAssetFolder(folderId: number, data: Api.Storage.AssetFolderForm) {
  return request.put<Api.Storage.AssetFolder>({
    url: `/admin/asset-folders/${folderId}`,
    data,
    showSuccessMessage: true
  })
}

export function deleteAssetFolder(folderId: number) {
  return request.del<void>({
    url: `/admin/asset-folders/${folderId}`,
    showSuccessMessage: true
  })
}
