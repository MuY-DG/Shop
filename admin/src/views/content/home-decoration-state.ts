export interface HomeCategoryEditorState {
  categoryId: number | null
  imageFileId: number | null
  sortOrder: number
  status: Api.Content.HomeItemStatus
}

export interface HomeProductEditorState {
  spuId: number | null
  imageFileId: number | null
  sortOrder: number
  status: Api.Content.HomeItemStatus
  badgeMode: Api.Content.HomeBadgeMode
  customBadgeText: string
}

export function toHomeCategoryPayload(
  state: HomeCategoryEditorState
): Api.Content.HomeCategoryForm {
  return {
    categoryId: state.categoryId,
    imageFileId: state.imageFileId,
    sortOrder: state.sortOrder,
    status: state.status
  }
}

export function toHomeProductPayload(state: HomeProductEditorState): Api.Content.HomeProductForm {
  return {
    spuId: state.spuId,
    imageFileId: state.imageFileId,
    sortOrder: state.sortOrder,
    status: state.status,
    badgeMode: state.badgeMode,
    customBadgeText: state.badgeMode === 'CUSTOM' ? state.customBadgeText.trim() : ''
  }
}

export function formatPriceRange(minPriceCent?: number | null, maxPriceCent?: number | null) {
  if (minPriceCent == null || maxPriceCent == null) return '-'
  const minPrice = `¥${(minPriceCent / 100).toFixed(2)}`
  const maxPrice = `¥${(maxPriceCent / 100).toFixed(2)}`
  return minPriceCent === maxPriceCent ? minPrice : `${minPrice} - ${maxPrice}`
}

export function trimPhone(phone: string) {
  return phone.trim()
}
