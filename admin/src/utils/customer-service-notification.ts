import customerMessageSoundUrl from '@/assets/audio/customer-message.mp3'

interface IncomingCustomerMessageNotification {
  conversationId: number
  senderName?: string | null
  messageType?: string | null
  content?: string | null
}

const SOUND_COOLDOWN_MS = 600
const NOTIFICATION_BODY_MAX_LENGTH = 160

export const customerServiceNotificationBody = (
  messageType?: string | null,
  content?: string | null
) => {
  if (messageType === 'IMAGE') return '[图片]'
  if (messageType === 'ORDER_CARD') return '[订单]'
  if (messageType === 'PRODUCT_CARD') return '[商品]'

  const normalizedContent = content?.trim().replace(/\s+/g, ' ')
  if (!normalizedContent) return '收到一条新消息'
  if (normalizedContent.length <= NOTIFICATION_BODY_MAX_LENGTH) return normalizedContent
  return `${normalizedContent.slice(0, NOTIFICATION_BODY_MAX_LENGTH)}…`
}

export const createCustomerServiceNotifier = (
  openConversation: (conversationId: number) => void
) => {
  let audio: HTMLAudioElement | null = null
  let lastSoundAt = 0
  let permissionRequest: Promise<NotificationPermission> | null = null
  let removeUnlockListeners: (() => void) | null = null
  const activeNotifications = new Set<Notification>()

  const supportsBrowserNotifications = () =>
    typeof window !== 'undefined' && 'Notification' in window

  const requestNotificationPermission = () => {
    if (!supportsBrowserNotifications() || Notification.permission !== 'default') return
    if (permissionRequest) return
    try {
      permissionRequest = Notification.requestPermission()
      void permissionRequest
        .catch(() => 'default' as NotificationPermission)
        .finally(() => {
          permissionRequest = null
        })
    } catch {
      permissionRequest = null
    }
  }

  const primeAudio = () => {
    if (!audio) return
    const previousMuted = audio.muted
    audio.muted = true
    try {
      const playback = audio.play()
      void playback
        .then(() => {
          audio?.pause()
          if (audio) audio.currentTime = 0
        })
        .catch(() => undefined)
        .finally(() => {
          if (audio) audio.muted = previousMuted
        })
    } catch {
      audio.muted = previousMuted
    }
  }

  const removeInteractionListeners = () => {
    if (typeof window === 'undefined') return
    window.removeEventListener('pointerdown', unlockNotifications, true)
    window.removeEventListener('keydown', unlockNotifications, true)
    removeUnlockListeners = null
  }

  const unlockNotifications = () => {
    removeInteractionListeners()
    primeAudio()
    requestNotificationPermission()
  }

  const start = () => {
    if (typeof window === 'undefined') return
    audio = new Audio(customerMessageSoundUrl)
    audio.preload = 'auto'
    audio.volume = 0.55
    audio.load()

    window.addEventListener('pointerdown', unlockNotifications, true)
    window.addEventListener('keydown', unlockNotifications, true)
    removeUnlockListeners = removeInteractionListeners
    requestNotificationPermission()
  }

  const playSound = () => {
    if (!audio) return
    const now = Date.now()
    if (now - lastSoundAt < SOUND_COOLDOWN_MS) return
    lastSoundAt = now
    try {
      audio.currentTime = 0
      void audio.play().catch(() => undefined)
    } catch {
      // Browsers can block playback until the first user interaction.
    }
  }

  const notify = (message: IncomingCustomerMessageNotification) => {
    playSound()
    if (!supportsBrowserNotifications() || Notification.permission !== 'granted') return

    try {
      const notification = new Notification(
        message.senderName ? `${message.senderName} 发来新消息` : '收到新的客服消息',
        {
          body: customerServiceNotificationBody(message.messageType, message.content),
          icon: `${import.meta.env.BASE_URL}favicon.ico`,
          tag: `customer-service-${message.conversationId}`,
          silent: true
        }
      )
      activeNotifications.add(notification)
      const forgetNotification = () => activeNotifications.delete(notification)
      notification.onclose = forgetNotification
      notification.onerror = forgetNotification
      notification.onclick = () => {
        notification.close()
        window.focus()
        openConversation(message.conversationId)
      }
    } catch {
      // Permission or platform support may change while the page is open.
    }
  }

  const stop = () => {
    removeUnlockListeners?.()
    activeNotifications.forEach((notification) => notification.close())
    activeNotifications.clear()
    audio?.pause()
    audio = null
  }

  return { notify, start, stop }
}
