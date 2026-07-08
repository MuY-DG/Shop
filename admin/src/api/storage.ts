import request from '@/utils/http'

export function uploadStorageFile(data: Api.Storage.UploadPayload) {
  const formData = new FormData()
  formData.append('file', data.file)
  formData.append('purpose', data.purpose)

  if (typeof data.assetCategoryId === 'number') {
    formData.append('assetCategoryId', String(data.assetCategoryId))
  }

  return request.post<Api.Storage.FileItem>({
    url: '/admin/files/upload',
    data: formData,
    showSuccessMessage: true
  })
}

export function fetchStorageFiles(params: Api.Storage.FileQueryParams) {
  return request.get<Api.Storage.FileList>({
    url: '/admin/files',
    params
  })
}

export function fetchStorageFileDetail(fileId: number) {
  return request.get<Api.Storage.FileItem>({
    url: `/admin/files/${fileId}`
  })
}

export function fetchStorageFileUsages(fileId: number) {
  return request.get<Api.Storage.FileUsage[]>({
    url: `/admin/files/${fileId}/usages`
  })
}

export function moveStorageFile(fileId: number, data: Api.Storage.MovePayload) {
  return request.post<void>({
    url: `/admin/files/${fileId}/move`,
    data,
    showSuccessMessage: true
  })
}

export function deleteStorageFile(fileId: number) {
  return request.del<void>({
    url: `/admin/files/${fileId}`,
    showSuccessMessage: true
  })
}

export function fetchStorageCategories() {
  return request.get<Api.Storage.AssetCategory[]>({
    url: '/admin/file-categories'
  })
}

export function createStorageCategory(data: Api.Storage.AssetCategoryForm) {
  return request.post<number>({
    url: '/admin/file-categories',
    data,
    showSuccessMessage: true
  })
}

export function updateStorageCategory(categoryId: number, data: Api.Storage.AssetCategoryForm) {
  return request.put<void>({
    url: `/admin/file-categories/${categoryId}`,
    data,
    showSuccessMessage: true
  })
}
