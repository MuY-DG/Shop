interface CustomerServiceImageCacheEntry {
  url: string
  size: number
  expiresAt: number
  hardExpiresAt?: number
  objectUrl: boolean
}

type CustomerServiceImageVariant = 'display' | 'original'

interface PersistedCustomerServiceImageCacheEntry {
  url: string
  expiresAt: number
  hardExpiresAt?: number
  lastAccessedAt: number
}

const MAX_BYTES = 32 * 1024 * 1024
const MAX_ENTRIES = 200
const IDLE_TTL_MS = 2 * 60 * 60 * 1000
const SESSION_CACHE_KEY = 'customer-service-image-url-cache-v1'

const entries = new Map<string, CustomerServiceImageCacheEntry>()
let totalBytes = 0
let expiryTimer: ReturnType<typeof setTimeout> | null = null
let persistedEntries: Record<string, PersistedCustomerServiceImageCacheEntry> | null = null
let persistentCacheHydrated = false

function revoke(entry: CustomerServiceImageCacheEntry) {
  if (entry.objectUrl) URL.revokeObjectURL(entry.url)
  totalBytes -= entry.size
}

const cacheKey = (messageId: number, variant: CustomerServiceImageVariant) =>
  `${variant}:${messageId}`

function sessionCache(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage
  } catch {
    return null
  }
}

function readPersistedEntries(): Record<string, PersistedCustomerServiceImageCacheEntry> {
  if (persistedEntries) return persistedEntries
  const now = Date.now()
  const nextEntries: Record<string, PersistedCustomerServiceImageCacheEntry> = {}
  try {
    const raw = sessionCache()?.getItem(SESSION_CACHE_KEY)
    const parsed = raw ? (JSON.parse(raw) as Record<string, unknown>) : {}
    Object.entries(parsed).forEach(([key, value]) => {
      if (!value || typeof value !== 'object') return
      const candidate = value as Record<string, unknown>
      if (
        !/^(display|original):\d+$/.test(key) ||
        typeof candidate.url !== 'string' ||
        !/^https?:\/\//.test(candidate.url) ||
        typeof candidate.expiresAt !== 'number' ||
        !Number.isFinite(candidate.expiresAt) ||
        candidate.expiresAt <= now ||
        typeof candidate.lastAccessedAt !== 'number' ||
        !Number.isFinite(candidate.lastAccessedAt) ||
        (candidate.hardExpiresAt !== undefined &&
          (typeof candidate.hardExpiresAt !== 'number' ||
            !Number.isFinite(candidate.hardExpiresAt) ||
            candidate.hardExpiresAt <= now))
      )
        return
      nextEntries[key] = {
        url: candidate.url,
        expiresAt: candidate.expiresAt,
        hardExpiresAt: candidate.hardExpiresAt as number | undefined,
        lastAccessedAt: candidate.lastAccessedAt
      }
    })
  } catch {
    // Storage can be unavailable in privacy mode; the in-memory cache remains usable.
  }
  persistedEntries = nextEntries
  return nextEntries
}

function writePersistedEntries() {
  const storage = sessionCache()
  if (!storage) return
  const now = Date.now()
  const retained = Object.entries(readPersistedEntries())
    .filter(([, entry]) => entry.expiresAt > now && (entry.hardExpiresAt ?? Infinity) > now)
    .sort((left, right) => right[1].lastAccessedAt - left[1].lastAccessedAt)
    .slice(0, MAX_ENTRIES)
  persistedEntries = Object.fromEntries(retained)
  try {
    if (retained.length) {
      storage.setItem(SESSION_CACHE_KEY, JSON.stringify(persistedEntries))
    } else {
      storage.removeItem(SESSION_CACHE_KEY)
    }
  } catch {
    // A full or disabled sessionStorage should not prevent images from loading.
  }
}

function persistRemoteEntry(key: string, entry: CustomerServiceImageCacheEntry) {
  const now = Date.now()
  readPersistedEntries()[key] = {
    url: entry.url,
    expiresAt: entry.expiresAt,
    hardExpiresAt: entry.hardExpiresAt,
    lastAccessedAt: now
  }
  writePersistedEntries()
}

function removePersistedEntry(key: string) {
  const storedEntries = readPersistedEntries()
  if (!(key in storedEntries)) return
  delete storedEntries[key]
  writePersistedEntries()
}

