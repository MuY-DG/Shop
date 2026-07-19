export type PeriodPreset =
  | 'TODAY'
  | 'YESTERDAY'
  | 'LAST_7_DAYS'
  | 'LAST_30_DAYS'
  | 'THIS_MONTH'
  | 'LAST_MONTH'
  | 'CUSTOM'

export interface OperationsFilter {
  preset: PeriodPreset
  customRange: [string, string] | null
  granularity: Api.Operations.Granularity
}

export type BetterDirection = 'UP' | 'DOWN' | 'NEUTRAL'
export type MetricComparisonMode = 'PERIOD' | 'SNAPSHOT'

export interface MetricDefinition {
  key: string
  title: string
  unit: Api.Operations.MetricUnit
  icon: string
  definition: string
  betterDirection?: BetterDirection
  group?: string
  comparisonMode?: MetricComparisonMode
}

export interface OperationDrilldownTarget {
  path: string
  query?: Record<string, string>
}

export interface OperationBreakdownItem extends Api.Operations.BreakdownItem {
  drilldown?: OperationDrilldownTarget
  tone?: 'INFO' | 'WARNING' | 'DANGER'
}

export interface OperationBreakdownSection {
  key: string
  title: string
  kind?: 'RING' | 'BAR' | 'ACTION_LIST'
  block: Api.Operations.DataBlock<OperationBreakdownItem[]>
}

export interface OperationListItem {
  id: string
  title: string
  description?: string | null
  imageUrl?: string | null
  value?: number | null
  unit?: Api.Operations.MetricUnit
  tag?: string | null
  tone?: 'INFO' | 'WARNING' | 'DANGER'
  drilldown?: OperationDrilldownTarget
}

export interface OperationListSection {
  key: string
  title: string
  valueLabel?: string
  block: Api.Operations.DataBlock<OperationListItem[]>
}

export interface OperationPageModel {
  meta: Api.Operations.ReportMeta
  metrics: Api.Operations.MetricGroup
  trend: Api.Operations.DataBlock<Api.Operations.TrendSeries[]>
  breakdowns: OperationBreakdownSection[]
  lists: OperationListSection[]
  retentionCohorts?: Api.Operations.DataBlock<Api.Operations.RetentionCohortItem[]>
}

export interface OperationPageConfig {
  title: string
  description: string
  trendTitle: string
  defaultMetricGroupTitle?: string
  metricDefinitions: MetricDefinition[]
}

export type OperationPageLoader = (query: Api.Operations.ReportQuery) => Promise<OperationPageModel>

const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

export const createDefaultOperationsFilter = (): OperationsFilter => ({
  preset: 'LAST_7_DAYS',
  customRange: null,
  granularity: 'AUTO'
})

export function shanghaiToday(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(now)
}

function parseIsoDate(value: string): Date {
  if (!ISO_DATE_PATTERN.test(value)) {
    throw new Error(`Invalid ISO date: ${value}`)
  }
  const [year, month, day] = value.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day))
}

function formatIsoDate(value: Date): string {
  return value.toISOString().slice(0, 10)
}

function addDays(value: string, amount: number): string {
  const date = parseIsoDate(value)
  date.setUTCDate(date.getUTCDate() + amount)
  return formatIsoDate(date)
}

export function resolvePresetRange(
  preset: Exclude<PeriodPreset, 'CUSTOM'>,
  today = shanghaiToday()
): [string, string] {
  const current = parseIsoDate(today)
  switch (preset) {
    case 'TODAY':
      return [today, today]
    case 'YESTERDAY': {
      const yesterday = addDays(today, -1)
      return [yesterday, yesterday]
    }
    case 'LAST_7_DAYS':
      return [addDays(today, -6), today]
    case 'LAST_30_DAYS':
      return [addDays(today, -29), today]
    case 'THIS_MONTH':
      return [`${today.slice(0, 8)}01`, today]
    case 'LAST_MONTH': {
      const firstThisMonth = new Date(Date.UTC(current.getUTCFullYear(), current.getUTCMonth(), 1))
      const lastPreviousMonth = new Date(firstThisMonth)
      lastPreviousMonth.setUTCDate(0)
      const firstPreviousMonth = new Date(
        Date.UTC(lastPreviousMonth.getUTCFullYear(), lastPreviousMonth.getUTCMonth(), 1)
      )
      return [formatIsoDate(firstPreviousMonth), formatIsoDate(lastPreviousMonth)]
    }
  }
}

