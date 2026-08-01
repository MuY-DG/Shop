<template>
  <div class="data-cleanup-config">
    <ElAlert
      title="清理配置统一保存在数据库中，保存后对后续调度立即生效。"
      description="每次执行严格限制为一个全局批次；删除的数据不可恢复，请根据业务留存、售后和合规要求谨慎调整。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never" class="page-card">
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-header__title">数据清理配置</div>
            <div class="page-header__subtitle">
              分别控制保留期限、单批上限、积压续跑间隔和日常调度时间
            </div>
          </div>
          <div class="page-header__actions">
            <ElButton :disabled="!dirty || loading || saving" @click="resetUnsavedChanges">
              撤销未保存修改
            </ElButton>
            <ElButton
              v-auth="'data-cleanup:config:write'"
              type="primary"
              :disabled="!dirty || loading"
              :loading="saving"
              @click="handleSave"
            >
              保存配置
            </ElButton>
          </div>
        </div>
      </template>

      <ElForm
        ref="formRef"
        v-loading="loading"
        :model="formData"
        label-position="top"
        class="cleanup-form"
      >
        <div v-if="formData.tasks.length" class="task-list">
          <section v-for="(task, index) in formData.tasks" :key="task.taskCode" class="task-card">
            <header class="task-card__header">
              <div class="task-card__heading">
                <div class="task-card__title-row">
                  <h2>{{ taskTitle(task.taskCode) }}</h2>
                  <ElTag :type="task.enabled ? 'success' : 'info'" effect="plain">
                    {{ task.enabled ? '已启用' : '已停用' }}
                  </ElTag>
                </div>
                <p>{{ taskDescription(task.taskCode) }}</p>
              </div>
              <ElSwitch
                v-model="task.enabled"
                :disabled="interactionDisabled"
                inline-prompt
                active-text="启"
                inactive-text="停"
                :aria-label="`${taskTitle(task.taskCode)}清理开关`"
              />
            </header>

            <ElAlert
              v-if="taskPresentation(task.taskCode).complianceWarning"
              :title="taskPresentation(task.taskCode).complianceWarning"
              type="warning"
              :closable="false"
              show-icon
              class="task-warning"
            />

            <div class="task-fields">
              <ElFormItem
                v-if="task.retentionDays !== null"
                :label="taskPresentation(task.taskCode).retentionLabel"
                :prop="`tasks.${index}.retentionDays`"
                :rules="
                  integerRules(
                    minRetentionDays(task.taskCode),
                    maxRetentionDays(task.taskCode),
                    `请输入 ${minRetentionDays(task.taskCode)}–${maxRetentionDays(task.taskCode)} 之间的保留天数`
                  )
                "
              >
                <ElInputNumber
                  v-model="task.retentionDays"
                  :min="minRetentionDays(task.taskCode)"
                  :max="maxRetentionDays(task.taskCode)"
                  :precision="0"
                  controls-position="right"
                  :disabled="interactionDisabled"
                />
                <div class="field-tip">只清理早于该期限的数据。</div>
              </ElFormItem>

              <ElFormItem
                :label="taskPresentation(task.taskCode).batchLabel"
                :prop="`tasks.${index}.batchSize`"
                :rules="
                  integerRules(
                    1,
                    maxBatchSize(task.taskCode),
                    `请输入 1–${maxBatchSize(task.taskCode)} 之间的单批上限`
                  )
                "
              >
                <ElInputNumber
                  v-model="task.batchSize"
                  :min="1"
                  :max="maxBatchSize(task.taskCode)"
                  :precision="0"
                  controls-position="right"
                  :disabled="interactionDisabled"
                />
                <div class="field-tip">一次执行跨全部候选类型合计最多处理此数量。</div>
              </ElFormItem>

              <ElFormItem
                label="积压续跑间隔（秒）"
                :prop="`tasks.${index}.batchIntervalSeconds`"
                :rules="integerRules(60, 86400, '请输入 60–86400 之间的续跑间隔')"
              >
                <ElInputNumber
                  v-model="task.batchIntervalSeconds"
                  :min="60"
                  :max="86400"
                  :precision="0"
                  controls-position="right"
                  :disabled="interactionDisabled"
                />
                <div class="field-tip">本批达到上限或执行失败后，等待此时间再尝试下一批。</div>
              </ElFormItem>

              <ElFormItem
                v-if="task.taskCode === 'STORAGE_ASSET'"
                label="上传保护时间（分钟）"
                :prop="`tasks.${index}.uploadPendingGraceMinutes`"
                :rules="integerRules(5, 10080, '请输入 5–10080 之间的上传保护时间')"
              >
                <ElInputNumber
                  v-model="task.uploadPendingGraceMinutes"
                  :min="5"
                  :max="10080"
                  :precision="0"
                  controls-position="right"
                  :disabled="interactionDisabled"
                />
                <div class="field-tip">上传待完成素材超过此时间后，才允许进入清理候选。</div>
              </ElFormItem>

              <ElFormItem
                label="执行计划（Cron）"
                :prop="`tasks.${index}.cronExpression`"
                :rules="cronRules"
                class="cron-field"
              >
                <ElInput
                  v-model="task.cronExpression"
                  maxlength="80"
                  placeholder="例如：0 15 3 * * *"
                  :disabled="interactionDisabled"
                />
                <div class="field-tip">
                  使用 Spring 六段 Cron：秒 分 时 日 月 周；当前时区：{{
                    runtimeTask(task.taskCode)?.zoneId || 'Asia/Shanghai'
                  }}。
                </div>
              </ElFormItem>
            </div>

            <div class="runtime-panel">
              <div class="runtime-panel__heading">
                <span>运行状态</span>
                <ElTag :type="runStatus(task.taskCode).type" size="small">
                  {{ runStatus(task.taskCode).label }}
                </ElTag>
              </div>
              <dl class="runtime-grid">
                <div>
                  <dt>下次执行</dt>
                  <dd>{{ formatRuntimeTime(runtimeTask(task.taskCode)?.nextRunAt) }}</dd>
                </div>
                <div>
                  <dt>最近开始</dt>
                  <dd>{{ formatRuntimeTime(runtimeTask(task.taskCode)?.lastStartedAt) }}</dd>
                </div>
                <div>
                  <dt>最近完成</dt>
                  <dd>{{ formatRuntimeTime(runtimeTask(task.taskCode)?.lastCompletedAt) }}</dd>
                </div>
                <div>
                  <dt>最近处理数量</dt>
                  <dd>{{
                    formatProcessedCount(runtimeTask(task.taskCode)?.lastProcessedCount)
                  }}</dd>
                </div>
              </dl>
              <div v-if="runtimeTask(task.taskCode)?.lastError" class="runtime-error">
                {{ runtimeTask(task.taskCode)?.lastError }}
              </div>
            </div>
          </section>
        </div>

        <ElEmpty v-else-if="!loading" description="暂无数据清理配置" />
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, nextTick, onMounted, reactive, ref } from 'vue'
  import type { FormInstance, FormItemRule } from 'element-plus'
  import { fetchDataCleanupConfig, updateDataCleanupConfig } from '@/api/data-cleanup'
  import { useAuth } from '@/hooks/core/useAuth'
  import { formatLocalDateTime } from '@/utils/date-time'
  import {
    createDataCleanupConfigForm,
    dataCleanupConfigSnapshot,
    DATA_CLEANUP_TASK_PRESENTATION,
    toDataCleanupConfigPayload
  } from './data-cleanup-state'

  defineOptions({ name: 'DataCleanupConfig' })

  type TagType = 'success' | 'warning' | 'danger' | 'info'

  const { hasAuth } = useAuth()
  const formRef = ref<FormInstance>()
  const loading = ref(false)
  const saving = ref(false)
  const config = ref<Api.DataCleanup.Config | null>(null)
  const baseline = ref('')
  const formData = reactive<Api.DataCleanup.ConfigForm>({
    revision: 0,
    tasks: []
  })

  const canWrite = computed(() => hasAuth('data-cleanup:config:write'))
  const interactionDisabled = computed(() => loading.value || saving.value || !canWrite.value)
  const dirty = computed(
    () => formData.tasks.length > 0 && dataCleanupConfigSnapshot(formData) !== baseline.value
  )

  const cronRules: FormItemRule[] = [
    {
      validator: (_rule, value, callback) => {
        if (String(value || '').trim()) {
          callback()
          return
        }
        callback(new Error('请输入执行计划'))
      },
      trigger: 'blur'
    }
  ]

  const integerRules = (min: number, max: number, message: string): FormItemRule[] => [
    {
      validator: (_rule, value, callback) => {
        if (Number.isInteger(value) && value >= min && value <= max) {
          callback()
          return
        }
        callback(new Error(message))
      },
      trigger: 'change'
    }
  ]

  const taskPresentation = (taskCode: Api.DataCleanup.TaskCode) =>
    DATA_CLEANUP_TASK_PRESENTATION[taskCode]

  const taskTitle = (taskCode: Api.DataCleanup.TaskCode) =>
    runtimeTask(taskCode)?.title || taskPresentation(taskCode).title

  const taskDescription = (taskCode: Api.DataCleanup.TaskCode) =>
    runtimeTask(taskCode)?.description || taskPresentation(taskCode).description

  const runtimeTask = (taskCode: Api.DataCleanup.TaskCode) =>
    config.value?.tasks.find((task) => task.taskCode === taskCode)

  const minRetentionDays = (taskCode: Api.DataCleanup.TaskCode) =>
    runtimeTask(taskCode)?.minRetentionDays ?? 1

  const maxRetentionDays = (taskCode: Api.DataCleanup.TaskCode) =>
    runtimeTask(taskCode)?.maxRetentionDays ?? 3650

  const maxBatchSize = (taskCode: Api.DataCleanup.TaskCode) =>
    runtimeTask(taskCode)?.maxBatchSize ?? 50000

  const runStatus = (taskCode: Api.DataCleanup.TaskCode): { label: string; type: TagType } => {
    switch (runtimeTask(taskCode)?.lastStatus) {
      case 'RUNNING':
        return { label: '执行中', type: 'warning' }
      case 'SUCCESS':
        return { label: '执行成功', type: 'success' }
      case 'FAILED':
        return { label: '执行失败', type: 'danger' }
      default:
        return { label: '尚未执行', type: 'info' }
    }
  }

  const formatRuntimeTime = (value?: string | null) => formatLocalDateTime(value, 'second')

  const formatProcessedCount = (value?: number | null) =>
    typeof value === 'number' ? value.toLocaleString('zh-CN') : '-'

  const fillForm = (value: Api.DataCleanup.Config) => {
    const nextForm = createDataCleanupConfigForm(value)
    config.value = value
    formData.revision = nextForm.revision
    formData.tasks.splice(0, formData.tasks.length, ...nextForm.tasks)
    baseline.value = dataCleanupConfigSnapshot(formData)
    nextTick(() => formRef.value?.clearValidate())
  }

  const loadConfig = async () => {
    loading.value = true
    try {
      fillForm(await fetchDataCleanupConfig())
    } finally {
      loading.value = false
    }
  }

  const resetUnsavedChanges = () => {
    if (config.value) fillForm(config.value)
  }

  const handleSave = async () => {
    await formRef.value?.validate()
    saving.value = true
    try {
      fillForm(await updateDataCleanupConfig(toDataCleanupConfigPayload(formData)))
    } finally {
      saving.value = false
    }
  }

  onMounted(loadConfig)
