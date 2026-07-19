<template>
  <OperationReportPanel
    :title="section.title"
    :loading="loading"
    :availability="section.block.availability"
    :message="section.block.message"
    :empty="section.block.data.length === 0"
  >
    <div v-if="section.kind === 'ACTION_LIST'" class="operation-action-list">
      <button
        v-for="item in section.block.data"
        :key="item.key"
        type="button"
        class="operation-action-list__item"
        :class="{
          'is-clickable': item.drilldown,
          'is-warning': item.tone === 'WARNING',
          'is-danger': item.tone === 'DANGER'
        }"
        :disabled="!item.drilldown"
        @click="openDrilldown(item.drilldown)"
      >
        <span class="operation-action-list__label">
          <span class="operation-action-list__dot"></span>
          {{ item.label }}
        </span>
        <span class="operation-action-list__value">
          <strong>{{ item.value.toLocaleString('zh-CN') }}</strong>
          <span v-if="item.drilldown" aria-hidden="true">›</span>
        </span>
      </button>
    </div>
    <OperationChart v-else :options="options" height="20rem" />
  </OperationReportPanel>
</template>

<script setup lang="ts">
  import type { EChartsOption } from '@/plugins/echarts'
  import OperationChart from './operation-chart.vue'
  import OperationReportPanel from './operation-report-panel.vue'
  import { buildBreakdownChartOptions } from '../operations-chart-options'
  import type { OperationBreakdownSection, OperationDrilldownTarget } from '../operations-state'

  defineOptions({ name: 'OperationBreakdownPanel' })

  const props = withDefaults(
    defineProps<{
      section: OperationBreakdownSection
      loading?: boolean
    }>(),
    { loading: false }
  )

  const router = useRouter()
  const options = computed<EChartsOption>(() =>
    buildBreakdownChartOptions(
      props.section.block.data,
      props.section.kind === 'BAR' ? 'BAR' : 'RING'
    )
  )

  const openDrilldown = (target?: OperationDrilldownTarget) => {
    if (target) void router.push(target)
  }
</script>

<style scoped lang="scss">
  .operation-action-list {
    display: grid;
    gap: 8px;
  }

  .operation-action-list__item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    padding: 10px 12px;
    color: var(--el-text-color-regular);
    text-align: left;
    background: var(--el-fill-color-light);
    border: 0;
    border-radius: 8px;

    &.is-warning {
      background: var(--el-color-warning-light-9);

      .operation-action-list__dot {
        background: var(--el-color-warning);
      }
    }

    &.is-danger {
      color: var(--el-color-danger);
      background: var(--el-color-danger-light-9);

      .operation-action-list__dot {
        background: var(--el-color-danger);
      }
    }

    &.is-clickable {
      cursor: pointer;

      &:hover {
        color: var(--el-color-primary);
        background: var(--el-color-primary-light-9);
      }
    }

    &:disabled {
      cursor: default;
    }
  }

  .operation-action-list__value {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .operation-action-list__label {
    display: inline-flex;
    gap: 8px;
    align-items: center;
  }

  .operation-action-list__dot {
    width: 7px;
    height: 7px;
    background: var(--el-color-info);
    border-radius: 50%;
  }
</style>
