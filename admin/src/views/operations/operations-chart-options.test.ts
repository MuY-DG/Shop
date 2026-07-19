import assert from 'node:assert/strict'
import test from 'node:test'

import { buildBreakdownChartOptions, buildTrendChartOptions } from './operations-chart-options'

const series = (
  key: string,
  unit: Api.Operations.MetricUnit,
  value: number | null
): Api.Operations.TrendSeries => ({
  key,
  name: key,
  unit,
  points: [{ bucket: '2026-07-15', label: '07-15', value }]
})

test('puts amount and count trend series on separate axes', () => {
  const options = buildTrendChartOptions([
    series('paidAmountCent', 'CENT', 1234),
    series('paidOrderCount', 'COUNT', 5)
  ])
  const axes = options.yAxis as Array<{ position: string }>
  const chartSeries = options.series as Array<{ yAxisIndex: number }>

  assert.equal(axes.length, 2)
  assert.deepEqual(
    axes.map((axis) => axis.position),
    ['left', 'right']
  )
  assert.deepEqual(
    chartSeries.map((item) => item.yAxisIndex),
    [0, 1]
  )
})

test('keeps an empty trend option valid before report data arrives', () => {
  const options = buildTrendChartOptions([])
  const axes = options.yAxis as Array<{ type: string }>

  assert.equal(axes.length, 1)
  assert.equal(axes[0].type, 'value')
  assert.deepEqual(options.series, [])
})

test('formats each tooltip with its own unit and preserves missing points', () => {
  const options = buildTrendChartOptions([
    series('paidAmountCent', 'CENT', 1234),
    series('paidOrderCount', 'COUNT', null)
  ])
  const chartSeries = options.series as Array<{
    tooltip: { valueFormatter: (value: unknown) => string }
  }>

  assert.equal(chartSeries[0].tooltip.valueFormatter(1234), '¥12.34')
  assert.equal(chartSeries[1].tooltip.valueFormatter(5), '5')
  assert.equal(chartSeries[1].tooltip.valueFormatter(null), '-')
})

test('shows funnel conversion rates next to persistent bar labels', () => {
  const options = buildBreakdownChartOptions(
    [
      { key: 'visit', label: '访问用户', value: 10 },
      { key: 'pay', label: '支付用户', value: 4, ratioBasisPoints: 4000 }
    ],
    'BAR'
  )
  const axis = options.yAxis as { data: string[]; inverse?: boolean }
  const chartSeries = options.series as Array<{ label?: { show?: boolean } }>

  assert.deepEqual(axis.data, ['访问用户', '支付用户  40.00%'])
  assert.equal(axis.inverse, true)
  assert.equal(chartSeries[0].label?.show, true)
})
