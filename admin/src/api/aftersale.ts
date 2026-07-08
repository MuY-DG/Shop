import request from '@/utils/http'

export function fetchAfterSales(params: Api.AfterSale.SearchParams) {
  return request.get<Api.AfterSale.List>({
    url: '/admin/after-sales',
    params
  })
}

export function fetchAfterSaleDetail(afterSaleId: number) {
  return request.get<Api.AfterSale.Item>({
    url: `/admin/after-sales/${afterSaleId}`
  })
}

export function approveAfterSale(afterSaleId: number, data: Api.AfterSale.AuditPayload) {
  return request.post<Api.AfterSale.Item>({
    url: `/admin/after-sales/${afterSaleId}/approve`,
    data,
    showSuccessMessage: true
  })
}

export function rejectAfterSale(afterSaleId: number, data: Api.AfterSale.AuditPayload) {
  return request.post<Api.AfterSale.Item>({
    url: `/admin/after-sales/${afterSaleId}/reject`,
    data,
    showSuccessMessage: true
  })
}