function hydratePersistentCache() {
  if (persistentCacheHydrated) return
  persistentCacheHydrated = true
  Object.entries(readPersistedEntries())
    .sort((left, right) => left[1].lastAccessedAt - right[1].lastAccessedAt)
    .forEach(([key, entry]) => {
      if (entries.has(key)) return
      entries.set(key, {
        url: entry.url,
        size: 0,
        expiresAt: entry.expiresAt,
        hardExpiresAt: entry.hardExpiresAt,
        objectUrl: false
      })
    })
  writePersistedEntries()
  evictOverflow()
}

function remove(key: string) {
  const entry = entries.get(key)
  if (entry) {
    entries.delete(key)
    revoke(entry)
  }
  removePersistedEntry(key)
}

function purgeExpired(now = Date.now()) {
  entries.forEach((entry, key) => {
    if (entry.expiresAt <= now) remove(key)
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
    const oldestKey = entries.keys().next().value
    if (oldestKey === undefined) break
    remove(oldestKey)
  }
}

function getCachedUrl(messageId: number, variant: CustomerServiceImageVariant): string | undefined {
  hydratePersistentCache()
  purgeExpired()
  const key = cacheKey(messageId, variant)
  const entry = entries.get(key)
  if (!entry) {
    scheduleExpirySweep()
    return undefined
  }

  entries.delete(key)
  entry.expiresAt = Math.min(
    Date.now() + IDLE_TTL_MS,
    entry.hardExpiresAt ?? Number.POSITIVE_INFINITY
  )
  entries.set(key, entry)
  if (!entry.objectUrl && entry.hardExpiresAt === undefined) persistRemoteEntry(key, entry)
  scheduleExpirySweep()
  return entry.url
}

function cacheBlob(messageId: number, blob: Blob, variant: CustomerServiceImageVariant): string {
  const key = cacheKey(messageId, variant)
  remove(key)
  const url = URL.createObjectURL(blob)
  entries.set(key, {
    url,
    size: blob.size,
    expiresAt: Date.now() + IDLE_TTL_MS,
    objectUrl: true
  })
  totalBytes += blob.size
  evictOverflow()
  scheduleExpirySweep()
  return url
}

function cacheRemoteUrl(
  messageId: number,
  url: string,
  variant: CustomerServiceImageVariant,
  hardExpiresAt?: number
): string {
  const key = cacheKey(messageId, variant)
  remove(key)
  const now = Date.now()
  if (hardExpiresAt !== undefined && hardExpiresAt <= now) {
    scheduleExpirySweep()
    return url
  }
  entries.set(key, {
    url,
    size: 0,
    expiresAt: Math.min(now + IDLE_TTL_MS, hardExpiresAt ?? Number.POSITIVE_INFINITY),
    hardExpiresAt,
    objectUrl: false
  })
  persistRemoteEntry(key, entries.get(key)!)
  evictOverflow()
  scheduleExpirySweep()
  return url
}

export function getCustomerServiceImageUrl(messageId: number): string | undefined {
  return getCachedUrl(messageId, 'display')
}

export function cacheCustomerServiceImage(messageId: number, blob: Blob): string {
  return cacheBlob(messageId, blob, 'display')
}

export function cacheCustomerServiceImageUrl(
  messageId: number,
  url: string,
  hardExpiresAt?: number
): string {
  return cacheRemoteUrl(messageId, url, 'display', hardExpiresAt)
}

export function getCustomerServiceOriginalImageUrl(messageId: number): string | undefined {
  return getCachedUrl(messageId, 'original')
}

export function cacheCustomerServiceOriginalImage(messageId: number, blob: Blob): string {
  return cacheBlob(messageId, blob, 'original')
}

export function cacheCustomerServiceOriginalImageUrl(
  messageId: number,
  url: string,
  hardExpiresAt?: number
): string {
  return cacheRemoteUrl(messageId, url, 'original', hardExpiresAt)
}

export function evictCustomerServiceImage(messageId: number) {
  remove(cacheKey(messageId, 'display'))
  scheduleExpirySweep()
}

export function clearCustomerServiceImageCache() {
  entries.forEach(revoke)
  entries.clear()
  totalBytes = 0
  if (expiryTimer) clearTimeout(expiryTimer)
  expiryTimer = null
  persistedEntries = {}
  persistentCacheHydrated = true
  try {
    sessionCache()?.removeItem(SESSION_CACHE_KEY)
  } catch {
    // Ignore unavailable storage during logout cleanup.
  }
}
