<template>
  <span
    class="asset-batch-upload"
    :class="{ 'is-dragging': dragging }"
    @click="openFilePicker"
    @dragenter.prevent="handleDragEnter"
    @dragover.prevent="handleDragOver"
    @dragleave.prevent="handleDragLeave"
    @drop.stop.prevent="handleDrop"
  >
    <slot :uploading="uploading" :dragging="dragging" :disabled="disabled || maxFiles < 1">
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
  import {
    assetUploadAccept,
    uniqueAssetUploadFiles,
    validateAssetUploadFile
  } from '@/utils/asset-upload'

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
  const dragging = ref(false)
  const uploadAccept = computed(() => assetUploadAccept(props.mediaKind))
  let dragDepth = 0

  const openFilePicker = () => {
    if (props.disabled || props.maxFiles < 1 || uploading.value) return
    fileInput.value?.click()
  }

  const canReceiveFiles = () => !props.disabled && props.maxFiles > 0 && !uploading.value
  const isFileDrag = (event: DragEvent) =>
    Array.from(event.dataTransfer?.types || []).includes('Files')

  const handleDragEnter = (event: DragEvent) => {
    if (!canReceiveFiles() || !isFileDrag(event)) return
    dragDepth += 1
    dragging.value = true
  }

  const handleDragOver = (event: DragEvent) => {
    if (!canReceiveFiles() || !isFileDrag(event)) return
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
  }

  const handleDragLeave = () => {
    if (dragDepth > 0) dragDepth -= 1
    if (dragDepth === 0) dragging.value = false
  }

  const resetDragging = () => {
    dragDepth = 0
    dragging.value = false
  }

  const uploadFiles = async (selectedFiles: File[]) => {
    if (!canReceiveFiles()) return
    if (!selectedFiles.length) return

    const uniqueFiles = uniqueAssetUploadFiles(selectedFiles)
    const validFiles = uniqueFiles.filter(
      (file) => validateAssetUploadFile(file, props.mediaKind).valid
    )
    const limitedFiles = validFiles.slice(0, props.maxFiles)
    const ignored = selectedFiles.length - limitedFiles.length
    if (ignored) {
      ElMessage.warning(`${ignored} 个文件因数量上限、格式或大小不符合要求而被忽略`)
    }
    if (!limitedFiles.length) return

    uploading.value = true
    try {
      const results = await settleWithConcurrency(limitedFiles, 3, (file) =>
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

  const handleFilesSelected = (event: Event) => {
    const input = event.target as HTMLInputElement
    const selectedFiles = Array.from(input.files || [])
    input.value = ''
    void uploadFiles(selectedFiles)
  }

  const handleDrop = (event: DragEvent) => {
    resetDragging()
    if (!canReceiveFiles()) return
    void uploadFiles(Array.from(event.dataTransfer?.files || []))
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
