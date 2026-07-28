import request from '@/utils/http'

export function fetchAfterSales(params: Api.AfterSale.SearchParams) {
  return request.get<Api.AfterSale.List>({
    url: '/admin/after-sales',
    params
  })
}

export function fetchAfterSaleStatusCounts(params: Api.AfterSale.SearchParams) {
  return request.get<Api.AfterSale.StatusCounts>({
    url: '/admin/after-sales/status-counts',
    params
  })
}

export function fetchAfterSaleDetail(afterSaleId: number) {
  return request.get<Api.AfterSale.Detail>({
    url: `/admin/after-sales/${afterSaleId}`
  })
}

export function fetchAfterSaleRecords(afterSaleId: number) {
  return request.get<Api.AfterSale.Record[]>({
    url: `/admin/after-sales/${afterSaleId}/records`
  })
}

export function fetchAfterSaleEvidence(afterSaleId: number, fileId: number) {
  return request.get<Blob>({
    url: `/admin/after-sales/${afterSaleId}/evidence/${fileId}`,
    responseType: 'blob'
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

export function retryClosedRefund(afterSaleId: number, data: Api.AfterSale.RefundOperationPayload) {
  return request.post<Api.AfterSale.Item>({
    url: `/admin/after-sales/${afterSaleId}/refund-retry`,
    data,
    showSuccessMessage: true
  })
}

export function queryRefundProvider(
  afterSaleId: number,
  refundOrderId: number | string,
  data: Api.AfterSale.RefundOperationPayload
) {
  return request.post<Api.AfterSale.RefundOperationResponse>({
    url: `/admin/after-sales/${afterSaleId}/refunds/${refundOrderId}/provider-query`,
    data,
    showSuccessMessage: true
  })
}

export function resubmitRefundProvider(
  afterSaleId: number,
  refundOrderId: number | string,
  data: Api.AfterSale.RefundOperationPayload
) {
  return request.post<Api.AfterSale.RefundOperationResponse>({
    url: `/admin/after-sales/${afterSaleId}/refunds/${refundOrderId}/provider-resubmit`,
    data,
    showSuccessMessage: true
  })
}

export function markRefundManualIntervention(
  afterSaleId: number,
  refundOrderId: number | string,
  data: Api.AfterSale.RefundOperationPayload
) {
  return request.post<Api.AfterSale.RefundOperationResponse>({
    url: `/admin/after-sales/${afterSaleId}/refunds/${refundOrderId}/manual-intervention`,
    data,
    showSuccessMessage: true
  })
}
