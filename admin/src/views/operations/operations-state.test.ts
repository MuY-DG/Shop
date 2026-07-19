import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildReportQuery,
  changeTone,
  createDefaultOperationsFilter,
  formatChangeRate,
  formatMetricValue,
  formatUnitValue,
  metricIsAvailable,
  resolvePresetRange,
  unavailableMetric
} from './operations-state'

test('uses the backend default for the default seven-day Shanghai range', () => {
  assert.deepEqual(buildReportQuery(createDefaultOperationsFilter(), '2026-07-15'), {
    granularity: 'AUTO'
  })
})

test('resolves calendar presets without browser-timezone date drift', () => {
  assert.deepEqual(resolvePresetRange('TODAY', '2026-07-15'), ['2026-07-15', '2026-07-15'])
  assert.deepEqual(resolvePresetRange('LAST_7_DAYS', '2026-07-15'), ['2026-07-09', '2026-07-15'])
  assert.deepEqual(resolvePresetRange('LAST_MONTH', '2026-03-08'), ['2026-02-01', '2026-02-28'])
})

test('requires both custom dates and preserves an inclusive ISO date range', () => {
  assert.deepEqual(
    buildReportQuery({
      preset: 'CUSTOM',
      customRange: ['2026-07-01', '2026-07-10'],
      granularity: 'DAY'
    }),
    { startDate: '2026-07-01', endDate: '2026-07-10', granularity: 'DAY' }
  )
  assert.throws(
    () => buildReportQuery({ preset: 'CUSTOM', customRange: null, granularity: 'AUTO' }),
    /requires both/
  )
})

test('keeps real zero values distinct from unavailable metrics', () => {
  assert.equal(formatMetricValue({ value: 0, unit: 'CENT', availability: 'AVAILABLE' }), '¥0.00')
  assert.equal(formatMetricValue({ value: 0, unit: 'COUNT', availability: 'AVAILABLE' }), '0')
  assert.equal(
    formatMetricValue({ value: 0, unit: 'BASIS_POINT', availability: 'AVAILABLE' }),
    '0.00%'
  )
  assert.equal(formatMetricValue(unavailableMetric('COUNT')), '-')
})

test('treats omitted and non-finite metric values as unavailable', () => {
  const omittedValue: Api.Operations.MetricValue = {
    unit: 'COUNT',
    availability: 'AVAILABLE'
  }

  assert.equal(metricIsAvailable(omittedValue), false)
  assert.equal(formatMetricValue(omittedValue), '-')

  for (const value of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
    const metric: Api.Operations.MetricValue = { value, unit: 'COUNT', availability: 'AVAILABLE' }
    assert.equal(metricIsAvailable(metric), false)
    assert.equal(formatMetricValue(metric), '-')
    assert.equal(formatUnitValue(value, 'COUNT'), '-')
  }
})

test('formats comparison rates and respects whether up or down is better', () => {
  const growth: Api.Operations.MetricValue = {
    value: 12,
    unit: 'COUNT',
    changeRateBasisPoints: 1250
  }
  assert.equal(formatChangeRate(growth), '+12.50%')
  assert.equal(changeTone(growth, 'UP'), 'positive')
  assert.equal(changeTone(growth, 'DOWN'), 'negative')
  assert.equal(
    formatChangeRate({ value: 0, unit: 'COUNT', changeRateBasisPoints: null }),
    '暂无对比'
  )
})
