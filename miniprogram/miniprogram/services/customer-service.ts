import { API_ENDPOINTS } from "../constants/api-endpoints";
import { isPersistedCustomerServiceMessageId } from "../features/customer-service";
import type {
  CustomerServiceConversation,
  CustomerServiceCommonQuestion,
  CustomerServiceImage,
  CustomerServiceMessage,
  CustomerServiceOpenRequest,
  CustomerServiceOrder,
  CustomerServiceProduct,
  CustomerServiceRealtimeTicket,
  CustomerServiceSendMessageRequest
} from "../types/customer-service";
import {
  downloadAuthenticatedFile,
  downloadExternalFile
} from "../utils/authenticated-download";
import { uploadFileDirect } from "../utils/direct-upload";
import { loadCachedImageFile } from "../utils/image-file-cache";
import { request } from "../utils/request";
import { uploadFile } from "../utils/upload";

function requirePersistedMessageId(messageId: number): number {
  if (!isPersistedCustomerServiceMessageId(messageId)) {
    throw new RangeError("Customer-service image requests require a persisted message id");
  }
  return messageId;
}

export function openCustomerServiceConversation(
  data: CustomerServiceOpenRequest
): Promise<CustomerServiceConversation> {
  return request<CustomerServiceConversation, CustomerServiceOpenRequest>({
    url: API_ENDPOINTS.customerService.open,
    method: "POST",
    data
  });
}

export function getCustomerServiceConversation(): Promise<CustomerServiceConversation | null> {
  return request<CustomerServiceConversation | null>({
    url: API_ENDPOINTS.customerService.conversation,
    method: "GET"
  });
}

export function getCustomerServiceCommonQuestions(): Promise<CustomerServiceCommonQuestion[]> {
  return request<CustomerServiceCommonQuestion[]>({
    url: API_ENDPOINTS.customerService.commonQuestions,
    method: "GET"
  });
}

export function getCustomerServiceMessages(
  afterId?: number
): Promise<CustomerServiceMessage[]> {
  return request<CustomerServiceMessage[]>({
    url: API_ENDPOINTS.customerService.messages,
    method: "GET",
    data: afterId ? { afterId } : undefined
  });
}

export function sendCustomerServiceMessage(
  data: CustomerServiceSendMessageRequest
): Promise<CustomerServiceMessage> {
  return request<CustomerServiceMessage, CustomerServiceSendMessageRequest>({
    url: API_ENDPOINTS.customerService.messages,
    method: "POST",
    data
  });
}

export function uploadCustomerServiceImage(
  filePath: string
): Promise<CustomerServiceMessage> {
  return uploadFileDirect<CustomerServiceMessage>({
    initUrl: API_ENDPOINTS.customerService.imageUploads,
    filePath,
    timeoutMs: 60_000,
    legacyFallback: () => uploadFile<CustomerServiceMessage>({
      url: API_ENDPOINTS.customerService.images,
      filePath
    })
  });
}

export function getCustomerServiceOrderCandidates(): Promise<CustomerServiceOrder[]> {
  return request<CustomerServiceOrder[]>({
    url: API_ENDPOINTS.customerService.orderCandidates,
    method: "GET"
  });
}

export function sendCustomerServiceOrder(orderId: number): Promise<CustomerServiceOrder> {
  return request<CustomerServiceOrder>({
    url: API_ENDPOINTS.customerService.order(orderId),
    method: "POST"
  });
}
export function getCustomerServiceProductCandidates(
  keyword?: string
): Promise<CustomerServiceProduct[]> {
  return request<CustomerServiceProduct[]>({
    url: API_ENDPOINTS.customerService.productCandidates,
    method: "GET",
    data: keyword?.trim() ? { keyword: keyword.trim() } : undefined
  });
}

export function sendCustomerServiceProduct(
  productId: number
): Promise<CustomerServiceProduct> {
  return request<CustomerServiceProduct>({
    url: API_ENDPOINTS.customerService.product(productId),
    method: "POST"
  });
}

export function downloadCustomerServiceImage(
  message: Pick<CustomerServiceMessage, "messageId" | "image">
): Promise<string> {
  const messageId = requirePersistedMessageId(message.messageId);
  if (message.image?.thumbnailStatus === "READY") {
    return loadCachedImageFile(
      `customer-service:${messageId}:thumbnail`,
      async () => {
        if (
          message.image?.thumbnailAccessMode === "SIGNED_URL" &&
          message.image.thumbnailAccessUrl
        ) {
          try {
            return await downloadExternalFile(message.image.thumbnailAccessUrl);
          } catch {
            const refreshed = await refreshCustomerServiceImageAccess(messageId)
              .catch(() => null);
            if (
              refreshed?.thumbnailAccessMode === "SIGNED_URL" &&
              refreshed.thumbnailAccessUrl
            ) {
              try {
                return await downloadExternalFile(refreshed.thumbnailAccessUrl);
              } catch {
                // 临时 URL 续签后仍失败，最后使用鉴权流作为兼容兜底。
              }
            }
          }
        }
        return downloadAuthenticatedFile(
          API_ENDPOINTS.customerService.thumbnail(messageId)
        );
      }
    );
  }
  if (
    message.image?.accessMode === "SIGNED_URL" &&
    message.image.accessUrl
  ) {
    return downloadExternalFile(message.image.accessUrl)
      .catch(() => downloadAuthenticatedFile(
        API_ENDPOINTS.customerService.image(messageId)
      ));
  }
  return downloadAuthenticatedFile(API_ENDPOINTS.customerService.image(messageId));
}

export function downloadCustomerServiceOriginalImage(
  message: Pick<CustomerServiceMessage, "messageId" | "image">
): Promise<string> {
  const messageId = requirePersistedMessageId(message.messageId);
  if (
    message.image?.accessMode === "SIGNED_URL" &&
    message.image.accessUrl
  ) {
    return downloadExternalFile(message.image.accessUrl)
      .catch(async () => {
        const refreshed = await refreshCustomerServiceImageAccess(messageId)
          .catch(() => null);
        if (refreshed?.accessMode === "SIGNED_URL" && refreshed.accessUrl) {
          try {
            return await downloadExternalFile(refreshed.accessUrl);
          } catch {
            // A single renewal failed to load; use the authenticated stream.
          }
        }
        return downloadAuthenticatedFile(
          API_ENDPOINTS.customerService.image(messageId)
        );
      });
  }
  return downloadAuthenticatedFile(API_ENDPOINTS.customerService.image(messageId));
}

export function refreshCustomerServiceImageAccess(
  messageId: number
): Promise<CustomerServiceImage> {
  const persistedMessageId = requirePersistedMessageId(messageId);
  return request<CustomerServiceImage>({
    url: API_ENDPOINTS.customerService.imageAccess(persistedMessageId),
    method: "GET"
  });
}

export function issueCustomerServiceRealtimeTicket(): Promise<CustomerServiceRealtimeTicket> {
  return request<CustomerServiceRealtimeTicket>({
    url: API_ENDPOINTS.realtime.ticket,
    method: "POST"
  });
}
