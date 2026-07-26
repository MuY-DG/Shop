<template>
  <div class="storage-config">
    <ElAlert
      title="保存后会立即应用到后续上传；已有文件仍按各自记录的存储提供方读取。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-header__title">对象存储配置</div>
            <div class="section-header__subtitle">选择文件保存位置，并配置公开文件的访问域名</div>
          </div>
          <div class="section-header__aside">
            <ElTag type="success">
              {{ config ? `正在使用：${formatProvider(config.provider)}` : '配置加载中' }}
            </ElTag>
          </div>
        </div>
      </template>

      <ElForm
        ref="formRef"
        v-loading="loading"
        :model="formData"
        :rules="rules"
        label-width="128px"
        class="storage-form"
      >
        <ElFormItem label="存储方式" prop="provider">
          <ElSegmented v-model="formData.provider" :options="providerOptions" />
        </ElFormItem>

        <template v-if="formData.provider === 'LOCAL'">
          <ElFormItem label="本地目录" prop="localRoot">
            <ElInput v-model="formData.localRoot" placeholder="例如：var/uploads" />
            <div class="form-tip">相对于后端启动目录，也可以填写绝对路径。</div>
          </ElFormItem>
          <ElFormItem label="公开访问地址" prop="localPublicBaseUrl">
            <ElInput
              v-model="formData.localPublicBaseUrl"
              placeholder="例如：http://localhost:8080"
            />
            <div class="form-tip">返回链接会拼接为：该地址 /files/public/文件路径。</div>
          </ElFormItem>
        </template>

        <template v-else>
          <ElFormItem label="地域" prop="cosRegion">
            <ElInput v-model="formData.cosRegion" placeholder="例如：ap-guangzhou" />
          </ElFormItem>
          <ElFormItem label="存储桶" prop="cosBucket">
            <ElInput v-model="formData.cosBucket" placeholder="例如：shop-1250000000" />
            <div class="form-tip">请填写包含 APPID 后缀的完整存储桶名称。</div>
          </ElFormItem>
          <ElFormItem label="SecretId" prop="cosSecretId">
            <ElInput
              v-model="formData.cosSecretId"
              autocomplete="new-password"
              :placeholder="secretIdPlaceholder"
            />
          </ElFormItem>
          <ElFormItem label="SecretKey" prop="cosSecretKey">
            <ElInput
              v-model="formData.cosSecretKey"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="
                config?.cosSecretKeyConfigured ? '已配置，留空不修改' : '请输入 SecretKey'
              "
            />
          </ElFormItem>
          <ElFormItem label="公开访问域名" prop="cosPublicBaseUrl">
            <ElInput v-model="formData.cosPublicBaseUrl" :placeholder="cosDomainPlaceholder" />
            <div class="form-tip"
              >可留空使用 COS 默认域名；如已绑定自定义源站域名，可填写 HTTPS 地址。</div
            >
          </ElFormItem>
        </template>

        <ElFormItem>
          <ElButton
            type="primary"
            v-auth="'storage:config:write'"
            :loading="saving"
            @click="handleSave"
          >
            {{ formData.provider === config?.provider ? '保存配置' : '保存并使用' }}
          </ElButton>
          <ElButton :disabled="!dirty || loading || saving" @click="resetUnsavedChanges">
            撤销未保存修改
          </ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { fetchStorageConfig, updateStorageConfig } from '@/api/storage'

  defineOptions({ name: 'DeveloperStorageConfig' })

  const loading = ref(false)
  const saving = ref(false)
  const config = ref<Api.Storage.Config | null>(null)
  const baseline = ref('')
  const formRef = ref<FormInstance>()

  const createDefaultForm = (): Api.Storage.ConfigForm => ({
    provider: 'LOCAL',
    localPublicBaseUrl: '',
    cosPublicBaseUrl: '',
    localRoot: 'var/uploads',
    cosRegion: '',
    cosBucket: '',
    cosSecretId: '',
    cosSecretKey: ''
  })

  const formData = reactive<Api.Storage.ConfigForm>(createDefaultForm())
  const snapshot = () =>
    JSON.stringify({
      provider: formData.provider,
      localPublicBaseUrl: formData.localPublicBaseUrl,
      cosPublicBaseUrl: formData.cosPublicBaseUrl,
      localRoot: formData.localRoot,
      cosRegion: formData.cosRegion,
      cosBucket: formData.cosBucket,
      cosSecretId: formData.cosSecretId,
      cosSecretKey: formData.cosSecretKey
    })
  const dirty = computed(() => snapshot() !== baseline.value)
  const providerOptions: Array<{ label: string; value: Api.Storage.Provider }> = [
    { label: '本地存储', value: 'LOCAL' },
    { label: '腾讯云 COS', value: 'TENCENT_COS' }
  ]

  const cosDomainPlaceholder = computed(() => {
    if (formData.cosBucket && formData.cosRegion) {
      return `https://${formData.cosBucket}.cos.${formData.cosRegion}.myqcloud.com`
    }
    return '留空自动生成 COS 默认域名'
  })

  const secretIdPlaceholder = computed(() =>
    config.value?.cosSecretIdMasked
      ? `当前：${config.value.cosSecretIdMasked}，留空不修改`
      : '请输入 SecretId'
  )

  const requireCosSecret = (configured: boolean, message: string) => ({
    validator: (_rule: unknown, value: string | undefined, callback: (error?: Error) => void) => {
      if (formData.provider !== 'TENCENT_COS' || String(value || '').trim() || configured) {
        callback()
        return
      }
      callback(new Error(message))
    },
    trigger: 'blur'
  })

  const rules = computed<FormRules<Api.Storage.ConfigForm>>(() => ({
    provider: [{ required: true, message: '请选择存储方式', trigger: 'change' }],
    localRoot: [
      {
        validator: (_rule, value, callback) => {
          if (formData.provider !== 'LOCAL' || String(value || '').trim()) callback()
          else callback(new Error('请输入本地目录'))
        },
        trigger: 'blur'
      }
    ],
    localPublicBaseUrl: [
      {
        validator: (_rule, value, callback) => {
          const text = String(value || '').trim()
          if (!/^https?:\/\/[^\s]+$/i.test(text)) {
            callback(new Error('请输入正确的 HTTP(S) 地址'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ],
    cosPublicBaseUrl: [
      {
        validator: (_rule, value, callback) => {
          const text = String(value || '').trim()
          if (!text) {
            callback()
            return
          }
          if (!/^https?:\/\/[^\s]+$/i.test(text)) {
            callback(new Error('请输入正确的 HTTP(S) 地址'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ],
    cosRegion: [
      {
        validator: (_rule, value, callback) => {
          if (
            formData.provider !== 'TENCENT_COS' ||
            /^[a-z0-9-]{2,64}$/.test(String(value || '').trim())
          )
            callback()
          else callback(new Error('请输入正确的 COS 地域简称'))
        },
        trigger: 'blur'
      }
    ],
    cosBucket: [
      {
        validator: (_rule, value, callback) => {
          if (
            formData.provider !== 'TENCENT_COS' ||
            /^[a-z0-9][a-z0-9-]{0,116}-[0-9]{5,20}$/.test(String(value || '').trim())
          )
            callback()
          else callback(new Error('请输入包含 APPID 后缀的完整存储桶名称'))
        },
        trigger: 'blur'
      }
    ],
    cosSecretId: [requireCosSecret(Boolean(config.value?.cosSecretIdMasked), '请输入 SecretId')],
    cosSecretKey: [
      requireCosSecret(Boolean(config.value?.cosSecretKeyConfigured), '请输入 SecretKey')
    ]
  }))

  const formatProvider = (provider?: Api.Storage.Provider) =>
    provider === 'TENCENT_COS' ? '腾讯云 COS' : '本地存储'

  const fillForm = (value: Api.Storage.Config) => {
    Object.assign(formData, {
      provider: value.provider,
      localPublicBaseUrl:
        value.localPublicBaseUrl ?? (value.provider === 'LOCAL' ? value.publicBaseUrl : ''),
      cosPublicBaseUrl:
        value.cosPublicBaseUrl ?? (value.provider === 'TENCENT_COS' ? value.publicBaseUrl : ''),
      localRoot: value.localRoot,
      cosRegion: value.cosRegion,
      cosBucket: value.cosBucket,
      cosSecretId: '',
      cosSecretKey: ''
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
      config.value = await fetchStorageConfig()
      fillForm(config.value)
    } finally {
      loading.value = false
    }
  }

  const handleSave = async () => {
    await formRef.value?.validate()
    saving.value = true
    try {
      const payload: Api.Storage.ConfigForm = {
        provider: formData.provider,
        publicBaseUrl:
          formData.provider === 'TENCENT_COS'
            ? String(formData.cosPublicBaseUrl || '').trim()
            : String(formData.localPublicBaseUrl || '').trim(),
        localPublicBaseUrl: String(formData.localPublicBaseUrl || '').trim(),
        cosPublicBaseUrl: String(formData.cosPublicBaseUrl || '').trim(),
        localRoot: String(formData.localRoot || '').trim(),
        cosRegion: String(formData.cosRegion || '').trim(),
        cosBucket: String(formData.cosBucket || '').trim()
      }
      const secretId = String(formData.cosSecretId || '').trim()
      const secretKey = String(formData.cosSecretKey || '').trim()
      if (secretId) payload.cosSecretId = secretId
      if (secretKey) payload.cosSecretKey = secretKey
      config.value = await updateStorageConfig(payload)
      fillForm(config.value)
    } finally {
      saving.value = false
    }
  }

  onMounted(loadConfig)
</script>

<style scoped lang="scss">
  .storage-config {
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

  .storage-form {
    max-width: 820px;
  }

  .form-tip {
    width: 100%;
  }
</style>