export function buildReportQuery(
  filter: OperationsFilter,
  today = shanghaiToday()
): Api.Operations.ReportQuery {
  if (filter.preset === 'CUSTOM') {
    if (!filter.customRange?.[0] || !filter.customRange?.[1]) {
      throw new Error('Custom range requires both startDate and endDate')
    }
    if (filter.customRange[0] > filter.customRange[1]) {
      throw new Error('startDate must not be after endDate')
    }
    return {
      startDate: filter.customRange[0],
      endDate: filter.customRange[1],
      granularity: filter.granularity
    }
  }

  // 后端缺省即最近 7 个上海自然日，保留缺省调用可避免客户端时钟漂移。
  if (filter.preset === 'LAST_7_DAYS') {
    return { granularity: filter.granularity }
  }

  const [startDate, endDate] = resolvePresetRange(filter.preset, today)
  return { startDate, endDate, granularity: filter.granularity }
}

export function unavailableMetric(unit: Api.Operations.MetricUnit): Api.Operations.MetricValue {
  return { value: null, unit, availability: 'NOT_COLLECTED' }
}

export function metricIsAvailable(metric?: Api.Operations.MetricValue | null): boolean {
  return Boolean(
    metric &&
      typeof metric.value === 'number' &&
      Number.isFinite(metric.value) &&
      metric.availability !== 'NOT_COLLECTED'
  )
}

export function formatMetricValue(metric?: Api.Operations.MetricValue | null): string {
  if (!metricIsAvailable(metric) || metric?.value === null || metric?.value === undefined) {
    return '-'
  }

  return formatUnitValue(metric.value, metric.unit)
}

export function formatUnitValue(value: number, unit: Api.Operations.MetricUnit): string {
  if (!Number.isFinite(value)) return '-'

  switch (unit) {
    case 'CENT':
      return `¥${(value / 100).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
      })}`
    case 'BASIS_POINT':
      return `${(value / 100).toFixed(2)}%`
    case 'SECOND':
      return formatDuration(value)
    case 'COUNT':
      return value.toLocaleString('zh-CN')
  }
}

export function formatDuration(totalSeconds: number): string {
  const safeSeconds = Math.max(0, Math.round(totalSeconds))
  if (safeSeconds < 60) return `${safeSeconds}秒`
  const hours = Math.floor(safeSeconds / 3600)
  const minutes = Math.floor((safeSeconds % 3600) / 60)
  if (hours > 0) return `${hours}小时${minutes}分`
  return `${minutes}分`
}

export function formatChangeRate(metric?: Api.Operations.MetricValue | null): string {
  const basisPoints = metric?.changeRateBasisPoints
  if (basisPoints === null || basisPoints === undefined || !Number.isFinite(basisPoints)) {
    return '暂无对比'
  }
  const value = basisPoints / 100
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
}

export function changeTone(
  metric: Api.Operations.MetricValue | undefined,
  betterDirection: BetterDirection = 'NEUTRAL'
): 'positive' | 'negative' | 'neutral' {
  const change = metric?.changeRateBasisPoints
  if (!change || !Number.isFinite(change) || betterDirection === 'NEUTRAL') return 'neutral'
  const improved = betterDirection === 'UP' ? change > 0 : change < 0
  return improved ? 'positive' : 'negative'
}

export function blockAvailability<T>(
  block?: Api.Operations.DataBlock<T> | null
): Api.Operations.Availability {
  return block?.availability || 'AVAILABLE'
}

export function availableBlock<T>(data: T): Api.Operations.DataBlock<T> {
  return { availability: 'AVAILABLE', data }
}

export function emptyBlock<T>(data: T): Api.Operations.DataBlock<T> {
  return { availability: 'NOT_COLLECTED', data }
}
