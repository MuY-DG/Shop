<template>
  <div class="storage-config">
    <ElAlert
      title="所有文件统一存储到腾讯云 COS；配置加密保存在数据库，保存后立即生效。"
      description="SecretId/SecretKey 是 COS 访问凭证；加密它们的应用主密钥由服务端独立配置，不是这两个值。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <div class="section-header__title">腾讯云 COS 配置</div>
            <div class="section-header__subtitle">配置存储桶、公开访问域名和访问凭证</div>
          </div>
          <div class="section-header__aside">
            <ElTag :type="config?.configured ? 'success' : 'warning'">
              {{ config?.configured ? '已配置' : '待配置' }}
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
        <ElFormItem label="地域" prop="region">
          <ElInput v-model="formData.region" placeholder="例如：ap-guangzhou" />
        </ElFormItem>
        <ElFormItem label="存储桶" prop="bucket">
          <ElInput v-model="formData.bucket" placeholder="例如：shop-1250000000" />
          <div class="form-tip">请填写包含 APPID 后缀的完整存储桶名称。</div>
        </ElFormItem>
        <ElFormItem label="SecretId" prop="secretId">
          <ElInput
            v-model="formData.secretId"
            autocomplete="new-password"
            :placeholder="secretIdPlaceholder"
          />
        </ElFormItem>
        <ElFormItem label="SecretKey" prop="secretKey">
          <ElInput
            v-model="formData.secretKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="config?.secretKeyConfigured ? '已配置，留空不修改' : '请输入 SecretKey'"
          />
        </ElFormItem>
        <ElFormItem label="COS 客户端域名" prop="publicBaseUrl">
          <ElInput v-model="formData.publicBaseUrl" :placeholder="cosDomainPlaceholder" />
          <div class="form-tip"
            >可留空使用 COS 默认域名；也可填写已绑定到当前存储桶的 HTTPS
            自定义源站根域名。客户端上传、公开读取和私有签名下载都会使用该配置，自定义源站不会开启
            CDN。</div
          >
        </ElFormItem>

        <ElFormItem>
          <ElButton
            type="primary"
            v-auth="'storage:config:write'"
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
    publicBaseUrl: '',
    region: '',
    bucket: '',
    secretId: '',
    secretKey: ''
  })

  const formData = reactive<Api.Storage.ConfigForm>(createDefaultForm())
  const snapshot = () =>
    JSON.stringify({
      publicBaseUrl: formData.publicBaseUrl,
      region: formData.region,
      bucket: formData.bucket,
      secretId: formData.secretId,
      secretKey: formData.secretKey
    })
  const dirty = computed(() => snapshot() !== baseline.value)

  const cosDomainPlaceholder = computed(() => {
    if (formData.bucket && formData.region) {
      return `https://${formData.bucket}.cos.${formData.region}.myqcloud.com`
    }
    return '留空自动生成 COS 默认域名'
  })

  const secretIdPlaceholder = computed(() =>
    config.value?.secretIdMasked
      ? `当前：${config.value.secretIdMasked}，留空不修改`
      : '请输入 SecretId'
  )

  const httpsRootHostname =
    /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/i

  const isHttpsRootOrigin = (value: string) => {
    const candidate = value.trim()
    const authority = /^https:\/\/([^/?#]+)\/?$/i.exec(candidate)?.[1]
    if (!authority || !httpsRootHostname.test(authority)) return false
    try {
      const parsed = new URL(candidate)
      return (
        parsed.protocol === 'https:' &&
        !parsed.username &&
        !parsed.password &&
        !parsed.port &&
        (parsed.pathname === '/' || parsed.pathname === '') &&
        !parsed.search &&
        !parsed.hash &&
        httpsRootHostname.test(parsed.hostname)
      )
    } catch {
      return false
    }
  }

  const requireSecret = (configured: boolean, message: string) => ({
    validator: (_rule: unknown, value: string | undefined, callback: (error?: Error) => void) => {
      if (String(value || '').trim() || configured) {
        callback()
        return
      }
      callback(new Error(message))
    },
    trigger: 'blur'
  })

  const rules = computed<FormRules<Api.Storage.ConfigForm>>(() => ({
    publicBaseUrl: [
      {
        validator: (_rule, value, callback) => {
          const text = String(value || '').trim()
          if (!text || isHttpsRootOrigin(text)) {
            callback()
            return
          }
          callback(new Error('请输入不带端口、路径或参数的 HTTPS 根域名'))
        },
        trigger: 'blur'
      }
    ],
    region: [
      {
        validator: (_rule, value, callback) => {
          if (/^[a-z0-9-]{2,64}$/.test(String(value || '').trim())) callback()
          else callback(new Error('请输入正确的 COS 地域简称'))
        },
        trigger: 'blur'
      }
    ],
    bucket: [
      {
        validator: (_rule, value, callback) => {
          if (/^[a-z0-9][a-z0-9-]{0,116}-[0-9]{5,20}$/.test(String(value || '').trim())) callback()
          else callback(new Error('请输入包含 APPID 后缀的完整存储桶名称'))
        },
        trigger: 'blur'
      }
    ],
    secretId: [requireSecret(Boolean(config.value?.secretIdMasked), '请输入 SecretId')],
    secretKey: [requireSecret(Boolean(config.value?.secretKeyConfigured), '请输入 SecretKey')]
  }))

  const fillForm = (value: Api.Storage.Config) => {
    Object.assign(formData, {
      publicBaseUrl: value.publicBaseUrl,
      region: value.region,
      bucket: value.bucket,
      secretId: '',
      secretKey: ''
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
        publicBaseUrl: String(formData.publicBaseUrl || '').trim(),
        region: String(formData.region || '').trim(),
        bucket: String(formData.bucket || '').trim()
      }
      const secretId = String(formData.secretId || '').trim()
      const secretKey = String(formData.secretKey || '').trim()
      if (secretId) payload.secretId = secretId
      if (secretKey) payload.secretKey = secretKey
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
</style>
