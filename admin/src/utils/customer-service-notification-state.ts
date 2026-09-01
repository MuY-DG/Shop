export interface IncomingCustomerMessageNotification {
  conversationId: number
  messageId?: number | null
  senderName?: string | null
  senderAvatar?: string | null
  messageType?: string | null
  content?: string | null
}

export interface CustomerServiceInAppNotification {
  conversationId: number
  senderName: string
  senderAvatar?: string | null
  body: string
}

interface NotificationDocumentState {
  visibilityState: string
  hasFocus: () => boolean
}

interface NotificationPreferenceStorage {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
}

export const CUSTOMER_SERVICE_NOTIFICATION_PREVIEW_KEY = 'customer-service:notification-preview'

const resolveBrowserStorage = (): NotificationPreferenceStorage | null => {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export const isCustomerServiceNotificationPreviewEnabled = (
  storage: NotificationPreferenceStorage | null = resolveBrowserStorage()
) => {
  try {
    return storage?.getItem(CUSTOMER_SERVICE_NOTIFICATION_PREVIEW_KEY) !== 'hidden'
  } catch {
    return true
  }
}

export const setCustomerServiceNotificationPreviewEnabled = (
  enabled: boolean,
  storage: NotificationPreferenceStorage | null = resolveBrowserStorage()
) => {
  try {
    storage?.setItem(CUSTOMER_SERVICE_NOTIFICATION_PREVIEW_KEY, enabled ? 'visible' : 'hidden')
  } catch {
    // Browser privacy settings can make localStorage unavailable.
  }
}

export const customerServiceNotificationTitle = (senderName?: string | null) => {
  const normalizedName = senderName?.trim()
  return normalizedName ? `客服新消息 · ${normalizedName}` : '客服新消息'
}

export const customerServiceNotificationBody = (
  messageType?: string | null,
  content?: string | null,
  previewEnabled = true
) => {
  if (!previewEnabled) return '收到一条新消息，点击查看'
  if (messageType === 'IMAGE') return '[图片]'
  if (messageType === 'ORDER_CARD') return '[订单]'
  if (messageType === 'PRODUCT_CARD') return '[商品]'

  const normalizedContent = content?.trim().replace(/\s+/g, ' ')
  if (!normalizedContent) return '收到一条新消息'
  if (normalizedContent.length <= 160) return normalizedContent
  return `${normalizedContent.slice(0, 160)}…`
}

export const isCustomerServicePageForeground = (
  documentState: NotificationDocumentState | null = typeof document === 'undefined'
    ? null
    : document
) => {
  if (!documentState || documentState.visibilityState !== 'visible') return false
  try {
    return documentState.hasFocus()
  } catch {
    return false
  }
}
