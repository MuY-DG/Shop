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

export function getAddress(addressId: string): Promise<AddressResponse> {
  return request<AddressResponse>({
    url: API_ENDPOINTS.addresses.item(addressId),
    method: "GET"
  });
}

export function updateAddress(
  addressId: string,
  data: AddressUpsertRequest
): Promise<AddressResponse> {
  return request<AddressResponse, AddressUpsertRequest>({
    url: API_ENDPOINTS.addresses.item(addressId),
    method: "PUT",
    data
  });
}

export function deleteAddress(addressId: string): Promise<void> {
  return request<void>({
    url: API_ENDPOINTS.addresses.item(addressId),
    method: "DELETE",
    expectData: false
  });
}

export function setDefaultAddress(addressId: string): Promise<AddressResponse> {
  return request<AddressResponse>({
    url: API_ENDPOINTS.addresses.setDefault(addressId),
    method: "POST"
  });
}
