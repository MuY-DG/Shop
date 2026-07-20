<template>
  <div class="asset-picker" :class="{ 'asset-picker--compact': compact }">
    <template v-if="compact">
      <AssetBatchUploadButton
        v-if="multiple"
        class="asset-picker__compact-upload"
        :class="{ 'is-small': compactSize === 'small' }"
        :media-kind="mediaKind"
        :max-files="maxSelection"
        :default-folder-id="defaultFolderId"
        :disabled="disabled"
        @uploaded="appendAssets"
      >
        <template #default="{ uploading: batchUploading }">
          <div
            class="asset-picker__compact-target"
            :class="{ 'is-empty': !previewUrl, 'is-disabled': disabled }"
            role="button"
            :aria-label="`上传一个或多个${mediaKindLabel}`"
          >
            <video
              v-if="mediaKind === 'VIDEO' && previewUrl"
              :src="previewUrl"
              muted
              preload="metadata"
            />
            <ElImage
              v-else-if="mediaKind === 'IMAGE' && previewUrl"
              :src="previewUrl"
              fit="cover"
            />
            <div v-else class="asset-picker__compact-placeholder">
              <ElIcon :size="compactSize === 'small' ? 20 : 24">
                <VideoCamera v-if="mediaKind === 'VIDEO'" />
                <Plus v-else />
              </ElIcon>
              <span>{{ batchUploading ? '上传中' : `上传${mediaKindLabel}` }}</span>
            </div>
            <button
              v-if="!disabled && !batchUploading"
              type="button"
              class="asset-picker__compact-library"
              :title="`从素材库选择一个或多个${mediaKindLabel}`"
              :aria-label="`从素材库选择一个或多个${mediaKindLabel}`"
              @mousedown.stop.prevent
              @click.stop.prevent="openBrowser"
            >
              <ElIcon size="14"><FolderOpened /></ElIcon>
              <span v-if="compactSize !== 'small'">素材库</span>
            </button>
          </div>
        </template>
      </AssetBatchUploadButton>
      <ElUpload
        v-else
        class="asset-picker__compact-upload"
        :class="{ 'is-small': compactSize === 'small' }"
        :accept="uploadAccept"
        :show-file-list="false"
        :disabled="disabled"
        :http-request="handleUploadRequest"
      >
        <div
          class="asset-picker__compact-target"
          :class="{ 'is-empty': !previewUrl, 'is-disabled': disabled }"
          role="button"
          :aria-label="previewUrl ? `更换${mediaKindLabel}` : `上传${mediaKindLabel}`"
        >
          <video
            v-if="mediaKind === 'VIDEO' && previewUrl"
            :src="previewUrl"
            muted
            preload="metadata"
          />
          <ElImage v-else-if="mediaKind === 'IMAGE' && previewUrl" :src="previewUrl" fit="cover" />
          <div v-else class="asset-picker__compact-placeholder">
            <ElIcon :size="compactSize === 'small' ? 20 : 24">
              <VideoCamera v-if="mediaKind === 'VIDEO'" />
              <Plus v-else />
            </ElIcon>
            <span>{{ uploading ? '上传中' : `上传${mediaKindLabel}` }}</span>
          </div>
          <div v-if="uploading && previewUrl" class="asset-picker__compact-loading">
            <ElIcon class="is-loading" size="20"><Loading /></ElIcon>
          </div>
          <button
            v-if="!disabled && !uploading"
            type="button"
            class="asset-picker__compact-library"
            title="从素材库选择"
            aria-label="从素材库选择"
            @mousedown.stop.prevent
            @click.stop.prevent="openBrowser"
          >
            <ElIcon size="14"><FolderOpened /></ElIcon>
            <span v-if="compactSize !== 'small'">素材库</span>
          </button>
          <button
            v-if="allowClear && previewUrl && !disabled && !uploading"
            type="button"
            class="asset-picker__compact-clear"
            :aria-label="`清除${mediaKindLabel}`"
            @mousedown.stop.prevent
            @click.stop.prevent="clearValue"
          >
            <ElIcon size="18"><CircleCloseFilled /></ElIcon>
          </button>
        </div>
      </ElUpload>
    </template>

    <template v-else>
      <div class="asset-picker__value">
        <div class="asset-picker__preview">
          <video
            v-if="mediaKind === 'VIDEO' && previewUrl"
            :src="previewUrl"
            controls
            preload="metadata"
          />
          <ElImage
            v-else-if="mediaKind === 'IMAGE' && previewUrl"
            :src="previewUrl"
            fit="cover"
            :preview-src-list="[previewUrl]"
            preview-teleported
          />
          <div v-else class="asset-picker__placeholder">
            <ElIcon size="22">
              <VideoCamera v-if="mediaKind === 'VIDEO'" />
              <Picture v-else />
            </ElIcon>
            <span>未选择{{ mediaKindLabel }}</span>
          </div>
        </div>

        <div class="asset-picker__meta">
          <div class="asset-picker__title">
            <span>{{ selectedAsset?.originalFilename || `未绑定${mediaKindLabel}` }}</span>
            <ElTag v-if="selectedAsset" size="small" type="success">
              {{ selectedAsset.mediaKind }}
            </ElTag>
          </div>
          <div class="asset-picker__hint">
            <template v-if="selectedAsset">
              <span>ID {{ selectedAsset.id }}</span>
              <span>{{ formatFileSize(selectedAsset.sizeBytes) }}</span>
              <span>引用 {{ selectedAsset.usageCount || 0 }} 次</span>
            </template>
            <template v-else-if="modelValue.url">
              <span>外部 URL</span>
              <span class="asset-picker__url">{{ modelValue.url }}</span>
            </template>
            <template v-else>
              <span>{{ mediaKindHint }}</span>
            </template>
          </div>
        </div>
      </div>

      <div class="asset-picker__actions">
        <ElUpload
          :accept="uploadAccept"
          :show-file-list="false"
          :disabled="disabled"
          :http-request="handleUploadRequest"
        >
          <ElButton :disabled="disabled" :loading="uploading" type="primary" plain>
            上传{{ mediaKindLabel }}
          </ElButton>
        </ElUpload>
        <ElButton :disabled="disabled" @click="openBrowser">选择素材</ElButton>
        <ElButton v-if="allowClear" :disabled="disabled" @click="clearValue">清空</ElButton>
      </div>
    </template>

    <AssetMultiPickerDialog
      v-if="multiple"
      v-model="dialogVisible"
      :media-kind="mediaKind"
      :max-selection="maxSelection"
      :exclude-file-ids="excludeFileIds"
      @confirm="appendAssets"
    />

    <ElDialog
      v-else
      v-model="dialogVisible"
      :title="`选择${mediaKindLabel}`"
      width="1080px"
      destroy-on-close
      align-center
    >
      <div class="asset-picker__dialog">
        <div class="asset-picker__toolbar">
          <ElInput
            v-model="filters.keyword"
            clearable
            placeholder="搜索文件名"
            style="width: 240px"
            @keyup.enter="handleSearch"
          />
          <ElTreeSelect
            v-model="filters.folderId"
            :data="folderOptions"
            node-key="value"
            check-strictly
            clearable
            default-expand-all
            :render-after-expand="false"
            placeholder="全部分组"
            style="width: 240px"
          />
          <ElButton type="primary" @click="handleSearch">筛选</ElButton>
          <ElButton @click="handleReset">重置</ElButton>
        </div>

        <div v-loading="loading" class="asset-picker__grid">
          <button
            v-for="asset in assets"
            :key="asset.id"
            type="button"
            class="asset-card"
            :class="{ 'is-active': asset.id === modelValue.fileId }"
            @click="selectAsset(asset)"
          >
            <div class="asset-card__preview">
              <video
                v-if="asset.mediaKind === 'VIDEO' && resolveAssetUrl(asset)"
                :src="resolveAssetUrl(asset)"
                muted
                preload="metadata"
              />
              <ElImage
                v-else-if="asset.mediaKind === 'IMAGE' && resolveAssetUrl(asset)"
                :src="resolveAssetUrl(asset)"
                fit="cover"
              />
              <div v-else class="asset-card__empty">
                <ElIcon size="22">
                  <VideoCamera v-if="asset.mediaKind === 'VIDEO'" />
                  <Picture v-else />
                </ElIcon>
                <span>无预览</span>
              </div>
            </div>
            <div class="asset-card__body">
              <div class="asset-card__name">{{ asset.originalFilename }}</div>
              <div class="asset-card__meta">
                <span>ID {{ asset.id }}</span>
                <span>{{ formatFileSize(asset.sizeBytes) }}</span>
                <span>引用 {{ asset.usageCount || 0 }} 次</span>
              </div>
            </div>
          </button>

          <ElEmpty v-if="!loading && !assets.length" :description="`暂无${mediaKindLabel}素材`" />
        </div>

        <div class="asset-picker__pagination">
          <ElPagination
            background
            layout="total, prev, pager, next"
            :current-page="pagination.current"
            :page-size="pagination.size"
            :total="pagination.total"
            @current-change="handleCurrentChange"
          />
        </div>
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, reactive, ref, watch } from 'vue'
  import { ElMessage, type UploadRequestOptions } from 'element-plus'
  import {
    CircleCloseFilled,
    FolderOpened,
    Loading,
    Picture,
    Plus,
    VideoCamera
  } from '@element-plus/icons-vue'
  import { fetchAssetDetail, fetchAssetFolders, fetchAssets, uploadAsset } from '@/api/assets'
  import AssetBatchUploadButton from './asset-batch-upload-button.vue'
  import AssetMultiPickerDialog from './asset-multi-picker-dialog.vue'

  defineOptions({ name: 'AssetPicker' })

  interface TreeOption {
    value: number
    label: string
    disabled?: boolean
    children?: TreeOption[]
  }

  interface Props {
    modelValue: Api.Common.AssetValue
    mediaKind: Exclude<Api.Storage.MediaKind, 'DOCUMENT'>
    defaultFolderId?: number | null
    disabled?: boolean
    allowClear?: boolean
    compact?: boolean
    compactSize?: 'default' | 'small'
    multiple?: boolean
    maxSelection?: number
    excludeFileIds?: number[]
  }

  interface Emits {
    (event: 'update:modelValue', value: Api.Common.AssetValue): void
    (event: 'change', value: Api.Common.AssetValue): void
    (event: 'append', value: Api.Common.AssetValue[]): void
  }

  const props = withDefaults(defineProps<Props>(), {
    defaultFolderId: null,
    disabled: false,
    allowClear: true,
    compact: false,
    compactSize: 'default',
    multiple: false,
    maxSelection: 1,
    excludeFileIds: () => []
  })
  const emit = defineEmits<Emits>()

  const dialogVisible = ref(false)
  const loading = ref(false)
  const uploading = ref(false)
  const selectedAsset = ref<Api.Storage.AssetItem | null>(null)
  const folders = ref<Api.Storage.AssetFolder[]>([])
  const assets = ref<Api.Storage.AssetItem[]>([])
  const pagination = reactive({ current: 1, size: 12, total: 0 })

  const defaultFilters = () => ({
    keyword: '',
    folderId: props.defaultFolderId ?? undefined
  })
  const filters = reactive<{ keyword: string; folderId?: number }>(defaultFilters())

  const mediaKind = computed(() => props.mediaKind)
  const mediaKindLabel = computed(() => (mediaKind.value === 'VIDEO' ? '视频' : '图片'))
  const mediaKindHint = computed(() =>
    mediaKind.value === 'VIDEO'
      ? '支持 MP4、WebM，最大 50 MB'
      : '支持 JPG、PNG、WebP、GIF、SVG，最大 5 MB'
  )
  const uploadAccept = computed(() =>
    mediaKind.value === 'VIDEO'
      ? 'video/mp4,video/webm,.mp4,.webm'
      : 'image/jpeg,image/png,image/webp,image/gif,image/svg+xml,.jpg,.jpeg,.png,.webp,.gif,.svg'
  )
  const modelValue = computed(() => props.modelValue || { fileId: null, url: '' })
  const previewUrl = computed(() => modelValue.value.url || resolveAssetUrl(selectedAsset.value))

  const folderOptions = computed<TreeOption[]>(() => {
    const walk = (items: Api.Storage.AssetFolder[]): TreeOption[] =>
      items.map((item) => ({
        value: item.id,
        label: item.status === 'DISABLED' ? `${item.name}（已停用）` : item.name,
        disabled: item.status === 'DISABLED',
        children: walk(item.children || [])
      }))

    return [{ value: 0, label: '未分组' }, ...walk(folders.value)]
  })

  const resolveAssetUrl = (asset?: Api.Storage.AssetItem | null) =>
    asset?.publicUrl || asset?.url || ''

  const formatFileSize = (sizeBytes?: number) => {
    if (!sizeBytes) return '0 B'
    if (sizeBytes < 1024) return `${sizeBytes} B`
    if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
    return `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
  }

  const emitValue = (value: Api.Common.AssetValue) => {
    emit('update:modelValue', value)
    emit('change', value)
  }

  const appendAssets = (values: Api.Common.AssetValue[]) => {
    emit('append', values)
  }

  const syncSelectedAsset = async () => {
    if (!modelValue.value.fileId) {
      selectedAsset.value = null
      return
    }

    try {
      const asset = await fetchAssetDetail(modelValue.value.fileId)
      selectedAsset.value = asset.mediaKind === props.mediaKind ? asset : null
    } catch {
      selectedAsset.value = null
    }
  }

  const loadFolders = async () => {
    folders.value = await fetchAssetFolders()
  }

  const loadAssets = async () => {
    loading.value = true
    try {
      const response = await fetchAssets({
        current: pagination.current,
        size: pagination.size,
        keyword: filters.keyword.trim() || undefined,
        mediaKind: props.mediaKind,
        folderId: filters.folderId
      })
      assets.value = response.records
      pagination.current = response.current
      pagination.size = response.size
      pagination.total = response.total
    } finally {
      loading.value = false
    }
  }

  const openBrowser = async () => {
    dialogVisible.value = true
    pagination.current = 1
    if (props.multiple) return
    await Promise.all([loadFolders(), loadAssets()])
  }

  const handleSearch = () => {
    pagination.current = 1
    loadAssets()
  }

  const handleReset = () => {
    Object.assign(filters, defaultFilters())
    pagination.current = 1
    loadAssets()
  }

  const handleCurrentChange = (page: number) => {
    pagination.current = page
    loadAssets()
  }

  const selectAsset = (asset: Api.Storage.AssetItem) => {
    if (asset.mediaKind !== props.mediaKind) return
    selectedAsset.value = asset
    emitValue({ fileId: asset.id, url: resolveAssetUrl(asset) })
    dialogVisible.value = false
  }

  const clearValue = () => {
    selectedAsset.value = null
    emitValue({ fileId: null, url: '' })
  }

  const validateUpload = (file: File) => {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (props.mediaKind === 'VIDEO') {
      if (!['mp4', 'webm'].includes(extension || '')) {
        ElMessage.error('视频仅支持 MP4 或 WebM')
        return false
      }
      if (file.size > 50 * 1024 * 1024) {
        ElMessage.error('视频不能超过 50 MB')
        return false
      }
      return true
    }

    if (!file.type.startsWith('image/')) {
      ElMessage.error('请选择图片文件')
      return false
    }
    if (!['jpg', 'jpeg', 'png', 'webp', 'gif', 'svg'].includes(extension || '')) {
      ElMessage.error('图片仅支持 JPG、PNG、WebP、GIF 或 SVG')
      return false
    }
    if (file.size > 5 * 1024 * 1024) {
      ElMessage.error('图片不能超过 5 MB')
      return false
    }
    return true
  }

  const handleUploadRequest = async (options: UploadRequestOptions) => {
    if (!validateUpload(options.file)) {
      options.onError?.(new Error(`Invalid ${props.mediaKind.toLowerCase()} asset`) as any)
      return
    }

    uploading.value = true
    try {
      const asset = await uploadAsset({
        folderId: props.defaultFolderId ?? 0,
        file: options.file
      })
      if (asset.mediaKind !== props.mediaKind) {
        throw new Error(`上传文件不是${mediaKindLabel.value}`)
      }
      selectedAsset.value = asset
      emitValue({ fileId: asset.id, url: resolveAssetUrl(asset) })
      options.onSuccess?.(asset)
      if (dialogVisible.value) await loadAssets()
    } catch (error) {
      options.onError?.(error as any)
    } finally {
      uploading.value = false
    }
  }

  watch(
    () => [props.modelValue.fileId, props.mediaKind] as const,
    () => syncSelectedAsset(),
    { immediate: true }
  )
</script>

<style scoped lang="scss">
  .asset-picker {
    display: grid;
    gap: 12px;
  }

  .asset-picker--compact {
    display: inline-flex;
    gap: 0;
    line-height: 1;
  }

  .asset-picker__compact-upload {
    display: inline-flex;

    :deep(.el-upload) {
      display: block;
    }
  }

  .asset-picker__compact-target {
    position: relative;
    width: 112px;
    aspect-ratio: 1;
    overflow: hidden;
    cursor: pointer;
    background: var(--el-fill-color-light);
    border: 1px dashed var(--el-border-color);
    border-radius: 8px;
    transition:
      border-color 0.2s ease,
      background-color 0.2s ease;

    &:hover {
      border-color: var(--el-color-primary);
    }

    &.is-disabled {
      cursor: not-allowed;
      opacity: 0.65;
    }

    :deep(img),
    video {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  .asset-picker__compact-upload.is-small .asset-picker__compact-target {
    width: 72px;
    border-radius: 6px;
  }

  .asset-picker__compact-placeholder,
  .asset-picker__compact-loading {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    gap: 7px;
    align-items: center;
    justify-content: center;
    color: var(--el-text-color-secondary);
  }

  .asset-picker__compact-placeholder span {
    font-size: 12px;
    line-height: 1.2;
  }

  .asset-picker__compact-upload.is-small .asset-picker__compact-placeholder {
    gap: 3px;

    span {
      font-size: 11px;
    }
  }

  .asset-picker__compact-loading {
    color: #fff;
    background: rgb(0 0 0 / 35%);
  }

  .asset-picker__compact-library {
    position: absolute;
    bottom: 4px;
    left: 4px;
    z-index: 2;
    display: inline-flex;
    gap: 3px;
    align-items: center;
    min-height: 22px;
    padding: 0 6px;
    font-size: 11px;
    line-height: 1;
    color: #fff;
    cursor: pointer;
    background: rgb(0 0 0 / 48%);
    border: 0;
    border-radius: 5px;
    transition: background-color 0.2s ease;

    &:hover,
    &:focus-visible {
      background: var(--el-color-primary);
      outline: none;
    }
  }

  .asset-picker__compact-upload.is-small .asset-picker__compact-library {
    min-width: 22px;
    padding: 0 4px;
  }

  .asset-picker__compact-clear {
    position: absolute;
    top: 4px;
    right: 4px;
    z-index: 2;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;
    color: var(--el-color-danger);
    cursor: pointer;
    visibility: hidden;
    background: #fff;
    border: 0;
    border-radius: 50%;
    opacity: 0;
    transition: opacity 0.2s ease;
  }

  .asset-picker__compact-target:hover .asset-picker__compact-clear,
  .asset-picker__compact-clear:focus-visible {
    visibility: visible;
    opacity: 1;
  }

  .asset-picker__value {
    display: grid;
    grid-template-columns: 104px minmax(0, 1fr);
    gap: 12px;
    align-items: center;
    padding: 12px;
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
  }

  .asset-picker__preview {
    width: 104px;
    aspect-ratio: 1;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 8px;

    :deep(img),
    video {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  .asset-picker__placeholder,
  .asset-card__empty {
    display: flex;
    flex-direction: column;
    gap: 6px;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .asset-picker__meta,
  .asset-card__body {
    display: grid;
    gap: 8px;
    min-width: 0;
  }

  .asset-picker__title {
    display: flex;
    gap: 8px;
    align-items: center;
    min-width: 0;
    font-weight: 500;

    > span:first-child {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .asset-picker__hint,
  .asset-card__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 12px;
    font-size: 12px;
    line-height: 1.4;
    color: var(--el-text-color-secondary);
  }

  .asset-picker__url,
  .asset-card__name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .asset-picker__actions,
  .asset-picker__toolbar {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .asset-picker__dialog {
    display: grid;
    gap: 16px;
  }

  .asset-picker__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 12px;
    min-height: 280px;
  }

  .asset-card {
    display: grid;
    gap: 10px;
    padding: 12px;
    text-align: left;
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    transition:
      border-color 0.2s ease,
      box-shadow 0.2s ease;
  }

  .asset-card:hover,
  .asset-card.is-active {
    border-color: var(--el-color-primary);
    box-shadow: 0 0 0 1px rgb(64 158 255 / 15%);
  }

  .asset-card__preview {
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 8px;

    :deep(img),
    video {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  .asset-card__name {
    font-size: 13px;
    font-weight: 500;
  }

  .asset-picker__pagination {
    display: flex;
    justify-content: flex-end;
  }
</style>
