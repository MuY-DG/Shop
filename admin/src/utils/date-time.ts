const OFFSET_AWARE_DATE_TIME =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/i

const pad = (value: number) => String(value).padStart(2, '0')

const offsetText = (date: Date) => {
  const totalMinutes = -date.getTimezoneOffset()
  const sign = totalMinutes >= 0 ? '+' : '-'
  const absolute = Math.abs(totalMinutes)
  return `${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
}

/**
 * Parses the offset-aware API contract. Offset-free values are rejected as ambiguous.
 */
export function parseApiDateTime(value?: string | null): Date | null {
  if (!value?.trim()) return null
  const source = value.trim()
  if (!OFFSET_AWARE_DATE_TIME.test(source)) return null
  const date = new Date(source)
  return Number.isFinite(date.getTime()) ? date : null
}

export function formatLocalDateTime(
  value?: string | null,
  precision: 'minute' | 'second' = 'second',
  fallback = '-'
): string {
  const date = parseApiDateTime(value)
  if (!date) return fallback
  const base = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}`
  return precision === 'second' ? `${base}:${pad(date.getSeconds())}` : base
}

export function formatLocalDate(value?: string | null, fallback = '-'): string {
  const date = parseApiDateTime(value)
  if (!date) return fallback
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** Formats a timestamp in an explicitly declared business/reporting timezone. */
export function formatDateInTimeZone(
  value?: string | null,
  timeZone = 'UTC',
  fallback = '-'
): string {
  const date = parseApiDateTime(value)
  if (!date) return fallback
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).formatToParts(date)
    const part = (type: Intl.DateTimeFormatPartTypes) =>
      parts.find((item) => item.type === type)?.value
    const year = part('year')
    const month = part('month')
    const day = part('day')
    return year && month && day ? `${year}-${month}-${day}` : fallback
  } catch {
    return fallback
  }
}

/** Formats a browser-local Date for an offset-aware API request. */
export function toOffsetDateTime(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}${offsetText(date)}`
}

export function localDateBoundaryToOffsetDateTime(dateText: string, endOfDay = false): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateText)
  if (!match) return ''
  const date = new Date(
    Number(match[1]),
    Number(match[2]) - 1,
    Number(match[3]),
    endOfDay ? 23 : 0,
    endOfDay ? 59 : 0,
    endOfDay ? 59 : 0
  )
  return toOffsetDateTime(date)
}
