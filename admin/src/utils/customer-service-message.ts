import { parseApiDateTime } from './date-time'

export function isPersistedCustomerServiceMessageId(messageId: unknown): messageId is number {
  return typeof messageId === 'number' && Number.isSafeInteger(messageId) && messageId > 0
}

export interface CustomerServiceTimedMessage {
  consultationNo: number
  createdAt: string
  localShowTime?: boolean
}

const MESSAGE_TIME_GAP_MS = 5 * 60 * 1000
const ONE_DAY_MS = 24 * 60 * 60 * 1000
export function parseCustomerServiceMessageDate(value: unknown): Date | null {
  return typeof value === 'string' ? parseApiDateTime(value) : null
}

const calendarDayOrdinal = (date: Date) =>
  Math.floor(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / ONE_DAY_MS)

export function shouldShowCustomerServiceMessageTime(
  message: CustomerServiceTimedMessage | undefined,
  previous?: CustomerServiceTimedMessage
): boolean {
  if (!message) return false
  if (typeof message.localShowTime === 'boolean') return message.localShowTime
  const currentDate = parseCustomerServiceMessageDate(message.createdAt)
  if (!currentDate) return false
  if (!previous || message.consultationNo !== previous.consultationNo) return true
  const previousDate = parseCustomerServiceMessageDate(previous.createdAt)
  if (!previousDate) return false
  return (
    calendarDayOrdinal(currentDate) !== calendarDayOrdinal(previousDate) ||
    currentDate.getTime() - previousDate.getTime() >= MESSAGE_TIME_GAP_MS
  )
}

export function preserveCustomerServiceMessageTimeVisibility<T extends CustomerServiceTimedMessage>(
  message: T,
  previous?: CustomerServiceTimedMessage
): T {
  if (typeof previous?.localShowTime !== 'boolean') return message
  return { ...message, localShowTime: previous.localShowTime }
}

export function requirePersistedCustomerServiceMessageId(messageId: unknown): number {
  if (!isPersistedCustomerServiceMessageId(messageId)) {
    throw new RangeError('Customer-service image requests require a persisted message id')
  }
  return messageId
}
