<template>
  <div ref="chartRef" class="operation-chart" :style="{ height }" v-loading="loading"></div>
</template>

<script setup lang="ts">
  import type { EChartsOption } from '@/plugins/echarts'
  import { useChart } from '@/hooks/core/useChart'

  defineOptions({ name: 'OperationChart' })

  const props = withDefaults(
    defineProps<{
      options: EChartsOption
      height?: string
      loading?: boolean
    }>(),
    {
      height: '20rem',
      loading: false
    }
  )

  const { chartRef, updateChart, isDark } = useChart()

  const render = () => nextTick(() => updateChart(props.options))
  const handleVisible = () => updateChart(props.options)

  watch([() => props.options, isDark], render, { deep: true })
  onMounted(() => {
    chartRef.value?.addEventListener('chartVisible', handleVisible)
    render()
  })
  onBeforeUnmount(() => chartRef.value?.removeEventListener('chartVisible', handleVisible))
</script>

<style scoped>
  .operation-chart {
    width: 100%;
    min-width: 0;
  }
</style>
