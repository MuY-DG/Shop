import assert from 'node:assert/strict'
import test from 'node:test'
import {
  auditActionLabel,
  batchStatusLabel,
  batchStatusTone,
  canInvestigateDifference,
  canRetryBatch,
  canResolveDifference,
  differenceStatusLabel,
  differenceTypeLabel,
  formatCentAmount,
  isBillDateWithinLookback,
  isInclusiveDateRangeWithinDays,
  validateReason,
  validateResolution
} from './reconciliation-state'

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
