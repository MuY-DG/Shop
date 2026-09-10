<template>
  <ElCard shadow="never">
    <template #header>小程序展示设置</template>
    <ElAlert
      v-if="loadError"
      title="展示设置加载失败，请重新加载"
      type="error"
      :closable="false"
      class="mb-4"
    />
    <ElForm
      ref="formRef"
      v-loading="loading"
      :model="form"
      :rules="rules"
      label-position="top"
      class="display-config-form"
    >
      <ElFormItem label="小程序展示名称" prop="displayName">
        <ElInput
          v-model.trim="form.displayName"
          maxlength="32"
          show-word-limit
          placeholder="请输入小程序展示名称"
          :disabled="!config || saving"
        />
        <div class="display-config-tip">
          用于小程序页面、分享标题和新支付的订单描述；微信平台上的正式名称需单独维护。
        </div>
      </ElFormItem>
      <ElFormItem>
        <ElButton
          v-auth="'wechat-platform:config:write'"
          type="primary"
          :loading="saving"
          :disabled="!dirty || loading"
          @click="save"
        >
          保存名称
        </ElButton>
        <ElButton :disabled="loading || saving" @click="loadConfig">重新加载</ElButton>
      </ElFormItem>
    </ElForm>
  </ElCard>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
  import { fetchDisplayConfig, updateDisplayConfig, type DisplayConfig } from '@/api/display-config'

  const formRef = ref<FormInstance>()
  const config = ref<DisplayConfig | null>(null)
  const form = reactive({ displayName: '' })
  const loading = ref(false)
  const saving = ref(false)
  const loadError = ref(false)
  const dirty = computed(
    () => config.value !== null && form.displayName !== config.value.displayName
  )
  const rules: FormRules = {
    displayName: [
      { required: true, whitespace: true, message: '请输入小程序展示名称', trigger: 'blur' },
      { max: 32, message: '展示名称最长 32 个字符', trigger: 'blur' }
    ]
  }

  const fillForm = (value: DisplayConfig) => {
    config.value = value
    form.displayName = value.displayName
    formRef.value?.clearValidate()
  }

  const loadConfig = async () => {
    loading.value = true
    loadError.value = false
    try {
      fillForm(await fetchDisplayConfig())
    } catch {
      loadError.value = true
    } finally {
      loading.value = false
    }
  }

  const save = async () => {
    if (!config.value || !dirty.value || saving.value) return
    await formRef.value?.validate()
    saving.value = true
    try {
      fillForm(
        await updateDisplayConfig({ displayName: form.displayName, version: config.value.version })
      )
      ElMessage.success('展示名称已保存，小程序刷新配置后生效')
    } finally {
      saving.value = false
    }
  }

  onMounted(loadConfig)
</script>

<style scoped lang="scss">
  .display-config-form {
    max-width: 620px;
  }

  .display-config-tip {
    margin-top: 6px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.6;
  }
</style>
