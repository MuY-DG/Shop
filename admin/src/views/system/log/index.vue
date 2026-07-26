<template>
  <div class="system-log-page art-full-height">
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
        <template #type="{ row }">
          <ElTag :type="logTypeTone(row.type)" effect="plain">
            {{ logTypeLabel(row.type) }}
          </ElTag>
        </template>

        <template #level="{ row }">
          <ElTag :type="logLevelTone(row.level)" size="small">
            {{ logLevelLabel(row.level) }}
          </ElTag>
        </template>

        <template #result="{ row }">
          <ElTag :type="logResultTone(row.result)" size="small">
            {{ logResultLabel(row.result) }}
          </ElTag>
        </template>

        <template #moduleAction="{ row }">
          <div class="stacked-cell">
            <span class="primary-text">{{ formatLogText(row.module) }}</span>
            <span class="secondary-text">{{ formatLogText(row.action) }}</span>
          </div>
        </template>

        <template #operator="{ row }">
          <div class="stacked-cell">
            <span class="primary-text">{{ formatLogOperator(row) }}</span>
            <span v-if="row.operatorUserId" class="secondary-text">
              管理员 ID：{{ row.operatorUserId }}
            </span>
          </div>
        </template>

        <template #request="{ row }">
          <div class="stacked-cell request-cell">
            <ElTooltip :content="formatLogRequest(row)" placement="top" :show-after="300">
              <span
                class="primary-text ellipsis-text tooltip-trigger"
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

        <template #operation="{ row }">
          <ElButton type="primary" link @click="showDetail(row)">详情</ElButton>
        </template>
      </ArtTable>
    </ElCard>

    <ElDrawer
      v-model="detailVisible"
      title="日志详情"
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
          <ElDescriptionsItem label="日志 ID">
            <span class="mono-text">{{ currentLog.id }}</span>
          </ElDescriptionsItem>
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
          <ElDescriptionsItem label="模块">
            {{ formatLogText(currentLog.module) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="动作">
            {{ formatLogText(currentLog.action) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="操作人">
            {{ formatLogOperator(currentLog) }}
            <span v-if="currentLog.operatorUserId">
              （管理员 ID：{{ currentLog.operatorUserId }}）
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="请求方法">
            {{ formatLogText(currentLog.requestMethod) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="请求路径">
            <span class="breakable-text">{{ formatLogText(currentLog.requestPath) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="匹配路由">
            <span class="breakable-text">{{ formatLogText(currentLog.requestPattern) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="Request ID">
            <span class="mono-text breakable-text">
              {{ formatLogText(currentLog.requestId) }}
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="客户端 IP">
            {{ formatLogText(currentLog.clientIp) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="User Agent">
            <span class="breakable-text">{{ formatLogText(currentLog.userAgent) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="HTTP 状态码">
            {{ currentLog.statusCode }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="请求耗时">
            {{ formatLogDuration(currentLog.durationMs) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="错误码">
            {{ formatLogText(currentLog.errorCode) }}
          </ElDescriptionsItem>
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
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { fetchSystemLogs } from '@/api/system-log'
  import { useTable } from '@/hooks/core/useTable'
  import {
    formatLogDateTime,
    formatLogDuration,
    formatLogOperator,
    formatLogRequest,
    formatLogText,
    logLevelLabel,
    logLevelTone,
    logResultLabel,
    logResultTone,
    logTypeLabel,
    logTypeTone,
    normalizeLogSearchParams,
    type SystemLogSearchForm
  } from './log-presenter'

  defineOptions({ name: 'SystemLog' })

  const createInitialSearchForm = (): SystemLogSearchForm => ({ type: 'ALL' })

  const searchForm = ref<SystemLogSearchForm>(createInitialSearchForm())
  const detailVisible = ref(false)
  const currentLog = ref<Api.SystemLog.LogListItem | null>(null)
  const { width: windowWidth } = useWindowSize()
  const detailDrawerSize = computed(() => (windowWidth.value < 768 ? '92%' : '640px'))

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '日志类型',
      key: 'type',
      type: 'radiogroup',
      span: 12,
      props: {
        options: [
          { label: '全部', value: 'ALL' },
          { label: '登录日志', value: 'LOGIN' },
          { label: '操作日志', value: 'OPERATION' },
          { label: '访问日志', value: 'ACCESS' },
          { label: '异常日志', value: 'EXCEPTION' }
        ]
      }
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
      label: '模块',
      key: 'module',
      type: 'input',
      span: 6,
      props: { clearable: true, placeholder: '请输入模块名称' }
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
        valueFormat: 'YYYY-MM-DD HH:mm:ss',
        rangeSeparator: '至',
        startPlaceholder: '开始时间',
        endPlaceholder: '结束时间'
      }
    }
  ])

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
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        {
          prop: 'createdAt',
          label: '发生时间',
          width: 180,
          formatter: (row) => formatLogDateTime(row.createdAt)
        },
        { prop: 'type', label: '类型', width: 110, useSlot: true },
        { prop: 'level', label: '级别', width: 90, useSlot: true },
        { prop: 'result', label: '结果', width: 90, useSlot: true },
        { prop: 'moduleAction', label: '模块 / 动作', minWidth: 180, useSlot: true },
        { prop: 'operator', label: '操作人', minWidth: 160, useSlot: true },
        { prop: 'request', label: '请求', minWidth: 280, useSlot: true },
        { prop: 'clientIp', label: '客户端 IP', minWidth: 150 },
        { prop: 'requestId', label: 'Request ID', minWidth: 210, useSlot: true },
        { prop: 'response', label: '响应 / 耗时', width: 120, useSlot: true },
        { prop: 'operation', label: '操作', width: 80, fixed: 'right', useSlot: true }
      ]
    }
  })

  const applySearch = async (form: SystemLogSearchForm = searchForm.value) => {
    replaceSearchParams(normalizeLogSearchParams(form))
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
  .system-log-table-card {
    margin-top: 12px;
  }

  .stacked-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }

  .primary-text {
    color: var(--el-text-color-primary);
  }

  .secondary-text {
    font-size: 12px;
    color: var(--el-text-color-secondary);
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
</style>
