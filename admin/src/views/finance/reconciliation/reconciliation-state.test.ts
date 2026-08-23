import assert from 'node:assert/strict'
import test from 'node:test'
import {
  auditActionLabel,
  batchStatusLabel,
  batchStatusTone,
  canInvestigateDifference,
  canApplyExternalRefund,
  canRetryBatch,
  canResolveDifference,
  differenceStatusLabel,
  differenceTypeLabel,
  financeRuntimeChanged,
  financeRuntimeConfirmation,
  financeRuntimeDraft,
  formatCentAmount,
  isBillDateWithinLookback,
  isInclusiveDateRangeWithinDays,
  validateReason,
  validateFinanceRuntimeDraft,
  validateFinanceRuntimePhrase,
  validateFinanceRuntimeReason,
  validateResolution
} from './reconciliation-state'

const runtimeStatus = (
  overrides: Partial<Api.FinanceReconciliation.RuntimeStatus> = {}
): Api.FinanceReconciliation.RuntimeStatus => ({
  workerEnabled: false,
  dailyEnabled: false,
  runtimePersisted: false,
  version: 0,
  defaultWorkerEnabled: false,
  defaultDailyEnabled: false,
  reason: '',
  updatedBy: null,
  updatedAt: null,
  paymentCredentialsReady: true,
  privateStorageReady: true,
  workerReady: false,
  dailyReady: false,
  pendingBatches: 2,
  runningBatches: 0,
  retryWaitBatches: 0,
  failedBatches: 0,
  openDifferences: 0,
  ...overrides
})

test('renders financial reconciliation states without calling it bank reconciliation', () => {
  assert.equal(batchStatusLabel('BALANCED'), '已平账')
  assert.equal(batchStatusTone('FAILED'), 'danger')
  assert.equal(differenceStatusLabel('INVESTIGATING'), '调查中')
  assert.equal(differenceTypeLabel('CHANNEL_ONLY'), '仅微信有记录')
  assert.equal(auditActionLabel('INVESTIGATE'), '开始调查')
  assert.equal(formatCentAmount(1234), '¥12.34')
})

test('only unresolved differences expose investigation and resolution actions', () => {
  assert.equal(canInvestigateDifference('OPEN'), true)
  assert.equal(canInvestigateDifference('INVESTIGATING'), false)
  assert.equal(canResolveDifference('OPEN'), true)
  assert.equal(canResolveDifference('INVESTIGATING'), true)
  assert.equal(canResolveDifference('AUTO_CLEARED'), false)
  assert.equal(
    canApplyExternalRefund({
      type: 'CHANNEL_ONLY',
      providerStatus: 'SUCCESS',
      providerAmountCent: 52,
      refundId: 'wx-refund',
      status: 'RESOLVED',
      externalRefundApplied: false
    } as Api.FinanceReconciliation.Difference),
    true
  )
  assert.equal(
    canApplyExternalRefund({
      type: 'CHANNEL_ONLY',
      providerStatus: 'SUCCESS',
      providerAmountCent: 52,
      refundId: 'wx-refund',
      status: 'RESOLVED',
      externalRefundApplied: true
    } as Api.FinanceReconciliation.Difference),
    false
  )
  assert.equal(canRetryBatch('PENDING'), false)
  assert.equal(canRetryBatch('RUNNING'), false)
  assert.equal(canRetryBatch('FAILED'), true)
  assert.equal(validateReason('  '), '必须填写真实处理依据')
  assert.equal(validateReason('x'.repeat(501)), '处理依据不能超过 500 个字符')
  assert.equal(validateResolution('', 'checked'), '必须填写解决代码')
  assert.equal(validateResolution('MATCHED_MANUALLY', 'checked'), null)
})

test('validates bill lookback and export range limits by calendar day', () => {
  assert.equal(isBillDateWithinLookback('2026-08-09', '2026-08-10', 90), true)
  assert.equal(isBillDateWithinLookback('2026-05-12', '2026-08-10', 90), true)
  assert.equal(isBillDateWithinLookback('2026-05-11', '2026-08-10', 90), false)
  assert.equal(isBillDateWithinLookback('2026-08-10', '2026-08-10', 90), false)
  assert.equal(isInclusiveDateRangeWithinDays('2026-07-11', '2026-08-10', 31), true)
  assert.equal(isInclusiveDateRangeWithinDays('2026-07-10', '2026-08-10', 31), false)
  assert.equal(isInclusiveDateRangeWithinDays('2026-08-10', '2026-08-09', 31), false)
})

test('requires staged and ready runtime enablement while always allowing shutdown', () => {
  const initial = runtimeStatus()
  assert.deepEqual(financeRuntimeDraft(initial), {
    workerEnabled: false,
    dailyEnabled: false
  })
  assert.equal(
    validateFinanceRuntimeDraft(initial, { workerEnabled: true, dailyEnabled: true }),
    '必须先单独开启并验收对账处理器，下一次变更才能开启每日自动对账'
  )
  assert.equal(
    validateFinanceRuntimeDraft(runtimeStatus({ paymentCredentialsReady: false }), {
      workerEnabled: true,
      dailyEnabled: false
    }),
    '微信支付对账凭据未就绪'
  )
  assert.equal(
    validateFinanceRuntimeDraft(runtimeStatus({ workerEnabled: true, dailyEnabled: true }), {
      workerEnabled: false,
      dailyEnabled: false
    }),
    null
  )
  assert.equal(financeRuntimeChanged(initial, financeRuntimeDraft(initial)), false)
  assert.equal(financeRuntimeChanged(initial, { workerEnabled: true, dailyEnabled: false }), true)
})

test('builds explicit runtime confirmation and validates its audit inputs', () => {
  const confirmation = financeRuntimeConfirmation(runtimeStatus(), {
    workerEnabled: true,
    dailyEnabled: false
  })
  assert.equal(confirmation.phrase, '确认开启对账处理器')
  assert.match(confirmation.message, /2 个/)
  assert.equal(validateFinanceRuntimeReason(' '), '请输入至少 2 个字符的真实变更原因')
  assert.equal(validateFinanceRuntimeReason('人工验收'), null)
  assert.equal(validateFinanceRuntimePhrase('确认开启对账处理器', confirmation.phrase), null)
})
