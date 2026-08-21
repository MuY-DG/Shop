import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AccountCancellationEligibilityResponse,
  AccountCancellationRequest,
  AccountCancellationResponse
} from "../types/account-cancellation";
import { request } from "../utils/request";

export function getAccountCancellationEligibility(): Promise<AccountCancellationEligibilityResponse> {
  return request<AccountCancellationEligibilityResponse>({
    url: API_ENDPOINTS.accountCancellation.eligibility,
    method: "GET"
  });
}

export function cancelAccount(
  data: AccountCancellationRequest
): Promise<AccountCancellationResponse> {
  return request<AccountCancellationResponse, AccountCancellationRequest>({
    url: API_ENDPOINTS.accountCancellation.cancel,
    method: "POST",
    data,
    recoverAuth: false
  });
}
