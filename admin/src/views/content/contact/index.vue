<template>
  <div class="contact-page art-full-height">
    <ElCard class="contact-card" v-loading="loading">
      <template #header>
        <div>
          <div class="title">联系我</div>
          <div class="description">配置小程序后续使用的联系电话，接口响应会缓存到 Redis</div>
        </div>
      </template>

      <ElAlert
        title="当前仅保存电话号码；后续在线客服可以在此配置基础上独立扩展。"
        type="info"
        :closable="false"
        show-icon
      />

      <ElForm
        ref="formRef"
        class="contact-form"
        :model="formData"
        :rules="rules"
        label-width="90px"
      >
        <ElFormItem label="电话号码" prop="phone">
          <ElInput
            v-model="formData.phone"
            maxlength="32"
            show-word-limit
            placeholder="例如：400-800-1234 或 13800138000"
          />
        </ElFormItem>
        <ElFormItem>
          <ElButton
            type="primary"
            :loading="submitting"
            v-auth="'content:contact:write'"
            @click="handleSubmit"
          >
            保存设置
          </ElButton>
        </ElFormItem>
      </ElForm>

      <div v-if="updatedAt" class="updated-at">最后更新：{{ updatedAt }}</div>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { onMounted, reactive, ref } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { fetchContactSetting, updateContactSetting } from '@/api/content'
  import { trimPhone } from '../home-decoration-state'

  defineOptions({ name: 'ContentContact' })

  const loading = ref(false)
  const submitting = ref(false)
  const updatedAt = ref('')
  const formRef = ref<FormInstance>()
  const formData = reactive<Api.Content.ContactForm>({ phone: '' })
  const rules: FormRules<Api.Content.ContactForm> = {
    phone: [
      { required: true, message: '请输入电话号码', trigger: 'blur' },
      { max: 32, message: '电话号码不能超过 32 个字符', trigger: 'blur' }
    ]
  }

  const loadSetting = async () => {
    loading.value = true
    try {
      const setting = await fetchContactSetting()
      formData.phone = setting.phone
      updatedAt.value = setting.updatedAt
    } finally {
      loading.value = false
    }
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    formData.phone = trimPhone(formData.phone)
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)
    if (!valid) return
    submitting.value = true
    try {
      const setting = await updateContactSetting({ phone: formData.phone })
      formData.phone = setting.phone
      updatedAt.value = setting.updatedAt
    } finally {
      submitting.value = false
    }
  }

  onMounted(loadSetting)
</script>

<style scoped lang="scss">
  .contact-card {
    max-width: 760px;
  }

  .title {
    font-size: 16px;
    font-weight: 600;
  }

  .description,
  .updated-at {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .contact-form {
    margin-top: 28px;
  }

  .updated-at {
    padding-left: 90px;
  }
</style>
