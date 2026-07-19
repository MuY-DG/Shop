import assert from 'node:assert/strict'
import test from 'node:test'

import { retentionCohortLabel, retentionWindowText } from './operations-retention'

const cohort = (
  window: Api.Operations.RetentionWindow,
  startDate = '2026-07-01',
  endDate = startDate
): Api.Operations.RetentionCohortItem => ({
  cohort: startDate,
  cohortStartDate: startDate,
  cohortEndDate: endDate,
  registeredUserCount: 2,
  windows: [window]
})

test('keeps a mature real zero distinct from an unavailable retention window', () => {
  assert.equal(
    retentionWindowText(
      cohort({
        dayOffset: 1,
        eligibleUserCount: 2,
        retainedUserCount: 0,
        retentionRateBasisPoints: 0
      }),
      1
    ),
    '0.00%（0/2）'
  )
  assert.equal(
    retentionWindowText(
      cohort({
        dayOffset: 1,
        eligibleUserCount: 0,
        retainedUserCount: null,
        retentionRateBasisPoints: null
      }),
      1
    ),
    '未成熟 / 未采集'
  )
})

test('treats nullable retention fields omitted from JSON as unavailable', () => {
  assert.equal(
    retentionWindowText(
      cohort({
        dayOffset: 1,
        eligibleUserCount: 0
      }),
      1
    ),
    '未成熟 / 未采集'
  )
})

test('does not format non-finite retention values', () => {
  assert.equal(
    retentionWindowText(
      cohort({
        dayOffset: 1,
        eligibleUserCount: 2,
        retainedUserCount: Number.NaN,
        retentionRateBasisPoints: 0
      }),
      1
    ),
    '未成熟 / 未采集'
  )
})

test('formats daily and ranged cohort labels without relying on the backend display key', () => {
  const window: Api.Operations.RetentionWindow = {
    dayOffset: 1,
    eligibleUserCount: 2,
    retainedUserCount: 1,
    retentionRateBasisPoints: 5000
  }
  assert.equal(retentionCohortLabel(cohort(window)), '2026-07-01')
  assert.equal(
    retentionCohortLabel(cohort(window, '2026-07-01', '2026-07-07')),
    '2026-07-01 至 2026-07-07'
  )
})
