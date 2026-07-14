<template>
  <div class="video-picker">
    <div class="video-picker__value">
      <video
        v-if="modelValue.url"
        :src="modelValue.url"
        class="video-picker__preview"
        controls
        preload="metadata"
      />
      <div v-else class="video-picker__placeholder">
        <ElIcon size="28"><VideoCamera /></ElIcon>
        <span>未选择主图视频</span>
      </div>

      <div class="video-picker__meta">
        <strong>{{ selectedFile?.originalFilename || '主图视频（可选）' }}</strong>
        <span v-if="selectedFile">
          ID {{ selectedFile.id }} · {{ formatFileSize(selectedFile.sizeBytes) }} ·
          {{ selectedFile.contentType }}
        </span>
        <span v-else-if="modelValue.url" class="video-picker__url">{{ modelValue.url }}</span>
        <span v-else>支持 MP4、WebM，最大 50 MB</span>
      </div>
    </div>

    <div class="video-picker__actions">
      <ElUpload
        accept="video/mp4,video/webm,.mp4,.webm"
        :show-file-list="false"
        :disabled="disabled"
        :http-request="handleUpload"
      >
        <ElButton type="primary" plain :disabled="disabled" :loading="uploading">上传视频</ElButton>
      </ElUpload>
      <ElButton :disabled="disabled" @click="openBrowser">选择素材</ElButton>
      <ElButton v-if="modelValue.fileId || modelValue.url" :disabled="disabled" @click="clear">
        清空
      </ElButton>
    </div>

    <ElDialog
      v-model="dialogVisible"
      title="选择商品视频"
      width="920px"
      destroy-on-close
      align-center
    >
      <div v-loading="loading" class="video-browser">
        <button
          v-for="file in files"
          :key="file.id"
          type="button"
          class="video-card"
          :class="{ 'is-active': file.id === modelValue.fileId }"
          @click="select(file)"
        >
          <video
            v-if="resolveUrl(file)"
            :src="resolveUrl(file)"
            muted
            preload="metadata"
            class="video-card__preview"
          />
          <div v-else class="video-card__placeholder">无公开预览</div>
          <strong>{{ file.originalFilename }}</strong>
          <span>ID {{ file.id }} · {{ formatFileSize(file.sizeBytes) }}</span>
        </button>
        <ElEmpty v-if="!loading && !files.length" description="暂无商品视频素材" />
      </div>
      <div class="video-browser__pagination">
        <ElPagination
          background
          layout="total, prev, pager, next"
          :current-page="pagination.current"
          :page-size="pagination.size"
          :total="pagination.total"
          @current-change="handlePageChange"
        />
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { reactive, ref, watch } from 'vue'
  import { ElMessage, type UploadRequestOptions } from 'element-plus'
  import { VideoCamera } from '@element-plus/icons-vue'
  import { fetchStorageFileDetail, fetchStorageFiles, uploadStorageFile } from '@/api/storage'

  interface Props {
    modelValue: Api.Common.AssetValue
    disabled?: boolean
  }

  interface Emits {
    (event: 'update:modelValue', value: Api.Common.AssetValue): void
    (event: 'change', value: Api.Common.AssetValue): void
  }

  const props = withDefaults(defineProps<Props>(), {
    disabled: false
  })
  const emit = defineEmits<Emits>()

  const dialogVisible = ref(false)
  const loading = ref(false)
  const uploading = ref(false)
  const files = ref<Api.Storage.FileItem[]>([])
  const selectedFile = ref<Api.Storage.FileItem | null>(null)
  const pagination = reactive({ current: 1, size: 12, total: 0 })

  const resolveUrl = (file?: Api.Storage.FileItem | null) => file?.publicUrl || file?.url || ''

  const emitValue = (value: Api.Common.AssetValue) => {
    emit('update:modelValue', value)
    emit('change', value)
  }

  const formatFileSize = (sizeBytes?: number) => {
    if (!sizeBytes) return '0 B'
    return sizeBytes < 1024 * 1024
      ? `${(sizeBytes / 1024).toFixed(1)} KB`
      : `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
  }

  const loadFiles = async () => {
    loading.value = true
    try {
      const response = await fetchStorageFiles({
        current: pagination.current,
        size: pagination.size,
        purpose: 'PRODUCT_VIDEO' as Api.Storage.Purpose,
        status: 'ACTIVE'
      })
      files.value = response.records
      pagination.current = response.current
      pagination.size = response.size
      pagination.total = response.total
    } finally {
      loading.value = false
    }
  }

  const openBrowser = () => {
    pagination.current = 1
    dialogVisible.value = true
    loadFiles()
  }

  const handlePageChange = (page: number) => {
    pagination.current = page
    loadFiles()
  }

  const select = (file: Api.Storage.FileItem) => {
    selectedFile.value = file
    emitValue({ fileId: file.id, url: resolveUrl(file) })
    dialogVisible.value = false
  }

  const clear = () => {
    selectedFile.value = null
    emitValue({ fileId: null, url: '' })
  }

  const validateVideo = (file: File) => {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['mp4', 'webm'].includes(extension || '')) {
      ElMessage.error('主图视频仅支持 MP4 或 WebM')
      return false
    }
    if (file.size > 50 * 1024 * 1024) {
      ElMessage.error('主图视频不能超过 50 MB')
      return false
    }
    return true
  }

  const handleUpload = async (options: UploadRequestOptions) => {
    if (!validateVideo(options.file)) {
      options.onError?.(new Error('Invalid product video') as any)
      return
    }
    uploading.value = true
    try {
      const file = await uploadStorageFile({
        purpose: 'PRODUCT_VIDEO' as Api.Storage.Purpose,
        file: options.file
      })
      selectedFile.value = file
      emitValue({ fileId: file.id, url: resolveUrl(file) })
      options.onSuccess?.(file)
    } catch (error) {
      options.onError?.(error as any)
    } finally {
      uploading.value = false
    }
  }

  const syncSelectedFile = async () => {
    if (!props.modelValue.fileId) {
      selectedFile.value = null
      return
    }
    try {
      selectedFile.value = await fetchStorageFileDetail(props.modelValue.fileId)
    } catch {
      selectedFile.value = null
    }
  }

  watch(() => props.modelValue.fileId, syncSelectedFile, { immediate: true })
</script>

<style scoped lang="scss">
  .video-picker {
    display: grid;
    gap: 12px;
  }

  .video-picker__value {
    display: grid;
    grid-template-columns: 220px minmax(0, 1fr);
    gap: 16px;
    align-items: center;
    padding: 14px;
    border: 1px solid var(--el-border-color);
    border-radius: 10px;
  }

  .video-picker__preview,
  .video-picker__placeholder {
    width: 220px;
    aspect-ratio: 16 / 9;
    background: #111827;
    border-radius: 8px;
  }

  .video-picker__placeholder {
    display: flex;
    flex-direction: column;
    gap: 8px;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    color: #cbd5e1;
  }

  .video-picker__meta {
    display: grid;
    gap: 8px;
    min-width: 0;

    span {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .video-picker__url {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .video-picker__actions {
    display: flex;
    gap: 8px;
  }

  .video-browser {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
    min-height: 260px;
  }

  .video-card {
    display: grid;
    gap: 8px;
    min-width: 0;
    padding: 10px;
    color: var(--el-text-color-primary);
    text-align: left;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;

    &:hover,
    &.is-active {
      border-color: var(--el-color-primary);
    }

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

  .video-card__preview,
  .video-card__placeholder {
    width: 100%;
    aspect-ratio: 16 / 9;
    background: #111827;
    border-radius: 6px;
  }

  .video-card__placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    color: #cbd5e1;
  }

  .video-browser__pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  @media (width <= 720px) {
    .video-picker__value {
      grid-template-columns: minmax(0, 1fr);
    }

    .video-picker__preview,
    .video-picker__placeholder {
      width: 100%;
    }

    .video-browser {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
