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

export const canApplyExternalRefund = (row: Api.FinanceReconciliation.Difference) =>
  row.type === 'CHANNEL_ONLY' &&
  row.providerStatus === 'SUCCESS' &&
  (row.providerAmountCent ?? 0) > 0 &&
  Boolean(row.refundId || row.outRefundNo) &&
  row.status !== 'AUTO_CLEARED' &&
  !row.externalRefundApplied

export const canRetryBatch = (status: Api.FinanceReconciliation.BatchStatus) =>
  status !== 'PENDING' && status !== 'RUNNING'

export interface FinanceRuntimeDraft {
  workerEnabled: boolean
  dailyEnabled: boolean
}

export interface FinanceRuntimeConfirmation {
  title: string
  message: string
  phrase: string
  tone: 'warning' | 'error'
}

export const financeRuntimeDraft = (
  status: Api.FinanceReconciliation.RuntimeStatus
): FinanceRuntimeDraft => ({
  workerEnabled: status.workerEnabled,
  dailyEnabled: status.dailyEnabled
})

export const financeRuntimeChanged = (
  status: Api.FinanceReconciliation.RuntimeStatus,
  draft: FinanceRuntimeDraft
) => status.workerEnabled !== draft.workerEnabled || status.dailyEnabled !== draft.dailyEnabled

export const validateFinanceRuntimeDraft = (
  status: Api.FinanceReconciliation.RuntimeStatus,
  draft: FinanceRuntimeDraft
): string | null => {
  const enablingWorker = !status.workerEnabled && draft.workerEnabled
  const enablingDaily = !status.dailyEnabled && draft.dailyEnabled
  if (draft.dailyEnabled && !draft.workerEnabled) return '每日自动对账依赖对账处理器'
  if (enablingWorker && !status.paymentCredentialsReady) return '微信支付对账凭据未就绪'
  if (enablingWorker && !status.privateStorageReady) return '私有 COS 存储未就绪'
  if (enablingDaily && !status.paymentCredentialsReady) return '微信支付对账凭据未就绪'
  if (enablingDaily && !status.privateStorageReady) return '私有 COS 存储未就绪'
  if (enablingDaily && !status.workerEnabled) {
    return '必须先单独开启并验收对账处理器，下一次变更才能开启每日自动对账'
  }
  return null
}

export const validateFinanceRuntimeReason = (value: string): string | null => {
  const reason = value.trim()
  if (reason.length < 2) return '请输入至少 2 个字符的真实变更原因'
  if (reason.length > 200) return '变更原因不能超过 200 个字符'
  return null
}

export const financeRuntimeConfirmation = (
  status: Api.FinanceReconciliation.RuntimeStatus,
  draft: FinanceRuntimeDraft
): FinanceRuntimeConfirmation => {
  const enablingWorker = !status.workerEnabled && draft.workerEnabled
  const enablingDaily = !status.dailyEnabled && draft.dailyEnabled
  const disablingWorker = status.workerEnabled && !draft.workerEnabled
  const disablingDaily = status.dailyEnabled && !draft.dailyEnabled
  const changes: string[] = []
  if (enablingWorker) {
    changes.push(
      `开启处理器后会访问微信并处理待执行批次${status.pendingBatches > 0 ? `（当前 ${status.pendingBatches} 个）` : ''}`
    )
  }
  if (enablingDaily) changes.push('开启每日自动对账后，将在北京时间 10:30 创建前一天批次')
  if (disablingDaily) changes.push('关闭每日自动对账后，不再创建新的每日批次')
  if (disablingWorker) changes.push('关闭处理器后，不再领取新批次；已运行的单个批次安全完成')
  const highRisk = enablingWorker || enablingDaily
  return {
    title: highRisk ? '确认开启真实财务对账' : '确认运行开关变更',
    message: changes.join('；') || '运行开关没有变化',
    phrase: enablingDaily
      ? '确认开启每日对账'
      : enablingWorker
        ? '确认开启对账处理器'
        : '确认保存运行变更',
    tone: highRisk ? 'error' : 'warning'
  }
}

export const validateFinanceRuntimePhrase = (value: string, phrase: string): string | null =>
  value.trim() === phrase ? null : `请输入“${phrase}”`

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
