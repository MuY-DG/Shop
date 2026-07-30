<template>
  <section class="overview-page">
    <header class="overview-header">
      <div>
        <h1>概况</h1>
        <p>会话接待与响应数据</p>
      </div>
      <div class="period-control">
        <span>{{ dateRangeLabel }}</span>
        <button
          v-for="option in periods"
          :key="option.days"
          type="button"
          :class="{ active: periodDays === option.days }"
          @click="periodDays = option.days"
        >
          {{ option.label }}
        </button>
      </div>
    </header>

    <div v-loading="loading" class="overview-surface">
      <div class="metrics">
        <article v-for="metric in metricCards" :key="metric.key">
          <span>{{ metric.label }}</span>
          <strong>
            {{ metric.value }}
            <small v-if="metric.unit">{{ metric.unit }}</small>
          </strong>
        </article>
      </div>

      <OperationChart :options="chartOptions" height="min(56vh, 520px)" />
    </div>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue'
  import type { EChartsOption } from '@/plugins/echarts'
  import { fetchCustomerServiceOverview } from '@/api/customer-service'
  import OperationChart from '@/views/operations/components/operation-chart.vue'

  defineOptions({ name: 'CustomerServiceOverview' })

  const loading = ref(false)
  const periodDays = ref(7)
  const report = ref<Api.Operations.ServiceStatisticsReport | null>(null)
  const periods = [
    { label: '近1天', days: 1 },
    { label: '近7天', days: 7 },
    { label: '近30天', days: 30 }
  ]

  const dateRange = computed(() => {
    const end = new Date()
    const start = new Date(end)
    start.setDate(start.getDate() - periodDays.value + 1)
    return {
      startDate: toDateString(start),
      endDate: toDateString(end)
    }
  })
  const dateRangeLabel = computed(
    () =>
      `${dateRange.value.startDate.replaceAll('-', '/')}  -  ${dateRange.value.endDate.replaceAll('-', '/')}`
  )

  const metricCards = computed(() => {
    const summary = report.value?.summary || {}
    return [
      {
        key: 'conversationCount',
        label: '会话总数',
        value: formatCount(summary.conversationCount?.value)
      },
      {
        key: 'waitingConversationCount',
        label: '待接入会话',
        value: formatCount(summary.waitingConversationCount?.value)
      },
      {
        key: 'activeConversationCount',
        label: '接待中会话',
        value: formatCount(summary.activeConversationCount?.value)
      },
      {
        key: 'averageFirstResponseSeconds',
        label: '平均首次响应',
        value: formatDuration(summary.averageFirstResponseSeconds?.value),
        unit: summary.averageFirstResponseSeconds?.value == null ? '' : '分钟'
      },
      {
        key: 'conversationCloseRate',
        label: '会话完成率',
        value: formatPercent(summary.conversationCloseRate?.value),
        unit: '%'
      }
    ]
  })

  const conversationSeries = computed(() =>
    report.value?.trend.data.find((series) => series.key === 'conversationCount')
  )
  const chartOptions = computed<EChartsOption>(() => {
    const points = conversationSeries.value?.points || []
    return {
      animationDuration: 450,
      grid: { left: 46, right: 26, top: 50, bottom: 42 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: '#fff',
        borderColor: '#e5e5e5',
        textStyle: { color: '#444' },
        formatter: (params: unknown) => {
          const items = params as Array<{ axisValueLabel: string; value: number }>
          const item = items[0]
          return item ? `${item.axisValueLabel}<br/>会话总数 ${item.value ?? 0}` : ''
        }
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: points.map((point) => point.label),
        axisLine: { lineStyle: { color: '#e5e5e5' } },
        axisTick: { show: false },
        axisLabel: { color: '#8a8a8a', margin: 16 }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color: '#8a8a8a' },
        splitLine: { lineStyle: { color: '#e8e8e8', type: 'dashed' } }
      },
      series: [
        {
          type: 'line',
          name: '会话总数',
          data: points.map((point) => point.value || 0),
          smooth: false,
          symbol: 'circle',
          symbolSize: 8,
          lineStyle: { width: 2, color: '#0bc369' },
          itemStyle: { color: '#0bc369' },
          areaStyle: { color: 'rgba(11, 195, 105, 0.035)' }
        }
      ]
    }
  })

  function toDateString(value: Date) {
    const year = value.getFullYear()
    const month = String(value.getMonth() + 1).padStart(2, '0')
    const day = String(value.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
  }

  function formatCount(value?: number | null) {
    return value == null ? '—' : String(value)
  }

  function formatDuration(value?: number | null) {
    return value == null ? '—' : (value / 60).toFixed(2)
  }

  function formatPercent(value?: number | null) {
    return value == null ? '—' : (value / 100).toFixed(2)
  }

  async function load() {
    loading.value = true
    try {
      report.value = await fetchCustomerServiceOverview({
        ...dateRange.value,
        granularity: periodDays.value === 1 ? 'HOUR' : 'DAY'
      })
    } finally {
      loading.value = false
    }
  }

  watch(periodDays, () => void load())
  onMounted(load)
</script>

<style scoped>
  .overview-page {
    box-sizing: border-box;
    height: 100%;
    padding: 32px 44px 24px;
    overflow: auto;
    background: #f3f3f3;
  }

  .overview-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    max-width: 1720px;
    margin: 0 auto 28px;
  }

  h1 {
    margin: 0;
    font-size: 25px;
    font-weight: 650;
    color: #222;
  }

  .overview-header p {
    margin: 6px 0 0;
    font-size: 13px;
    color: #a0a0a0;
  }

  .period-control {
    display: flex;
    align-items: center;
    overflow: hidden;
    background: #fff;
    border: 1px solid #e6e6e6;
    border-radius: 8px;
  }

  .period-control span {
    padding: 11px 24px;
    font-size: 13px;
    color: #444;
    border-right: 1px solid #ededed;
  }

  .period-control button {
    height: 42px;
    padding: 0 22px;
    color: #999;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-right: 1px solid #ededed;
  }

  .period-control button:last-child {
    border-right: 0;
  }

  .period-control button.active {
    font-weight: 600;
    color: #242424;
    background: #fafafa;
  }

  .overview-surface {
    max-width: 1720px;
    min-height: calc(100vh - 150px);
    margin: 0 auto;
    overflow: hidden;
    background: #fff;
    border-radius: 12px;
  }

  .metrics {
    display: grid;
    grid-template-columns: repeat(5, minmax(150px, 1fr));
    border-bottom: 1px solid #f0f0f0;
  }

  .metrics article {
    box-sizing: border-box;
    min-height: 150px;
    padding: 31px 42px;
    border-top: 2px solid transparent;
  }

  .metrics article:first-child {
    background: linear-gradient(180deg, #f4fff9 0%, #fff 95%);
    border-top-color: #20c777;
  }

  .metrics span {
    display: block;
    margin-bottom: 13px;
    font-size: 14px;
    color: #555;
  }

  .metrics strong {
    font-size: 31px;
    font-weight: 500;
    color: #292929;
  }

  .metrics small {
    margin-left: 6px;
    font-size: 13px;
    font-weight: 400;
  }

  @media (width <= 1280px) {
    .overview-page {
      padding: 24px;
    }

    .metrics article {
      padding: 28px 20px;
    }
  }
</style>
