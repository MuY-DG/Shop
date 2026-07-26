export type AssetPickerFolderPreference = number | null | undefined

interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

interface AssetFolderLike {
  id: number
  status: string
  children?: AssetFolderLike[]
}

const STORAGE_PREFIX = 'shop:asset-picker:last-folder:v1'
const ALL_FOLDERS_VALUE = 'all'

export const assetPickerFolderPreferenceKey = (
  userId: number | string | null | undefined,
  mediaKind: 'IMAGE' | 'VIDEO'
) => `${STORAGE_PREFIX}:${userId || 'anonymous'}:${mediaKind}`

export function readAssetPickerFolderPreference(
  storage: StorageLike,
  userId: number | string | null | undefined,
  mediaKind: 'IMAGE' | 'VIDEO'
): AssetPickerFolderPreference {
  try {
    const value = storage.getItem(assetPickerFolderPreferenceKey(userId, mediaKind))
    if (value === null) return undefined
    if (value === ALL_FOLDERS_VALUE) return null
    const folderId = Number(value)
    return Number.isSafeInteger(folderId) && folderId >= 0 ? folderId : undefined
  } catch {
    return undefined
  }
}

export function writeAssetPickerFolderPreference(
  storage: StorageLike,
  userId: number | string | null | undefined,
  mediaKind: 'IMAGE' | 'VIDEO',
  folderId: number | undefined
) {
  try {
    storage.setItem(
      assetPickerFolderPreferenceKey(userId, mediaKind),
      folderId === undefined ? ALL_FOLDERS_VALUE : String(folderId)
    )
  } catch {
    // Browsing preferences are optional when browser storage is unavailable.
  }
}

const hasEnabledFolder = (folders: readonly AssetFolderLike[], folderId: number): boolean => {
  for (const folder of folders) {
    if (folder.id === folderId) return folder.status === 'ENABLED'
    if (hasEnabledFolder(folder.children || [], folderId)) return true
  }
  return false
}

const resolveFolderId = (
  folderId: number | null | undefined,
  folders: readonly AssetFolderLike[]
) => {
  if (folderId === null) return undefined
  if (folderId === 0) return 0
  if (typeof folderId === 'number' && hasEnabledFolder(folders, folderId)) return folderId
  return undefined
}

export function resolveAssetPickerFolderPreference(
  preference: AssetPickerFolderPreference,
  defaultFolderId: number | null | undefined,
  folders: readonly AssetFolderLike[]
) {
  if (preference !== undefined) {
    const preferredFolderId = resolveFolderId(preference, folders)
    if (preference === null || preferredFolderId !== undefined) return preferredFolderId
  }
  return resolveFolderId(defaultFolderId, folders)
}
