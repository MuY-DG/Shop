<template>
  <div class="operation-report-page">
    <div class="operation-report-page__heading">
      <div>
        <h2>{{ config.title }}</h2>
        <p>{{ config.description }}</p>
      </div>
    </div>

    <OperationPeriodToolbar
      v-model="filter"
      :meta="report?.meta"
      :loading="loading"
      @apply="applyFilter"
      @refresh="refresh"
    />

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon class="mb-5">
      <template #title>统计数据加载失败</template>
      <template #default>
        <div class="operation-report-page__error">
          <span>{{ errorMessage }}</span>
          <ElButton size="small" @click="refresh">重试</ElButton>
        </div>
      </template>
    </ElAlert>

    <ElAlert
      v-if="partialCollectionCoverage"
      title="当前查询跨越数据采集起点，采集前空白不代表真实为 0。"
      type="warning"
      :closable="false"
      show-icon
      class="operation-report-page__coverage-alert"
    />

    <section
      v-for="group in metricGroups"
      :key="group.title"
      class="operation-report-page__metric-section"
    >
      <header class="operation-report-page__section-heading">
        <div>
          <h3>{{ group.title }}</h3>
          <p>{{ group.definitions.length }} 项指标</p>
        </div>
      </header>
      <div
        class="operation-report-page__metric-grid"
        :style="{ '--metric-columns': metricColumns(group.definitions.length) }"
      >
        <OperationMetricCard
          v-for="definition in group.definitions"
          :key="definition.key"
          :title="definition.title"
          :icon="definition.icon"
          :definition="definition.definition"
          :better-direction="definition.betterDirection"
          :comparison-mode="definition.comparisonMode"
          :generated-at="report?.meta.generatedAt"
          :metric="metricFor(definition)"
          :loading="loading && !report"
        />
      </div>
    </section>

    <ElRow :gutter="20" class="operation-report-page__panel-row">
      <ElCol :xs="24" :lg="firstBreakdown ? 16 : 24">
        <OperationReportPanel
          :title="config.trendTitle"
          :loading="loading && !report"
          :availability="trendBlock.availability"
          :message="trendBlock.message"
          :empty="trendBlock.data.length === 0"
        >
          <OperationChart :options="trendOptions" height="20rem" />
        </OperationReportPanel>
      </ElCol>
      <ElCol v-if="firstBreakdown" :xs="24" :lg="8">
        <BreakdownPanel :section="firstBreakdown" :loading="loading && !report" />
      </ElCol>
    </ElRow>

    <ElRow v-if="remainingBreakdowns.length" :gutter="20" class="operation-report-page__panel-row">
      <ElCol
        v-for="(section, index) in remainingBreakdowns"
        :key="section.key"
        :xs="24"
        :lg="balancedColumnSpan(index, remainingBreakdowns.length)"
      >
        <BreakdownPanel :section="section" :loading="loading && !report" />
      </ElCol>
    </ElRow>

    <ElRow v-if="report?.retentionCohorts" :gutter="20" class="operation-report-page__panel-row">
      <ElCol :span="24">
        <OperationReportPanel
          title="注册用户留存"
          subtitle="按注册 cohort 观察 D1、D7、D30 服务端活跃留存"
          :loading="loading && !report"
          :empty="report.retentionCohorts.data.length === 0"
        >
          <ElAlert
            v-if="retentionUnavailable"
            :title="retentionAvailabilityMessage"
            type="info"
            :closable="false"
            show-icon
            class="operation-report-page__retention-alert"
          />
          <div class="operation-report-page__retention-legend">
            “未成熟 / 未采集”表示该窗口尚不能计算；0.00% 表示窗口已成熟且真实留存人数为零。
          </div>
          <ArtTable :data="report.retentionCohorts.data" :show-pagination="false" :border="false">
            <ElTableColumn label="注册 cohort" min-width="210">
              <template #default="{ row }">{{ retentionCohortLabel(row) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="registeredUserCount" label="注册用户" width="110" align="right" />
            <ElTableColumn label="D1 留存" min-width="170" align="right">
              <template #default="{ row }">{{ retentionWindowText(row, 1) }}</template>
            </ElTableColumn>
            <ElTableColumn label="D7 留存" min-width="170" align="right">
              <template #default="{ row }">{{ retentionWindowText(row, 7) }}</template>
            </ElTableColumn>
            <ElTableColumn label="D30 留存" min-width="170" align="right">
              <template #default="{ row }">{{ retentionWindowText(row, 30) }}</template>
            </ElTableColumn>
          </ArtTable>
        </OperationReportPanel>
      </ElCol>
    </ElRow>

    <ElRow v-if="report?.lists.length" :gutter="20" class="operation-report-page__panel-row">
      <ElCol
        v-for="(section, index) in report.lists"
        :key="section.key"
        :xs="24"
        :lg="balancedColumnSpan(index, report.lists.length)"
      >
        <OperationReportPanel
          :title="section.title"
          :loading="loading && !report"
          :availability="section.block.availability"
          :message="section.block.message"
          :empty="section.block.data.length === 0"
        >
          <ArtTable :data="section.block.data" :show-pagination="false" :border="false">
            <ElTableColumn label="名称" min-width="180">
              <template #default="{ row }">
                <div class="operation-report-page__list-main">
                  <ElImage
                    v-if="row.imageUrl"
                    :src="row.imageUrl"
                    :alt="row.title"
                    fit="cover"
                    loading="lazy"
                    class="operation-report-page__list-image"
                  />
                  <div class="operation-report-page__list-copy">
                    <ElButton
                      v-if="row.drilldown"
                      class="operation-report-page__list-title"
                      type="primary"
                      link
                      @click="openDrilldown(row.drilldown)"
                    >
                      {{ row.title }}
                    </ElButton>
                    <div v-else class="operation-report-page__list-title">{{ row.title }}</div>
                    <div
                      v-if="row.description"
                      class="operation-report-page__list-description"
                      :title="row.description"
                    >
                      {{ row.description }}
                    </div>
                  </div>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn v-if="hasTags(section.block.data)" label="状态" width="100">
              <template #default="{ row }">
                <ElTag v-if="row.tag" size="small" :type="tagType(row.tone)">{{ row.tag }}</ElTag>
                <span v-else>-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="section.valueLabel || '数值'" width="140" align="right">
              <template #default="{ row }">
                {{ formatListValue(row) }}
              </template>
            </ElTableColumn>
          </ArtTable>
        </OperationReportPanel>
      </ElCol>
    </ElRow>
  </div>
