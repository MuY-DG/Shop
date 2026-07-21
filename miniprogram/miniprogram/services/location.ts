import { API_ENDPOINTS } from "../constants/api-endpoints";
import type { AmapClientConfig } from "../types/location";
import { request } from "../utils/request";

export function getAmapClientConfig(): Promise<AmapClientConfig> {
  return request<AmapClientConfig>({
    url: API_ENDPOINTS.location.config,
    method: "GET"
  });
}
