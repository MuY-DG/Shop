import { API_ENDPOINTS } from "../constants/api-endpoints";
import type { HomeResponse } from "../types/home";
import { request } from "../utils/request";

export function getHome(): Promise<HomeResponse> {
  return request<HomeResponse>({
    url: API_ENDPOINTS.home,
    method: "GET",
    auth: false
  });
}
