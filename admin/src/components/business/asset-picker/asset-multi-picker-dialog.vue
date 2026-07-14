<template>
  <ElDialog
    :model-value="modelValue"
    :title="`批量选择${mediaKindLabel}`"
    width="1080px"
    destroy-on-close
    align-center
    @update:model-value="emit('update:modelValue', $event)"
    @open="handleOpen"
  >
    <div class="asset-multi-picker">
      <div class="asset-multi-picker__toolbar">
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
        <span class="asset-multi-picker__count">
          已选 {{ selectedAssetIds.length }} / {{ maxSelection }}
        </span>
      </div>

      <div v-loading="loading" class="asset-multi-picker__grid">
        <div
          v-for="asset in assets"
          :key="asset.id"
          class="asset-multi-picker__card"
          :class="{
            'is-active': selectedAssetIdSet.has(asset.id),
            'is-disabled': excludedAssetIdSet.has(asset.id)
          }"
          role="button"
          tabindex="0"
          @click="toggleAsset(asset)"
          @keydown.enter="toggleAsset(asset)"
          @keydown.space.prevent="toggleAsset(asset)"
        >
          <ElCheckbox
            class="asset-multi-picker__checkbox"
            :model-value="selectedAssetIdSet.has(asset.id)"
            :disabled="excludedAssetIdSet.has(asset.id)"
            @click.stop
            @change="toggleAsset(asset)"
          />
          <div class="asset-multi-picker__preview">
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
            <div v-else class="asset-multi-picker__empty">无预览</div>
          </div>
          <div class="asset-multi-picker__name" :title="asset.originalFilename">
            {{ asset.originalFilename }}
          </div>
          <div class="asset-multi-picker__meta">
            <span>ID {{ asset.id }}</span>
            <span v-if="excludedAssetIdSet.has(asset.id)">已添加</span>
            <span v-else>{{ formatFileSize(asset.sizeBytes) }}</span>
          </div>
        </div>

        <ElEmpty v-if="!loading && !assets.length" :description="`暂无${mediaKindLabel}素材`" />
      </div>

      <div class="asset-multi-picker__pagination">
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

    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton type="primary" :disabled="!selectedAssetIds.length" @click="confirmSelection">
        添加 {{ selectedAssetIds.length }} 个素材
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { computed, reactive, ref } from 'vue'
  import { ElMessage } from 'element-plus'
  import { fetchAssetFolders, fetchAssets } from '@/api/assets'

  defineOptions({ name: 'AssetMultiPickerDialog' })

  interface TreeOption {
    value: number
    label: string
    disabled?: boolean
    children?: TreeOption[]
  }

  interface Props {
    modelValue: boolean
    mediaKind: Exclude<Api.Storage.MediaKind, 'DOCUMENT'>
    maxSelection: number
    excludeFileIds?: number[]
  }

  interface Emits {
    (event: 'update:modelValue', value: boolean): void
    (event: 'confirm', value: Api.Common.AssetValue[]): void
  }

  const props = withDefaults(defineProps<Props>(), {
    excludeFileIds: () => []
  })
  const emit = defineEmits<Emits>()

  const loading = ref(false)
  const folders = ref<Api.Storage.AssetFolder[]>([])
  const assets = ref<Api.Storage.AssetItem[]>([])
  const selectedAssetIds = ref<number[]>([])
  const selectedAssets = ref<Record<number, Api.Storage.AssetItem>>({})
  const pagination = reactive({ current: 1, size: 12, total: 0 })
  const filters = reactive<{ keyword: string; folderId?: number }>({ keyword: '' })

  const mediaKindLabel = computed(() => (props.mediaKind === 'VIDEO' ? '视频' : '图片'))
  const selectedAssetIdSet = computed(() => new Set(selectedAssetIds.value))
  const excludedAssetIdSet = computed(() => new Set(props.excludeFileIds))
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

  const resolveAssetUrl = (asset: Api.Storage.AssetItem) => asset.publicUrl || asset.url || ''
  const formatFileSize = (sizeBytes?: number) => {
    if (!sizeBytes) return '0 B'
    if (sizeBytes < 1024) return `${sizeBytes} B`
    if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
    return `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
  }

  const loadAssets = async () => {
    loading.value = true
    try {
      const response = await fetchAssets({
        current: pagination.current,
        size: pagination.size,
        keyword: filters.keyword.trim() || undefined,
        folderId: filters.folderId,
        mediaKind: props.mediaKind
      })
      assets.value = response.records
      pagination.current = response.current
      pagination.size = response.size
      pagination.total = response.total
    } finally {
      loading.value = false
    }
  }

  const handleOpen = async () => {
    selectedAssetIds.value = []
    selectedAssets.value = {}
    filters.keyword = ''
    filters.folderId = undefined
    pagination.current = 1
    await Promise.all([
      fetchAssetFolders().then((response) => (folders.value = response)),
      loadAssets()
    ])
  }

  const handleSearch = () => {
    pagination.current = 1
    loadAssets()
  }

  const handleReset = () => {
    filters.keyword = ''
    filters.folderId = undefined
    pagination.current = 1
    loadAssets()
  }

  const handleCurrentChange = (page: number) => {
    pagination.current = page
    loadAssets()
  }

  const toggleAsset = (asset: Api.Storage.AssetItem) => {
    if (excludedAssetIdSet.value.has(asset.id)) return
    if (selectedAssetIdSet.value.has(asset.id)) {
      selectedAssetIds.value = selectedAssetIds.value.filter((id) => id !== asset.id)
      const next = { ...selectedAssets.value }
      delete next[asset.id]
      selectedAssets.value = next
      return
    }
    if (selectedAssetIds.value.length >= props.maxSelection) {
      ElMessage.warning(`本次最多还能选择 ${props.maxSelection} 个素材`)
      return
    }
    selectedAssetIds.value = [...selectedAssetIds.value, asset.id]
    selectedAssets.value = { ...selectedAssets.value, [asset.id]: asset }
  }

  const confirmSelection = () => {
    const values = selectedAssetIds.value.map((assetId) => {
      const asset = selectedAssets.value[assetId]
      return { fileId: asset.id, url: resolveAssetUrl(asset) }
    })
    emit('confirm', values)
    emit('update:modelValue', false)
  }
</script>

<style scoped lang="scss">
  .asset-multi-picker {
    display: grid;
    gap: 14px;
  }

  .asset-multi-picker__toolbar {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }

  .asset-multi-picker__count {
    margin-left: auto;
    color: var(--el-text-color-secondary);
  }

  .asset-multi-picker__grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
    min-height: 360px;
  }

  .asset-multi-picker__card {
    position: relative;
    min-width: 0;
    padding: 10px;
    cursor: pointer;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;

    &.is-active {
      border-color: var(--el-color-primary);
      box-shadow: 0 0 0 2px var(--el-color-primary-light-7);
    }

    &.is-disabled {
      cursor: not-allowed;
      opacity: 0.58;
    }
  }

  .asset-multi-picker__checkbox {
    position: absolute;
    top: 14px;
    left: 14px;
    z-index: 2;
    padding: 3px;
    margin: 0;
    background: rgb(255 255 255 / 90%);
    border-radius: 5px;
  }

  .asset-multi-picker__preview {
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 6px;

    :deep(img),
    video {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  .asset-multi-picker__empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: var(--el-text-color-secondary);
  }

  .asset-multi-picker__name {
    margin-top: 8px;
    overflow: hidden;
    font-size: 13px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .asset-multi-picker__meta {
    display: flex;
    justify-content: space-between;
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .asset-multi-picker__pagination {
    display: flex;
    justify-content: flex-end;
  }

  @media (width <= 760px) {
    .asset-multi-picker__grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .asset-multi-picker__count {
      width: 100%;
      margin-left: 0;
    }
  }
</style>