</template>

<script setup lang="ts">
  import type { TagProps } from 'element-plus'
  import type { EChartsOption } from '@/plugins/echarts'
  import BreakdownPanel from './operation-breakdown-panel.vue'
  import OperationChart from './operation-chart.vue'
  import OperationMetricCard from './operation-metric-card.vue'
  import OperationPeriodToolbar from './operation-period-toolbar.vue'
  import OperationReportPanel from './operation-report-panel.vue'
  import { useOperationReport } from '../composables/use-operation-report'
  import { buildTrendChartOptions } from '../operations-chart-options'
  import { retentionCohortLabel, retentionWindowText } from '../operations-retention'
  import {
    emptyBlock,
    formatUnitValue,
    unavailableMetric,
    type OperationDrilldownTarget,
    type MetricDefinition,
    type OperationListItem,
    type OperationPageConfig,
    type OperationPageLoader
  } from '../operations-state'

  defineOptions({ name: 'OperationReportPage' })

  const props = defineProps<{
    config: OperationPageConfig
    loadReport: OperationPageLoader
  }>()

  const router = useRouter()
  const { filter, report, loading, errorMessage, applyFilter, refresh } = useOperationReport(
    props.loadReport
  )

  const trendBlock = computed(
    () => report.value?.trend || emptyBlock<Api.Operations.TrendSeries[]>([])
  )
  const trendOptions = computed<EChartsOption>(() => buildTrendChartOptions(trendBlock.value.data))
  const metricGroups = computed(() => {
    const groups = new Map<string, MetricDefinition[]>()
    props.config.metricDefinitions.forEach((definition) => {
      const title = definition.group || props.config.defaultMetricGroupTitle || '核心指标'
      groups.set(title, [...(groups.get(title) || []), definition])
    })
    return Array.from(groups, ([title, definitions]) => ({ title, definitions }))
  })
  const firstBreakdown = computed(() => report.value?.breakdowns[0] || null)
  const remainingBreakdowns = computed(() => report.value?.breakdowns.slice(1) || [])
  const retentionUnavailable = computed(() => {
    const availability = report.value?.retentionCohorts?.availability
    return Boolean(availability && availability !== 'AVAILABLE')
  })
  const retentionAvailabilityMessage = computed(
    () =>
      report.value?.retentionCohorts?.message || '部分留存窗口不在完整采集范围内，未提供估算值。'
  )
  const partialCollectionCoverage = computed(() => {
    const meta = report.value?.meta
    const collectionDate = meta?.collectionStartedAt?.slice(0, 10)
    return Boolean(collectionDate && meta && meta.range.startDate < collectionDate)
  })

  const metricFor = (definition: MetricDefinition) =>
    report.value?.metrics[definition.key] || unavailableMetric(definition.unit)

  const hasTags = (rows: OperationListItem[]) => rows.some((row) => row.tag)
  const metricColumns = (count: number) => {
    if (count <= 5) return String(count)
    if (count % 4 === 0) return '4'
    if (count % 3 === 0) return '3'
    return '4'
  }
  const balancedColumnSpan = (index: number, total: number) => {
    if (total === 1 || (total % 2 === 1 && index === total - 1)) return 24
    return 12
  }
  const formatListValue = (row: OperationListItem) => {
    if (row.value === null || row.value === undefined || !row.unit) return '-'
    return formatUnitValue(row.value, row.unit)
  }
  const openDrilldown = (target: OperationDrilldownTarget) => void router.push(target)
  const tagType = (tone?: OperationListItem['tone']): TagProps['type'] => {
    if (tone === 'DANGER') return 'danger'
    if (tone === 'WARNING') return 'warning'
    return 'info'
  }
