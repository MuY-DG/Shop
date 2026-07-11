import type { AddressResponse, AddressUpsertRequest } from "../types/api";
import { request } from "../utils/request";

export function getAddresses(): Promise<AddressResponse[]> {
  return request<AddressResponse[]>({
    url: "/app/addresses"
  });
}

export function getAddress(addressId: string): Promise<AddressResponse> {
  return request<AddressResponse>({
    url: `/app/addresses/${addressId}`
  });
}

export function createAddress(
  payload: AddressUpsertRequest
): Promise<AddressResponse> {
  return request<AddressResponse>({
    url: "/app/addresses",
    method: "POST",
    data: payload
  });
}

export function updateAddress(
  addressId: string,
  payload: AddressUpsertRequest
): Promise<AddressResponse> {
  return request<AddressResponse>({
    url: `/app/addresses/${addressId}`,
    method: "PUT",
    data: payload
  });
}

export function deleteAddress(addressId: string): Promise<void> {
  return request<void>({
    url: `/app/addresses/${addressId}`,
    method: "DELETE"
  });
}

export function setDefaultAddress(addressId: string): Promise<AddressResponse> {
  return request<AddressResponse>({
    url: `/app/addresses/${addressId}/default`,
    method: "POST"
  });
}
