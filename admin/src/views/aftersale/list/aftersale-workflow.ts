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

export function refundOperationDefaultNote(
  mode: 'resubmit' | 'manual',
  lastErrorCode?: string | null
): string {
  if (mode === 'manual') return ''
  return lastErrorCode === 'NOT_ENOUGH'
    ? '微信商户余额已补足，申请安全重试退款'
    : '退款状态异常，申请安全重试退款'
}

export function refundOperationSuccessMessage(input: {
  mode: 'query' | 'resubmit' | 'manual'
  result: string
  providerStatus: string
  resubmitted: boolean
}): string {
  if (input.mode === 'manual') return '已转人工处理，自动恢复已暂停'
  if (input.resubmitted) return '已安全重试退款，等待微信处理'
  if (input.providerStatus === 'NOT_FOUND') return '微信未找到该退款单，尚未重新提交'
  if (input.providerStatus === 'CLOSED') return '微信已关闭该退款，请排除原因后新单重试'
  if (input.providerStatus === 'ABNORMAL') return '微信退款状态异常，建议转人工处理'
  if (input.result === 'SUCCESS' || input.providerStatus === 'SUCCESS') {
    return '退款状态已同步：退款成功'
  }
  if (input.result === 'PROCESSING' || input.providerStatus === 'PROCESSING') {
    return '退款状态已同步：微信处理中'
  }
  if (input.result === 'FAILED' || input.providerStatus === 'FAILED') {
    return '退款状态已同步：微信退款失败'
  }
  return input.mode === 'query' ? '退款状态已刷新' : '已核对微信状态，无需重复退款'
}

export function afterSaleAuditSuccessMessage(input: {
  approved: boolean
  afterSaleType: string
}): string {
  if (!input.approved) return '售后申请已拒绝'
  return input.afterSaleType === 'RETURN_REFUND'
    ? '审核已通过，等待用户寄回商品'
    : '审核已通过，退款处理中'
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
