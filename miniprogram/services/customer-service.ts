import type {
  ApiResponse,
  CustomerServiceConversation,
  CustomerServiceLinkedOrder,
  CustomerServiceLinkedProduct,
  CustomerServiceMessage,
  RealtimeTicket
} from "../types/api";
import type { RawHttpResult } from "../utils/http";
import { request, withAuthRecovery } from "../utils/request";
import { sessionManager } from "./session";

const SUCCESS_CODE = 200;

export type CustomerServiceContextType = "GENERAL" | "ORDER" | "PRODUCT";

export interface OpenCustomerServiceOptions {
  contextType?: CustomerServiceContextType;
  contextId?: number;
}

function getApiBaseUrl(): string {
  return getApp<{ globalData: { apiBaseUrl: string } }>().globalData.apiBaseUrl;
}

function parseMessageEnvelope(rawData: unknown): ApiResponse<CustomerServiceMessage> | null {
  if (typeof rawData !== "string") {
    return null;
  }
  try {
    const parsed = JSON.parse(rawData) as Partial<ApiResponse<CustomerServiceMessage>>;
    if (!parsed || typeof parsed.code !== "number" || typeof parsed.msg !== "string") {
      return null;
    }
    return {
      code: parsed.code,
      msg: parsed.msg,
      data: ("data" in parsed ? parsed.data : null) as CustomerServiceMessage
    };
  } catch {
    return null;
  }
}

export function getCustomerServiceConversation(): Promise<CustomerServiceConversation | null> {
  return request<CustomerServiceConversation | null>({
    url: "/app/customer-service/conversation"
  });
}

export function openCustomerServiceConversation(
  options: OpenCustomerServiceOptions = {}
): Promise<CustomerServiceConversation> {
  return request<CustomerServiceConversation>({
    url: "/app/customer-service/conversation/open",
    method: "POST",
    data:
      options.contextType && options.contextType !== "GENERAL" && options.contextId
        ? { contextType: options.contextType, contextId: options.contextId }
        : { contextType: "GENERAL" }
  });
}

export function sendCustomerServiceMessage(payload: {
  content: string;
  clientMessageId: string;
}): Promise<CustomerServiceMessage> {
  return request<CustomerServiceMessage>({
    url: "/app/customer-service/conversation/messages",
    method: "POST",
    data: payload
  });
}

export function linkCustomerServiceOrder(orderId: number): Promise<CustomerServiceLinkedOrder> {
  return request<CustomerServiceLinkedOrder>({
    url: `/app/customer-service/conversation/orders/${orderId}`,
    method: "POST"
  });
}

export function getCustomerServiceOrderCandidates(): Promise<CustomerServiceLinkedOrder[]> {
  return request<CustomerServiceLinkedOrder[]>({
    url: "/app/customer-service/conversation/order-candidates"
  });
}

export function linkCustomerServiceProduct(
  productId: number
): Promise<CustomerServiceLinkedProduct> {
  return request<CustomerServiceLinkedProduct>({
    url: `/app/customer-service/conversation/products/${productId}`,
    method: "POST"
  });
}

export function getCustomerServiceProductCandidates(): Promise<CustomerServiceLinkedProduct[]> {
  return request<CustomerServiceLinkedProduct[]>({
    url: "/app/customer-service/conversation/product-candidates"
  });
}

function rawUploadCustomerServiceImage(
  filePath: string,
  authToken: string | null
): Promise<RawHttpResult<CustomerServiceMessage>> {
  const authTokenUsed = authToken || null;
  const header: Record<string, string> = {};
  if (authTokenUsed) {
    header.Authorization = `Bearer ${authTokenUsed}`;
  }
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${getApiBaseUrl()}/app/customer-service/conversation/images`,
      filePath,
      name: "file",
      header,
      success: (response) => resolve({
        statusCode: response.statusCode,
        body: parseMessageEnvelope(response.data),
        authTokenUsed
      }),
      fail: (error) => reject(new Error(error.errMsg || "图片发送失败"))
    });
  });
}

export async function uploadCustomerServiceImage(
  filePath: string
): Promise<CustomerServiceMessage> {
  const result = await withAuthRecovery(
    (authToken) => rawUploadCustomerServiceImage(filePath, authToken),
    {},
    sessionManager
  );
  if (
    result.statusCode < 200 ||
    result.statusCode >= 300 ||
    result.body?.code !== SUCCESS_CODE ||
    !result.body.data
  ) {
    throw new Error(result.body?.msg || "图片发送失败");
  }
  return result.body.data;
}

function rawDownloadCustomerServiceImage(
  messageId: number,
  authToken: string | null
): Promise<RawHttpResult<string>> {
  const authTokenUsed = authToken || null;
  const header: Record<string, string> = {};
  if (authTokenUsed) {
    header.Authorization = `Bearer ${authTokenUsed}`;
  }
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url: `${getApiBaseUrl()}/app/customer-service/conversation/messages/${messageId}/image`,
      header,
      success: (response) => resolve({
        statusCode: response.statusCode,
        body:
          response.statusCode >= 200 && response.statusCode < 300
            ? { code: SUCCESS_CODE, msg: "success", data: response.tempFilePath }
            : null,
        authTokenUsed
      }),
      fail: (error) => reject(new Error(error.errMsg || "图片加载失败"))
    });
  });
}

export async function downloadCustomerServiceImage(messageId: number): Promise<string> {
  const result = await withAuthRecovery(
    (authToken) => rawDownloadCustomerServiceImage(messageId, authToken),
    {},
    sessionManager
  );
  if (
    result.statusCode < 200 ||
    result.statusCode >= 300 ||
    result.body?.code !== SUCCESS_CODE ||
    !result.body.data
  ) {
    throw new Error(result.body?.msg || "图片加载失败");
  }
  return result.body.data;
}

export function issueAppRealtimeTicket(): Promise<RealtimeTicket> {
  return request<RealtimeTicket>({
    url: "/app/realtime/tickets",
    method: "POST"
  });
}