</script>

<style scoped lang="scss">
  .data-cleanup-config {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .page-card :deep(.el-card__body) {
    padding: 18px;
  }

  .page-header,
  .task-card__header,
  .runtime-panel__heading {
    display: flex;
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .page-header__title {
    font-size: 16px;
    font-weight: 600;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .page-header__subtitle,
  .task-card__heading p,
  .field-tip {
    margin-top: 3px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .page-header__actions {
    display: flex;
    flex-shrink: 0;
    gap: 8px;
  }

  .task-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  .task-card {
    min-width: 0;
    padding: 18px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .task-card__heading {
    min-width: 0;
  }

  .task-card__title-row {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .task-card__heading h2 {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .task-card__heading p {
    margin-bottom: 0;
  }

  .task-warning {
    margin-top: 14px;
  }

  .task-fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 16px;
    margin-top: 18px;
  }

  .task-fields :deep(.el-form-item) {
    min-width: 0;
    margin-bottom: 18px;
  }

  .task-fields :deep(.el-input-number) {
    width: 100%;
  }

  .cron-field {
    grid-column: 1 / -1;
  }

  .runtime-panel {
    padding: 13px 14px;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  .runtime-panel__heading {
    align-items: center;
    margin-bottom: 11px;
    font-size: 13px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .runtime-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px 16px;
    margin: 0;
  }

  .runtime-grid div {
    min-width: 0;
  }

  .runtime-grid dt {
    margin-bottom: 2px;
    font-size: 11px;
    line-height: 17px;
    color: var(--el-text-color-secondary);
  }

  .runtime-grid dd {
    margin: 0;
    overflow: hidden;
    font-size: 12px;
    line-height: 19px;
    color: var(--el-text-color-regular);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .runtime-error {
    padding-top: 9px;
    margin-top: 10px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-color-danger);
    overflow-wrap: anywhere;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  @media (width <= 1200px) {
    .task-list {
      grid-template-columns: 1fr;
    }
  }

  @media (width <= 720px) {
    .page-header {
      flex-direction: column;
    }

    .page-header__actions {
      width: 100%;
    }

    .page-header__actions :deep(.el-button) {
      flex: 1;
    }

    .task-fields,
    .runtime-grid {
      grid-template-columns: 1fr;
    }

    .cron-field {
      grid-column: auto;
    }
  }
</style>
