<template>
  <div class="image-compression-config">
    <ElAlert
      title="JPEG、PNG、WebP 经 Tinify 成功处理后统一输出为 WebP，且不保留版权、拍摄时间、GPS 等元数据；GIF、SVG 和压缩失败时的回退原图保持不变。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElAlert
      v-if="config?.requestedEnabled && !config.effectiveEnabled"
      :title="disabledAlertTitle"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-header__title">图片压缩配置</div>
            <div class="section-header__subtitle">
              统一控制上传图片的 Tinify 压缩、密钥来源与月度使用上限
            </div>
          </div>
          <ElTag :type="config?.effectiveEnabled ? 'success' : 'info'">
            {{ config?.effectiveEnabled ? '压缩已生效' : '压缩未生效' }}
          </ElTag>
        </div>
      </template>

      <ElForm
        ref="formRef"
        v-loading="loading"
        :model="formData"
        :rules="rules"
        label-width="150px"
        class="compression-form"
      >
        <ElFormItem label="启用图片压缩" prop="requestedEnabled">
          <ElSwitch v-model="formData.requestedEnabled" />
          <div class="form-tip">
            默认开启。额度耗尽或密钥不可用时系统会暂停实际压缩，但保留此开关意愿，以便条件恢复后自动生效。
          </div>
        </ElFormItem>

        <ElFormItem label="配置来源" prop="configSource">
          <ElRadioGroup v-model="formData.configSource">
            <ElRadioButton value="AUTO">自动</ElRadioButton>
            <ElRadioButton value="ENV">配置文件</ElRadioButton>
            <ElRadioButton value="DB">后台配置</ElRadioButton>
          </ElRadioGroup>
          <div class="form-tip">{{ sourceTip }}</div>
        </ElFormItem>

        <ElFormItem v-if="usesDatabaseKey" label="Tinify API Key" prop="apiKey">
          <ElInput
            v-model="formData.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="keyPlaceholder"
          />
          <div class="form-tip">
            密钥由后端加密存储且不会回显；留空表示不修改已经保存的后台密钥。
          </div>
        </ElFormItem>

        <ElFormItem label="月度使用上限" prop="monthlyLimit">
          <ElInputNumber
            v-model="formData.monthlyLimit"
            :min="1"
            :max="10000000"
            :precision="0"
            controls-position="right"
          />
          <span class="input-suffix">次</span>
          <div class="form-tip">
            用于计算剩余次数并在耗尽时自动暂停。WebP 源图通常消耗 1 次，JPEG、PNG 转为 WebP 通常消耗
            2 次。
          </div>
        </ElFormItem>

        <ElFormItem>
          <ElButton
            v-auth="'image-compression:config:write'"
            type="primary"
            :loading="saving"
            @click="handleSave"
          >
            保存配置
          </ElButton>
          <ElButton :disabled="!dirty || loading || saving" @click="resetUnsavedChanges">
            撤销未保存修改
          </ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>

    <div class="info-grid">
      <ElCard shadow="never" class="status-card">
        <template #header>
          <div class="section-header">
            <div>
              <div class="section-header__title">当前状态</div>
              <div class="section-header__subtitle">管理员配置与系统实际运行状态</div>
            </div>
          </div>
        </template>

        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="管理员开关">
            <ElTag :type="config?.requestedEnabled ? 'success' : 'info'" size="small">
              {{ config?.requestedEnabled ? '已开启' : '已关闭' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="实际状态">
            <ElTag :type="config?.effectiveEnabled ? 'success' : 'warning'" size="small">
              {{ config?.effectiveEnabled ? '生效中' : '未生效' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="当前来源">
            {{ config ? configSourceLabel(config.configSource) : '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="默认来源">
            {{ config ? configSourceLabel(config.defaultConfigSource) : '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="后台配置">
            {{ config?.persisted ? '已持久化' : '未持久化，使用默认配置' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="密钥">
            {{ keyStatusText }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="自动停用原因">
            <span :class="{ 'warning-text': Boolean(config?.autoDisabledReason) }">
              {{
                config?.autoDisabledReason
                  ? formatAutoDisabledReason(config.autoDisabledReason)
                  : '-'
              }}
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="配置更新时间">
            {{ formatConfigDateTime(config?.updatedAt) }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </ElCard>

      <ElCard shadow="never" class="quota-card">
        <template #header>
          <div class="section-header">
            <div>
              <div class="section-header__title">本月额度</div>
              <div class="section-header__subtitle">以 Tinify 最近一次返回的用量为准</div>
            </div>
            <ElButton
              v-auth="'image-compression:config:write'"
              :loading="refreshing"
              :disabled="loading || saving"
              @click="handleRefresh"
            >
              刷新额度
            </ElButton>
          </div>
        </template>

        <div class="quota-stats">
          <div class="quota-stat">
            <span>月度上限</span>
            <strong>{{ formatCount(config?.monthlyLimit) }}</strong>
          </div>
          <div class="quota-stat">
            <span>已使用</span>
            <strong>{{ formatCount(config?.compressionCount) }}</strong>
          </div>
          <div class="quota-stat quota-stat--remaining">
            <span>剩余可用</span>
            <strong>{{ formatCount(config?.remainingCount) }}</strong>
          </div>
        </div>

        <ElProgress
          v-if="quotaPercentage != null"
          :percentage="quotaPercentage"
          :status="quotaStatus"
          :stroke-width="12"
        />
        <div v-else class="quota-empty">额度尚未同步，请配置有效密钥后刷新。</div>

        <div class="quota-meta">
          <span>计费周期：{{ config?.quotaPeriod || '-' }}</span>
          <span>最后同步：{{ formatConfigDateTime(config?.lastCheckedAt) }}</span>
        </div>
      </ElCard>
    </div>

    <ElCard shadow="never">
      <template #header>
        <div>
          <div class="section-header__title">固定处理策略</div>
          <div class="section-header__subtitle">以下策略对 Tinify 成功处理的上传图片生效</div>
        </div>
      </template>

      <ElDescriptions :column="2" border class="policy-descriptions">
        <ElDescriptionsItem label="输出格式">
          <ElTag type="success">WebP</ElTag>
          <span class="policy-note">固定转换格式，不在管理端变更</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="图片元数据">
          <ElTag type="success">不保留</ElTag>
          <span class="policy-note">WebP 结果不复制版权、拍摄时间及位置信息</span>
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchImageCompressionConfig,
    refreshImageCompressionQuota,
    updateImageCompressionConfig
  } from '@/api/image-compression'
  import {
    buildImageCompressionConfigPayload,
    calculateQuotaUsagePercentage,
    canReuseDatabaseKey,
    configSourceLabel,
    formatAutoDisabledReason,
    formatConfigDateTime,
    quotaProgressStatus
  } from './image-compression-state'

  defineOptions({ name: 'ImageCompressionConfig' })

  const loading = ref(false)
  const saving = ref(false)
  const refreshing = ref(false)
  const config = ref<Api.ImageCompression.Config | null>(null)
  const baseline = ref('')
  const formRef = ref<FormInstance>()
  const formData = reactive<Api.ImageCompression.ConfigForm>({
    requestedEnabled: true,
    configSource: 'AUTO',
    apiKey: '',
    monthlyLimit: null
  })

  const snapshot = () =>
    JSON.stringify({
      requestedEnabled: formData.requestedEnabled,
      configSource: formData.configSource,
      apiKey: formData.apiKey,
      monthlyLimit: formData.monthlyLimit
    })

  const dirty = computed(() => snapshot() !== baseline.value)
  const usesDatabaseKey = computed(
    () =>
      formData.configSource === 'DB' ||
      (formData.configSource === 'AUTO' && config.value?.defaultConfigSource === 'DB')
  )
  const reusableDatabaseKey = computed(() => canReuseDatabaseKey(config.value))
  const keyPlaceholder = computed(() =>
    reusableDatabaseKey.value && config.value?.apiKeyMasked
      ? `当前：${config.value.apiKeyMasked}，留空不修改`
      : '请输入 Tinify API Key'
  )
  const sourceTip = computed(() => {
    if (formData.configSource === 'AUTO') {
      const defaultSource = config.value
        ? configSourceLabel(config.value.defaultConfigSource)
        : '服务端默认配置'
      return `跟随服务端默认来源，当前默认：${defaultSource}。`
    }
    if (formData.configSource === 'ENV') {
      return '从服务端配置文件或环境变量读取；如需修改密钥，请更新部署环境。'
    }
    return '使用后台加密保存的密钥；输入新密钥会替换当前后台密钥。'
  })
  const keyStatusText = computed(() => {
    if (!config.value?.keyConfigured) return '未配置'
    return config.value.apiKeyMasked ? `已配置（${config.value.apiKeyMasked}）` : '已配置'
  })
  const disabledAlertTitle = computed(() => {
    const reason = !config.value?.keyConfigured
      ? '未配置可用的 Tinify API Key'
      : formatAutoDisabledReason(config.value?.autoDisabledReason)
    return `管理员已开启图片压缩，但当前未生效。原因：${reason}`
  })
  const quotaPercentage = computed(() => calculateQuotaUsagePercentage(config.value))
  const quotaStatus = computed(() => quotaProgressStatus(config.value, quotaPercentage.value))

  const rules = computed<FormRules<Api.ImageCompression.ConfigForm>>(() => ({
    apiKey: [
      {
        validator: (_rule, value, callback) => {
          const key = String(value || '').trim()
          if (
            !formData.requestedEnabled ||
            !usesDatabaseKey.value ||
            key ||
            reusableDatabaseKey.value
          ) {
            callback()
            return
          }
          callback(new Error('启用后台密钥来源前，请先填写 Tinify API Key'))
        },
        trigger: 'blur'
      },
      {
        max: 256,
        message: 'Tinify API Key 不能超过 256 个字符',
        trigger: 'blur'
      }
    ],
    monthlyLimit: [
      {
        validator: (_rule, value, callback) => {
          if (
            value == null ||
            (Number.isInteger(Number(value)) && Number(value) >= 1 && Number(value) <= 10000000)
          ) {
            callback()
            return
          }
          callback(new Error('月度使用上限必须是 1 到 10000000 之间的整数'))
        },
        trigger: 'change'
      }
    ]
  }))

  const fillForm = (value: Api.ImageCompression.Config) => {
    Object.assign(formData, {
      requestedEnabled: value.requestedEnabled,
      configSource: value.configSource,
      apiKey: '',
      monthlyLimit: value.monthlyLimit
    })
    baseline.value = snapshot()
    formRef.value?.clearValidate()
  }

  const resetUnsavedChanges = () => {
    if (config.value) fillForm(config.value)
  }

  const loadConfig = async () => {
    loading.value = true
    try {
      const value = await fetchImageCompressionConfig()
      config.value = value
      fillForm(value)
    } finally {
      loading.value = false
    }
  }

  const handleSave = async () => {
    await formRef.value?.validate()
    saving.value = true
    try {
      const value = await updateImageCompressionConfig(buildImageCompressionConfigPayload(formData))
      config.value = value
      fillForm(value)
    } finally {
      saving.value = false
    }
  }

  const handleRefresh = async () => {
    const preserveUnsavedForm = dirty.value
    refreshing.value = true
    try {
      const value = await refreshImageCompressionQuota()
      config.value = value
      if (!preserveUnsavedForm) fillForm(value)
    } finally {
      refreshing.value = false
    }
  }

  const formatCount = (value?: number | null) =>
    value == null ? '待同步' : `${value.toLocaleString('zh-CN')} 次`

  onMounted(loadConfig)
</script>

<style scoped lang="scss">
  .image-compression-config {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .section-header {
    display: flex;
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .section-header__title {
    font-size: 15px;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .section-header__subtitle,
  .form-tip {
    margin-top: 2px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .compression-form {
    max-width: 860px;
  }

  .form-tip {
    width: 100%;
  }

  .input-suffix {
    margin-left: 8px;
    color: var(--el-text-color-regular);
  }

  .info-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 12px;
  }

  .status-card,
  .quota-card {
    min-width: 0;
  }

  .warning-text {
    color: var(--el-color-warning);
  }

  .quota-stats {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
    margin-bottom: 24px;
  }

  .quota-stat {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 14px;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  .quota-stat span {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .quota-stat strong {
    font-size: 18px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .quota-stat--remaining strong {
    color: var(--el-color-primary);
  }

  .quota-empty {
    padding: 10px 12px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
    text-align: center;
    background: var(--el-fill-color-lighter);
    border-radius: 6px;
  }

  .quota-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 24px;
    margin-top: 18px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .policy-note {
    margin-left: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  @media (width <= 960px) {
    .info-grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  @media (width <= 640px) {
    .section-header {
      flex-direction: column;
      align-items: stretch;
    }

    .quota-stats {
      grid-template-columns: minmax(0, 1fr);
    }

    .policy-descriptions {
      :deep(.el-descriptions__body) {
        overflow-x: auto;
      }
    }
  }
</style>
