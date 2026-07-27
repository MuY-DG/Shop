import request from '@/utils/http'

const ASSET_UPLOAD_TIMEOUT_MS = 120_000

export function uploadAsset(
  data: Api.Storage.AssetUploadPayload,
  options: { showSuccessMessage?: boolean } = {}
) {
  const formData = new FormData()
  formData.append('file', data.file)

  if (typeof data.folderId === 'number') {
    formData.append('folderId', String(data.folderId))
  }

  return request.post<Api.Storage.Asset>({
    url: '/admin/assets/upload',
    data: formData,
    timeout: ASSET_UPLOAD_TIMEOUT_MS,
    showSuccessMessage: options.showSuccessMessage ?? true
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

export function batchMoveAssets(data: Api.Storage.AssetBatchMovePayload) {
  return request.post<void>({
    url: '/admin/assets/batch-move',
    data,
    showSuccessMessage: true
  })
}

export function batchDeleteAssets(data: Api.Storage.AssetBatchDeletePayload) {
  return request.post<Api.Storage.AssetBatchDeleteResult>({
    url: '/admin/assets/batch-delete',
    data,
    showSuccessMessage: false
  })
}

export function updateAssetDisplayName(assetId: number, data: Api.Storage.AssetDisplayNamePayload) {
  return request.put<Api.Storage.Asset>({
    url: `/admin/assets/${assetId}/display-name`,
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

export function updateAssetFolderPosition(
  folderId: number,
  data: Api.Storage.AssetFolderPositionPayload
) {
  return request.put<Api.Storage.AssetFolder>({
    url: `/admin/asset-folders/${folderId}/position`,
    data,
    showSuccessMessage: false
  })
}

export function deleteAssetFolder(folderId: number) {
  return request.del<void>({
    url: `/admin/asset-folders/${folderId}`,
    showSuccessMessage: true
  })
}
