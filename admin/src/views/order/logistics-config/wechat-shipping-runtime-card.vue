<template>
  <ElCard v-if="canRead" shadow="never" class="runtime-card">
    <template #header>
      <div class="runtime-header">
        <div class="runtime-title">微信订单同步设置</div>
        <div class="runtime-actions">
          <ElButton :disabled="loading || saving" @click="loadRuntime">刷新</ElButton>
          <ElButton
            v-auth="'wechat-shipping:runtime:write'"
            type="primary"
            :loading="saving"
            :disabled="loading || !canSave"
            @click="saveRuntime"
          >
            保存设置
          </ElButton>
        </div>
      </div>
    </template>

    <ElAlert
      v-if="runtime && !runtime.runtimePersisted"
      title="运行设置尚未保存"
      description="当前按安全模式暂停微信订单同步；保存后立即生效。"
      type="warning"
      :closable="false"
      show-icon
      class="runtime-alert"
    />

    <div v-loading="loading" class="runtime-form">
      <div class="runtime-switches">
        <div class="runtime-switch-row">
          <div class="runtime-switch-title">
            <span>同步发货信息到微信</span>
            <ElPopover placement="top" :width="300" trigger="click">
              <template #reference>
                <button type="button" class="runtime-help" aria-label="同步发货信息到微信说明">
                  <ArtSvgIcon icon="ri:question-line" />
                </button>
              </template>
              开启后，发货时会将配送方式、快递公司和运单号同步到微信订单。关闭不影响商城本地发货和物流查询。
            </ElPopover>
          </div>
          <ElSwitch
            :model-value="draft.uploadEnabled"
            :disabled="!canWrite || saving"
            aria-label="同步发货信息到微信"
            @change="handleUploadChange"
          />
        </div>
        <div class="runtime-switch-row">
          <div class="runtime-switch-title">
            <span>自动补传失败记录</span>
            <ElPopover placement="top" :width="300" trigger="click">
              <template #reference>
                <button type="button" class="runtime-help" aria-label="自动补传失败记录说明">
                  <ArtSvgIcon icon="ri:question-line" />
                </button>
              </template>
              开启后，系统会自动重试待上传或暂时失败的记录，并核对结果未知的记录；同时自动开启发货信息同步。
            </ElPopover>
          </div>
          <ElSwitch
            :model-value="draft.deliveryEnabled"
            :disabled="!canWrite || saving"
            aria-label="自动补传失败记录"
            @change="handleDeliveryChange"
          />
        </div>
        <div class="runtime-switch-row">
          <div class="runtime-switch-title">
            <span>自动同步微信收货状态</span>
            <ElPopover placement="top" :width="300" trigger="click">
              <template #reference>
                <button type="button" class="runtime-help" aria-label="自动同步微信收货状态说明">
                  <ArtSvgIcon icon="ri:question-line" />
                </button>
              </template>
              开启后，系统会定期查询微信订单的收货状态；微信确认收货后，本地订单会自动完成，同时自动开启发货信息同步。
            </ElPopover>
          </div>
          <ElSwitch
            :model-value="draft.receiptReconciliationEnabled"
            :disabled="!canWrite || saving"
            aria-label="自动同步微信收货状态"
            @change="handleReceiptChange"
          />
        </div>
      </div>

      <div v-if="runtime?.updatedAt" class="runtime-meta">
        上次保存：{{ formatDateTime(runtime.updatedAt) }}
      </div>
    </div>
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
    receiptReconciliationEnabled: false
  })

  const changed = computed(
    () =>
      runtime.value !== null &&
      (draft.uploadEnabled !== runtime.value.uploadEnabled ||
        draft.deliveryEnabled !== runtime.value.deliveryEnabled ||
        draft.receiptReconciliationEnabled !== runtime.value.receiptReconciliationEnabled)
  )
  const canSave = computed(
    () =>
      canWrite.value && runtime.value !== null && (changed.value || !runtime.value.runtimePersisted)
  )

  const fill = (value: Api.Order.WechatShippingRuntime) => {
    runtime.value = value
    draft.uploadEnabled = value.uploadEnabled
    draft.deliveryEnabled = value.deliveryEnabled
    draft.receiptReconciliationEnabled = value.receiptReconciliationEnabled
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
        version: runtime.value.version
      })
      fill(updated)
      ElMessage.success('微信订单同步设置已保存')
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

  .runtime-switch-title {
    display: inline-flex;
    gap: 5px;
    align-items: center;
  }

  .runtime-help {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 2px;
    margin: 0;
    font-size: 15px;
    color: var(--el-text-color-secondary);
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 4px;

    &:hover {
      color: var(--el-color-primary);
    }

    &:focus-visible {
      color: var(--el-color-primary);
      outline: 2px solid var(--el-color-primary-light-5);
      outline-offset: 1px;
    }
  }

  @media (max-width: 760px) {
    .runtime-header,
    .runtime-actions {
      align-items: stretch;
      flex-direction: column;
    }
  }
</style>
