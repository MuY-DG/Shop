export type CustomerServiceContextType = "GENERAL" | "PRODUCT" | "ORDER";
export type CustomerServiceConversationStatus = "DRAFT" | "WAITING" | "ACTIVE" | "CLOSED";
export type CustomerServiceSenderType = "APP_USER" | "ADMIN" | "SYSTEM";
export type CustomerServiceMessageType =
  | "TEXT"
  | "IMAGE"
  | "ORDER_CARD"
  | "PRODUCT_CARD"
  | "SYSTEM";

export interface CustomerServiceOpenRequest extends WechatMiniprogram.IAnyObject {
  contextType?: CustomerServiceContextType;
  contextId?: number;
}

export interface CustomerServiceSendMessageRequest extends WechatMiniprogram.IAnyObject {
  content: string;
  clientMessageId: string;
}

export interface CustomerServiceImage {
  originalFilename: string;
  contentType: string;
  width?: number;
  height?: number;
  accessMode?: "SIGNED_URL" | "AUTHENTICATED_BLOB";
  accessUrl?: string;
  accessExpiresAt?: string;
}

export interface CustomerServiceOrder {
  orderId: number;
  orderNo: string;
  status: string;
  payableAmountCent: number;
  primaryProductTitle: string;
  primaryProductImage?: string;
  itemCount: number;
  createdAt: string;
}

export interface CustomerServiceProduct {
  productId: number;
  title: string;
  image?: string;
  minPriceCent?: number;
  maxPriceCent?: number;
  status: string;
}

export interface CustomerServiceMessage {
  messageId: number;
  conversationId: number;
  consultationNo: number;
  senderType: CustomerServiceSenderType;
  senderId?: number | string;
  senderName: string;
  senderAvatar?: string;
  messageType: CustomerServiceMessageType;
  content: string;
  resourceId?: number;
  order?: CustomerServiceOrder;
  product?: CustomerServiceProduct;
  image?: CustomerServiceImage;
  clientMessageId?: string;
  createdAt: string;
}

export interface CustomerServiceContext {
  type: CustomerServiceContextType;
  resourceId?: number;
  order?: CustomerServiceOrder;
  product?: CustomerServiceProduct;
}

export interface CustomerServiceConversation {
  conversationId: number;
  appUserId: string;
  userNickname: string;
  status: CustomerServiceConversationStatus;
  assignedAdminUserId?: number;
  assignedAdminDisplayName?: string;
  lastMessagePreview?: string;
  lastMessageAt?: string;
  appUnreadCount: number;
  adminUnreadCount: number;
  claimedAt?: string;
  closedAt?: string;
  createdAt: string;
  updatedAt: string;
  consultationNo: number;
  currentContext: CustomerServiceContext;
  messages: CustomerServiceMessage[];
  linkedOrders: CustomerServiceOrder[];
  linkedProducts: CustomerServiceProduct[];
}

export interface CustomerServiceRealtimeTicket {
  ticket: string;
  expiresIn: number;
}
