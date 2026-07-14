<template>
  <div class="asset-picker">
    <div class="asset-picker__value">
      <div class="asset-picker__preview">
        <ElImage
          v-if="canPreviewSelected"
          :src="previewUrl"
          fit="cover"
          :preview-src-list="[previewUrl]"
          preview-teleported
        />
        <div v-else class="asset-picker__placeholder">
          <ElIcon size="20">
            <Lock v-if="selectedFile?.visibility === 'PRIVATE'" />
            <Picture v-else />
          </ElIcon>
          <span>{{ selectedFile?.visibility === 'PRIVATE' ? '私有文件' : '未选择' }}</span>
        </div>
      </div>

      <div class="asset-picker__meta">
        <div class="asset-picker__title">
          <span>{{ selectedFile?.originalFilename || '未绑定素材' }}</span>
          <ElTag
            v-if="selectedFile"
            size="small"
            :type="selectedFile.visibility === 'PRIVATE' ? 'warning' : 'success'"
          >
            {{ selectedFile.visibility === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC' }}
          </ElTag>
        </div>
        <div class="asset-picker__hint">
          <template v-if="selectedFile">
            <span>ID {{ selectedFile.id }}</span>
            <span>{{ formatPurpose(selectedFile.purpose) }}</span>
            <span>{{ formatFileSize(selectedFile.sizeBytes) }}</span>
          </template>
          <template v-else-if="modelValue.url">
            <span>URL</span>
            <span class="asset-picker__url">{{ modelValue.url }}</span>
          </template>
          <template v-else>
            <span>{{ purpose ? `${formatPurpose(purpose)} 素材` : '请选择素材' }}</span>
          </template>
        </div>
      </div>
    </div>

    <div class="asset-picker__actions">
      <ElUpload
        :show-file-list="false"
        :disabled="disabled || !purpose"
        :http-request="handleUploadRequest"
      >
        <ElButton :disabled="disabled || !purpose" :loading="uploading" type="primary" plain>
          上传
        </ElButton>
      </ElUpload>
      <ElButton :disabled="disabled" @click="openBrowser">选择素材</ElButton>
      <ElButton v-if="allowClear" :disabled="disabled" @click="clearValue">清空</ElButton>
    </div>

    <ElDialog v-model="dialogVisible" title="选择素材" width="1080px" destroy-on-close align-center>
      <div class="asset-picker__dialog">
        <div class="asset-picker__toolbar">
          <ElSelect
            v-model="filters.purpose"
            :disabled="!!purpose"
            clearable
            placeholder="用途"
            style="width: 180px"
          >
            <ElOption
              v-for="item in purposeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElTreeSelect
            v-model="filters.assetCategoryId"
            :data="categoryOptions"
            node-key="value"
            check-strictly
            clearable
            default-expand-all
            :render-after-expand="false"
            placeholder="分类"
            style="width: 220px"
          />
          <ElSelect
            v-model="filters.visibility"
            :disabled="!!visibility"
            clearable
            placeholder="可见性"
            style="width: 140px"
          >
            <ElOption label="PUBLIC" value="PUBLIC" />
            <ElOption label="PRIVATE" value="PRIVATE" />
          </ElSelect>
          <ElSelect v-model="filters.status" clearable placeholder="状态" style="width: 140px">
            <ElOption label="ACTIVE" value="ACTIVE" />
            <ElOption label="DELETED" value="DELETED" />
          </ElSelect>
          <ElButton type="primary" @click="handleSearch">筛选</ElButton>
          <ElButton @click="handleReset">重置</ElButton>
        </div>

        <div v-loading="loading" class="asset-picker__grid">
          <button
            v-for="file in files"
            :key="file.id"
            type="button"
            class="asset-card"
            :class="{ 'is-active': file.id === modelValue.fileId }"
            @click="selectFile(file)"
          >
            <div class="asset-card__preview">
              <ElImage
                v-if="file.visibility !== 'PRIVATE' && resolveFileUrl(file)"
                :src="resolveFileUrl(file)"
                fit="cover"
              />
              <div v-else class="asset-card__private">
                <ElIcon size="20">
                  <Lock v-if="file.visibility === 'PRIVATE'" />
                  <Picture v-else />
                </ElIcon>
                <span>{{ file.visibility === 'PRIVATE' ? '私有文件' : '无预览' }}</span>
              </div>
            </div>
            <div class="asset-card__body">
              <div class="asset-card__name">{{ file.originalFilename }}</div>
              <div class="asset-card__meta">
                <span>ID {{ file.id }}</span>
                <span>{{ formatPurpose(file.purpose) }}</span>
              </div>
            </div>
          </button>

          <ElEmpty v-if="!loading && !files.length" description="暂无素材" />
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
  import { Lock, Picture } from '@element-plus/icons-vue'
  import {
    fetchStorageCategories,
    fetchStorageFileDetail,
    fetchStorageFiles,
    uploadStorageFile
  } from '@/api/storage'

  defineOptions({ name: 'AssetPicker' })

  interface TreeOption {
    value: number
    label: string
    children?: TreeOption[]
  }

  interface Props {
    modelValue: Api.Common.AssetValue
    purpose?: Api.Storage.Purpose
    visibility?: Api.Storage.Visibility
    assetCategoryId?: number | null
    disabled?: boolean
    allowClear?: boolean
  }

  interface Emits {
    (e: 'update:modelValue', value: Api.Common.AssetValue): void
    (e: 'change', value: Api.Common.AssetValue): void
  }

  const props = withDefaults(defineProps<Props>(), {
    purpose: undefined,
    visibility: undefined,
    assetCategoryId: null,
    disabled: false,
    allowClear: true
  })

  const emit = defineEmits<Emits>()

  const purposeLabelMap: Record<Api.Storage.Purpose, string> = {
    PRODUCT_IMAGE: '商品主图',
    PRODUCT_SKU_IMAGE: 'SKU 图片',
    SPEC_VALUE_IMAGE: '规格值图片',
    GUARANTEE_SERVICE_ICON: '保障服务图标',
    PRODUCT_VIDEO: '商品视频',
    CATEGORY_ICON: '分类图标',
    HOME_BANNER: '首页轮播',
    MARKETING_IMAGE: '运营活动',
    APP_ICON: '小程序图标',
    RICH_TEXT_IMAGE: '富文本图片',
    PAYMENT_CERTIFICATE: '支付证书',
    AFTER_SALE_IMAGE: '售后凭证',
    REFUND_EVIDENCE: '退款凭证'
  }

  const purposeOptions = Object.entries(purposeLabelMap).map(([value, label]) => ({
    value: value as Api.Storage.Purpose,
    label
  }))

  const dialogVisible = ref(false)
  const loading = ref(false)
  const uploading = ref(false)
  const selectedFile = ref<Api.Storage.FileItem | null>(null)
  const categories = ref<Api.Storage.AssetCategory[]>([])
  const files = ref<Api.Storage.FileItem[]>([])
  const pagination = reactive({
    current: 1,
    size: 12,
    total: 0
  })

  const defaultFilters = () => ({
    purpose: props.purpose,
    assetCategoryId: props.assetCategoryId ?? undefined,
    visibility: props.visibility,
    status: 'ACTIVE' as Api.Storage.FileStatus
  })

  const filters = reactive(defaultFilters())

  const categoryOptions = computed<TreeOption[]>(() => {
    const walk = (items: Api.Storage.AssetCategory[]): TreeOption[] =>
      items.map((item) => ({
        value: item.id,
        label: item.name,
        children: walk(item.children || [])
      }))

    return walk(categories.value)
  })

  const previewUrl = computed(() => modelValue.value.url || resolveFileUrl(selectedFile.value))

  const canPreviewSelected = computed(() => {
    if (selectedFile.value?.visibility === 'PRIVATE') {
      return false
    }
    return Boolean(previewUrl.value)
  })

  const modelValue = computed(() => props.modelValue || { fileId: null, url: '' })

  const emitValue = (value: Api.Common.AssetValue) => {
    emit('update:modelValue', value)
    emit('change', value)
  }

  const resolveFileUrl = (file?: Api.Storage.FileItem | null) => file?.publicUrl || file?.url || ''

  const formatPurpose = (purpose?: Api.Storage.Purpose | string | null) =>
    (purpose && purposeLabelMap[purpose as Api.Storage.Purpose]) || purpose || '-'

  const formatFileSize = (sizeBytes?: number) => {
    if (!sizeBytes) return '0 B'
    if (sizeBytes < 1024) return `${sizeBytes} B`
    if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
    return `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
  }

  const syncSelectedFile = async () => {
    if (!modelValue.value.fileId) {
      selectedFile.value = null
      return
    }

    try {
      selectedFile.value = await fetchStorageFileDetail(modelValue.value.fileId)
    } catch {
      selectedFile.value = null
    }
  }

  const loadCategories = async () => {
    categories.value = await fetchStorageCategories()
  }

  const loadFiles = async () => {
    loading.value = true
    try {
      const response = await fetchStorageFiles({
        current: pagination.current,
        size: pagination.size,
        purpose: props.purpose || filters.purpose,
        assetCategoryId: filters.assetCategoryId,
        visibility: props.visibility || filters.visibility,
        status: filters.status
      })
      files.value = response.records
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
    await Promise.all([loadCategories(), loadFiles()])
  }

  const selectFile = (file: Api.Storage.FileItem) => {
    selectedFile.value = file
    emitValue({
      fileId: file.id,
      url: file.visibility === 'PRIVATE' ? '' : resolveFileUrl(file)
    })
    dialogVisible.value = false
  }

  const clearValue = () => {
    selectedFile.value = null
    emitValue({
      fileId: null,
      url: ''
    })
  }

  const handleSearch = () => {
    pagination.current = 1
    loadFiles()
  }

  const handleReset = () => {
    Object.assign(filters, defaultFilters())
    pagination.current = 1
    loadFiles()
  }

  const handleCurrentChange = (page: number) => {
    pagination.current = page
    loadFiles()
  }

  const handleUploadRequest = async (options: UploadRequestOptions) => {
    if (!props.purpose) {
      ElMessage.error('请先指定素材用途')
      return
    }

    uploading.value = true
    try {
      const file = await uploadStorageFile({
        purpose: props.purpose,
        assetCategoryId: props.assetCategoryId,
        file: options.file
      })
      selectedFile.value = file
      emitValue({
        fileId: file.id,
        url: file.visibility === 'PRIVATE' ? '' : resolveFileUrl(file)
      })
      options.onSuccess?.(file)
      if (dialogVisible.value) {
        await loadFiles()
      }
    } catch (error) {
      options.onError?.(error as any)
    } finally {
      uploading.value = false
    }
  }

  watch(
    () => props.modelValue.fileId,
    () => {
      syncSelectedFile()
    },
    { immediate: true }
  )
</script>

<style scoped lang="scss">
  .asset-picker {
    display: grid;
    gap: 12px;
  }

  .asset-picker__value {
    display: grid;
    grid-template-columns: 88px minmax(0, 1fr);
    gap: 12px;
    align-items: center;
    padding: 12px;
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
  }

  .asset-picker__preview {
    width: 88px;
    height: 88px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 8px;

    :deep(img) {
      width: 100%;
      height: 100%;
    }
  }

  .asset-picker__placeholder,
  .asset-card__private {
    display: flex;
    flex-direction: column;
    gap: 6px;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    text-align: center;
  }

  .asset-picker__meta {
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

  .asset-picker__hint {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 12px;
    font-size: 12px;
    line-height: 1.4;
    color: var(--el-text-color-secondary);
  }

  .asset-picker__url {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .asset-picker__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .asset-picker__dialog {
    display: grid;
    gap: 16px;
  }

  .asset-picker__toolbar {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
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
    box-shadow: 0 0 0 1px rgb(from var(--el-color-primary) r g b / 15%);
  }

  .asset-card__preview {
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 8px;

    :deep(img) {
      width: 100%;
      height: 100%;
    }
  }

  .asset-card__body {
    display: grid;
    gap: 6px;
  }

  .asset-card__name {
    font-size: 13px;
    font-weight: 500;
    line-height: 1.4;
    color: var(--el-text-color-primary);
    word-break: break-word;
  }

  .asset-card__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .asset-picker__pagination {
    display: flex;
    justify-content: flex-end;
  }
</style>
