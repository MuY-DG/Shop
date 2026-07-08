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
