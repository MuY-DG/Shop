import type { LocationQueryRaw } from 'vue-router'
import {
  buildReportQuery,
  createDefaultOperationsFilter,
  type OperationPageLoader,
  type OperationPageModel,
  type OperationsFilter,
  type PeriodPreset
} from '../operations-state'

const presets: PeriodPreset[] = [
  'TODAY',
  'YESTERDAY',
  'LAST_7_DAYS',
  'LAST_30_DAYS',
  'THIS_MONTH',
  'LAST_MONTH',
  'CUSTOM'
]

const granularities: Api.Operations.Granularity[] = ['AUTO', 'HOUR', 'DAY', 'WEEK', 'MONTH']

const firstQueryValue = (value: unknown): string | undefined => {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

const filterFromQuery = (query: Record<string, unknown>): OperationsFilter => {
  const defaultFilter = createDefaultOperationsFilter()
  const presetValue = firstQueryValue(query.period)
  const startDate = firstQueryValue(query.startDate)
  const endDate = firstQueryValue(query.endDate)
  const granularityValue = firstQueryValue(query.granularity)

  const preset = presets.includes(presetValue as PeriodPreset)
    ? (presetValue as PeriodPreset)
    : startDate && endDate
      ? 'CUSTOM'
      : defaultFilter.preset
  const granularity = granularities.includes(granularityValue as Api.Operations.Granularity)
    ? (granularityValue as Api.Operations.Granularity)
    : defaultFilter.granularity

  return {
    preset,
    customRange: startDate && endDate ? [startDate, endDate] : null,
    granularity
  }
}

export function useOperationReport(loadReport: OperationPageLoader) {
  const route = useRoute()
  const router = useRouter()
  const filter = ref<OperationsFilter>(filterFromQuery(route.query))
  const report = shallowRef<OperationPageModel | null>(null)
  const loading = ref(false)
  const errorMessage = ref('')
  let requestVersion = 0

  const syncQuery = async () => {
    const query: LocationQueryRaw = {
      period: filter.value.preset,
      granularity: filter.value.granularity
    }
    if (filter.value.preset === 'CUSTOM' && filter.value.customRange) {
      query.startDate = filter.value.customRange[0]
      query.endDate = filter.value.customRange[1]
    }
    await router.replace({ query })
  }

  const load = async (syncRoute = true) => {
    const version = ++requestVersion
    loading.value = true
    errorMessage.value = ''
    try {
      const query = buildReportQuery(filter.value)
      if (syncRoute) await syncQuery()
      const data = await loadReport(query)
      if (version === requestVersion) report.value = data
    } catch (error) {
      if (version === requestVersion) {
        errorMessage.value = error instanceof Error ? error.message : '统计数据加载失败'
      }
    } finally {
      if (version === requestVersion) loading.value = false
    }
  }

  onMounted(() => load(false))

  return {
    filter,
    report,
    loading,
    errorMessage,
    applyFilter: () => load(true),
    refresh: () => load(false)
  }
}
