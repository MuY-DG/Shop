<template>
  <div class="system-log-page art-full-height">
    <ElCard class="log-overview-card" shadow="never">
      <div class="log-overview">
        <div>
          <h2>{{ viewConfig.title }}</h2>
          <p>{{ viewConfig.description }}</p>
        </div>
        <ElTag :type="viewConfig.tone" effect="plain">{{ viewConfig.retention }}</ElTag>
      </div>
    </ElCard>

    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      :span="6"
      :default-expanded="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card system-log-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />
      <ArtTable
        row-key="id"
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <template #summary="{ row }">
          <div class="stacked-cell summary-cell">
            <span class="primary-text">{{ formatLogSummary(row) }}</span>
            <span v-if="row.eventCode" class="secondary-text mono-text">{{ row.eventCode }}</span>
          </div>
        </template>

        <template #target="{ row }">
          <span>{{ formatLogTarget(row) }}</span>
        </template>

        <template #operator="{ row }">
          <div class="stacked-cell">
            <span class="primary-text">{{ formatLogOperator(row) }}</span>
            <span v-if="row.operatorUserId" class="secondary-text">
              管理员 ID：{{ row.operatorUserId }}
            </span>
          </div>
        </template>

        <template #result="{ row }">
          <ElTag :type="logResultTone(row.result)" size="small">
            {{ logResultLabel(row.result) }}
          </ElTag>
        </template>

        <template #request="{ row }">
          <div class="stacked-cell request-cell">
            <ElTooltip :content="formatLogRequest(row)" placement="top" :show-after="300">
              <span
                class="primary-text ellipsis-text tooltip-trigger mono-text"
                tabindex="0"
                :aria-label="`请求：${formatLogRequest(row)}`"
              >
                {{ formatLogRequest(row) }}
              </span>
            </ElTooltip>
            <span v-if="row.requestPattern" class="secondary-text ellipsis-text">
              匹配路由：{{ row.requestPattern }}
            </span>
          </div>
        </template>

        <template #requestId="{ row }">
          <ElTooltip :content="formatLogText(row.requestId)" placement="top" :show-after="300">
            <span
              class="mono-text ellipsis-text tooltip-trigger"
              tabindex="0"
              :aria-label="`Request ID：${formatLogText(row.requestId)}`"
            >
              {{ formatLogText(row.requestId) }}
            </span>
          </ElTooltip>
        </template>

        <template #response="{ row }">
          <div class="stacked-cell">
            <span class="primary-text">HTTP {{ row.statusCode }}</span>
            <span class="secondary-text">{{ formatLogDuration(row.durationMs) }}</span>
          </div>
        </template>

        <template #error="{ row }">
          <div class="stacked-cell error-cell">
            <span :class="row.errorMessage ? 'error-text' : 'secondary-text'">
              {{ formatLogText(row.errorMessage) }}
            </span>
            <span v-if="row.errorCode" class="secondary-text mono-text">
              错误码：{{ row.errorCode }}
            </span>
          </div>
        </template>

        <template #device="{ row }">
          <span>{{ formatLogDevice(row.userAgent) }}</span>
        </template>

        <template #operation="{ row }">
          <ElButton type="primary" link @click="showDetail(row)">详情</ElButton>
        </template>
      </ArtTable>
    </ElCard>

    <ElDrawer
      v-model="detailVisible"
      :title="`${viewConfig.title}详情`"
      :size="detailDrawerSize"
      destroy-on-close
      append-to-body
    >
      <template v-if="currentLog">
        <ElAlert
          title="仅展示排障所需的最小化请求元数据，不采集或展示请求体、响应体、令牌和密钥。"
          type="info"
          show-icon
          :closable="false"
          class="detail-alert"
        />

        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="摘要">{{ formatLogSummary(currentLog) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="事件编码">
            <span class="mono-text">{{ formatLogText(currentLog.eventCode) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="操作对象">{{
            formatLogTarget(currentLog)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="发生时间">
            {{ formatLogDateTime(currentLog.createdAt) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="日志类型">
            <ElTag :type="logTypeTone(currentLog.type)" effect="plain">
              {{ logTypeLabel(currentLog.type) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="级别">
            <ElTag :type="logLevelTone(currentLog.level)" size="small">
              {{ logLevelLabel(currentLog.level) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="结果">
            <ElTag :type="logResultTone(currentLog.result)" size="small">
              {{ logResultLabel(currentLog.result) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="模块 / 技术动作">
            {{ formatLogText(currentLog.module) }} / {{ formatLogText(currentLog.action) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="操作人">
            {{ formatLogOperator(currentLog) }}
            <span v-if="currentLog.operatorUserId"
              >（管理员 ID：{{ currentLog.operatorUserId }}）</span
            >
          </ElDescriptionsItem>
          <ElDescriptionsItem label="请求">
            <span class="breakable-text">{{ formatLogRequest(currentLog) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="匹配路由">
            <span class="breakable-text">{{ formatLogText(currentLog.requestPattern) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="Request ID">
            <span class="mono-text breakable-text">{{ formatLogText(currentLog.requestId) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="客户端 IP">{{
            formatLogText(currentLog.clientIp)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="设备">{{
            formatLogDevice(currentLog.userAgent)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="User Agent">
            <span class="breakable-text">{{ formatLogText(currentLog.userAgent) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="响应 / 耗时">
            HTTP {{ currentLog.statusCode }} / {{ formatLogDuration(currentLog.durationMs) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="错误码">{{
            formatLogText(currentLog.errorCode)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="错误信息">
            <span class="breakable-text">{{ formatLogText(currentLog.errorMessage) }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>
      </template>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { useWindowSize } from '@vueuse/core'
  import { computed, ref } from 'vue'
  import { useRoute } from 'vue-router'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import type { ColumnOption } from '@/types/component'
  import { fetchSystemLogs } from '@/api/system-log'
  import { useTable } from '@/hooks/core/useTable'
  import {
    formatLogDateTime,
    formatLogDevice,
    formatLogDuration,
    formatLogOperator,
    formatLogRequest,
    formatLogSummary,
    formatLogTarget,
    formatLogText,
    logLevelLabel,
    logLevelTone,
    logResultLabel,
    logResultTone,
    logTypeLabel,
    logTypeTone,
    normalizeLogSearchParams,
    type LogTagTone,
    type SystemLogSearchForm
  } from './log-presenter'

  defineOptions({ name: 'SystemLog' })

  interface LogViewConfig {
    type: Api.SystemLog.LogType
    title: string
    description: string
    retention: string
    tone: LogTagTone
  }

  const VIEW_CONFIGS: Record<string, LogViewConfig> = {
    AuditOperation: {
      type: 'OPERATION',
      title: '操作审计',
      description: '查看管理员执行的新增、修改、删除和状态变更，技术请求信息收纳在详情中。',
      retention: '按审计保留策略',
      tone: 'success'
    },
    AuditSecurity: {
      type: 'SECURITY',
      title: '登录与安全',
      description: '查看登录成功、密码错误、访问限流和认证服务不可用等安全事件。',
      retention: '按审计保留策略',
      tone: 'primary'
    },
    AuditException: {
      type: 'EXCEPTION',
      title: '异常与告警',
      description: '聚焦后台请求中的服务异常，可通过 Request ID 关联后端运行日志。',
      retention: '按审计保留策略',
      tone: 'danger'
    },
    AuditRequest: {
      type: 'REQUEST',
      title: '请求追踪',
      description: '只保留失败请求和超过 1 秒的慢请求，不再记录普通成功访问。',
      retention: '保留 14 天',
      tone: 'warning'
    }
  }

  const route = useRoute()
  const viewConfig = computed(() => VIEW_CONFIGS[String(route.name)] || VIEW_CONFIGS.AuditOperation)
  const createInitialSearchForm = (): SystemLogSearchForm => ({ type: viewConfig.value.type })

  const searchForm = ref<SystemLogSearchForm>(createInitialSearchForm())
  const detailVisible = ref(false)
  const currentLog = ref<Api.SystemLog.LogListItem | null>(null)
  const { width: windowWidth } = useWindowSize()
  const detailDrawerSize = computed(() => (windowWidth.value < 768 ? '92%' : '680px'))

  const searchItems = computed<SearchFormItem[]>(() => {
    const items: SearchFormItem[] = [
      {
        label: '关键词',
        key: 'keyword',
        type: 'input',
        span: 6,
        props: { clearable: true, placeholder: '摘要、事件编码或对象 ID' }
      },
      {
        label: '执行结果',
        key: 'result',
        type: 'select',
        span: 6,
        props: {
          clearable: true,
          placeholder: '请选择执行结果',
          options: [
            { label: '成功', value: 'SUCCESS' },
            { label: '失败', value: 'FAILURE' }
          ]
        }
      },
      {
        label: '操作人',
        key: 'operator',
        type: 'input',
        span: 6,
        props: { clearable: true, placeholder: '用户名或管理员 ID' }
      },
      {
        label: '客户端 IP',
        key: 'clientIp',
        type: 'input',
        span: 6,
        props: { clearable: true, placeholder: '请输入客户端 IP' }
      },
      {
        label: 'Request ID',
        key: 'requestId',
        type: 'input',
        span: 6,
        props: { clearable: true, placeholder: '请输入 Request ID' }
      },
      {
        label: '发生时间',
        key: 'occurredRange',
        type: 'datetimerange',
        span: 12,
        props: {
          type: 'datetimerange',
          clearable: true,
          style: { width: '100%' },
          valueFormat: 'YYYY-MM-DDTHH:mm:ssZ',
          rangeSeparator: '至',
          startPlaceholder: '开始时间',
          endPlaceholder: '结束时间'
        }
      }
    ]
    if (viewConfig.value.type === 'OPERATION' || viewConfig.value.type === 'EXCEPTION') {
      items.splice(2, 0, {
        label: '模块',
        key: 'module',
        type: 'input',
        span: 6,
        props: { clearable: true, placeholder: '例如 product、orders' }
      })
    }
    return items
  })

  const commonColumns: ColumnOption<Api.SystemLog.LogListItem>[] = [
    {
      prop: 'createdAt',
      label: '发生时间',
      width: 180,
      formatter: (row: Api.SystemLog.LogListItem) => formatLogDateTime(row.createdAt)
    }
  ]

  const columnsFor = (type: Api.SystemLog.LogType): ColumnOption<Api.SystemLog.LogListItem>[] => {
    const operation: ColumnOption<Api.SystemLog.LogListItem> = {
      prop: 'operation',
      label: '操作',
      width: 80,
      fixed: 'right',
      useSlot: true
    }
    if (type === 'OPERATION') {
      return [
        ...commonColumns,
        { prop: 'summary', label: '操作摘要', minWidth: 280, useSlot: true },
        { prop: 'target', label: '操作对象', minWidth: 160, useSlot: true },
        { prop: 'operator', label: '操作人', minWidth: 150, useSlot: true },
        { prop: 'result', label: '结果', width: 90, useSlot: true },
        { prop: 'clientIp', label: '客户端 IP', minWidth: 145 },
        operation
      ]
    }
    if (type === 'SECURITY') {
      return [
        ...commonColumns,
        { prop: 'operator', label: '账号', minWidth: 160, useSlot: true },
        { prop: 'result', label: '结果', width: 90, useSlot: true },
        { prop: 'error', label: '失败原因', minWidth: 240, useSlot: true },
        { prop: 'clientIp', label: '客户端 IP', minWidth: 145 },
        { prop: 'device', label: '设备', minWidth: 150, useSlot: true },
        operation
      ]
    }
    if (type === 'EXCEPTION') {
      return [
        ...commonColumns,
        { prop: 'summary', label: '异常摘要', minWidth: 300, useSlot: true },
        { prop: 'module', label: '模块', minWidth: 120 },
        { prop: 'response', label: '响应 / 耗时', width: 120, useSlot: true },
        { prop: 'requestId', label: 'Request ID', minWidth: 210, useSlot: true },
        operation
      ]
    }
    return [
      ...commonColumns,
      { prop: 'request', label: '请求', minWidth: 300, useSlot: true },
      { prop: 'response', label: '响应 / 耗时', width: 120, useSlot: true },
      { prop: 'result', label: '结果', width: 90, useSlot: true },
      { prop: 'operator', label: '操作人', minWidth: 150, useSlot: true },
      { prop: 'requestId', label: 'Request ID', minWidth: 210, useSlot: true },
      operation
    ]
  }

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchSystemLogs,
      apiParams: { current: 1, size: 20, type: viewConfig.value.type },
      columnsFactory: () => columnsFor(viewConfig.value.type)
    }
  })

  const applySearch = async (form: SystemLogSearchForm = searchForm.value) => {
    replaceSearchParams({ ...normalizeLogSearchParams(form), type: viewConfig.value.type })
    await getData()
  }

  const handleSearch = async (form: SystemLogSearchForm) => {
    await applySearch(form)
  }

  const handleReset = async () => {
    searchForm.value = createInitialSearchForm()
    await applySearch()
  }

  const showDetail = (row: Api.SystemLog.LogListItem) => {
    currentLog.value = { ...row }
    detailVisible.value = true
  }
</script>

<style scoped lang="scss">
  .log-overview-card {
    margin-bottom: 12px;
  }

  .log-overview {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    justify-content: space-between;

    h2 {
      margin: 0;
      font-size: 18px;
      font-weight: 600;
      color: var(--el-text-color-primary);
    }
    p {
      margin: 8px 0 0;
      line-height: 1.6;
      color: var(--el-text-color-secondary);
    }
  }

  .system-log-table-card {
    margin-top: 12px;
  }
  .stacked-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .summary-cell {
    line-height: 1.5;
  }
  .primary-text {
    color: var(--el-text-color-primary);
  }
  .secondary-text {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .error-text {
    color: var(--el-color-danger);
  }
  .request-cell,
  .ellipsis-text {
    min-width: 0;
  }
  .ellipsis-text {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .tooltip-trigger:focus-visible {
    border-radius: 2px;
    outline: 2px solid var(--el-color-primary);
    outline-offset: 2px;
  }
  .mono-text {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
  }
  .detail-alert {
    margin-bottom: 16px;
  }
  .breakable-text {
    line-height: 22px;
    white-space: pre-wrap;
    word-break: break-word;
  }

  @media (max-width: 767px) {
    .log-overview {
      flex-direction: column;
    }
  }
</style>