</script>

<style scoped lang="scss">
  .operation-report-page {
    padding-bottom: 12px;
  }

  .operation-report-page__heading {
    margin-bottom: 18px;

    h2 {
      margin: 0;
      font-size: 22px;
      font-weight: 700;
      letter-spacing: -0.02em;
    }

    p {
      margin: 6px 0 0;
      font-size: 13px;
      color: var(--art-gray-500);
    }
  }

  .operation-report-page__coverage-alert {
    margin-bottom: 20px;
  }

  .operation-report-page__metric-section {
    padding: 18px;
    margin-bottom: 20px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 14px;
  }

  .operation-report-page__section-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14px;

    h3 {
      margin: 0;
      font-size: 15px;
      font-weight: 600;
    }

    p {
      margin: 3px 0 0;
      font-size: 12px;
      color: var(--art-gray-500);
    }
  }

  .operation-report-page__metric-grid {
    display: grid;
    grid-template-columns: repeat(var(--metric-columns), minmax(0, 1fr));
    gap: 14px;
  }

  .operation-report-page__panel-row {
    margin-right: 0 !important;
    margin-left: 0 !important;

    :deep(.el-col:first-child) {
      padding-left: 0 !important;
    }

    :deep(.el-col:last-child) {
      padding-right: 0 !important;
    }
  }

  .operation-report-page__error {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
  }

  .operation-report-page__list-title {
    font-weight: 500;

    &.el-button {
      height: auto;
      padding: 0;
      white-space: normal;
    }
  }

  .operation-report-page__list-main {
    display: flex;
    gap: 12px;
    align-items: center;
    min-width: 0;
  }

  .operation-report-page__list-image {
    flex: 0 0 auto;
    width: 46px;
    height: 46px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
  }

  .operation-report-page__list-copy {
    min-width: 0;
  }

  .operation-report-page__list-description {
    margin-top: 3px;
    overflow: hidden;
    font-size: 12px;
    color: var(--art-gray-500);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .operation-report-page__retention-alert {
    margin-bottom: 12px;
  }

  .operation-report-page__retention-legend {
    margin-bottom: 12px;
    font-size: 12px;
    color: var(--art-gray-500);
  }

  @media (width <= 1200px) {
    .operation-report-page__metric-grid {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }

  @media (width <= 768px) {
    .operation-report-page__metric-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (width <= 520px) {
    .operation-report-page__metric-section {
      padding: 14px;
    }

    .operation-report-page__metric-grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
