import customerMessageSoundUrl from '@/assets/audio/customer-message.mp3'
import {
  customerServiceNotificationBody,
  customerServiceNotificationTitle,
  isCustomerServiceNotificationPreviewEnabled,
  isCustomerServicePageForeground,
  type CustomerServiceInAppNotification,
  type IncomingCustomerMessageNotification
} from '@/utils/customer-service-notification-state'

const SOUND_COOLDOWN_MS = 600

export const createCustomerServiceNotifier = (
  openConversation: (conversationId: number) => void,
  showInAppNotification: (notification: CustomerServiceInAppNotification) => void
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
    const previewEnabled = isCustomerServiceNotificationPreviewEnabled()
    const body = customerServiceNotificationBody(
      message.messageType,
      message.content,
      previewEnabled
    )
    if (isCustomerServicePageForeground()) {
      showInAppNotification({
        conversationId: message.conversationId,
        senderName: message.senderName?.trim() || '新用户',
        senderAvatar: message.senderAvatar,
        body
      })
      return
    }
    if (!supportsBrowserNotifications() || Notification.permission !== 'granted') return

    try {
      const notification = new Notification(customerServiceNotificationTitle(message.senderName), {
        body,
        icon: `${import.meta.env.BASE_URL}pwa/icon-192.png`,
        tag: `customer-service-${message.conversationId}`,
        silent: true
      })
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
