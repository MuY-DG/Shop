<template>
  <span class="asset-batch-upload" @click="openFilePicker">
    <slot :uploading="uploading" :disabled="disabled || maxFiles < 1">
      <ElButton :disabled="disabled || maxFiles < 1" :loading="uploading">
        {{ label }}
      </ElButton>
    </slot>
    <input
      ref="fileInput"
      class="asset-batch-upload__input"
      type="file"
      :accept="uploadAccept"
      multiple
      @click.stop
      @change="handleFilesSelected"
    />
  </span>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import { uploadAsset } from '@/api/assets'
  import { settleWithConcurrency } from '@/utils/asset-batch'

  defineOptions({ name: 'AssetBatchUploadButton' })

  interface Props {
    mediaKind: Exclude<Api.Storage.MediaKind, 'DOCUMENT'>
    maxFiles: number
    defaultFolderId?: number | null
    disabled?: boolean
    label?: string
  }

  interface Emits {
    (event: 'uploaded', value: Api.Common.AssetValue[]): void
  }

  const props = withDefaults(defineProps<Props>(), {
    defaultFolderId: 0,
    disabled: false,
    label: '批量上传'
  })
  const emit = defineEmits<Emits>()

  const fileInput = ref<HTMLInputElement | null>(null)
  const uploading = ref(false)
  const uploadAccept = computed(() =>
    props.mediaKind === 'VIDEO'
      ? 'video/mp4,video/webm,.mp4,.webm'
      : 'image/jpeg,image/png,image/webp,image/gif,image/svg+xml,.jpg,.jpeg,.png,.webp,.gif,.svg'
  )

  const openFilePicker = () => {
    if (props.disabled || props.maxFiles < 1 || uploading.value) return
    fileInput.value?.click()
  }

  const validFile = (file: File) => {
    const extension = file.name.split('.').pop()?.toLowerCase() || ''
    if (props.mediaKind === 'VIDEO') {
      return ['mp4', 'webm'].includes(extension) && file.size <= 50 * 1024 * 1024
    }
    return (
      file.type.startsWith('image/') &&
      ['jpg', 'jpeg', 'png', 'webp', 'gif', 'svg'].includes(extension) &&
      file.size <= 5 * 1024 * 1024
    )
  }

  const handleFilesSelected = async (event: Event) => {
    const input = event.target as HTMLInputElement
    const selectedFiles = Array.from(input.files || [])
    input.value = ''
    if (!selectedFiles.length) return

    const limitedFiles = selectedFiles.slice(0, props.maxFiles)
    const validFiles = limitedFiles.filter(validFile)
    const ignored = selectedFiles.length - validFiles.length
    if (ignored) {
      ElMessage.warning(`${ignored} 个文件因数量上限、格式或大小不符合要求而被忽略`)
    }
    if (!validFiles.length) return

    uploading.value = true
    try {
      const results = await settleWithConcurrency(validFiles, 3, (file) =>
        uploadAsset({ file, folderId: props.defaultFolderId }, { showSuccessMessage: false })
      )
      const uploaded = results.flatMap((result) =>
        result.status === 'fulfilled'
          ? [
              {
                fileId: result.value.id,
                url: result.value.publicUrl || result.value.url || ''
              }
            ]
          : []
      )
      const failed = results.length - uploaded.length
      if (uploaded.length) emit('uploaded', uploaded)
      if (failed) {
        ElMessage.warning(`成功上传 ${uploaded.length} 个，失败 ${failed} 个`)
      } else {
        ElMessage.success(`成功上传 ${uploaded.length} 个素材`)
      }
    } finally {
      uploading.value = false
    }
  }
</script>

<style scoped>
  .asset-batch-upload {
    display: inline-flex;
  }

  .asset-batch-upload__input {
    display: none;
  }
</style>
