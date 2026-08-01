<template>
  <div class="art-card operation-period-toolbar">
    <div class="operation-period-toolbar__filters">
      <ElSelect
        v-model="presetModel"
        class="operation-period-toolbar__preset"
        aria-label="统计周期"
      >
        <ElOption
          v-for="item in presetOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </ElSelect>
      <ElDatePicker
        v-if="modelValue.preset === 'CUSTOM'"
        v-model="customRangeModel"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        unlink-panels
      />
      <ElSelect
        v-model="granularityModel"
        class="operation-period-toolbar__granularity"
        aria-label="统计粒度"
      >
        <ElOption
          v-for="item in granularityOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </ElSelect>
      <ElButton type="primary" :loading="loading" :disabled="applyDisabled" @click="emit('apply')">
        应用
      </ElButton>
      <ElButton :loading="loading" @click="emit('refresh')">
        <ArtSvgIcon icon="ri:refresh-line" class="mr-1" />刷新
      </ElButton>
    </div>

    <div v-if="meta" class="operation-period-toolbar__meta">
      <span>{{ meta.range.startDate }} 至 {{ meta.range.endDate }}</span>
      <span>对比 {{ meta.comparisonRange.startDate }} 至 {{ meta.comparisonRange.endDate }}</span>
      <span>{{ meta.timezone }}</span>
      <span>更新于 {{ formattedGeneratedAt }}</span>
      <span v-if="meta.collectionStartedAt">
        采集始于 {{ formatMetaDateTime(meta.collectionStartedAt) }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { OperationsFilter, PeriodPreset } from '../operations-state'
  import { formatLocalDateTime } from '@/utils/date-time'

  defineOptions({ name: 'OperationPeriodToolbar' })

  const props = withDefaults(
    defineProps<{
      modelValue: OperationsFilter
      meta?: Api.Operations.ReportMeta | null
      loading?: boolean
    }>(),
    {
      meta: null,
      loading: false
    }
  )

  const emit = defineEmits<{
    'update:modelValue': [value: OperationsFilter]
    apply: []
    refresh: []
  }>()

  const presetOptions: Array<{ label: string; value: PeriodPreset }> = [
    { label: '今天', value: 'TODAY' },
    { label: '昨天', value: 'YESTERDAY' },
    { label: '近 7 天', value: 'LAST_7_DAYS' },
    { label: '近 30 天', value: 'LAST_30_DAYS' },
    { label: '本月', value: 'THIS_MONTH' },
    { label: '上月', value: 'LAST_MONTH' },
    { label: '自定义', value: 'CUSTOM' }
  ]

  const granularityOptions: Array<{ label: string; value: Api.Operations.Granularity }> = [
    { label: '自动粒度', value: 'AUTO' },
    { label: '按小时', value: 'HOUR' },
    { label: '按天', value: 'DAY' },
    { label: '按周', value: 'WEEK' },
    { label: '按月', value: 'MONTH' }
  ]

  const updateModel = (patch: Partial<OperationsFilter>) =>
    emit('update:modelValue', { ...props.modelValue, ...patch })

  const presetModel = computed({
    get: () => props.modelValue.preset,
    set: (value: PeriodPreset) =>
      updateModel({
        preset: value,
        customRange: value === 'CUSTOM' ? props.modelValue.customRange : null
      })
  })

  const granularityModel = computed({
    get: () => props.modelValue.granularity,
    set: (value: Api.Operations.Granularity) => updateModel({ granularity: value })
  })

  const customRangeModel = computed<string[] | null>({
    get: () => props.modelValue.customRange,
    set: (value) => updateModel({ customRange: value?.length === 2 ? [value[0], value[1]] : null })
  })

  const applyDisabled = computed(
    () => props.modelValue.preset === 'CUSTOM' && !props.modelValue.customRange
  )

  const formatMetaDateTime = (value: string) => formatLocalDateTime(value)
  const formattedGeneratedAt = computed(() =>
    props.meta?.generatedAt ? formatMetaDateTime(props.meta.generatedAt) : '-'
  )
</script>

<style scoped lang="scss">
  .operation-period-toolbar {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    padding: 16px 18px;
    margin-bottom: 20px;
  }

  .operation-period-toolbar__filters,
  .operation-period-toolbar__meta {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  .operation-period-toolbar__preset {
    width: 128px;
  }

  .operation-period-toolbar__granularity {
    width: 120px;
  }

  .operation-period-toolbar__meta {
    flex-wrap: wrap;
    justify-content: flex-end;
    font-size: 12px;
    color: var(--art-gray-500);
  }

  @media (width <= 1100px) {
    .operation-period-toolbar {
      flex-direction: column;
      align-items: flex-start;
    }

    .operation-period-toolbar__filters {
      flex-wrap: wrap;
    }

    .operation-period-toolbar__meta {
      justify-content: flex-start;
    }
  }
</style>
