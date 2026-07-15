declare namespace Api {
  namespace CustomerService {
    type ConversationStatus = 'DRAFT' | 'WAITING' | 'ACTIVE' | 'CLOSED'
    type SenderType = 'APP_USER' | 'ADMIN' | 'SYSTEM'
    type ContextType = 'GENERAL' | 'PRODUCT' | 'ORDER'
    type AgentWorkStatus = 'OFFLINE' | 'AVAILABLE' | 'BUSY'
    type TransferRequestStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'TIMEOUT' | 'CANCELLED'

    interface ImageMessage {
      originalFilename: string
      contentType: string
      width: number | null
      height: number | null
    }

    interface Message {
      messageId: number
      conversationId: number
      consultationNo: number
      senderType: SenderType
      senderId: number | null
      senderName: string
      messageType: 'TEXT' | 'IMAGE' | 'ORDER_CARD' | 'PRODUCT_CARD' | 'SYSTEM'
      content: string
      resourceId: number | null
      order: LinkedOrder | null
      product: LinkedProduct | null
      image: ImageMessage | null
      clientMessageId: string | null
      createdAt: string
    }

    interface LinkedOrder {
      orderId: number
      orderNo: string
      status: Api.Order.OrderStatus
      payableAmountCent: number
      primaryProductTitle: string | null
      primaryProductImage: string | null
      itemCount: number
      createdAt: string
    }

    interface LinkedProduct {
      productId: number
      title: string
      image: string
      minPriceCent: number | null
      maxPriceCent: number | null
      status: string
    }

    interface ConsultationContext {
      type: ContextType
      resourceId: number | null
      order: LinkedOrder | null
      product: LinkedProduct | null
    }

    interface ConversationSummary {
      conversationId: number
      appUserId: string
      userNickname: string
      status: ConversationStatus
      assignedAdminUserId: number | null
      assignedAdminDisplayName: string | null
      lastMessagePreview: string | null
      lastMessageAt: string | null
      appUnreadCount: number
      adminUnreadCount: number
      claimedAt: string | null
      closedAt: string | null
      createdAt: string
      updatedAt: string
      consultationNo: number
      currentContext: ConsultationContext
    }

    interface ConversationDetail extends ConversationSummary {
      messages: Message[]
      linkedOrders: LinkedOrder[]
      linkedProducts: LinkedProduct[]
    }

    type ConversationPage = Api.Common.PaginatedResponse<ConversationSummary>

    interface Agent {
      adminUserId: number
      username: string
      displayName: string
      avatar: string
      online: boolean
      workStatus: AgentWorkStatus
      activeConversationCount: number
      maxActiveConversations: number
      canReceive: boolean
    }

    interface AgentState {
      adminUserId: number
      online: boolean
      workStatus: AgentWorkStatus
      activeConversationCount: number
      maxActiveConversations: number
      canReceive: boolean
    }

    interface TransferRequest {
      requestId: number
      conversationId: number
      appUserId: string
      userNickname: string
      lastMessagePreview: string | null
      currentContext: ConsultationContext
      fromAdminUserId: number
      fromAdminDisplayName: string
      toAdminUserId: number
      toAdminDisplayName: string
      status: TransferRequestStatus
      reasonCode: string
      reasonNote: string | null
      expiresAt: string
      resolvedAt: string | null
      createdAt: string
      updatedAt: string
    }

    interface TransferForm {
      targetAdminUserId: number
      reasonCode: string
      reasonNote?: string
    }

    interface SendMessageForm {
      content: string
      clientMessageId: string
    }
  }

  namespace Realtime {
    interface Ticket {
      ticket: string
      expiresIn: number
    }

    interface Event<T = Record<string, unknown>> {
      eventId: string
      type: string
      occurredAt: string
      data: T
    }

    interface OrderPaidData {
      orderId: number
      orderNo: string
      paidAmountCent: number
      paidAt: string
    }

    interface CustomerServiceChangedData {
      conversationId: number
      appUserId: string
      changeType: string
      messageId: number | null
    }
  }
}
