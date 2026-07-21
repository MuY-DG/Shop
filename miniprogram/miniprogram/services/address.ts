import { API_ENDPOINTS } from "../constants/api-endpoints";
import type { AddressResponse, AddressUpsertRequest } from "../types/checkout";
import { request } from "../utils/request";

export function getAddresses(): Promise<AddressResponse[]> {
  return request<AddressResponse[]>({
    url: API_ENDPOINTS.addresses.list,
    method: "GET"
  });
}

export function createAddress(data: AddressUpsertRequest): Promise<AddressResponse> {
  return request<AddressResponse, AddressUpsertRequest>({
    url: API_ENDPOINTS.addresses.list,
    method: "POST",
    data
  });
}
