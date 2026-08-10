export const batchStatusOptions: Array<{
  value: Api.FinanceReconciliation.BatchStatus
  label: string
}> = [
  { value: 'PENDING', label: '待执行' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'RETRY_WAIT', label: '等待重试' },
  { value: 'BALANCED', label: '已平账' },
  { value: 'DIFFERENCES', label: '存在差异' },
  { value: 'EMPTY', label: '当日无账单' },
  { value: 'FAILED', label: '执行失败' }
]

export const differenceStatusOptions: Array<{
  value: Api.FinanceReconciliation.DifferenceStatus
  label: string
}> = [
  { value: 'OPEN', label: '待处理' },
  { value: 'INVESTIGATING', label: '调查中' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'AUTO_CLEARED', label: '自动消除' }
]

export const differenceTypeOptions: Array<{
  value: Api.FinanceReconciliation.DifferenceType
  label: string
}> = [
  { value: 'CHANNEL_ONLY', label: '仅微信有记录' },
  { value: 'LOCAL_ONLY', label: '仅本地有记录' },
  { value: 'AMOUNT_MISMATCH', label: '金额不一致' },
  { value: 'IDENTITY_MISMATCH', label: '业务标识不一致' },
  { value: 'STATUS_MISMATCH', label: '状态不一致' },
  { value: 'DUPLICATE_CHANNEL_ROW', label: '微信账单重复行' },
  { value: 'SOURCE_CHANGED', label: '账单来源发生变化' }
]

export const batchStatusLabel = (status: Api.FinanceReconciliation.BatchStatus) =>
  batchStatusOptions.find((item) => item.value === status)?.label ?? status

export const batchStatusTone = (
  status: Api.FinanceReconciliation.BatchStatus
): 'success' | 'warning' | 'danger' | 'info' | 'primary' => {
  if (status === 'BALANCED') return 'success'
  if (status === 'DIFFERENCES' || status === 'RETRY_WAIT') return 'warning'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}

export const differenceStatusLabel = (status: Api.FinanceReconciliation.DifferenceStatus) =>
  differenceStatusOptions.find((item) => item.value === status)?.label ?? status

export const differenceStatusTone = (
  status: Api.FinanceReconciliation.DifferenceStatus
): 'success' | 'warning' | 'danger' | 'info' => {
  if (status === 'RESOLVED' || status === 'AUTO_CLEARED') return 'success'
  if (status === 'INVESTIGATING') return 'warning'
  if (status === 'OPEN') return 'danger'
  return 'info'
}

export const differenceTypeLabel = (type: Api.FinanceReconciliation.DifferenceType) =>
  differenceTypeOptions.find((item) => item.value === type)?.label ?? type

const auditActionLabels: Record<string, string> = {
  INVESTIGATE: '开始调查',
  RESOLVE: '记录解决',
  AUTO_CLEAR: '自动消除',
  REOPEN: '重新打开'
}

export const auditActionLabel = (action: string) => auditActionLabels[action] ?? action

export const differenceSeverityTone = (
  severity: Api.FinanceReconciliation.DifferenceSeverity
): 'info' | 'warning' | 'danger' => {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARNING') return 'warning'
  return 'info'
}

export const canInvestigateDifference = (status: Api.FinanceReconciliation.DifferenceStatus) =>
  status === 'OPEN'

export const canResolveDifference = (status: Api.FinanceReconciliation.DifferenceStatus) =>
  status === 'OPEN' || status === 'INVESTIGATING'

export const canRetryBatch = (status: Api.FinanceReconciliation.BatchStatus) =>
  status !== 'PENDING' && status !== 'RUNNING'

export const formatCentAmount = (value?: number | null) =>
  value == null ? '-' : `¥${(value / 100).toFixed(2)}`

export const validateReason = (reason: string) => {
  const normalized = reason.trim()
  if (!normalized) return '必须填写真实处理依据'
  if (normalized.length > 500) return '处理依据不能超过 500 个字符'
  return null
}

export const validateResolution = (resolutionCode: string, reason: string) => {
  if (!resolutionCode.trim()) return '必须填写解决代码'
  return validateReason(reason)
}

const isoDayNumber = (value: string) => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return Number.NaN
  const timestamp = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(timestamp) ? Math.floor(timestamp / 86_400_000) : Number.NaN
}

export const isInclusiveDateRangeWithinDays = (from: string, to: string, maxDays: number) => {
  const fromDay = isoDayNumber(from)
  const toDay = isoDayNumber(to)
  if (!Number.isFinite(fromDay) || !Number.isFinite(toDay) || maxDays < 1) return false
  const inclusiveDays = toDay - fromDay + 1
  return inclusiveDays >= 1 && inclusiveDays <= maxDays
}

export const isBillDateWithinLookback = (billDate: string, today: string, lookbackDays: number) => {
  const billDay = isoDayNumber(billDate)
  const todayDay = isoDayNumber(today)
  if (!Number.isFinite(billDay) || !Number.isFinite(todayDay) || lookbackDays < 1) return false
  const ageDays = todayDay - billDay
  return ageDays >= 1 && ageDays <= lookbackDays
}
