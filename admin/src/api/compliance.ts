import request from '@/utils/http'

export function fetchMerchantPublicationHistory() {
  return request.get<Api.Compliance.MerchantPublication[]>({
    url: '/admin/compliance/merchant'
  })
}

export function createMerchantPublicationDraft(data: Api.Compliance.MerchantPublicationDraft) {
  return request.post<Api.Compliance.MerchantPublication>({
    url: '/admin/compliance/merchant/drafts',
    data,
    showSuccessMessage: true
  })
}

export function publishMerchantPublication(id: Api.Compliance.Identifier) {
  return request.post<Api.Compliance.MerchantPublication>({
    url: `/admin/compliance/merchant/${id}/publish`,
    showSuccessMessage: true
  })
}

export function fetchLegalDocumentHistory(type: Api.Compliance.LegalDocumentType) {
  return request.get<Api.Compliance.LegalDocument[]>({
    url: `/admin/compliance/documents/${type}`
  })
}

export function createLegalDocumentDraft(
  type: Api.Compliance.LegalDocumentType,
  data: Api.Compliance.LegalDocumentDraft
) {
  return request.post<Api.Compliance.LegalDocument>({
    url: `/admin/compliance/documents/${type}/drafts`,
    data,
    showSuccessMessage: true
  })
}

export function publishLegalDocument(id: Api.Compliance.Identifier) {
  return request.post<Api.Compliance.LegalDocument>({
    url: `/admin/compliance/documents/${id}/publish`,
    showSuccessMessage: true
  })
}
