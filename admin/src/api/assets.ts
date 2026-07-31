import request from '@/utils/http'
import { isHttpError, showError } from '@/utils/http/error'
import {
  uploadFileToCosPostWithSessionCancellation,
  type CosPostUploadOptions,
  type CosPostUploadProgress
} from '@/utils/cos-post-upload'

const LEGACY_ASSET_UPLOAD_TIMEOUT_MS = 180_000
const DIRECT_UPLOAD_COMPLETE_TIMEOUT_MS = 180_000
const DIRECT_UPLOAD_CANCEL_TIMEOUT_MS = 5_000
const DIRECT_UPLOAD_PROCESSING_RETRY_DELAYS_MS = [5_500, 10_500] as const
const DIRECT_UPLOAD_PROCESSING_FAILED_CODE = 800007
const LEGACY_ONLY_EXTENSIONS = new Set(['svg'])
const DIRECT_UPLOAD_UNAVAILABLE_HTTP_STATUSES = new Set([404, 405, 501])
const DIRECT_UPLOAD_UNAVAILABLE_CODE = 800009

export interface AssetUploadOptions {
  showSuccessMessage?: boolean
  signal?: AbortSignal
  onProgress?: (progress: CosPostUploadProgress) => void
}

const fileExtension = (filename: string) =>
  filename.includes('.') ? filename.split('.').pop()?.toLowerCase() || '' : ''

const contentTypeForUpload = (file: File) => {
  if (file.type) return file.type.toLowerCase() === 'image/jpg' ? 'image/jpeg' : file.type
  const extension = fileExtension(file.name)
  const knownTypes: Record<string, string> = {
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    png: 'image/png',
    webp: 'image/webp',
    gif: 'image/gif',
    svg: 'image/svg+xml',
    mp4: 'video/mp4',
    webm: 'video/webm'
  }
  return knownTypes[extension] || 'application/octet-stream'
}

function createAssetUploadSession(data: Api.Storage.AssetUploadPayload) {
  return request.post<Api.Storage.AssetUploadSession>({
    url: '/admin/assets/upload-sessions',
    data: {
      folderId: data.folderId && data.folderId > 0 ? data.folderId : null,
      originalFilename: data.file.name,
      contentType: contentTypeForUpload(data.file),
      sizeBytes: data.file.size
    } satisfies Api.Storage.AssetUploadSessionPayload,
    showErrorMessage: false
  })
}

async function completeAssetUploadSession(uploadId: string, showSuccessMessage: boolean) {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await request.post<Api.Storage.Asset>({
        url: `/admin/assets/upload-sessions/${encodeURIComponent(uploadId)}/complete`,
        timeout: DIRECT_UPLOAD_COMPLETE_TIMEOUT_MS,
        showErrorMessage: false,
        showSuccessMessage
      })
    } catch (error) {
      const retryDelay = DIRECT_UPLOAD_PROCESSING_RETRY_DELAYS_MS[attempt]
      if (
        !isHttpError(error) ||
        error.code !== DIRECT_UPLOAD_PROCESSING_FAILED_CODE ||
        retryDelay === undefined
      ) {
        if (isHttpError(error)) showError(error)
        throw error
      }
      await new Promise((resolve) => setTimeout(resolve, retryDelay))
    }
  }
}

function cancelAssetUploadSession(uploadId: string) {
  return request.del<void>({
    url: `/admin/assets/upload-sessions/${encodeURIComponent(uploadId)}`,
    timeout: DIRECT_UPLOAD_CANCEL_TIMEOUT_MS,
    showErrorMessage: false
  })
}

function uploadAssetThroughBusinessApi(
  data: Api.Storage.AssetUploadPayload,
  options: AssetUploadOptions
) {
  const formData = new FormData()
  formData.append('file', data.file)

  if (typeof data.folderId === 'number') {
    formData.append('folderId', String(data.folderId))
  }

  return request.post<Api.Storage.Asset>({
    url: '/admin/assets/upload',
    data: formData,
    timeout: LEGACY_ASSET_UPLOAD_TIMEOUT_MS,
    signal: options.signal,
    onUploadProgress: (event) => {
      const total = event.total || data.file.size
      options.onProgress?.({
        loaded: Math.min(event.loaded, total),
        total,
        percent: total > 0 ? Math.min(100, Math.round((event.loaded / total) * 100)) : 0
      })
    },
    showSuccessMessage: options.showSuccessMessage ?? true
  })
}

const shouldUseLegacyUpload = (file: File) => LEGACY_ONLY_EXTENSIONS.has(fileExtension(file.name))

const shouldFallbackAfterSessionFailure = (error: unknown) => {
  if (!isHttpError(error)) return false
  return (
    error.code === DIRECT_UPLOAD_UNAVAILABLE_CODE ||
    (error.httpStatus !== undefined &&
      DIRECT_UPLOAD_UNAVAILABLE_HTTP_STATUSES.has(error.httpStatus))
  )
}

export async function uploadAsset(
  data: Api.Storage.AssetUploadPayload,
  options: AssetUploadOptions = {}
) {
  if (shouldUseLegacyUpload(data.file)) {
    return uploadAssetThroughBusinessApi(data, options)
  }

  let session: Api.Storage.AssetUploadSession
  try {
    session = await createAssetUploadSession(data)
  } catch (error) {
    if (shouldFallbackAfterSessionFailure(error)) {
      return uploadAssetThroughBusinessApi(data, options)
    }
    if (isHttpError(error)) showError(error)
    throw error
  }

  await uploadFileToCosPostWithSessionCancellation(
    { uploadUrl: session.uploadUrl, formData: session.formData },
    data.file,
    () => cancelAssetUploadSession(session.uploadId),
    {
      signal: options.signal,
      onProgress: options.onProgress
    } satisfies CosPostUploadOptions
  )

  return completeAssetUploadSession(session.uploadId, options.showSuccessMessage ?? true)
}

export function fetchAssets(params: Api.Storage.AssetQueryParams) {
  return request.get<Api.Storage.AssetList>({
    url: '/admin/assets',
    params
  })
}

export function fetchAssetDetail(assetId: number) {
  return request.get<Api.Storage.Asset>({
    url: `/admin/assets/${assetId}`,
    params: { includeUsages: false }
  })
}

export function fetchAssetUsages(assetId: number, params: Api.Storage.AssetUsageQueryParams) {
  return request.get<Api.Storage.AssetUsageList>({
    url: `/admin/assets/${assetId}/usages`,
    params
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
