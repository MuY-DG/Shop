import request from '@/utils/http'
import { requirePersistedCustomerServiceMessageId } from '@/utils/customer-service-message'
import {
  uploadFileToCosPostWithSessionCancellation,
  type CosPostUploadProgress
} from '@/utils/cos-post-upload'
import { isHttpError, showError } from '@/utils/http/error'

const DIRECT_UPLOAD_UNAVAILABLE_HTTP_STATUSES = new Set([404, 405, 501])
const DIRECT_UPLOAD_UNAVAILABLE_CODE = 800009
const LEGACY_IMAGE_UPLOAD_TIMEOUT_MS = 180_000
const DIRECT_UPLOAD_COMPLETE_TIMEOUT_MS = 180_000
const DIRECT_UPLOAD_CANCEL_TIMEOUT_MS = 5_000
const DIRECT_UPLOAD_PROCESSING_RETRY_DELAYS_MS = [5_500, 10_500] as const
const DIRECT_UPLOAD_PROCESSING_FAILED_CODE = 800007

const customerServiceImageContentType = (file: File) => {
  if (file.type) return file.type.toLowerCase() === 'image/jpg' ? 'image/jpeg' : file.type
  const extension = file.name.split('.').pop()?.toLowerCase()
  return (
    {
      jpg: 'image/jpeg',
      jpeg: 'image/jpeg',
      png: 'image/png',
      webp: 'image/webp',
      gif: 'image/gif'
    }[extension || ''] || 'application/octet-stream'
  )
}

export function fetchCustomerServiceConversations(params: {
  status?: Api.CustomerService.ConversationStatus
  keyword?: string
  current?: number
  size?: number
}) {
  return request.get<Api.CustomerService.ConversationPage>({
    url: '/admin/customer-service/conversations',
    params
  })
}

export function fetchCustomerServiceWorkspace(keyword?: string) {
  return request.get<Api.CustomerService.ConversationWorkspace>({
    url: '/admin/customer-service/conversations/workspace',
    params: { keyword }
  })
}

export function fetchCustomerServiceConversation(conversationId: number) {
  return request.get<Api.CustomerService.ConversationDetail>({
    url: `/admin/customer-service/conversations/${conversationId}`
  })
}

export function fetchCustomerServiceMessages(
  conversationId: number,
  params: {
    afterId?: number
    beforeId?: number
    limit?: number
  }
) {
  return request.get<Api.CustomerService.Message[]>({
    url: `/admin/customer-service/conversations/${conversationId}/messages`,
    params
  })
}

export function claimCustomerServiceConversation(conversationId: number) {
  return request.post<Api.CustomerService.ConversationDetail>({
    url: `/admin/customer-service/conversations/${conversationId}/claim`
  })
}

export function requestCustomerServiceTransfer(
  conversationId: number,
  data: Api.CustomerService.TransferForm
) {
  return request.post<Api.CustomerService.TransferRequest>({
    url: `/admin/customer-service/conversations/${conversationId}/transfer-requests`,
    data
  })
}

export function fetchPendingCustomerServiceTransfers() {
  return request.get<Api.CustomerService.TransferRequest[]>({
    url: '/admin/customer-service/transfer-requests/pending'
  })
}

export function acceptCustomerServiceTransfer(requestId: number) {
  return request.post<Api.CustomerService.ConversationDetail>({
    url: `/admin/customer-service/transfer-requests/${requestId}/accept`
  })
}

export function rejectCustomerServiceTransfer(requestId: number) {
  return request.post<Api.CustomerService.TransferRequest>({
    url: `/admin/customer-service/transfer-requests/${requestId}/reject`
  })
}

export function releaseCustomerServiceConversation(conversationId: number) {
  return request.post<Api.CustomerService.ConversationDetail>({
    url: `/admin/customer-service/conversations/${conversationId}/release`
  })
}

export function forceTransferCustomerServiceConversation(
  conversationId: number,
  data: Api.CustomerService.TransferForm
) {
  return request.post<Api.CustomerService.ConversationDetail>({
    url: `/admin/customer-service/conversations/${conversationId}/force-transfer`,
    data
  })
}

export function closeCustomerServiceConversation(conversationId: number) {
  return request.post<Api.CustomerService.ConversationDetail>({
    url: `/admin/customer-service/conversations/${conversationId}/close`
  })
}

export function sendCustomerServiceMessage(
  conversationId: number,
  data: Api.CustomerService.SendMessageForm
) {
  return request.post<Api.CustomerService.Message>({
    url: `/admin/customer-service/conversations/${conversationId}/messages`,
    data,
    showErrorMessage: false
  })
}

export function fetchCustomerServiceAgents() {
  return request.get<Api.CustomerService.Agent[]>({
    url: '/admin/customer-service/agents'
  })
}

export function fetchCustomerServiceAgentState() {
  return request.get<Api.CustomerService.AgentState>({
    url: '/admin/customer-service/agent-state'
  })
}

export function fetchCustomerServiceAgentProfile() {
  return request.get<Api.CustomerService.AgentProfile>({
    url: '/admin/customer-service/profile'
  })
}

export function updateCustomerServiceAgentState(
  workStatus: Extract<Api.CustomerService.AgentWorkStatus, 'AVAILABLE' | 'OFFLINE'>
) {
  return request.put<Api.CustomerService.AgentState>({
    url: '/admin/customer-service/agent-state',
    data: { workStatus }
  })
}

export function fetchCustomerServiceOverview(params: Api.Operations.ReportQuery) {
  return request.get<Api.Operations.ServiceStatisticsReport>({
    url: '/admin/customer-service/overview',
    params
  })
}

