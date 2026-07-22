import { API_ENDPOINTS } from "../constants/api-endpoints";
import type { ContactResponse } from "../types/contact";
import { request } from "../utils/request";

export function getPublicContact(): Promise<ContactResponse> {
  return request<ContactResponse>({
    url: API_ENDPOINTS.contact,
    method: "GET",
    auth: false
  });
}
