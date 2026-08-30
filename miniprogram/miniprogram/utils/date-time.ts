const OFFSET_AWARE_DATE_TIME =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/i;

const pad = (value: number): string => String(value).padStart(2, "0");

/** Parses offset-aware API values and rejects ambiguous offset-free values. */
export function parseApiDateTime(value: unknown): Date | null {
  if (typeof value !== "string" || !value.trim()) {
    return null;
  }
  const source = value.trim();
  if (!OFFSET_AWARE_DATE_TIME.test(source)) {
    return null;
  }
  const date = new Date(source);
  return Number.isFinite(date.getTime()) ? date : null;
}

export function formatLocalDateTime(
  value: unknown,
  precision: "minute" | "second" = "second"
): string {
  const date = parseApiDateTime(value);
  if (!date) {
    return "";
  }
  const base = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}`;
  return precision === "second" ? `${base}:${pad(date.getSeconds())}` : base;
}

export function formatLocalDate(value: unknown): string {
  const date = parseApiDateTime(value);
  if (!date) {
    return "";
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
