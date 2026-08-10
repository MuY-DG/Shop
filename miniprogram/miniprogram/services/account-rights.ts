import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AccountRightsDetailResponse,
  AccountRightsRequestResponse,
  AccountRightsSubmitRequest,
  AccountRightsWithdrawRequest
} from "../types/account-rights";
import { request } from "../utils/request";

export function getAccountRightsRequests(): Promise<AccountRightsRequestResponse[]> {
  return request<AccountRightsRequestResponse[]>({
    url: API_ENDPOINTS.accountRights.list,
    method: "GET"
  });
}

export function getAccountRightsRequestDetail(
  requestId: string
): Promise<AccountRightsDetailResponse> {
  return request<AccountRightsDetailResponse>({
    url: API_ENDPOINTS.accountRights.detail(requestId),
    method: "GET"
  });
}

export function submitAccountRightsRequest(
  data: AccountRightsSubmitRequest
): Promise<AccountRightsRequestResponse> {
  return request<AccountRightsRequestResponse, AccountRightsSubmitRequest>({
    url: API_ENDPOINTS.accountRights.list,
    method: "POST",
    data
  });
}

export function withdrawAccountRightsRequest(
  requestId: string,
  data: AccountRightsWithdrawRequest
): Promise<AccountRightsRequestResponse> {
  return request<AccountRightsRequestResponse, AccountRightsWithdrawRequest>({
    url: API_ENDPOINTS.accountRights.withdraw(requestId),
    method: "POST",
    data
  });
}
