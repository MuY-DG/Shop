<template>
  <div class="payment-secret-file-field">
    <div class="payment-secret-file-field__value">
      <ElIcon size="22"><DocumentChecked /></ElIcon>
      <div class="payment-secret-file-field__meta">
        <strong>{{ displayName }}</strong>
        <span v-if="modelValue">文件 ID {{ modelValue }} · 内容不会在后台返回或预览</span>
        <span v-else>仅支持 PEM、CRT、CER、TXT 私有文件</span>
      </div>
      <ElTag v-if="modelValue" size="small" type="warning">私有</ElTag>
    </div>

    <div class="payment-secret-file-field__actions">
      <ElUpload
        accept=".pem,.crt,.cer,.txt,text/plain,application/x-pem-file,application/x-x509-ca-cert,application/pkix-cert"
        :show-file-list="false"
        :disabled="disabled"
        :http-request="handleUpload"
      >
        <ElButton type="primary" plain :disabled="disabled" :loading="uploading">
          {{ modelValue ? '替换文件' : '上传文件' }}
        </ElButton>
      </ElUpload>
      <ElButton v-if="modelValue" :disabled="disabled" @click="clearValue">清空</ElButton>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import { DocumentChecked } from '@element-plus/icons-vue'
  import type { UploadRequestOptions } from 'element-plus'
  import { uploadPaymentSecretFile } from '@/api/payment'

  defineOptions({ name: 'PaymentSecretFileField' })

  interface Props {
    modelValue: number | null
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: number | null): void
    (event: 'change', value: number | null): void
  }

  const props = withDefaults(defineProps<Props>(), { disabled: false })
  const emit = defineEmits<Emits>()
  const uploading = ref(false)
  const uploadedAssetId = ref<number | null>(null)
  const uploadedFilename = ref('')

  const displayName = computed(() => {
    if (!props.modelValue) return '未上传秘密文件'
    if (uploadedAssetId.value === props.modelValue && uploadedFilename.value) {
      return uploadedFilename.value
    }
    return `已绑定秘密文件 #${props.modelValue}`
  })

  const emitValue = (value: number | null) => {
    emit('update:modelValue', value)
    emit('change', value)
  }

  const clearValue = () => {
    uploadedAssetId.value = null
    uploadedFilename.value = ''
    emitValue(null)
  }

  const handleUpload = async (options: UploadRequestOptions) => {
    uploading.value = true
    try {
      const asset = await uploadPaymentSecretFile(options.file)
      uploadedAssetId.value = asset.id
      uploadedFilename.value = asset.originalFilename
      emitValue(asset.id)
      options.onSuccess?.(asset)
    } catch (error) {
      options.onError?.(error as any)
    } finally {
      uploading.value = false
    }
  }

  watch(
    () => props.modelValue,
    (value) => {
      if (!value || value !== uploadedAssetId.value) {
        uploadedAssetId.value = null
        uploadedFilename.value = ''
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
