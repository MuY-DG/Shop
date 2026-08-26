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
): Promise<LegalDocumentResponse | null> {
  const value = await request<unknown>({
    url: API_ENDPOINTS.compliance.currentDocument(type),
    method: "GET",
    auth: false,
    // 全新环境没有已发布内容时，后端会返回成功响应并省略 data。
    expectData: false
  });
  if (value === undefined || value === null) {
    return null;
  }
  const document = normalizeLegalDocument(value, type);
  if (!document) {
    throw protocolError("当前政策响应内容不完整");
  }
  return document;
}

export async function getCurrentMerchantPublication(): Promise<MerchantPublicationView | null> {
  const value = await request<unknown>({
    url: API_ENDPOINTS.compliance.merchant,
    method: "GET",
    auth: false,
    // 未发布商家资料是正常的空配置状态，不应被公共请求层判为协议错误。
    expectData: false
  });
  if (value === undefined || value === null) {
    return null;
  }
  const publication = buildMerchantPublicationView(value);
  if (!publication) {
    throw protocolError("商家经营资质响应内容不完整");
  }
  return publication;
}
