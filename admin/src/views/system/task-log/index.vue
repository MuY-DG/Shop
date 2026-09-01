<template>
  <div class="task-log-page art-full-height">
    <ElCard class="task-overview-card" shadow="never">
      <div class="task-overview">
        <div>
          <h2>任务运行</h2>
          <p>集中查看具有持久化运行状态的清理与归档任务；失败原因会保留到下一次运行。</p>
        </div>
        <ElButton :loading="loading" @click="loadTasks">刷新状态</ElButton>
      </div>
    </ElCard>

    <ElCard class="art-table-card">
      <ElTable v-loading="loading" :data="tasks" row-key="taskCode">
        <ElTableColumn label="任务" min-width="230">
          <template #default="{ row }">
            <div class="stacked-cell">
              <span class="primary-text">{{ row.title || row.taskCode }}</span>
              <span class="secondary-text">{{ row.description || row.taskCode }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="runStatusTone(row.lastStatus)" size="small">
              {{ runStatusLabel(row.lastStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="启用" width="90">
          <template #default="{ row }">
            <ElTag :type="row.enabled ? 'success' : 'info'" effect="plain" size="small">
              {{ row.enabled ? '已启用' : '已停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="调度" min-width="190">
          <template #default="{ row }">
            <div class="stacked-cell">
              <span class="mono-text">{{ row.cronExpression }}</span>
              <span class="secondary-text">{{ row.zoneId || 'Asia/Shanghai' }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="下次运行" min-width="175">
          <template #default="{ row }">{{ formatLocalDateTime(row.nextRunAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="最近完成" min-width="175">
          <template #default="{ row }">{{ formatLocalDateTime(row.lastCompletedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="处理数量" width="110" align="right">
          <template #default="{ row }">{{ row.lastProcessedCount ?? 0 }}</template>
        </ElTableColumn>
        <ElTableColumn label="最近错误" min-width="240">
          <template #default="{ row }">
            <span :class="row.lastError ? 'error-text' : 'secondary-text'">
              {{ row.lastError || '-' }}
            </span>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { onMounted, ref } from 'vue'
  import { fetchDataCleanupConfig } from '@/api/data-cleanup'
  import { formatLocalDateTime } from '@/utils/date-time'

  defineOptions({ name: 'AuditTask' })

  const loading = ref(false)
  const tasks = ref<Api.DataCleanup.TaskConfig[]>([])

  const runStatusLabel = (status?: string | null) => {
    switch (status) {
      case 'RUNNING':
        return '运行中'
      case 'SUCCESS':
        return '成功'
      case 'FAILED':
        return '失败'
      default:
        return '未运行'
    }
  }

  const runStatusTone = (status?: string | null): 'primary' | 'success' | 'danger' | 'info' => {
    switch (status) {
      case 'RUNNING':
        return 'primary'
      case 'SUCCESS':
        return 'success'
      case 'FAILED':
        return 'danger'
      default:
        return 'info'
    }
  }

  const loadTasks = async () => {
    loading.value = true
    try {
      const config = await fetchDataCleanupConfig()
      tasks.value = config.tasks
    } finally {
      loading.value = false
    }
  }

  onMounted(loadTasks)
</script>

<style scoped lang="scss">
  .task-overview-card {
    margin-bottom: 12px;
  }
  .task-overview {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    justify-content: space-between;
  }
  .task-overview h2 {
    margin: 0;
    font-size: 18px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }
  .task-overview p {
    margin: 8px 0 0;
    line-height: 1.6;
    color: var(--el-text-color-secondary);
  }
  .stacked-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;
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
  .mono-text {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
  }

  @media (max-width: 767px) {
    .task-overview {
      flex-direction: column;
    }
  }
</style>
