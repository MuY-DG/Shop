import type { EChartsOption } from '@/plugins/echarts'
import { formatUnitValue } from './operations-state'

const axisColor = '#9ca3af'
const splitColor = 'rgba(156, 163, 175, 0.18)'
const palette = ['#5D87FF', '#38C0FC', '#14DEBA', '#FFAF20', '#FA8A6C', '#B48DF3']

const compactValue = (value: number, unit: Api.Operations.MetricUnit): string => {
  if (unit === 'CENT') {
    const yuan = value / 100
    if (Math.abs(yuan) >= 10000) return `¥${(yuan / 10000).toFixed(1)}万`
    return `¥${yuan.toLocaleString('zh-CN')}`
  }
  if (unit === 'BASIS_POINT') return `${(value / 100).toFixed(0)}%`
  if (unit === 'SECOND') return formatUnitValue(value, unit)
  if (Math.abs(value) >= 10000) return `${(value / 10000).toFixed(1)}万`
  return value.toLocaleString('zh-CN')
}

export function buildTrendChartOptions(series: Api.Operations.TrendSeries[]): EChartsOption {
  const measuredUnits = Array.from(new Set(series.map((item) => item.unit))).slice(0, 2)
  const axisUnits: Api.Operations.MetricUnit[] = measuredUnits.length ? measuredUnits : ['COUNT']
  const displayedSeries = series.filter((item) => axisUnits.includes(item.unit))
  const labels = displayedSeries[0]?.points.map((point) => point.label) || []
  return {
    color: palette,
    animationDuration: 500,
    grid: { top: 24, right: 16, bottom: 12, left: 12, containLabel: true },
    legend: { top: 0, right: 0, textStyle: { color: axisColor } },
    tooltip: {
      trigger: 'axis'
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: labels,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: splitColor } },
      axisLabel: { color: axisColor }
    },
    yAxis: axisUnits.map((unit, index) => ({
      type: 'value',
      scale: true,
      position: index === 0 ? 'left' : 'right',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { show: index === 0, lineStyle: { color: splitColor, type: 'dashed' } },
      axisLabel: {
        color: axisColor,
        formatter: (value: number) => compactValue(value, unit)
      }
    })),
    series: displayedSeries.map((item, index) => ({
      name: item.name,
      type: 'line',
      yAxisIndex: axisUnits.indexOf(item.unit),
      smooth: true,
      showSymbol: false,
      connectNulls: false,
      data: item.points.map((point) => point.value),
      lineStyle: { width: 2.5 },
      tooltip: {
        valueFormatter: (value: unknown) => {
          if (value === null || value === undefined || value === '-') return '-'
          const numericValue = Number(value)
          return Number.isFinite(numericValue) ? formatUnitValue(numericValue, item.unit) : '-'
        }
      },
      areaStyle: index === 0 ? { opacity: 0.08 } : undefined
    }))
  }
}

export function buildBreakdownChartOptions(
  items: Api.Operations.BreakdownItem[],
  kind: 'RING' | 'BAR' = 'RING'
): EChartsOption {
  if (kind === 'BAR') {
    return {
      color: [palette[0]],
      animationDuration: 500,
      grid: { top: 10, right: 16, bottom: 8, left: 8, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: splitColor, type: 'dashed' } },
        axisLabel: { color: axisColor }
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: items.map((item) =>
          item.ratioBasisPoints === null || item.ratioBasisPoints === undefined
            ? item.label
            : `${item.label}  ${(item.ratioBasisPoints / 100).toFixed(2)}%`
        ),
        axisTick: { show: false },
        axisLine: { show: false },
        axisLabel: { color: axisColor }
      },
      series: [
        {
          type: 'bar',
          barMaxWidth: 18,
          data: items.map((item) => item.value),
          itemStyle: { borderRadius: [0, 5, 5, 0] },
          label: { show: true, position: 'right', color: axisColor }
        }
      ]
    }
  }

  return {
    color: palette,
    animationDuration: 500,
    tooltip: {
      trigger: 'item',
      formatter: '{b}<br/>{c}（{d}%）'
    },
    legend: {
      type: 'scroll',
      bottom: 0,
      textStyle: { color: axisColor }
    },
    series: [
      {
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['50%', '42%'],
        avoidLabelOverlap: true,
        label: { show: false },
        emphasis: { label: { show: true, formatter: '{b}\n{d}%' } },
        data: items.map((item) => ({ name: item.label, value: item.value }))
      }
    ]
  }
}
