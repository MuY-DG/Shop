import request from '@/utils/http'

export function fetchCustomers(params: Api.Customer.CustomerSearchParams) {
  return request.get<Api.Customer.CustomerList>({
    url: '/admin/customers',
    params
  })
}

export function updateCustomerStatus(userId: string, data: Api.Customer.CustomerStatusForm) {
  return request.request<Api.Customer.CustomerStatusResult>({
    method: 'PATCH',
    url: `/admin/customers/${userId}/status`,
    data,
    showSuccessMessage: true
  })
}

export function fetchIssuableCouponTemplates(userId: string) {
  return request.get<Api.Customer.IssuableCouponTemplate[]>({
    url: `/admin/customers/${userId}/issuable-coupon-templates`
  })
}

export function issueCustomerCoupon(userId: string, data: Api.Customer.CouponIssueForm) {
  return request.post<Api.Customer.CouponIssueResult>({
    url: `/admin/customers/${userId}/coupons`,
    data
  })
}

export function createDirectCustomerCoupon(
  userId: string,
  data: Api.Customer.DirectCouponIssueForm
) {
  return request.post<Api.Customer.CouponIssueResult>({
    url: `/admin/customers/${userId}/direct-coupons`,
    data
  })
}
