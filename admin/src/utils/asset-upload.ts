export type UploadableAssetMediaKind = Exclude<Api.Storage.MediaKind, 'DOCUMENT'>

interface AssetUploadFileLike {
  name: string
  size: number
  type: string
  lastModified?: number
}

export interface AssetUploadValidation {
  valid: boolean
  message?: string
}

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'webp', 'gif', 'svg'])
const VIDEO_EXTENSIONS = new Set(['mp4', 'webm'])
const IMAGE_MAX_SIZE = 5 * 1024 * 1024
const VIDEO_MAX_SIZE = 50 * 1024 * 1024

const fileExtension = (file: AssetUploadFileLike) => file.name.split('.').pop()?.toLowerCase() || ''

export const assetUploadAccept = (mediaKind: UploadableAssetMediaKind) =>
  mediaKind === 'VIDEO'
    ? 'video/mp4,video/webm,.mp4,.webm'
    : 'image/jpeg,image/png,image/webp,image/gif,image/svg+xml,.jpg,.jpeg,.png,.webp,.gif,.svg'

export function validateAssetUploadFile(
  file: AssetUploadFileLike,
  mediaKind: UploadableAssetMediaKind
): AssetUploadValidation {
  const extension = fileExtension(file)

  if (mediaKind === 'VIDEO') {
    if (!VIDEO_EXTENSIONS.has(extension)) {
      return { valid: false, message: '视频仅支持 MP4 或 WebM' }
    }
    if (file.size > VIDEO_MAX_SIZE) {
      return { valid: false, message: '视频不能超过 50 MB' }
    }
    return { valid: true }
  }

  if (!file.type.startsWith('image/')) {
    return { valid: false, message: '请选择图片文件' }
  }
  if (!IMAGE_EXTENSIONS.has(extension)) {
    return { valid: false, message: '图片仅支持 JPG、PNG、WebP、GIF 或 SVG' }
  }
  if (file.size > IMAGE_MAX_SIZE) {
    return { valid: false, message: '图片不能超过 5 MB' }
  }
  return { valid: true }
}

export function validateLibraryAssetUploadFile(file: AssetUploadFileLike): AssetUploadValidation {
  const extension = fileExtension(file)
  if (VIDEO_EXTENSIONS.has(extension)) {
    return validateAssetUploadFile(file, 'VIDEO')
  }
  if (IMAGE_EXTENSIONS.has(extension)) {
    return validateAssetUploadFile(file, 'IMAGE')
  }
  return { valid: false, message: '仅支持 JPG、PNG、WebP、GIF、SVG、MP4 或 WebM' }
}

export const assetUploadFileKey = (file: AssetUploadFileLike) =>
  `${file.name}\u0000${file.size}\u0000${file.lastModified || 0}`

export function uniqueAssetUploadFiles<T extends AssetUploadFileLike>(files: readonly T[]): T[] {
  const seen = new Set<string>()
  return files.filter((file) => {
    const key = assetUploadFileKey(file)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}
