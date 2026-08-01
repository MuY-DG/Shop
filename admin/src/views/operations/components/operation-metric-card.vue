<template>
  <button
    type="button"
    class="art-card operation-metric-card"
    :class="{ 'is-clickable': clickable }"
    :disabled="!clickable"
    @click="emit('click')"
  >
    <ElSkeleton v-if="loading" :rows="2" animated />
    <template v-else>
      <div class="operation-metric-card__heading">
        <span class="operation-metric-card__icon"><ArtSvgIcon :icon="icon" /></span>
        <span>{{ title }}</span>
        <ElTooltip v-if="definition" :content="definition" placement="top">
          <ArtSvgIcon icon="ri:question-line" class="operation-metric-card__help" />
        </ElTooltip>
      </div>

      <p v-if="!available" class="operation-metric-card__unavailable">
        {{ availabilityLabel }}
      </p>
      <ArtCountTo v-else-if="countProps" class="operation-metric-card__value" v-bind="countProps" />
      <p v-else class="operation-metric-card__value">{{ formattedValue }}</p>

      <p class="operation-metric-card__comparison" :class="comparisonClass">
        <template v-if="available && comparisonMode === 'SNAPSHOT'">
          当前快照<span v-if="snapshotLabel"> · {{ snapshotLabel }}</span>
        </template>
        <template v-else-if="available">较上一周期 {{ comparisonLabel }}</template>
        <template v-else>{{ availabilityDescription }}</template>
      </p>
    </template>
  </button>
</template>

<script setup lang="ts">
  import { formatLocalDateTime } from '@/utils/date-time'
  import {
    changeTone,
    formatChangeRate,
    formatMetricValue,
    metricIsAvailable,
    type BetterDirection,
    type MetricComparisonMode
  } from '../operations-state'

  defineOptions({ name: 'OperationMetricCard' })

  const props = withDefaults(
    defineProps<{
      title: string
      icon: string
      definition?: string
      metric: Api.Operations.MetricValue
      betterDirection?: BetterDirection
      comparisonMode?: MetricComparisonMode
      generatedAt?: string
      loading?: boolean
      clickable?: boolean
    }>(),
    {
      definition: '',
      betterDirection: 'NEUTRAL',
      comparisonMode: 'PERIOD',
      generatedAt: '',
      loading: false,
      clickable: false
    }
  )

  const emit = defineEmits<{ click: [] }>()
  const available = computed(() => metricIsAvailable(props.metric))
  const availabilityLabel = computed(() => {
    if (props.metric.availability === 'NOT_COLLECTED') return '尚未采集'
    if (props.metric.availability === 'NOT_APPLICABLE') return '不适用'
    if (props.metric.availability === 'DELAYED') return '数据延迟'
    return '暂无数据'
  })
  const availabilityDescription = computed(() => {
    if (props.metric.availability === 'NOT_COLLECTED') return '从采集启用后开始统计'
    if (props.metric.availability === 'DELAYED') return '数据尚未完成汇总'
    return '当前口径无法计算'
  })
  const formattedValue = computed(() => formatMetricValue(props.metric))
  const comparisonLabel = computed(() => formatChangeRate(props.metric))
  const snapshotLabel = computed(() => {
    if (!props.generatedAt) return ''
    const value = formatLocalDateTime(props.generatedAt)
    return `截至 ${value}`
  })
  const comparisonClass = computed(() => `is-${changeTone(props.metric, props.betterDirection)}`)
  const countProps = computed(() => {
    const value = props.metric.value
    if (
      !available.value ||
      typeof value !== 'number' ||
      !Number.isFinite(value) ||
      props.metric.unit === 'SECOND'
    ) {
      return null
    }
    if (props.metric.unit === 'CENT') {
      return {
        target: value / 100,
        decimals: 2,
        separator: ',',
        prefix: '¥',
        duration: 700
      }
    }
    if (props.metric.unit === 'BASIS_POINT') {
      return {
        target: value / 100,
        decimals: 2,
        separator: ',',
        suffix: '%',
        duration: 700
      }
    }
    return {
      target: value,
      decimals: 0,
      separator: ',',
      duration: 700
    }
  })
</script>

<style scoped lang="scss">
  .operation-metric-card {
    width: 100%;
    min-height: 132px;
    padding: 18px;
    margin: 0;
    color: inherit;
    text-align: left;
    border: 0;
    box-shadow: 0 8px 24px rgb(15 23 42 / 4%);
    transition:
      transform 0.2s ease,
      box-shadow 0.2s ease;

    &:disabled {
      cursor: default;
    }

    &.is-clickable {
      cursor: pointer;

      &:hover {
        transform: translateY(-2px);
      }
    }
  }

  .operation-metric-card__heading {
    display: flex;
    gap: 8px;
    align-items: center;
    font-size: 13px;
    color: var(--art-gray-600);
  }

  .operation-metric-card__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
    border-radius: 8px;
  }

  .operation-metric-card__help {
    margin-left: auto;
    color: var(--art-gray-400);
  }

  .operation-metric-card__value,
  .operation-metric-card__unavailable {
    display: block;
    margin: 14px 0 6px;
    font-size: 25px;
    font-weight: 600;
    line-height: 1;
    color: var(--art-gray-900);
  }

  .operation-metric-card__comparison {
    margin: 0;
    font-size: 12px;
    color: var(--art-gray-500);
    white-space: nowrap;

    &.is-positive {
      color: var(--el-color-success);
    }

    &.is-negative {
      color: var(--el-color-danger);
    }
  }
</style>
