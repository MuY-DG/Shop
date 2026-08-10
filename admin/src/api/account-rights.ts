import request from '@/utils/http'

export function fetchAccountRightsRequests(params: Api.AccountRights.SearchParams) {
  return request.get<Api.AccountRights.RequestList>({
    url: '/admin/account-rights/requests',
    params
  })
}

export function fetchAccountRightsRequestDetail(requestId: Api.AccountRights.Identifier) {
  return request.get<Api.AccountRights.RequestDetail>({
    url: `/admin/account-rights/requests/${requestId}`
  })
}

export function transitionAccountRightsRequest(
  requestId: Api.AccountRights.Identifier,
  action: Api.AccountRights.AdminAction,
  data: Api.AccountRights.ActionForm
) {
  return request.post<Api.AccountRights.RequestItem>({
    url: `/admin/account-rights/requests/${requestId}/${action}`,
    data,
    showSuccessMessage: true
  })
}
