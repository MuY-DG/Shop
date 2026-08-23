import request from '@/utils/http'

const baseUrl = '/admin/finance/reconciliation'

export function fetchReconciliationRuntime() {
  return request.get<Api.FinanceReconciliation.RuntimeStatus>({
    url: `${baseUrl}/runtime`
  })
}

export function updateReconciliationRuntime(data: Api.FinanceReconciliation.RuntimeUpdate) {
  return request.put<Api.FinanceReconciliation.RuntimeStatus>({
    url: `${baseUrl}/runtime`,
    data,
    showSuccessMessage: true
  })
}

export function fetchReconciliationBatches(params: Api.FinanceReconciliation.BatchSearchParams) {
  return request.get<Api.FinanceReconciliation.BatchList>({
    url: `${baseUrl}/batches`,
    params
  })
}

export function fetchReconciliationBatch(batchId: Api.FinanceReconciliation.Identifier) {
  return request.get<Api.FinanceReconciliation.BatchDetail>({
    url: `${baseUrl}/batches/${batchId}`
  })
}

export function fetchReconciliationEntries(
  batchId: Api.FinanceReconciliation.Identifier,
  params: Api.FinanceReconciliation.EntrySearchParams
) {
  return request.get<Api.FinanceReconciliation.EntryList>({
    url: `${baseUrl}/batches/${batchId}/entries`,
    params
  })
}

export function fetchReconciliationDifferences(
  batchId: Api.FinanceReconciliation.Identifier,
  params: Api.FinanceReconciliation.DifferenceSearchParams
) {
  return request.get<Api.FinanceReconciliation.DifferenceList>({
    url: `${baseUrl}/batches/${batchId}/differences`,
    params
  })
}

export function fetchReconciliationDifferenceAudits(
  differenceId: Api.FinanceReconciliation.Identifier
) {
  return request.get<Api.FinanceReconciliation.ResolutionAudit[]>({
    url: `${baseUrl}/differences/${differenceId}/audits`
  })
}

export function runReconciliation(data: Api.FinanceReconciliation.RunForm) {
  return request.post<Api.FinanceReconciliation.Batch[]>({
    url: `${baseUrl}/runs`,
    data,
    showSuccessMessage: true
  })
}

export function retryReconciliationBatch(
  batchId: Api.FinanceReconciliation.Identifier,
  data: Api.FinanceReconciliation.RetryForm
) {
  return request.post<Api.FinanceReconciliation.Batch>({
    url: `${baseUrl}/batches/${batchId}/retry`,
    data,
    showSuccessMessage: true
  })
}

export function investigateReconciliationDifference(
  differenceId: Api.FinanceReconciliation.Identifier,
  data: Api.FinanceReconciliation.InvestigateForm
) {
  return request.post<Api.FinanceReconciliation.Difference>({
    url: `${baseUrl}/differences/${differenceId}/investigate`,
    data,
    showSuccessMessage: true
  })
}

export function resolveReconciliationDifference(
  differenceId: Api.FinanceReconciliation.Identifier,
  data: Api.FinanceReconciliation.ResolveForm
) {
  return request.post<Api.FinanceReconciliation.Difference>({
    url: `${baseUrl}/differences/${differenceId}/resolve`,
    data,
    showSuccessMessage: true
  })
}

export function applyExternalRefundDifference(
  differenceId: Api.FinanceReconciliation.Identifier,
  data: Api.FinanceReconciliation.ExternalRefundApplyForm
) {
  return request.post<Api.FinanceReconciliation.Difference>({
    url: `${baseUrl}/differences/${differenceId}/external-refund`,
    data,
    showSuccessMessage: true
  })
}

export function downloadReconciliationSource(batchId: Api.FinanceReconciliation.Identifier) {
  return request.get<Blob>({
    url: `${baseUrl}/batches/${batchId}/source`,
    responseType: 'blob'
  })
}

export function downloadReconciliationCandidateSource(
  differenceId: Api.FinanceReconciliation.Identifier
) {
  return request.get<Blob>({
    url: `${baseUrl}/differences/${differenceId}/candidate-source`,
    responseType: 'blob'
  })
}

export function exportReconciliationCsv(params: Api.FinanceReconciliation.ExportParams) {
  return request.get<Blob>({
    url: `${baseUrl}/export.csv`,
    params,
    responseType: 'blob'
  })
}
