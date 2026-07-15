import request from '@/utils/http'

export function fetchCustomerServiceConversations(params: {
  status?: Api.CustomerService.ConversationStatus
  current?: number
  size?: number
}) {
  return request.get<Api.CustomerService.ConversationPage>({
    url: '/admin/customer-service/conversations',
    params
  })
}

export function fetchCustomerServiceConversation(conversationId: number) {
  return request.get<Api.CustomerService.ConversationDetail>({
    url: `/admin/customer-service/conversations/${conversationId}`
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
    data
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

export function updateCustomerServiceAgentState(
  workStatus: Exclude<Api.CustomerService.AgentWorkStatus, 'OFFLINE'>
) {
  return request.put<Api.CustomerService.AgentState>({
    url: '/admin/customer-service/agent-state',
    data: { workStatus }
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

export function uploadCustomerServiceImage(conversationId: number, file: File) {
  const data = new FormData()
  data.append('file', file)
  return request.post<Api.CustomerService.Message>({
    url: `/admin/customer-service/conversations/${conversationId}/images`,
    data
  })
}

export function fetchCustomerServiceImage(messageId: number) {
  return request.get<Blob>({
    url: `/admin/customer-service/messages/${messageId}/image`,
    responseType: 'blob',
    showErrorMessage: false
  })
}

export function issueAdminRealtimeTicket() {
  return request.post<Api.Realtime.Ticket>({
    url: '/admin/realtime/tickets'
  })
}
