/** Convert integer cents from API responses into a nullable yuan value for form controls. */
export function centToYuan(value: number | null | undefined): number | null {
  if (value == null || !Number.isFinite(value)) return null
  return Number((value / 100).toFixed(2))
}

/** Convert a yuan form value into integer cents without treating an empty field as zero. */
export function yuanToCent(value: number | string | null | undefined): number | null {
  if (value == null || value === '') return null
  const numericValue = Number(value)
  if (!Number.isFinite(numericValue)) return null
  return Math.round(numericValue * 100)
}

/** Normalize optional numeric form values while preserving an intentionally empty field. */
export function toNullableNumber(value: number | string | null | undefined): number | null {
  if (value == null || value === '') return null
  const numericValue = Number(value)
  return Number.isFinite(numericValue) ? numericValue : null
}
