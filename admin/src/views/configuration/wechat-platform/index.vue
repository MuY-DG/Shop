<template>
  <div class="wechat-platform-config">
    <ElAlert
      title="AppSecret 使用应用主密钥加密保存到数据库；后台不会返回明文。"
      description="加密主密钥仍由服务器管理。如当前仍来自旧 ENV，请先显式导入数据库，验证后再删除 ENV。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-header__title">微信小程序平台配置</div>
            <div class="section-header__subtitle">登录、手机号、发货与服务动态共用这组凭据</div>
          </div>
          <div class="section-header__aside">
            <ElTag :type="sourceTagType">{{ sourceLabel }}</ElTag>
          </div>
        </div>
      </template>

      <ElForm
        ref="formRef"
        v-loading="loading"
        :model="formData"
        :rules="rules"
        label-width="120px"
        class="platform-form"
      >
        <ElFormItem label="AppID" prop="appId">
          <ElInput v-model="formData.appId" maxlength="64" placeholder="请输入微信小程序 AppID" />
        </ElFormItem>

        <ElFormItem label="AppSecret" prop="appSecret">
          <ElInput
            v-model="formData.appSecret"
            type="password"
            show-password
            maxlength="256"
            autocomplete="new-password"
            :placeholder="secretPlaceholder"
          />
          <div class="form-tip">{{ secretTip }}</div>
        </ElFormItem>

        <ElFormItem>
          <ElButton
            type="primary"
            v-auth="'wechat-platform:config:write'"
            :loading="saving"
            @click="handleSave"
          >
            保存配置
          </ElButton>
          <ElButton
            v-if="config?.legacyEnvironmentImportAvailable"
            v-auth="'wechat-platform:config:write'"
            :loading="importing"
            @click="handleImportEnvironment"
          >
            导入旧 ENV 配置
          </ElButton>
          <ElButton :disabled="!dirty || loading || saving || importing" @click="resetForm">
            撤销未保存修改
          </ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import {
    fetchWechatPlatformConfig,
    importWechatPlatformLegacyEnvironment,
    updateWechatPlatformConfig
  } from '@/api/wechat-platform'
  import {
    buildWechatPlatformUpdate,
    canRetainWechatPlatformSecret,
    createWechatPlatformForm
  } from './wechat-platform-state'

  defineOptions({ name: 'WechatPlatformConfig' })

  const formRef = ref<FormInstance>()
  const loading = ref(false)
  const saving = ref(false)
  const importing = ref(false)
  const config = ref<Api.WechatPlatform.Config | null>(null)
  const formData = reactive<Api.WechatPlatform.ConfigForm>(createWechatPlatformForm())
  const baseline = ref('')

  const snapshot = () => JSON.stringify(formData)
  const dirty = computed(() => snapshot() !== baseline.value)
  const canRetainSecret = computed(() => canRetainWechatPlatformSecret(config.value))
  const sourceLabel = computed(() => {
    if (config.value?.source === 'DATABASE') return '数据库配置'
    if (config.value?.source === 'ENVIRONMENT') return '待导入的旧 ENV'
    return '待配置'
  })
  const sourceTagType = computed(() => {
    if (config.value?.source === 'DATABASE') return 'success'
    if (config.value?.source === 'ENVIRONMENT') return 'warning'
    return 'danger'
  })
  const secretPlaceholder = computed(() =>
    canRetainSecret.value ? '已配置，留空不修改' : '请输入 AppSecret'
  )
  const secretTip = computed(() =>
    canRetainSecret.value ? '已配置，留空表示不修改' : '新建配置时必须输入；页面不会回填明文'
  )

  const rules = computed<FormRules<Api.WechatPlatform.ConfigForm>>(() => ({
    appId: [
      { required: true, message: '请输入 AppID', trigger: 'blur' },
      { max: 64, message: 'AppID 最长 64 个字符', trigger: 'blur' }
    ],
    appSecret: [
      {
        validator: (_rule, value, callback) => {
          if (String(value || '').trim() || canRetainSecret.value) callback()
          else callback(new Error('请输入 AppSecret，或使用旧 ENV 导入'))
        },
        trigger: 'blur'
      }
    ]
  }))

  const fillForm = (value: Api.WechatPlatform.Config) => {
    Object.assign(formData, createWechatPlatformForm(value))
    baseline.value = snapshot()
    formRef.value?.clearValidate()
  }

  const resetForm = () => {
    if (config.value) fillForm(config.value)
  }

  const loadConfig = async () => {
    loading.value = true
    try {
      config.value = await fetchWechatPlatformConfig()
      fillForm(config.value)
    } finally {
      loading.value = false
    }
  }

  const handleSave = async () => {
    if (!config.value) return
    await formRef.value?.validate()
    saving.value = true
    try {
      config.value = await updateWechatPlatformConfig(
        buildWechatPlatformUpdate(config.value, formData)
      )
      fillForm(config.value)
    } finally {
      saving.value = false
    }
  }

  const handleImportEnvironment = async () => {
    if (!config.value?.legacyEnvironmentImportAvailable) return
    await ElMessageBox.confirm(
      '仅当数据库尚无微信平台配置时才会导入；导入后应先验证真实微信功能，再删除服务器 ENV。',
      '确认导入旧 ENV',
      { type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '取消' }
    )
    importing.value = true
    try {
      config.value = await importWechatPlatformLegacyEnvironment(config.value.version)
      fillForm(config.value)
    } finally {
      importing.value = false
    }
  }

  onMounted(loadConfig)
</script>

<style scoped lang="scss">
  .wechat-platform-config {
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

  .section-header__aside {
    flex-shrink: 0;
  }

  .platform-form {
    max-width: 820px;
  }
</style>
