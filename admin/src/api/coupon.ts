import request from '@/utils/http'

export function fetchCouponTemplates(params: Api.Marketing.CouponTemplateSearchParams) {
  return request.get<Api.Marketing.CouponTemplateList>({
    url: '/admin/marketing/coupons/templates',
    params
  })
}

export function createCouponTemplate(data: Api.Marketing.CouponTemplateForm) {
  return request.post<number>({
    url: '/admin/marketing/coupons/templates',
    data,
    showSuccessMessage: true
  })
}

export function updateCouponTemplate(
  templateId: number,
  data: Api.Marketing.CouponTemplateForm
) {
  return request.put<void>({
    url: `/admin/marketing/coupons/templates/${templateId}`,
    data,
    showSuccessMessage: true
  })
}

export function enableCouponTemplate(templateId: number) {
  return request.post<void>({
    url: `/admin/marketing/coupons/templates/${templateId}/enable`,
    showSuccessMessage: true
  })
}

export function disableCouponTemplate(templateId: number) {
  return request.post<void>({
    url: `/admin/marketing/coupons/templates/${templateId}/disable`,
    showSuccessMessage: true
  })
}
