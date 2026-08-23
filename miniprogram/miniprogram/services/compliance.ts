import { API_ENDPOINTS } from "../constants/api-endpoints";
import {
  buildMerchantPublicationView,
  normalizeLegalDocument,
  type MerchantPublicationView
} from "../features/compliance";
import type {
  LegalDocumentResponse,
  LegalDocumentType
} from "../types/compliance";
import { ApiError } from "../utils/api-error";
import { request } from "../utils/request";

function protocolError(message: string): ApiError {
  return new ApiError({ kind: "PROTOCOL", message });
}
export async function getCurrentLegalDocument(
  type: LegalDocumentType
): Promise<LegalDocumentResponse> {
  const value = await request<unknown>({
    url: API_ENDPOINTS.compliance.currentDocument(type),
    method: "GET",
    auth: false
  });
  const document = normalizeLegalDocument(value, type);
  if (!document) {
    throw protocolError("当前政策尚未发布或内容不完整");
  }
  return document;
}

export async function getCurrentMerchantPublication(): Promise<MerchantPublicationView> {
  const value = await request<unknown>({
    url: API_ENDPOINTS.compliance.merchant,
    method: "GET",
    auth: false
  });
  const publication = buildMerchantPublicationView(value);
  if (!publication) {
    throw protocolError("商家经营资质尚未发布");
  }
  return publication;
}
