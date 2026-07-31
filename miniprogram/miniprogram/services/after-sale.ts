import { API_ENDPOINTS } from "../constants/api-endpoints";
import type {
  AfterSaleApplyRequest,
  AfterSalePage,
  AfterSaleResponse,
  AfterSaleStatus,
  StorageAssetUploadResponse
} from "../types/after-sale";
import { request } from "../utils/request";
import { uploadFileDirect } from "../utils/direct-upload";
import { uploadFile } from "../utils/upload";

export function getAfterSales(
  current = 1,
  size = 10,
  status?: AfterSaleStatus
): Promise<AfterSalePage> {
  return request<AfterSalePage>({
    url: API_ENDPOINTS.afterSales.list,
    method: "GET",
    data: { current, size, ...(status ? { status } : {}) }
  });
}

export function getAfterSaleDetail(afterSaleId: number): Promise<AfterSaleResponse> {
  return request<AfterSaleResponse>({
    url: API_ENDPOINTS.afterSales.detail(afterSaleId),
    method: "GET"
  });
}

export function getOrderAfterSales(orderId: number): Promise<AfterSaleResponse[]> {
  return request<AfterSaleResponse[]>({
    url: API_ENDPOINTS.afterSales.forOrder(orderId),
    method: "GET"
  });
}

export function applyAfterSale(
  orderId: number,
  data: AfterSaleApplyRequest
): Promise<AfterSaleResponse> {
  return request<AfterSaleResponse, AfterSaleApplyRequest>({
    url: API_ENDPOINTS.afterSales.forOrder(orderId),
    method: "POST",
    data
  });
}

export function uploadAfterSaleEvidence(
  orderId: number,
  filePath: string
): Promise<StorageAssetUploadResponse> {
  return uploadFileDirect<StorageAssetUploadResponse>({
    initUrl: API_ENDPOINTS.afterSales.evidenceUploads(orderId),
    filePath,
    timeoutMs: 60_000,
    legacyFallback: () => uploadFile<StorageAssetUploadResponse>({
      url: API_ENDPOINTS.afterSales.evidence(orderId),
      filePath,
      name: "file",
      timeoutMs: 30_000
    })
  });
}
