import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildImageCompressionConfigPayload,
  calculateQuotaUsagePercentage,
  canReuseDatabaseKey,
  configSourceLabel,
  formatAutoDisabledReason,
  formatConfigDateTime,
  quotaProgressStatus
} from './image-compression-state'

const config = (
  overrides: Partial<Api.ImageCompression.Config> = {}
): Api.ImageCompression.Config => ({
  requestedEnabled: true,
  effectiveEnabled: true,
  configSource: 'AUTO',
  persisted: true,
  defaultConfigSource: 'ENV',
  keyConfigured: true,
  apiKeyMasked: 'api***key',
  outputFormat: 'WEBP',
  preserveMetadata: false,
  monthlyLimit: 500,
  compressionCount: 200,
  remainingCount: 300,
  quotaPeriod: '2026-07',
  lastCheckedAt: '2026-07-26T10:20:30',
  autoDisabledReason: null,
  updatedAt: '2026-07-26T10:20:30',
  ...overrides
})

test('calculates and clamps quota usage', () => {
  assert.equal(calculateQuotaUsagePercentage(config()), 40)
  assert.equal(calculateQuotaUsagePercentage(config({ compressionCount: 520 })), 100)
  assert.equal(
    calculateQuotaUsagePercentage(config({ compressionCount: null, remainingCount: 125 })),
    75
  )
  assert.equal(calculateQuotaUsagePercentage(config({ monthlyLimit: null })), null)
})

test('marks exhausted and nearly exhausted quota', () => {
  assert.equal(quotaProgressStatus(config({ remainingCount: 0 }), 100), 'exception')
  assert.equal(quotaProgressStatus(config({ remainingCount: 25 }), 95), 'warning')
  assert.equal(quotaProgressStatus(config(), 40), 'success')
  assert.equal(
    quotaProgressStatus(
      config({ monthlyLimit: null, compressionCount: null, remainingCount: null }),
      null
    ),
    undefined
  )
})

test('normalizes update payload without overwriting a stored key with blanks', () => {
  assert.deepEqual(
    buildImageCompressionConfigPayload({
      requestedEnabled: true,
      configSource: 'DB',
      apiKey: '   ',
      monthlyLimit: 500
    }),
    {
      requestedEnabled: true,
      configSource: 'DB',
      monthlyLimit: 500
    }
  )
  assert.deepEqual(
    buildImageCompressionConfigPayload({
      requestedEnabled: false,
      configSource: 'ENV',
      apiKey: '  secret-key  ',
      monthlyLimit: null
    }),
    {
      requestedEnabled: false,
      configSource: 'ENV',
      apiKey: 'secret-key'
    }
  )
})

test('presents source, time, and reusable database key state', () => {
  assert.equal(configSourceLabel('AUTO'), '自动选择')
  assert.equal(formatAutoDisabledReason('QUOTA_EXHAUSTED'), '本月额度已耗尽')
  assert.equal(formatAutoDisabledReason('INVALID_KEY'), '密钥无效')
  assert.equal(formatAutoDisabledReason('SERVICE_UNAVAILABLE'), 'SERVICE_UNAVAILABLE')
  assert.equal(formatAutoDisabledReason(null), '未知')
  assert.equal(formatConfigDateTime('2026-07-26T10:20:30.123Z'), '2026-07-26 10:20:30 UTC')
  assert.equal(formatConfigDateTime(null), '-')
  assert.equal(canReuseDatabaseKey(config({ configSource: 'DB' })), true)
  assert.equal(
    canReuseDatabaseKey(config({ configSource: 'AUTO', defaultConfigSource: 'DB' })),
    true
  )
  assert.equal(canReuseDatabaseKey(config({ configSource: 'ENV' })), false)
})
