<template>
  <div class="amap-config">
    <ElAlert
      title="小程序使用微信原生地图展示与选点，使用高德微信小程序 SDK 获取地址、附近地点和搜索结果；Key 会下发到已登录小程序，请在高德控制台绑定正确的小程序。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-header__title">高德地图配置</div>
            <div class="section-header__subtitle">
              微信原生地图负责展示与选点，高德小程序 SDK 负责地址解析、附近地点和关键词搜索
            </div>
          </div>
          <ElTag :type="config?.enabled ? 'success' : 'info'">
            {{ config?.enabled ? '定位服务已启用' : '定位服务未启用' }}
          </ElTag>
        </div>
      </template>

      <ElForm
        ref="formRef"
        v-loading="loading"
        :model="formData"
        :rules="rules"
        label-width="140px"
        class="amap-form"
      >
        <ElFormItem label="启用定位辅助" prop="enabled">
          <ElSwitch v-model="formData.enabled" />
          <div class="form-tip">关闭后，小程序仍可继续手工填写收货地址。</div>
        </ElFormItem>

        <ElFormItem label="微信小程序 Key" prop="miniProgramKey">
          <ElInput
            v-model="formData.miniProgramKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="keyPlaceholder"
          />
          <div class="form-tip">
            请在高德开放平台创建“微信小程序”类型 Key。后端会加密存储，小程序进入选址页时获取；留空表示不修改。
          </div>
        </ElFormItem>

        <ElFormItem>
          <ElButton
            type="primary"
            v-auth="'amap:config:write'"
            :loading="saving"
            @click="handleSave"
          >
            保存配置
          </ElButton>
          <ElButton :disabled="!dirty || loading || saving" @click="resetUnsavedChanges">
            撤销未保存修改
          </ElButton>
          <ElLink
            href="https://console.amap.com/dev/key/app"
            target="_blank"
            type="primary"
            :underline="false"
          >
            前往高德开放平台
          </ElLink>
        </ElFormItem>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { fetchAmapConfig, updateAmapConfig } from '@/api/amap'

  defineOptions({ name: 'AmapConfig' })

  const loading = ref(false)
  const saving = ref(false)
  const config = ref<Api.Amap.Config | null>(null)
  const baseline = ref('')
  const formRef = ref<FormInstance>()
  const formData = reactive<Api.Amap.ConfigForm>({
    enabled: false,
    miniProgramKey: ''
  })

  const snapshot = () =>
    JSON.stringify({
      enabled: formData.enabled,
      miniProgramKey: formData.miniProgramKey
    })
  const dirty = computed(() => snapshot() !== baseline.value)
  const keyPlaceholder = computed(() =>
    config.value?.keyConfigured
      ? `当前：${config.value.miniProgramKeyMasked}，留空不修改`
      : '请输入高德微信小程序 Key'
  )
  const rules = computed<FormRules<Api.Amap.ConfigForm>>(() => ({
    miniProgramKey: [
      {
        validator: (_rule, value, callback) => {
          const key = String(value || '').trim()
          if (!formData.enabled || key || config.value?.keyConfigured) {
            callback()
            return
          }
          callback(new Error('启用地图选址前，请先填写微信小程序 Key'))
        },
        trigger: 'blur'
      },
      {
        max: 128,
        message: '微信小程序 Key 不能超过 128 个字符',
        trigger: 'blur'
      }
    ]
  }))

  const fillForm = (value: Api.Amap.Config) => {
    Object.assign(formData, {
      enabled: value.enabled,
      miniProgramKey: ''
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
      config.value = await fetchAmapConfig()
      fillForm(config.value)
    } finally {
      loading.value = false
    }
  }

  const handleSave = async () => {
    await formRef.value?.validate()
    saving.value = true
    try {
      const payload: Api.Amap.ConfigForm = {
        enabled: formData.enabled
      }
      const miniProgramKey = String(formData.miniProgramKey || '').trim()
      if (miniProgramKey) payload.miniProgramKey = miniProgramKey
      config.value = await updateAmapConfig(payload)
      fillForm(config.value)
    } finally {
      saving.value = false
    }
  }

  onMounted(loadConfig)
</script>

<style scoped lang="scss">
  .amap-config {
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

  .amap-form {
    max-width: 860px;
  }

  .form-tip {
    width: 100%;
  }

  :deep(.el-link) {
    margin-left: 8px;
  }
</style>
