interface CustomerServiceImageCacheEntry {
  url: string
  size: number
  expiresAt: number
}

const MAX_BYTES = 32 * 1024 * 1024
const MAX_ENTRIES = 200
const IDLE_TTL_MS = 2 * 60 * 60 * 1000

const entries = new Map<number, CustomerServiceImageCacheEntry>()
let totalBytes = 0
let expiryTimer: ReturnType<typeof setTimeout> | null = null

function revoke(entry: CustomerServiceImageCacheEntry) {
  URL.revokeObjectURL(entry.url)
  totalBytes -= entry.size
}

function remove(messageId: number) {
  const entry = entries.get(messageId)
  if (!entry) return
  entries.delete(messageId)
  revoke(entry)
}

function purgeExpired(now = Date.now()) {
  entries.forEach((entry, messageId) => {
    if (entry.expiresAt <= now) remove(messageId)
  })
}

function scheduleExpirySweep() {
  if (expiryTimer) clearTimeout(expiryTimer)
  expiryTimer = null
  if (!entries.size) return

  const nextExpiry = Math.min(...Array.from(entries.values(), (entry) => entry.expiresAt))
  expiryTimer = setTimeout(
    () => {
      expiryTimer = null
      purgeExpired()
      scheduleExpirySweep()
    },
    Math.max(0, nextExpiry - Date.now())
  )
}

function evictOverflow() {
  while (entries.size > MAX_ENTRIES || totalBytes > MAX_BYTES) {
    const oldestMessageId = entries.keys().next().value
    if (oldestMessageId === undefined) break
    remove(oldestMessageId)
  }
}

export function getCustomerServiceImageUrl(messageId: number): string | undefined {
  purgeExpired()
  const entry = entries.get(messageId)
  if (!entry) {
    scheduleExpirySweep()
    return undefined
  }

  entries.delete(messageId)
  entry.expiresAt = Date.now() + IDLE_TTL_MS
  entries.set(messageId, entry)
  scheduleExpirySweep()
  return entry.url
}

export function cacheCustomerServiceImage(messageId: number, blob: Blob): string {
  remove(messageId)
  const url = URL.createObjectURL(blob)
  entries.set(messageId, {
    url,
    size: blob.size,
    expiresAt: Date.now() + IDLE_TTL_MS
  })
  totalBytes += blob.size
  evictOverflow()
  scheduleExpirySweep()
  return url
}

export function evictCustomerServiceImage(messageId: number) {
  remove(messageId)
  scheduleExpirySweep()
}

export function clearCustomerServiceImageCache() {
  entries.forEach(revoke)
  entries.clear()
  totalBytes = 0
  if (expiryTimer) clearTimeout(expiryTimer)
  expiryTimer = null
}
