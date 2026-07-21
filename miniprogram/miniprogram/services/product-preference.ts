import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  ProductBrowseHistoryPage,
  ProductBrowseRecord,
  ProductFavoritePage,
  ProductFavoriteStatus
} from "../types/product-engagement";
import { request } from "../utils/request";

export function getFavoriteStatus(spuId: number): Promise<ProductFavoriteStatus> {
  return request<ProductFavoriteStatus>({
    url: API_ENDPOINTS.userProduct.favorite(spuId),
    method: "GET"
  });
}

export function addFavorite(spuId: number): Promise<ProductFavoriteStatus> {
  return request<ProductFavoriteStatus>({
    url: API_ENDPOINTS.userProduct.favorite(spuId),
    method: "PUT"
  });
}

export function removeFavorite(spuId: number): Promise<void> {
  return request<void>({
    url: API_ENDPOINTS.userProduct.favorite(spuId),
    method: "DELETE",
    expectData: false
  });
}

export function getFavorites(current = 1, size = 10): Promise<ProductFavoritePage> {
  return request<ProductFavoritePage>({
    url: API_ENDPOINTS.userProduct.favorites,
    method: "GET",
    data: { current, size }
  });
}

export function recordProductBrowse(spuId: number): Promise<ProductBrowseRecord> {
  return request<ProductBrowseRecord>({
    url: API_ENDPOINTS.userProduct.browseRecord(spuId),
    method: "POST"
  });
}

export function getBrowseHistory(
  current = 1,
  size = 10
): Promise<ProductBrowseHistoryPage> {
  return request<ProductBrowseHistoryPage>({
    url: API_ENDPOINTS.userProduct.browseHistory,
    method: "GET",
    data: { current, size }
  });
}

export function deleteBrowseHistoryItem(spuId: number): Promise<void> {
  return request<void>({
    url: API_ENDPOINTS.userProduct.browseRecord(spuId),
    method: "DELETE",
    expectData: false
  });
}

export function clearBrowseHistory(): Promise<void> {
  return request<void>({
    url: API_ENDPOINTS.userProduct.browseHistory,
    method: "DELETE",
    expectData: false
  });
}