export function fetchCustomerServiceOrderCandidates(conversationId: number) {
  return request.get<Api.CustomerService.LinkedOrder[]>({
    url: `/admin/customer-service/conversations/${conversationId}/order-candidates`
  })
}

export function linkCustomerServiceOrder(conversationId: number, orderId: number) {
  return request.post<Api.CustomerService.LinkedOrder>({
    url: `/admin/customer-service/conversations/${conversationId}/orders/${orderId}`
  })
}

export function fetchCustomerServiceProductCandidates(conversationId: number, keyword?: string) {
  return request.get<Api.CustomerService.LinkedProduct[]>({
    url: `/admin/customer-service/conversations/${conversationId}/product-candidates`,
    params: { keyword }
  })
}

export function linkCustomerServiceProduct(conversationId: number, productId: number) {
  return request.post<Api.CustomerService.LinkedProduct>({
    url: `/admin/customer-service/conversations/${conversationId}/products/${productId}`
  })
}

export async function uploadCustomerServiceImage(
  conversationId: number,
  file: File,
  options: {
    signal?: AbortSignal
    onProgress?: (progress: CosPostUploadProgress) => void
  } = {}
) {
  let session: Api.Storage.AssetUploadSession
  try {
    session = await request.post<Api.Storage.AssetUploadSession>({
      url: `/admin/customer-service/conversations/${conversationId}/images/upload-sessions`,
      data: {
        originalFilename: file.name,
        contentType: customerServiceImageContentType(file),
        sizeBytes: file.size
      },
      showErrorMessage: false
    })
  } catch (error) {
    if (
      isHttpError(error) &&
      (error.code === DIRECT_UPLOAD_UNAVAILABLE_CODE ||
        (error.httpStatus !== undefined &&
          DIRECT_UPLOAD_UNAVAILABLE_HTTP_STATUSES.has(error.httpStatus)))
    ) {
      const formData = new FormData()
      formData.append('file', file)
      return request.post<Api.CustomerService.Message>({
        url: `/admin/customer-service/conversations/${conversationId}/images`,
        data: formData,
        signal: options.signal,
        timeout: LEGACY_IMAGE_UPLOAD_TIMEOUT_MS,
        onUploadProgress: (event) => {
          const total = event.total || file.size
          options.onProgress?.({
            loaded: Math.min(event.loaded, total),
            total,
            percent: total > 0 ? Math.min(100, Math.round((event.loaded / total) * 100)) : 0
          })
        }
      })
    }
    if (isHttpError(error)) showError(error)
    throw error
  }
  await uploadFileToCosPostWithSessionCancellation(
    { uploadUrl: session.uploadUrl, formData: session.formData },
    file,
    () =>
      request.del<void>({
        url: `/admin/customer-service/conversations/${conversationId}/images/upload-sessions/${encodeURIComponent(session.uploadId)}`,
        timeout: DIRECT_UPLOAD_CANCEL_TIMEOUT_MS,
        showErrorMessage: false
      }),
    options
  )
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await request.post<Api.CustomerService.Message>({
        url: `/admin/customer-service/conversations/${conversationId}/images/upload-sessions/${encodeURIComponent(session.uploadId)}/complete`,
        timeout: DIRECT_UPLOAD_COMPLETE_TIMEOUT_MS,
        showErrorMessage: false
      })
    } catch (error) {
      const retryDelay = DIRECT_UPLOAD_PROCESSING_RETRY_DELAYS_MS[attempt]
      if (
        !isHttpError(error) ||
        error.code !== DIRECT_UPLOAD_PROCESSING_FAILED_CODE ||
        retryDelay === undefined
      ) {
        if (isHttpError(error)) showError(error)
        throw error
      }
      await new Promise((resolve) => setTimeout(resolve, retryDelay))
    }
  }
}

export function fetchCustomerServiceImage(messageId: number) {
  const persistedMessageId = requirePersistedCustomerServiceMessageId(messageId)
  return request.get<Blob>({
    url: `/admin/customer-service/messages/${persistedMessageId}/image`,
    responseType: 'blob',
    showErrorMessage: false
  })
}

export function fetchCustomerServiceThumbnail(messageId: number) {
  const persistedMessageId = requirePersistedCustomerServiceMessageId(messageId)
  return request.get<Blob>({
    url: `/admin/customer-service/messages/${persistedMessageId}/thumbnail`,
    responseType: 'blob',
    showErrorMessage: false
  })
}

export function fetchCustomerServiceImageAccess(messageId: number) {
  const persistedMessageId = requirePersistedCustomerServiceMessageId(messageId)
  return request.get<Api.CustomerService.ImageMessage>({
    url: `/admin/customer-service/messages/${persistedMessageId}/image-access`,
    showErrorMessage: false
  })
}

export function issueAdminRealtimeTicket() {
  return request.post<Api.Realtime.Ticket>({
    url: '/admin/realtime/tickets'
  })
}

export function fetchCustomerServiceManagedUsers(keyword?: string) {
  return request.get<Api.CustomerService.ManagedUser[]>({
    url: '/admin/customer-service/management/users',
    params: { keyword }
  })
}

export function updateCustomerServiceManagedUser(
  adminUserId: string,
  data: Api.CustomerService.ManagedUserForm
) {
  return request.put<Api.CustomerService.ManagedUser>({
    url: `/admin/customer-service/management/users/${adminUserId}`,
    data
  })
}

export function fetchCustomerServiceManagementConfig() {
  return request.get<Api.CustomerService.ManagementConfig>({
    url: '/admin/customer-service/management/config'
  })
}

export function updateCustomerServiceManagementConfig(
  data: Api.CustomerService.ManagementConfigForm
) {
  return request.put<Api.CustomerService.ManagementConfig>({
    url: '/admin/customer-service/management/config',
    data
  })
}
