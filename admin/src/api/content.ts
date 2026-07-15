import request from '@/utils/http'

export function fetchHomeBanners(params: Api.Content.BannerQueryParams) {
  return request.get<Api.Content.BannerList>({
    url: '/admin/home/banners',
    params
  })
}

export function createHomeBanner(data: Api.Content.BannerForm) {
  return request.post<number>({
    url: '/admin/home/banners',
    data,
    showSuccessMessage: true
  })
}

export function updateHomeBanner(bannerId: number, data: Api.Content.BannerForm) {
  return request.put<void>({
    url: `/admin/home/banners/${bannerId}`,
    data,
    showSuccessMessage: true
  })
}

export function enableHomeBanner(bannerId: number) {
  return request.post<void>({
    url: `/admin/home/banners/${bannerId}/enable`,
    showSuccessMessage: true
  })
}

export function disableHomeBanner(bannerId: number) {
  return request.post<void>({
    url: `/admin/home/banners/${bannerId}/disable`,
    showSuccessMessage: true
  })
}

export function fetchHomeCategories() {
  return request.get<Api.Content.HomeCategoryItem[]>({ url: '/admin/home/categories' })
}

export function createHomeCategory(data: Api.Content.HomeCategoryForm) {
  return request.post<number>({ url: '/admin/home/categories', data, showSuccessMessage: true })
}

export function updateHomeCategory(itemId: number, data: Api.Content.HomeCategoryForm) {
  return request.put<void>({
    url: `/admin/home/categories/${itemId}`,
    data,
    showSuccessMessage: true
  })
}

export function deleteHomeCategory(itemId: number) {
  return request.del<void>({
    url: `/admin/home/categories/${itemId}`,
    showSuccessMessage: true
  })
}

export function fetchHomeCategoryOptions() {
  return request.get<Api.Content.HomeCategoryOption[]>({ url: '/admin/home/options/categories' })
}

const productSectionUrl: Record<Api.Content.HomeProductSection, string> = {
  HOT: '/admin/home/hot-products',
  RECOMMENDED: '/admin/home/recommended-products'
}

export function fetchHomeProducts(section: Api.Content.HomeProductSection) {
  return request.get<Api.Content.HomeProductItem[]>({ url: productSectionUrl[section] })
}

export function createHomeProduct(
  section: Api.Content.HomeProductSection,
  data: Api.Content.HomeProductForm
) {
  return request.post<number>({
    url: productSectionUrl[section],
    data,
    showSuccessMessage: true
  })
}

export function updateHomeProduct(
  section: Api.Content.HomeProductSection,
  itemId: number,
  data: Api.Content.HomeProductForm
) {
  return request.put<void>({
    url: `${productSectionUrl[section]}/${itemId}`,
    data,
    showSuccessMessage: true
  })
}

export function deleteHomeProduct(section: Api.Content.HomeProductSection, itemId: number) {
  return request.del<void>({
    url: `${productSectionUrl[section]}/${itemId}`,
    showSuccessMessage: true
  })
}

export function fetchHomeProductOptions(params: Api.Content.HomeProductOptionQuery) {
  return request.get<Api.Content.HomeProductOptionList>({
    url: '/admin/home/options/products',
    params
  })
}

export function fetchContactSetting() {
  return request.get<Api.Content.ContactSetting>({ url: '/admin/contact' })
}

export function updateContactSetting(data: Api.Content.ContactForm) {
  return request.put<Api.Content.ContactSetting>({
    url: '/admin/contact',
    data,
    showSuccessMessage: true
  })
}
