<template>
  <div class="asset-library art-full-height">
    <div class="asset-library__layout">
      <aside class="asset-library__sidebar">
        <div class="asset-library__sidebar-header">
          <span>素材分组</span>
        </div>

        <button
          type="button"
          class="asset-library__virtual-node"
          :class="{ 'is-active': selectedFolderId === undefined }"
          @click="selectFolder(undefined)"
        >
          全部素材
        </button>
        <button
          type="button"
          class="asset-library__virtual-node"
          :class="{ 'is-active': selectedFolderId === 0 }"
          @click="selectFolder(0)"
        >
          未分组
        </button>

        <ElTree
          v-loading="folderSorting"
          class="asset-library__folder-tree"
          :data="folders"
          node-key="id"
          default-expand-all
          highlight-current
          :expand-on-click-node="false"
          :draggable="canSortFolders"
          :allow-drop="allowFolderDrop"
          @node-click="handleFolderSelect"
          @node-drag-start="captureFolderOrder"
          @node-drop="handleFolderDrop"
        >
          <template #default="{ data }">
            <div class="asset-library__tree-node">
              <ElIcon
                v-if="canSortFolders"
                class="asset-library__drag-handle"
                title="拖动调整分组顺序或层级"
              >
                <Rank />
              </ElIcon>
              <span class="asset-library__tree-name">{{ data.name }}</span>
              <ElTag v-if="data.status === 'DISABLED'" size="small" type="info">停用</ElTag>
              <ElDropdown
                v-auth="'asset:folder'"
                trigger="click"
                @click.stop
                @command="handleFolderCommand($event, data)"
              >
                <ElButton text size="small" @click.stop>
                  <ElIcon><MoreFilled /></ElIcon>
                </ElButton>
                <template #dropdown>
                  <ElDropdownMenu>
                    <ElDropdownItem command="add">添加子分组</ElDropdownItem>
                    <ElDropdownItem command="edit">编辑</ElDropdownItem>
                    <ElDropdownItem command="toggle">
                      {{ data.status === 'ENABLED' ? '停用' : '启用' }}
                    </ElDropdownItem>
                    <ElDropdownItem command="delete" divided>删除</ElDropdownItem>
                  </ElDropdownMenu>
                </template>
              </ElDropdown>
            </div>
          </template>
        </ElTree>

        <button
          v-auth="'asset:folder'"
          type="button"
          class="asset-library__virtual-node asset-library__create-node"
          @click="openFolderDialog('create')"
        >
          <ElIcon><Plus /></ElIcon>
          <span>新建分组</span>
        </button>
      </aside>

      <section class="asset-library__main">
        <ArtSearchBar
          v-model="searchForm"
          :items="searchItems"
          :show-expand="false"
          @search="handleSearch"
          @reset="handleReset"
        />

        <ElCard class="art-table-card" :style="{ marginTop: '12px' }">
          <ArtTableHeader :loading="loading" v-model:columns="columnChecks" @refresh="loadAssets">
            <template #left>
              <ElButton v-auth="'asset:upload'" type="primary" @click="openUploadDialog">
                上传素材
              </ElButton>
              <ElButton
                v-if="selectedAssetIds.length"
                v-auth="'asset:folder'"
                @click="openBatchMoveDialog"
              >
                移动分组（{{ selectedAssetIds.length }}）
              </ElButton>
              <ElButton v-if="selectedAssetIds.length" text @click="clearAssetSelection">
                取消选择
              </ElButton>
            </template>
            <template #right>
              <div class="asset-library__toolbar">
                <ElButton
                  :type="viewMode === 'grid' ? 'primary' : 'default'"
                  @click="viewMode = 'grid'"
                >
                  网格
                </ElButton>
                <ElButton
                  :type="viewMode === 'list' ? 'primary' : 'default'"
                  @click="viewMode = 'list'"
                >
                  列表
                </ElButton>
              </div>
            </template>
          </ArtTableHeader>

          <div v-if="viewMode === 'grid'" v-loading="loading" class="asset-library__grid-wrap">
            <div class="asset-library__grid">
              <div
                v-for="asset in assets"
                :key="asset.id"
                class="asset-card"
                :class="{ 'is-selected': isAssetSelected(asset.id) }"
                role="button"
                tabindex="0"
                @click="openDetail(asset.id)"
                @keydown.enter="openDetail(asset.id)"
                @keydown.space.prevent="openDetail(asset.id)"
              >
                <ElCheckbox
                  class="asset-card__select"
                  :model-value="isAssetSelected(asset.id)"
                  :aria-label="`选择素材 ${asset.originalFilename}`"
                  @click.stop
                  @change="toggleAssetSelection(asset.id, Boolean($event))"
                />
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
                    <ElTag size="small">{{ formatMediaKind(asset.mediaKind) }}</ElTag>
                    <span>{{ folderName(asset.folderId) }}</span>
                  </div>
                  <div class="asset-card__meta">
                    <span>{{ formatFileSize(asset.sizeBytes) }}</span>
                    <span>当前引用 {{ asset.usageCount || 0 }} 处</span>
                  </div>
                </div>
              </div>
            </div>

            <ElEmpty v-if="!loading && !assets.length" description="暂无素材" />

            <div class="asset-library__pagination">
              <ElPagination
                background
                layout="total, prev, pager, next, sizes"
                :current-page="pagination.current"
                :page-size="pagination.size"
                :total="pagination.total"
                @current-change="handleCurrentChange"
                @size-change="handleSizeChange"
              />
            </div>
          </div>

          <ArtTable
            v-else
            ref="assetTableRef"
            :loading="loading"
            :data="assets"
            :columns="columns"
            :pagination="pagination"
            :empty-height="ASSET_LIBRARY_EMPTY_TABLE_HEIGHT"
            row-key="id"
            @selection-change="handleTableSelectionChange"
            @pagination:current-change="handleCurrentChange"
            @pagination:size-change="handleSizeChange"
          />
        </ElCard>
      </section>
    </div>

    <ElDialog
      v-model="uploadDialogVisible"
      title="上传素材"
      width="560px"
      align-center
      @closed="resetUpload"
    >
      <ElForm label-width="92px">
        <ElFormItem label="素材分组">
          <ElTreeSelect
            v-model="uploadFolderId"
            :data="folderOptions"
            node-key="value"
            check-strictly
            default-expand-all
            :render-after-expand="false"
            placeholder="请选择分组"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="选择文件">
          <ElUpload
            accept="image/*,video/mp4,video/webm,.mp4,.webm"
            :auto-upload="false"
            multiple
            :limit="50"
            :disabled="uploading"
            :file-list="uploadFileList"
            @change="handleUploadChange"
            @remove="handleUploadRemove"
            @exceed="handleUploadExceed"
          >
            <ElButton :disabled="uploading">批量选择图片或视频</ElButton>
          </ElUpload>
        </ElFormItem>
        <ElAlert
          title="图片和视频类型由服务端自动识别；视频仅支持 MP4、WebM，最大 50 MB。"
          type="info"
          :closable="false"
        />
        <ElAlert
          v-if="uploadSummary"
          class="asset-library__upload-summary"
          :title="`本次上传成功 ${uploadSummary.succeeded} 个，失败 ${uploadSummary.failed} 个`"
          :type="uploadSummary.failed ? 'warning' : 'success'"
          :closable="false"
        />
      </ElForm>

      <template #footer>
        <ElButton @click="uploadDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="uploading" @click="submitUpload">上传</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="moveDialogVisible"
      :title="movingAssetIds.length > 1 ? `批量移动 ${movingAssetIds.length} 个素材` : '移动分组'"
      width="440px"
      align-center
    >
      <ElTreeSelect
        v-model="moveTargetFolderId"
        :data="folderOptions"
        node-key="value"
        check-strictly
        default-expand-all
        :render-after-expand="false"
        placeholder="请选择目标分组"
        style="width: 100%"
      />
      <template #footer>
        <ElButton @click="moveDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="moving" @click="submitMove">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="folderDialogVisible"
      :title="folderDialogMode === 'edit' ? '编辑分组' : '新建分组'"
      width="500px"
      align-center
    >
      <ElForm label-width="92px">
        <ElFormItem label="上级分组">
          <ElTreeSelect
            v-model="folderForm.parentId"
            :data="folderParentOptions"
            node-key="value"
            check-strictly
            default-expand-all
            :render-after-expand="false"
            placeholder="请选择上级分组"
            style="width: 100%"
            @change="handleFolderParentChange"
          />
        </ElFormItem>
        <ElFormItem label="分组名称" required>
          <ElInput v-model="folderForm.name" maxlength="64" show-word-limit />
        </ElFormItem>
        <ElFormItem v-if="folderDialogMode === 'edit'" label="排序">
          <ElInputNumber
            v-model="folderForm.sortOrder"
            :min="0"
            :precision="0"
            controls-position="right"
          />
        </ElFormItem>
        <ElFormItem label="状态">
          <ElRadioGroup v-model="folderForm.status">
            <ElRadioButton value="ENABLED">启用</ElRadioButton>
            <ElRadioButton value="DISABLED">停用</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="folderDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="folderSaving" @click="submitFolder">保存</ElButton>
      </template>
    </ElDialog>

    <ElDrawer
      v-model="detailDrawerVisible"
      title="素材详情"
      size="50%"
      destroy-on-close
      @closed="cancelDisplayNameEdit"
    >
      <div v-if="detailAsset" v-loading="detailLoading" class="asset-detail">
        <div class="asset-detail__hero">
          <video
            v-if="detailAsset.mediaKind === 'VIDEO' && resolveAssetUrl(detailAsset)"
            :src="resolveAssetUrl(detailAsset)"
            controls
            preload="metadata"
          />
          <ElImage
            v-else-if="detailAsset.mediaKind === 'IMAGE' && resolveAssetUrl(detailAsset)"
            :src="resolveAssetUrl(detailAsset)"
            fit="contain"
            :preview-src-list="[resolveAssetUrl(detailAsset)]"
            preview-teleported
          />
          <div v-else class="asset-detail__empty">无预览</div>
        </div>

        <ElDescriptions class="asset-detail__descriptions" :column="2" border size="small">
          <ElDescriptionsItem label="素材 ID">{{ detailAsset.id }}</ElDescriptionsItem>
          <ElDescriptionsItem label="文件名">
            <div class="asset-detail__filename">
              <template v-if="displayNameEditing">
                <ElInput
                  v-model="displayNameDraft"
                  size="small"
                  :maxlength="displayNameMaxLength"
                  show-word-limit
                  autofocus
                  @keyup.enter="submitDisplayName"
                >
                  <template #append>{{ displayNameExtension }}</template>
                </ElInput>
                <ElButton
                  type="primary"
                  link
                  size="small"
                  :loading="displayNameSaving"
                  @click="submitDisplayName"
                >
                  保存
                </ElButton>
                <ElButton
                  link
                  size="small"
                  :disabled="displayNameSaving"
                  @click="cancelDisplayNameEdit"
                >
                  取消
                </ElButton>
              </template>
              <template v-else>
                <span :title="detailAsset.originalFilename">{{
                  detailAsset.originalFilename
                }}</span>
                <ElButton
                  v-auth="'asset:folder'"
                  type="primary"
                  link
                  size="small"
                  @click="startDisplayNameEdit"
                >
                  修改
                </ElButton>
              </template>
            </div>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="媒体类型">
            {{ formatMediaKind(detailAsset.mediaKind) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="素材分组">
            {{ folderName(detailAsset.folderId) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="文件类型">{{ detailAsset.contentType }}</ElDescriptionsItem>
          <ElDescriptionsItem label="大小">
            {{ formatFileSize(detailAsset.sizeBytes) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="尺寸">
            {{
              detailAsset.width && detailAsset.height
                ? `${detailAsset.width} × ${detailAsset.height}`
                : '-'
            }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="detailAsset.mediaKind === 'VIDEO'" label="时长">
            {{ formatDuration(detailAsset.durationSeconds) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="当前引用数">{{
            detailAsset.usageCount || 0
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="存储提供商">{{ detailAsset.provider }}</ElDescriptionsItem>
          <ElDescriptionsItem label="创建时间" :span="detailAsset.mediaKind === 'VIDEO' ? 2 : 1">
            {{ formatDateTime(detailAsset.createdAt) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="URL" :span="2">
            <div class="asset-detail__url">
              <span :title="resolveAssetUrl(detailAsset)">{{
                resolveAssetUrl(detailAsset) || '-'
              }}</span>
              <ElButton
                type="primary"
                link
                size="small"
                :icon="CopyDocument"
                :disabled="!resolveAssetUrl(detailAsset)"
                @click="copyAssetUrl"
              >
                复制
              </ElButton>
            </div>
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="asset-detail__usage">
          <div class="asset-detail__usage-title">当前引用位置</div>
          <ElTable v-if="currentUsages.length" :data="currentUsages" border size="small">
            <ElTableColumn prop="usageType" label="引用角色" min-width="150" />
            <ElTableColumn prop="ownerType" label="对象类型" min-width="130" />
            <ElTableColumn prop="ownerLabel" label="归属对象" min-width="180" />
            <ElTableColumn prop="createdAt" label="引用时间" width="170">
              <template #default="{ row }">
                {{ formatDateTime(row.createdAt) }}
              </template>
            </ElTableColumn>
            <ElTableColumn prop="protected" label="保护" width="80">
              <template #default="{ row }">
                <ElTag size="small" :type="row.protected ? 'warning' : 'info'">
                  {{ row.protected ? '是' : '否' }}
                </ElTag>
              </template>
            </ElTableColumn>
          </ElTable>
          <ElEmpty v-else description="当前未被引用" :image-size="64" />

          <ElCollapse v-if="historicalUsages.length" class="asset-detail__history">
            <ElCollapseItem :title="`历史引用记录（${historicalUsages.length}）`" name="history">
              <ElTable :data="historicalUsages" border size="small">
                <ElTableColumn prop="usageType" label="引用角色" min-width="150" />
                <ElTableColumn prop="ownerType" label="对象类型" min-width="130" />
                <ElTableColumn prop="ownerLabel" label="归属对象" min-width="180" />
                <ElTableColumn prop="updatedAt" label="取消时间" width="170">
                  <template #default="{ row }">
                    {{ formatDateTime(row.updatedAt) }}
                  </template>
                </ElTableColumn>
              </ElTable>
            </ElCollapseItem>
          </ElCollapse>
        </div>
      </div>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, nextTick, onMounted, reactive, ref, watch } from 'vue'
  import {
    CopyDocument,
    MoreFilled,
    Picture,
    Plus,
    Rank,
    VideoCamera
  } from '@element-plus/icons-vue'
  import {
    ElImage,
    ElMessage,
    ElMessageBox,
    ElTag,
    type AllowDropFunction,
    type NodeDropType,
    type TableInstance,
    type UploadUserFile
  } from 'element-plus'
  import { useClipboard } from '@vueuse/core'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import type { ColumnOption } from '@/types'
  import { useAuth } from '@/hooks'
  import { settleWithConcurrency } from '@/utils/asset-batch'
  import { ASSET_LIBRARY_EMPTY_TABLE_HEIGHT } from './asset-library-layout'
  import {
    batchMoveAssets,
    createAssetFolder,
    deleteAsset,
    deleteAssetFolder,
    fetchAssetDetail,
    fetchAssetFolders,
    fetchAssets,
    updateAssetDisplayName,
    updateAssetFolder,
    updateAssetFolderPosition,
    uploadAsset
  } from '@/api/assets'

  defineOptions({ name: 'StorageFiles' })

  interface TreeOption {
    value: number
    label: string
    disabled?: boolean
    children?: TreeOption[]
  }

  interface AssetSearchForm {
    keyword?: string
    mediaKind?: Exclude<Api.Storage.MediaKind, 'DOCUMENT'>
    referenceStatus?: Api.Storage.ReferenceStatus
    createdRange?: string[]
  }

  type FolderDialogMode = 'create' | 'edit'

  interface AssetTableExpose {
    elTableRef: TableInstance | null
  }

  interface UploadSummary {
    succeeded: number
    failed: number
  }

  const loading = ref(false)
  const uploading = ref(false)
  const moving = ref(false)
  const folderSaving = ref(false)
  const folderSorting = ref(false)
  const assets = ref<Api.Storage.AssetItem[]>([])
  const folders = ref<Api.Storage.AssetFolder[]>([])
  const selectedFolderId = ref<number | undefined>()
  const viewMode = ref<'grid' | 'list'>('list')
  const uploadDialogVisible = ref(false)
  const moveDialogVisible = ref(false)
  const folderDialogVisible = ref(false)
  const detailDrawerVisible = ref(false)
  const detailLoading = ref(false)
  const displayNameEditing = ref(false)
  const displayNameSaving = ref(false)
  const displayNameDraft = ref('')
  const detailAsset = ref<Api.Storage.AssetItem | null>(null)
  const movingAssetIds = ref<number[]>([])
  const moveTargetFolderId = ref<number>(0)
  const uploadFolderId = ref<number>(0)
  const uploadFileList = ref<UploadUserFile[]>([])
  const uploadSummary = ref<UploadSummary | null>(null)
  const folderDialogMode = ref<FolderDialogMode>('create')
  const editingFolderId = ref<number | null>(null)
  const selectedAssetIds = ref<number[]>([])
  const assetTableRef = ref<AssetTableExpose | null>(null)
  const syncingTableSelection = ref(false)
  let folderOrderSnapshot: Api.Storage.AssetFolder[] = []

  const currentUsages = computed(() =>
    (detailAsset.value?.usages || []).filter((usage) => usage.status === 'ACTIVE')
  )
  const historicalUsages = computed(() =>
    (detailAsset.value?.usages || []).filter((usage) => usage.status !== 'ACTIVE')
  )
  const { copy } = useClipboard({ legacy: true })
  const { hasAuth } = useAuth()
  const canSortFolders = computed(() => hasAuth('asset:folder') && !folderSorting.value)
  const selectedAssetIdSet = computed(() => new Set(selectedAssetIds.value))
  const displayNameExtension = computed(() =>
    detailAsset.value?.extension ? `.${detailAsset.value.extension}` : ''
  )
  const displayNameMaxLength = computed(() => Math.max(1, 255 - displayNameExtension.value.length))

  const pagination = reactive<Api.Common.PaginationParams>({ current: 1, size: 20, total: 0 })
  const searchForm = ref<AssetSearchForm>({
    keyword: undefined,
    mediaKind: undefined,
    referenceStatus: undefined,
    createdRange: undefined
  })
  const folderForm = reactive<Api.Storage.AssetFolderForm>({
    parentId: 0,
    name: '',
    sortOrder: 0,
    status: 'ENABLED'
  })

  const folderNameMap = computed<Record<number, string>>(() => {
    const map: Record<number, string> = { 0: '未分组' }
    const walk = (items: Api.Storage.AssetFolder[]) => {
      items.forEach((item) => {
        map[item.id] = item.name
        walk(item.children || [])
      })
    }
    walk(folders.value)
    return map
  })

  const buildFolderOptions = (
    items: Api.Storage.AssetFolder[],
    excludedIds = new Set<number>(),
    disableInactive = true
  ): TreeOption[] =>
    items
      .filter((item) => !excludedIds.has(item.id))
      .map((item) => ({
        value: item.id,
        label: item.status === 'DISABLED' ? `${item.name}（已停用）` : item.name,
        disabled: disableInactive && item.status === 'DISABLED',
        children: buildFolderOptions(item.children || [], excludedIds, disableInactive)
      }))

  const folderOptions = computed<TreeOption[]>(() => [
    { value: 0, label: '未分组' },
    ...buildFolderOptions(folders.value)
  ])

  const findFolder = (
    items: Api.Storage.AssetFolder[],
    id: number
  ): Api.Storage.AssetFolder | null => {
    for (const item of items) {
      if (item.id === id) return item
      const child = findFolder(item.children || [], id)
      if (child) return child
    }
    return null
  }

  const cloneFolderTree = (items: Api.Storage.AssetFolder[]): Api.Storage.AssetFolder[] =>
    items.map((item) => ({
      ...item,
      children: cloneFolderTree(item.children || [])
    }))

  const folderContainsId = (folder: Api.Storage.AssetFolder, id: number): boolean =>
    folder.id === id || folder.children?.some((child) => folderContainsId(child, id)) || false

  const isEnabledFolderChain = (folderId: number): boolean => {
    if (folderId === 0) return true
    const folder = findFolder(folders.value, folderId)
    return Boolean(folder && folder.status === 'ENABLED' && isEnabledFolderChain(folder.parentId))
  }

  const allowFolderDrop: AllowDropFunction = (draggingNode, dropNode, dropType) => {
    if (folderSorting.value) return false
    const draggedFolder = draggingNode.data as Api.Storage.AssetFolder
    const targetFolder = dropNode.data as Api.Storage.AssetFolder
    const targetParentId = dropType === 'inner' ? targetFolder.id : targetFolder.parentId
    if (folderContainsId(draggedFolder, targetParentId)) return false
    return isEnabledFolderChain(targetParentId)
  }

  const captureFolderOrder = () => {
    folderOrderSnapshot = cloneFolderTree(folders.value)
  }

  const handleFolderDrop = async (
    draggingNode: Parameters<AllowDropFunction>[0],
    dropNode: Parameters<AllowDropFunction>[1],
    dropType: NodeDropType
  ) => {
    if (dropType === 'none') return

    const draggedFolder = draggingNode.data as Api.Storage.AssetFolder
    const targetFolder = dropNode.data as Api.Storage.AssetFolder
    const targetParentId = dropType === 'inner' ? targetFolder.id : targetFolder.parentId
    const targetSiblings =
      targetParentId === 0
        ? folders.value
        : findFolder(folders.value, targetParentId)?.children || []
    const targetIndex = targetSiblings.findIndex((folder) => folder.id === draggedFolder.id)
    if (targetIndex < 0) {
      folders.value = folderOrderSnapshot
      await loadFolders()
      return
    }

    folderSorting.value = true
    try {
      await updateAssetFolderPosition(draggedFolder.id, {
        parentId: targetParentId,
        index: targetIndex
      })
      ElMessage.success(
        targetParentId === draggedFolder.parentId ? '分组排序已更新' : '分组位置已更新'
      )
      await loadFolders()
    } catch {
      folders.value = folderOrderSnapshot
      await loadFolders()
    } finally {
      folderSorting.value = false
      folderOrderSnapshot = []
    }
  }

  const collectFolderIds = (folder?: Api.Storage.AssetFolder | null, ids = new Set<number>()) => {
    if (!folder) return ids
    ids.add(folder.id)
    folder.children?.forEach((child) => collectFolderIds(child, ids))
    return ids
  }

  const folderParentOptions = computed<TreeOption[]>(() => {
    const editing = editingFolderId.value ? findFolder(folders.value, editingFolderId.value) : null
    const excludedIds = collectFolderIds(editing)
    return [
      { value: 0, label: '顶级分组' },
      ...buildFolderOptions(folders.value, excludedIds, false)
    ]
  })

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '文件名',
      key: 'keyword',
      type: 'input',
      props: { clearable: true, placeholder: '请输入文件名' }
    },
    {
      label: '类型',
      key: 'mediaKind',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '图片或视频',
        options: [
          { label: '图片', value: 'IMAGE' },
          { label: '视频', value: 'VIDEO' }
        ]
      }
    },
    {
      label: '引用',
      key: 'referenceStatus',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '引用状态',
        options: [
          { label: '已引用', value: 'REFERENCED' },
          { label: '未引用', value: 'UNREFERENCED' }
        ]
      }
    },
    {
      label: '上传日期',
      key: 'createdRange',
      type: 'daterange',
      props: {
        type: 'daterange',
        valueFormat: 'YYYY-MM-DD',
        rangeSeparator: '至',
        startPlaceholder: '开始日期',
        endPlaceholder: '结束日期'
      }
    }
  ])

  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')
  const formatMediaKind = (kind?: Api.Storage.MediaKind) => {
    if (kind === 'IMAGE') return '图片'
    if (kind === 'VIDEO') return '视频'
    return '文档'
  }
  const formatFileSize = (sizeBytes?: number) => {
    if (!sizeBytes) return '0 B'
    if (sizeBytes < 1024) return `${sizeBytes} B`
    if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
    return `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
  }
  const formatDuration = (seconds?: number | null) => {
    if (!seconds) return '-'
    const minutes = Math.floor(seconds / 60)
    const remainder = Math.round(seconds % 60)
    return `${minutes}:${String(remainder).padStart(2, '0')}`
  }
  const resolveAssetUrl = (asset?: Api.Storage.AssetItem | null) =>
    asset?.publicUrl || asset?.url || ''
  const folderName = (folderId?: number | null) => folderNameMap.value[folderId || 0] || '未分组'

  const copyAssetUrl = async () => {
    const url = resolveAssetUrl(detailAsset.value)
    if (!url) return
    try {
      await copy(url)
      ElMessage.success('URL 已复制')
    } catch {
      ElMessage.error('复制失败，请手动复制')
    }
  }

  const displayNameFromFilename = (filename: string, extension?: string | null) => {
    const suffix = extension ? `.${extension}` : ''
    return suffix && filename.toLowerCase().endsWith(suffix.toLowerCase())
      ? filename.slice(0, -suffix.length)
      : filename
  }

  const startDisplayNameEdit = () => {
    if (!detailAsset.value) return
    displayNameDraft.value = displayNameFromFilename(
      detailAsset.value.originalFilename,
      detailAsset.value.extension
    )
    displayNameEditing.value = true
  }

  const cancelDisplayNameEdit = () => {
    if (displayNameSaving.value) return
    displayNameEditing.value = false
    displayNameDraft.value = ''
  }

  const submitDisplayName = async () => {
    if (!detailAsset.value || displayNameSaving.value) return
    const displayName = displayNameDraft.value.trim()
    if (!displayName || displayName === '.' || displayName === '..') {
      ElMessage.warning('请输入有效的文件名')
      return
    }
    if (/[\\/\u0000-\u001f\u007f]/.test(displayName)) {
      ElMessage.warning('文件名不能包含斜杠、反斜杠或控制字符')
      return
    }
    displayNameSaving.value = true
    try {
      detailAsset.value = await updateAssetDisplayName(detailAsset.value.id, { displayName })
      displayNameEditing.value = false
      displayNameDraft.value = ''
      await loadAssets()
    } finally {
      displayNameSaving.value = false
    }
  }

  const { columns, columnChecks } = useTableColumns<Api.Storage.AssetItem>(() => {
    const tableColumns: ColumnOption<Api.Storage.AssetItem>[] = [
      { type: 'selection', width: 48, fixed: 'left' },
      {
        prop: 'preview',
        label: '预览',
        width: 88,
        formatter: (asset) => {
          const url = resolveAssetUrl(asset)
          if (!url) return h('div', { class: 'asset-library__empty-cell' }, '无预览')
          if (asset.mediaKind === 'VIDEO') {
            return h('video', {
              src: url,
              muted: true,
              preload: 'metadata',
              style: {
                width: '56px',
                height: '56px',
                objectFit: 'cover',
                borderRadius: '6px',
                backgroundColor: '#111827'
              }
            })
          }
          return h(ElImage, {
            src: url,
            fit: 'cover',
            previewSrcList: [url],
            previewTeleported: true,
            style: { width: '56px', height: '56px', borderRadius: '6px' }
          })
        }
      },
      {
        prop: 'originalFilename',
        label: '文件信息',
        minWidth: 260,
        formatter: (asset) =>
          h('div', { class: 'asset-library__file-cell' }, [
            h('div', { class: 'title' }, asset.originalFilename),
            h('div', { class: 'subtitle' }, `ID ${asset.id} / ${formatMediaKind(asset.mediaKind)}`)
          ])
      },
      {
        prop: 'folderId',
        label: '分组',
        minWidth: 130,
        formatter: (asset) => folderName(asset.folderId)
      },
      {
        prop: 'usageCount',
        label: '当前引用数',
        width: 110,
        formatter: (asset) => String(asset.usageCount || 0)
      },
      {
        prop: 'sizeBytes',
        label: '大小',
        width: 110,
        formatter: (asset) => formatFileSize(asset.sizeBytes)
      },
      {
        prop: 'createdAt',
        label: '上传时间',
        width: 180,
        formatter: (asset) => formatDateTime(asset.createdAt)
      },
      {
        prop: 'operation',
        label: '操作',
        width: 120,
        fixed: 'right',
        formatter: (asset) =>
          h(ArtButtonMore, {
            list: buildAssetActions(),
            onClick: (item: ButtonMoreItem) => handleAssetAction(item, asset)
          })
      }
    ]
    return tableColumns
  })

  const dateRangeParams = () => {
    const [start, end] = searchForm.value.createdRange || []
    return {
      createdFrom: start ? `${start}T00:00:00` : undefined,
      createdTo: end ? `${end}T23:59:59` : undefined
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
        keyword: searchForm.value.keyword?.trim() || undefined,
        mediaKind: searchForm.value.mediaKind,
        folderId: selectedFolderId.value,
        referenceStatus: searchForm.value.referenceStatus,
        ...dateRangeParams()
      })
      assets.value = response.records
      pagination.current = response.current
      pagination.size = response.size
      pagination.total = response.total
    } finally {
      loading.value = false
    }
  }

  const isAssetSelected = (assetId: number) => selectedAssetIdSet.value.has(assetId)

  const syncTableSelection = async () => {
    if (viewMode.value !== 'list') return
    await nextTick()
    const table = assetTableRef.value?.elTableRef
    if (!table) return
    syncingTableSelection.value = true
    table.clearSelection()
    assets.value
      .filter((asset) => selectedAssetIdSet.value.has(asset.id))
      .forEach((asset) => table.toggleRowSelection(asset, true))
    await nextTick()
    syncingTableSelection.value = false
  }

  const clearAssetSelection = () => {
    selectedAssetIds.value = []
    assetTableRef.value?.elTableRef?.clearSelection()
  }

  const toggleAssetSelection = (assetId: number, selected: boolean) => {
    if (selected) {
      selectedAssetIds.value = Array.from(new Set([...selectedAssetIds.value, assetId]))
    } else {
      selectedAssetIds.value = selectedAssetIds.value.filter((id) => id !== assetId)
    }
  }

  const handleTableSelectionChange = (selection: Api.Storage.AssetItem[]) => {
    if (syncingTableSelection.value) return
    selectedAssetIds.value = selection.map((asset) => asset.id)
  }

  watch(viewMode, () => syncTableSelection())

  const openDetail = async (assetId: number) => {
    displayNameEditing.value = false
    displayNameDraft.value = ''
    detailDrawerVisible.value = true
    detailLoading.value = true
    try {
      detailAsset.value = await fetchAssetDetail(assetId)
    } finally {
      detailLoading.value = false
    }
  }

  const buildAssetActions = (): ButtonMoreItem[] => [
    { key: 'detail', label: '详情', icon: 'ri:eye-line', auth: 'asset:read' },
    { key: 'move', label: '移动分组', icon: 'ri:folder-transfer-line', auth: 'asset:folder' },
    {
      key: 'delete',
      label: '删除',
      icon: 'ri:delete-bin-5-line',
      color: '#f56c6c',
      auth: 'asset:delete'
    }
  ]

  const handleAssetAction = (item: ButtonMoreItem, asset: Api.Storage.AssetItem) => {
    if (item.key === 'detail') openDetail(asset.id)
    if (item.key === 'move') {
      movingAssetIds.value = [asset.id]
      moveTargetFolderId.value = asset.folderId || 0
      moveDialogVisible.value = true
    }
    if (item.key === 'delete') confirmDeleteAsset(asset)
  }

  const confirmDeleteAsset = async (asset: Api.Storage.AssetItem) => {
    try {
      await ElMessageBox.confirm(`确定删除素材“${asset.originalFilename}”吗？`, '删除确认', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      })
      await deleteAsset(asset.id)
      toggleAssetSelection(asset.id, false)
      if (detailAsset.value?.id === asset.id) detailDrawerVisible.value = false
      await loadAssets()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  const handleSearch = () => {
    clearAssetSelection()
    pagination.current = 1
    loadAssets()
  }

  const handleReset = () => {
    clearAssetSelection()
    searchForm.value = {
      keyword: undefined,
      mediaKind: undefined,
      referenceStatus: undefined,
      createdRange: undefined
    }
    selectedFolderId.value = undefined
    pagination.current = 1
    loadAssets()
  }

  const selectFolder = (folderId?: number) => {
    clearAssetSelection()
    selectedFolderId.value = folderId
    pagination.current = 1
    loadAssets()
  }
  const handleFolderSelect = (folder: Api.Storage.AssetFolder) => selectFolder(folder.id)
  const handleCurrentChange = (current: number) => {
    clearAssetSelection()
    pagination.current = current
    loadAssets()
  }
  const handleSizeChange = (size: number) => {
    clearAssetSelection()
    pagination.size = size
    pagination.current = 1
    loadAssets()
  }

  const openUploadDialog = () => {
    const currentFolder = selectedFolderId.value
      ? findFolder(folders.value, selectedFolderId.value)
      : null
    uploadFolderId.value = currentFolder?.status === 'ENABLED' ? currentFolder.id : 0
    uploadDialogVisible.value = true
  }
  const handleUploadChange = (_file: UploadUserFile, fileList: UploadUserFile[]) => {
    uploadFileList.value = fileList.filter((file) => file.raw)
    uploadSummary.value = null
  }
  const handleUploadRemove = (_file: UploadUserFile, fileList: UploadUserFile[]) => {
    uploadFileList.value = fileList.filter((file) => file.raw)
  }
  const handleUploadExceed = () => ElMessage.warning('单次最多选择 50 个文件')
  const resetUpload = () => {
    uploadFileList.value = []
    uploadSummary.value = null
  }
  const submitUpload = async () => {
    const pendingFiles = uploadFileList.value.filter((file) => file.raw)
    if (!pendingFiles.length) {
      ElMessage.warning('请先选择一个或多个文件')
      return
    }
    uploading.value = true
    try {
      const results = await settleWithConcurrency(pendingFiles, 3, async (file) => {
        file.status = 'uploading'
        const asset = await uploadAsset(
          { file: file.raw!, folderId: uploadFolderId.value },
          { showSuccessMessage: false }
        )
        file.status = 'success'
        return asset
      })
      results.forEach((result, index) => {
        if (result.status === 'rejected') pendingFiles[index].status = 'fail'
      })
      const succeeded = results.filter((result) => result.status === 'fulfilled').length
      const failed = results.length - succeeded
      uploadSummary.value = { succeeded, failed }
      await Promise.all([loadAssets(), loadFolders()])
      if (failed) {
        uploadFileList.value = pendingFiles.filter((file) => file.status === 'fail')
        ElMessage.warning(`上传完成：成功 ${succeeded} 个，失败 ${failed} 个，可重试失败项`)
      } else {
        ElMessage.success(`成功上传 ${succeeded} 个素材`)
        uploadDialogVisible.value = false
        resetUpload()
      }
    } finally {
      uploading.value = false
    }
  }

  const openBatchMoveDialog = () => {
    if (!selectedAssetIds.value.length) return
    movingAssetIds.value = [...selectedAssetIds.value]
    const selectedAssets = assets.value.filter((asset) => selectedAssetIdSet.value.has(asset.id))
    const currentFolders = new Set(selectedAssets.map((asset) => asset.folderId || 0))
    moveTargetFolderId.value = currentFolders.size === 1 ? [...currentFolders][0] : 0
    moveDialogVisible.value = true
  }

  const submitMove = async () => {
    if (!movingAssetIds.value.length) return
    moving.value = true
    try {
      await batchMoveAssets({
        assetIds: movingAssetIds.value,
        folderId: moveTargetFolderId.value
      })
      moveDialogVisible.value = false
      movingAssetIds.value = []
      clearAssetSelection()
      await Promise.all([loadAssets(), loadFolders()])
    } finally {
      moving.value = false
    }
  }

  const nextFolderSortOrder = (parentId: number) => {
    const siblings =
      parentId === 0 ? folders.value : findFolder(folders.value, parentId)?.children || []
    return siblings.reduce((maximum, folder) => Math.max(maximum, folder.sortOrder + 1), 0)
  }
  const resetFolderForm = (parentId = 0) => {
    Object.assign(folderForm, {
      parentId,
      name: '',
      sortOrder: nextFolderSortOrder(parentId),
      status: 'ENABLED'
    })
  }
  const handleFolderParentChange = (parentId: number) => {
    if (folderDialogMode.value !== 'create') return
    folderForm.sortOrder = nextFolderSortOrder(parentId)
  }
  const openFolderDialog = (
    mode: FolderDialogMode,
    folder?: Api.Storage.AssetFolder,
    parentId = 0
  ) => {
    folderDialogMode.value = mode
    editingFolderId.value = folder?.id || null
    if (mode === 'edit' && folder) {
      Object.assign(folderForm, {
        parentId: folder.parentId,
        name: folder.name,
        sortOrder: folder.sortOrder,
        status: folder.status
      })
    } else {
      resetFolderForm(parentId)
    }
    folderDialogVisible.value = true
  }

  const handleFolderCommand = async (command: string, folder: Api.Storage.AssetFolder) => {
    if (command === 'add') openFolderDialog('create', undefined, folder.id)
    if (command === 'edit') openFolderDialog('edit', folder)
    if (command === 'toggle') {
      await updateAssetFolder(folder.id, {
        parentId: folder.parentId,
        name: folder.name,
        sortOrder: folder.sortOrder,
        status: folder.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
      })
      await loadFolders()
    }
    if (command === 'delete') await confirmDeleteFolder(folder)
  }

  const confirmDeleteFolder = async (folder: Api.Storage.AssetFolder) => {
    try {
      await ElMessageBox.confirm(`确定删除分组“${folder.name}”吗？非空分组无法删除。`, '删除分组', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      })
      await deleteAssetFolder(folder.id)
      if (selectedFolderId.value === folder.id) selectedFolderId.value = undefined
      await Promise.all([loadFolders(), loadAssets()])
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  const submitFolder = async () => {
    const name = folderForm.name.trim()
    if (!name) {
      ElMessage.warning('请输入分组名称')
      return
    }
    folderSaving.value = true
    try {
      const payload: Api.Storage.AssetFolderForm = { ...folderForm, name }
      if (folderDialogMode.value === 'edit' && editingFolderId.value) {
        await updateAssetFolder(editingFolderId.value, payload)
      } else {
        await createAssetFolder(payload)
      }
      folderDialogVisible.value = false
      await loadFolders()
    } finally {
      folderSaving.value = false
    }
  }

  onMounted(async () => {
    await Promise.all([loadFolders(), loadAssets()])
  })
</script>

<style scoped lang="scss">
  .asset-library__layout {
    display: grid;
    grid-template-columns: 240px minmax(0, 1fr);
    gap: 16px;
    height: 100%;
  }

  .asset-library__sidebar,
  .asset-library__main {
    min-height: 0;
  }

  .asset-library__sidebar {
    padding: 16px;
    overflow: auto;
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color-light);
    border-radius: 8px;
  }

  .asset-library__sidebar-header,
  .asset-library__tree-node,
  .asset-card__meta,
  .asset-library__toolbar {
    display: flex;
    align-items: center;
  }

  .asset-library__sidebar-header {
    justify-content: space-between;
    margin-bottom: 10px;
    font-weight: 600;
  }

  .asset-library__virtual-node {
    width: 100%;
    padding: 8px 10px;
    margin-bottom: 4px;
    font-size: 13px;
    color: var(--el-text-color-regular);
    text-align: left;
    background: transparent;
    border: 0;
    border-radius: 6px;

    &:hover,
    &.is-active {
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }
  }

  .asset-library__tree-node {
    gap: 6px;
    width: 100%;
    min-width: 0;
  }

  .asset-library__create-node {
    display: flex;
    gap: 6px;
    align-items: center;
    margin-top: 4px;
    color: var(--el-color-primary);
    cursor: pointer;
  }

  .asset-library__drag-handle {
    flex: none;
    color: var(--el-text-color-placeholder);
    cursor: grab;
  }

  .asset-library__folder-tree :deep(.el-tree-node__content:active) .asset-library__drag-handle {
    cursor: grabbing;
  }

  .asset-library__tree-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .asset-library__toolbar {
    gap: 8px;
  }

  .asset-library__grid-wrap {
    display: grid;
    gap: 16px;
  }

  .asset-detail {
    display: grid;
    gap: 10px;
  }

  .asset-detail__usage {
    display: grid;
    gap: 8px;
  }

  .asset-library__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
    gap: 12px;
  }

  .asset-card {
    position: relative;
    display: grid;
    gap: 10px;
    padding: 12px;
    text-align: left;
    cursor: pointer;
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    transition:
      border-color 0.2s ease,
      box-shadow 0.2s ease;

    &:hover {
      border-color: var(--el-color-primary);
      box-shadow: 0 0 0 1px rgb(64 158 255 / 15%);
    }

    &.is-selected {
      border-color: var(--el-color-primary);
      box-shadow: 0 0 0 2px var(--el-color-primary-light-7);
    }
  }

  .asset-card__select {
    position: absolute;
    top: 8px;
    left: 8px;
    z-index: 2;
    padding: 4px;
    margin: 0;
    background: rgb(255 255 255 / 90%);
    border-radius: 6px;
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

  .asset-card__empty,
  .asset-detail__empty {
    display: flex;
    flex-direction: column;
    gap: 6px;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    color: var(--el-text-color-secondary);
  }

  .asset-card__body {
    display: grid;
    gap: 8px;
    min-width: 0;
  }

  .asset-card__name {
    overflow: hidden;
    font-size: 13px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .asset-card__meta {
    flex-wrap: wrap;
    gap: 6px 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .asset-library__pagination {
    display: flex;
    justify-content: flex-end;
  }

  .asset-library__upload-summary {
    margin-top: 10px;
  }

  .asset-detail__hero {
    width: 100%;
    min-height: 180px;
    max-height: 260px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 10px;

    :deep(img),
    video {
      width: 100%;
      height: 100%;
      max-height: 260px;
      object-fit: contain;
    }
  }

  .asset-detail__descriptions :deep(.el-descriptions__cell) {
    padding: 6px 10px !important;
  }

  .asset-detail__filename {
    display: flex;
    gap: 6px;
    align-items: center;
    min-width: 0;

    > span {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    :deep(.el-input) {
      flex: 1;
      min-width: 160px;
    }
  }

  .asset-detail__url {
    display: flex;
    gap: 8px;
    align-items: center;
    min-width: 0;

    span {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .asset-detail__usage-title {
    font-weight: 600;
  }

  .asset-detail__history {
    margin-top: 4px;
  }

  :deep(.asset-library__file-cell) {
    display: grid;
    gap: 4px;

    .title {
      font-weight: 500;
    }

    .subtitle {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  @media (width <= 900px) {
    .asset-library__layout {
      grid-template-columns: 1fr;
    }

    .asset-library__sidebar {
      max-height: 300px;
    }
  }
</style>
