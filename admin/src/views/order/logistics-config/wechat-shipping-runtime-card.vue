<template>
  <ElCard v-if="canRead" shadow="never" class="runtime-card">
    <template #header>
      <div class="runtime-header">
        <div>
          <div class="runtime-title">微信发货运行控制</div>
          <div class="runtime-subtitle">
            数据库配置优先；开关在每次外呼前读取，数据库异常时自动停止外呼
          </div>
        </div>
        <div class="runtime-actions">
          <ElTag :type="runtime?.runtimePersisted ? 'success' : 'warning'" effect="plain">
            {{ runtime?.runtimePersisted ? '数据库已接管' : '等待写入数据库' }}
          </ElTag>
          <ElButton :disabled="loading || saving" @click="loadRuntime">刷新</ElButton>
          <ElButton
            v-auth="'wechat-shipping:runtime:write'"
            type="primary"
            :loading="saving"
            :disabled="loading || !canSave"
            @click="saveRuntime"
          >
            {{ runtime?.runtimePersisted ? '保存运行配置' : '写入数据库并接管' }}
          </ElButton>
        </div>
      </div>
    </template>

    <ElAlert
      v-if="runtime && !runtime.runtimePersisted"
      title="当前仍使用部署时默认值"
      description="运行开关持久化在数据库并记录版本；启用前先完成配置与真实能力核验。"
      type="warning"
      :closable="false"
      show-icon
      class="runtime-alert"
    />

    <ElForm v-loading="loading" label-position="top" class="runtime-form">
      <div class="runtime-switches">
        <div class="runtime-switch-row">
          <div>
            <div class="runtime-switch-title">微信发货上传</div>
            <div class="runtime-switch-tip">控制新发货记录和管理员手动重试是否调用微信。</div>
          </div>
          <ElSwitch
            :model-value="draft.uploadEnabled"
            :disabled="!canWrite || saving"
            @change="handleUploadChange"
          />
        </div>
        <div class="runtime-switch-row">
          <div>
            <div class="runtime-switch-title">可靠投递 Worker</div>
            <div class="runtime-switch-tip">扫描待上传及结果未知记录；开启时会自动启用总开关。</div>
          </div>
          <ElSwitch
            :model-value="draft.deliveryEnabled"
            :disabled="!canWrite || saving"
            @change="handleDeliveryChange"
          />
        </div>
        <div class="runtime-switch-row">
          <div>
            <div class="runtime-switch-title">微信收货对账</div>
            <div class="runtime-switch-tip">定期查询微信收货状态；开启时会自动启用总开关。</div>
          </div>
          <ElSwitch
            :model-value="draft.receiptReconciliationEnabled"
            :disabled="!canWrite || saving"
            @change="handleReceiptChange"
          />
        </div>
      </div>

      <ElFormItem label="变更原因" required class="runtime-reason">
        <ElInput
          v-model="draft.reason"
          maxlength="200"
          show-word-limit
          :disabled="!canWrite || saving"
          placeholder="请输入 2–200 个字符，写入追加审计"
        />
      </ElFormItem>

      <div v-if="runtime?.updatedAt" class="runtime-meta">
        最近保存：{{ formatDateTime(runtime.updatedAt) }} · revision {{ runtime.version }}
      </div>
    </ElForm>
  </ElCard>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import { fetchWechatShippingRuntime, updateWechatShippingRuntime } from '@/api/wechat-shipping'
  import { useAuth } from '@/hooks/core/useAuth'
  import { formatLocalDateTime } from '@/utils/date-time'
  import { isHttpError } from '@/utils/http/error'

  const { hasAuth } = useAuth()
  const canRead = computed(() => hasAuth('wechat-shipping:runtime:read'))
  const canWrite = computed(() => hasAuth('wechat-shipping:runtime:write'))
  const runtime = ref<Api.Order.WechatShippingRuntime | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const draft = reactive({
    uploadEnabled: false,
    deliveryEnabled: false,
    receiptReconciliationEnabled: false,
    reason: ''
  })

  const changed = computed(
    () =>
      runtime.value !== null &&
      (draft.uploadEnabled !== runtime.value.uploadEnabled ||
        draft.deliveryEnabled !== runtime.value.deliveryEnabled ||
        draft.receiptReconciliationEnabled !== runtime.value.receiptReconciliationEnabled)
  )
  const reasonValid = computed(() => {
    const length = Array.from(draft.reason.trim()).length
    return length >= 2 && length <= 200
  })
  const canSave = computed(
    () =>
      canWrite.value &&
      runtime.value !== null &&
      reasonValid.value &&
      (changed.value || !runtime.value.runtimePersisted)
  )

  const fill = (value: Api.Order.WechatShippingRuntime) => {
    runtime.value = value
    draft.uploadEnabled = value.uploadEnabled
    draft.deliveryEnabled = value.deliveryEnabled
    draft.receiptReconciliationEnabled = value.receiptReconciliationEnabled
    draft.reason = ''
  }

  const loadRuntime = async () => {
    if (!canRead.value || loading.value) return
    loading.value = true
    try {
      fill(await fetchWechatShippingRuntime())
    } finally {
      loading.value = false
    }
  }

  const handleUploadChange = (value: string | number | boolean) => {
    draft.uploadEnabled = Boolean(value)
    if (!draft.uploadEnabled) {
      draft.deliveryEnabled = false
      draft.receiptReconciliationEnabled = false
    }
  }

  const handleDeliveryChange = (value: string | number | boolean) => {
    draft.deliveryEnabled = Boolean(value)
    if (draft.deliveryEnabled) draft.uploadEnabled = true
  }

  const handleReceiptChange = (value: string | number | boolean) => {
    draft.receiptReconciliationEnabled = Boolean(value)
    if (draft.receiptReconciliationEnabled) draft.uploadEnabled = true
  }

  const saveRuntime = async () => {
    if (!runtime.value || !canSave.value || saving.value) return
    saving.value = true
    try {
      const updated = await updateWechatShippingRuntime({
        uploadEnabled: draft.uploadEnabled,
        deliveryEnabled: draft.deliveryEnabled,
        receiptReconciliationEnabled: draft.receiptReconciliationEnabled,
        version: runtime.value.version,
        reason: draft.reason.trim()
      })
      fill(updated)
      ElMessage.success('微信发货运行配置已写入数据库')
    } catch (error) {
      if (isHttpError(error) && error.httpStatus === 409) {
        ElMessage.warning('运行配置已被其他管理员修改，已刷新为最新状态')
        await loadRuntime()
        return
      }
      throw error
    } finally {
      saving.value = false
    }
  }

  const formatDateTime = (value?: string | null) => formatLocalDateTime(value, 'second')

  onMounted(loadRuntime)
</script>

<style scoped lang="scss">
  .runtime-header,
  .runtime-actions,
  .runtime-switch-row {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
  }

  .runtime-title,
  .runtime-switch-title {
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .runtime-subtitle,
  .runtime-switch-tip,
  .runtime-meta {
    margin-top: 3px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .runtime-alert {
    margin-bottom: 16px;
  }

  .runtime-switches {
    display: grid;
    gap: 12px;
  }

  .runtime-switch-row {
    padding: 12px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .runtime-reason {
    margin-top: 16px;
  }

  @media (max-width: 760px) {
    .runtime-header,
    .runtime-actions {
      align-items: stretch;
      flex-direction: column;
    }
  }
</style>
