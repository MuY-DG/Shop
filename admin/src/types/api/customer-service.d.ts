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
      accessMode: 'SIGNED_URL' | 'AUTHENTICATED_BLOB'
      accessUrl?: string | null
      accessExpiresAt?: string | null
      thumbnailStatus: 'NONE' | 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED' | 'UNAVAILABLE'
      thumbnailAccessMode?: 'SIGNED_URL' | 'AUTHENTICATED_BLOB' | null
      thumbnailAccessUrl?: string | null
      thumbnailAccessExpiresAt?: string | null
    }

    interface Message {
      messageId: number
      conversationId: number
      consultationNo: number
      senderType: SenderType
      senderId: string | null
      senderName: string
      senderAvatar: string
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
      userAvatar: string | null
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

    interface ConversationWorkspace {
      waiting: ConversationSummary[]
      waitingTotal: number
      active: ConversationSummary[]
      activeTotal: number
      closed: ConversationSummary[]
      closedTotal: number
    }

    interface Agent {
      adminUserId: number
      username: string
      displayName: string
      avatar: string
      online: boolean
      workStatus: AgentWorkStatus
      activeConversationCount: number
      maxActiveConversations: number | null
      canReceive: boolean
    }

    interface AgentState {
      adminUserId: number
      online: boolean
      workStatus: AgentWorkStatus
      activeConversationCount: number
      maxActiveConversations: number | null
      canReceive: boolean
    }

    interface AgentProfile {
      adminUserId: number
      serviceName: string
      avatar: string
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

    interface CustomerServiceMember {
      adminUserId: string
      username: string
      serviceName: string
      serviceAvatar: string
      online: boolean
      manager: boolean
      boundAt: string
    }

    interface GuestCandidate {
      adminUserId: string
      username: string
      displayName: string
      avatar: string
    }

    type AssignmentStrategy = 'LEAST_LOADED' | 'ROUND_ROBIN' | 'WEIGHTED'

    interface PersonalSettings {
      serviceName: string
      serviceNameOverride: string | null
      defaultServiceName: string
      avatar: string
      autoAcceptEnabled: boolean
      autoAcceptBelow: number
      autoAcceptCount: number
      activeConversationCount: number
    }

    interface PersonalSettingsForm {
      serviceName: string | null
      autoAcceptEnabled: boolean
      autoAcceptBelow: number
      autoAcceptCount: number
    }

    interface RoutingAgent {
      adminUserId: string
      username: string
      serviceName: string
      online: boolean
      maxActiveConversations: number | null
      calculatedWeight: number
      calculatedWeightPercent: number
    }

    interface RoutingAgentForm {
      adminUserId: string
      maxActiveConversations: number | null
    }

    interface ManagementConfig {
      defaultServiceName: string
      avatar: string
      avatarFileId: number | null
      assignmentStrategy: AssignmentStrategy
      stickyAgentEnabled: boolean
      stickyWindowHours: number
      routingAgents: RoutingAgent[]
    }

    interface ManagementRoutingForm {
      assignmentStrategy: AssignmentStrategy
      stickyAgentEnabled: boolean
      stickyWindowHours: number
      agents: RoutingAgentForm[]
    }

    interface ManagementIdentityForm {
      defaultServiceName: string
      avatarFileId: number | null
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
