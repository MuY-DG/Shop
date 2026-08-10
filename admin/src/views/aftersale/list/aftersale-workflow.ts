export type AfterSaleAdminAction = 'APPROVE' | 'REJECT' | 'RECEIVE_RETURN' | 'INSPECT_RETURN'

const STATUS_ACTIONS: Readonly<Record<string, readonly AfterSaleAdminAction[]>> = Object.freeze({
  REQUESTED: ['APPROVE', 'REJECT'] as const,
  RETURNING: ['RECEIVE_RETURN'] as const,
  WAITING_INSPECTION: ['INSPECT_RETURN'] as const
})

export function adminAfterSaleActions(input: {
  status: string
  allowedActions?: readonly string[] | null
  canAudit: boolean
}): AfterSaleAdminAction[] {
  if (!input.canAudit) return []

  const expected = STATUS_ACTIONS[input.status] || []
  const advertised = new Set(input.allowedActions || [])
  const advertisesAdminActions = [...advertised].some((action) =>
    ['APPROVE', 'REJECT', 'RECEIVE_RETURN', 'INSPECT_RETURN'].includes(action)
  )
  if (!advertisesAdminActions) return [...expected]

  return expected.filter((action) => advertised.has(action))
}

export function canManageReturnAddresses(hasWritePermission: boolean): boolean {
  return hasWritePermission
}

export function returnAddressText(address: {
  contactName?: string | null
  contactPhone?: string | null
  province?: string | null
  city?: string | null
  district?: string | null
  detailAddress?: string | null
}): string {
  const region = [address.province, address.city, address.district]
    .map((value) => value?.trim())
    .filter(Boolean)
    .join('')
  return [address.contactName, address.contactPhone, `${region}${address.detailAddress || ''}`]
    .map((value) => value?.trim())
    .filter(Boolean)
    .join(' · ')
}
