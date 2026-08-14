<template>
  <div class="payment-secret-file-field">
    <div class="payment-secret-file-field__value">
      <ElIcon size="22"><DocumentChecked /></ElIcon>
      <div class="payment-secret-file-field__meta">
        <strong>{{ displayName }}</strong>
        <span>{{ displayHint }}</span>
      </div>
      <ElTag v-if="selectedFilename" size="small" type="warning">待保存</ElTag>
      <ElTag v-else-if="configured" size="small" type="success">已配置</ElTag>
    </div>

    <div class="payment-secret-file-field__actions">
      <ElUpload
        accept=".pem,text/plain,application/x-pem-file"
        :auto-upload="false"
        :show-file-list="false"
        :disabled="disabled || reading"
        :on-change="handleSelection"
      >
        <ElButton type="primary" plain :disabled="disabled" :loading="reading">
          {{ configured || selectedFilename ? '选择新 PEM' : '选择 PEM' }}
        </ElButton>
      </ElUpload>
      <ElButton v-if="selectedFilename" :disabled="disabled || reading" @click="clearSelection">
        取消本次替换
      </ElButton>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import { DocumentChecked } from '@element-plus/icons-vue'
  import { ElMessage, type UploadFile } from 'element-plus'

  defineOptions({ name: 'PaymentSecretFileField' })

  interface Props {
    modelValue?: string
    configured?: boolean
    keyType: 'PRIVATE_KEY' | 'PUBLIC_KEY'
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: string): void
    (event: 'change', value: string): void
  }

  const props = withDefaults(defineProps<Props>(), {
    modelValue: '',
    configured: false,
    disabled: false
  })
  const emit = defineEmits<Emits>()
  const reading = ref(false)
  const selectedFilename = ref('')
  const selectedValue = ref('')

  const displayName = computed(() => {
    if (selectedFilename.value) return selectedFilename.value
    return props.configured ? '密钥正文已加密存储' : '尚未选择 PEM'
  })

  const displayHint = computed(() => {
    if (selectedFilename.value) return '仅在本次保存请求中发送正文，后台不会回显'
    if (props.configured) return '选择新文件可替换；未选择时保留原内容'
    return props.keyType === 'PRIVATE_KEY'
      ? '仅支持 PKCS#8 RSA 私钥（BEGIN PRIVATE KEY）'
      : '仅支持 RSA 公钥（BEGIN PUBLIC KEY）'
  })

  const emitValue = (value: string) => {
    selectedValue.value = value
    emit('update:modelValue', value)
    emit('change', value)
  }

  const clearSelection = () => {
    selectedFilename.value = ''
    emitValue('')
  }

  const hasExpectedEnvelope = (value: string) => {
    const label = props.keyType === 'PRIVATE_KEY' ? 'PRIVATE KEY' : 'PUBLIC KEY'
    return value.includes(`-----BEGIN ${label}-----`) && value.includes(`-----END ${label}-----`)
  }

  const handleSelection = async (uploadFile: UploadFile) => {
    const file = uploadFile.raw
    if (!file) return
    if (file.size > 32 * 1024) {
      ElMessage.error('PEM 文件不能超过 32 KB')
      return
    }
    reading.value = true
    try {
      const content = await file.text()
      if (!hasExpectedEnvelope(content)) {
        ElMessage.error(
          props.keyType === 'PRIVATE_KEY' ? '请选择 PKCS#8 RSA 私钥 PEM' : '请选择 RSA 公钥 PEM'
        )
        return
      }
      selectedFilename.value = file.name
      emitValue(content)
    } finally {
      reading.value = false
    }
  }

  watch(
    () => props.modelValue,
    (value) => {
      if (!value || value !== selectedValue.value) {
        selectedFilename.value = ''
        selectedValue.value = value || ''
      }
    }
  )
</script>

<style scoped lang="scss">
  .payment-secret-file-field {
    display: grid;
    gap: 10px;
  }

  .payment-secret-file-field__value {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    gap: 12px;
    align-items: center;
    padding: 12px;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
  }

  .payment-secret-file-field__meta {
    display: grid;
    gap: 4px;
    min-width: 0;

    strong,
    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    span {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .payment-secret-file-field__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
</style>
