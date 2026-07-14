<template>
  <div class="asset-library art-full-height">
    <div class="asset-library__layout">
      <aside class="asset-library__sidebar">
        <div class="asset-library__sidebar-header">
          <span>素材分组</span>
          <ElButton v-auth="'asset:folder'" text type="primary" @click="openFolderDialog('create')">
            新建
          </ElButton>
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
          :data="folders"
          node-key="id"
          default-expand-all
          highlight-current
          :expand-on-click-node="false"
          @node-click="handleFolderSelect"
        >
          <template #default="{ data }">
            <div class="asset-library__tree-node">
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
              <button
                v-for="asset in assets"
                :key="asset.id"
                type="button"
                class="asset-card"
                @click="openDetail(asset.id)"
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
                    <ElTag size="small">{{ formatMediaKind(asset.mediaKind) }}</ElTag>
                    <span>{{ folderName(asset.folderId) }}</span>
                  </div>
                  <div class="asset-card__meta">
                    <span>{{ formatFileSize(asset.sizeBytes) }}</span>
                    <span>引用 {{ asset.usageCount || 0 }} 次</span>
                  </div>
                </div>
              </button>
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
            :loading="loading"
            :data="assets"
            :columns="columns"
            :pagination="pagination"
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
            :limit="1"
            :file-list="uploadFileList"
            @change="handleUploadChange"
            @remove="handleUploadRemove"
          >
            <ElButton>选择图片或视频</ElButton>
          </ElUpload>
        </ElFormItem>
        <ElAlert
          title="图片和视频类型由服务端自动识别；视频仅支持 MP4、WebM，最大 50 MB。"
          type="info"
          :closable="false"
        />
      </ElForm>

      <template #footer>
        <ElButton @click="uploadDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="uploading" @click="submitUpload">上传</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="moveDialogVisible" title="移动分组" width="440px" align-center>
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
          />
        </ElFormItem>
        <ElFormItem label="分组名称" required>
          <ElInput v-model="folderForm.name" maxlength="64" show-word-limit />
        </ElFormItem>
        <ElFormItem label="排序">
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

    <ElDrawer v-model="detailDrawerVisible" title="素材详情" size="560px" destroy-on-close>
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

        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="素材 ID">{{ detailAsset.id }}</ElDescriptionsItem>
          <ElDescriptionsItem label="文件名">{{ detailAsset.originalFilename }}</ElDescriptionsItem>
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
          <ElDescriptionsItem label="引用次数">{{
            detailAsset.usageCount || 0
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="存储提供商">{{ detailAsset.provider }}</ElDescriptionsItem>
          <ElDescriptionsItem label="URL">{{
            resolveAssetUrl(detailAsset) || '-'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="创建时间">
            {{ formatDateTime(detailAsset.createdAt) }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="asset-detail__usage">
          <div class="asset-detail__usage-title">引用位置</div>
          <ElTable :data="detailAsset.usages || []" border>
            <ElTableColumn prop="usageType" label="引用角色" min-width="150" />
            <ElTableColumn prop="ownerType" label="对象类型" min-width="130" />
            <ElTableColumn prop="ownerLabel" label="归属对象" min-width="180" />
            <ElTableColumn prop="status" label="状态" width="90">
              <template #default="{ row }">
                <ElTag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '当前' : '历史' }}
                </ElTag>
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
          <ElEmpty
            v-if="!(detailAsset.usages || []).length"
            description="暂无引用"
            :image-size="64"
          />
        </div>
      </div>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, reactive, ref } from 'vue'
  import { MoreFilled, Picture, VideoCamera } from '@element-plus/icons-vue'
  import { ElImage, ElMessage, ElMessageBox, ElTag, type UploadUserFile } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import type { ColumnOption } from '@/types'
  import {
    createAssetFolder,
    deleteAsset,
    deleteAssetFolder,
    fetchAssetDetail,
    fetchAssetFolders,
    fetchAssets,
    moveAsset,
    updateAssetFolder,
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

  const loading = ref(false)
  const uploading = ref(false)
  const moving = ref(false)
  const folderSaving = ref(false)
  const assets = ref<Api.Storage.AssetItem[]>([])
  const folders = ref<Api.Storage.AssetFolder[]>([])
  const selectedFolderId = ref<number | undefined>()
  const viewMode = ref<'grid' | 'list'>('grid')
  const uploadDialogVisible = ref(false)
  const moveDialogVisible = ref(false)
  const folderDialogVisible = ref(false)
  const detailDrawerVisible = ref(false)
  const detailLoading = ref(false)
  const detailAsset = ref<Api.Storage.AssetItem | null>(null)
  const movingAssetId = ref<number | null>(null)
  const moveTargetFolderId = ref<number>(0)
  const uploadFolderId = ref<number>(0)
  const uploadFileList = ref<UploadUserFile[]>([])
  const uploadRawFile = ref<File | null>(null)
  const folderDialogMode = ref<FolderDialogMode>('create')
  const editingFolderId = ref<number | null>(null)

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

  const { columns, columnChecks } = useTableColumns<Api.Storage.AssetItem>(() => {
    const tableColumns: ColumnOption<Api.Storage.AssetItem>[] = [
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
        label: '引用次数',
        width: 100,
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

  const openDetail = async (assetId: number) => {
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
      movingAssetId.value = asset.id
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
      if (detailAsset.value?.id === asset.id) detailDrawerVisible.value = false
      await loadAssets()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  const handleSearch = () => {
    pagination.current = 1
    loadAssets()
  }

  const handleReset = () => {
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
    selectedFolderId.value = folderId
    pagination.current = 1
    loadAssets()
  }
  const handleFolderSelect = (folder: Api.Storage.AssetFolder) => selectFolder(folder.id)
  const handleCurrentChange = (current: number) => {
    pagination.current = current
    loadAssets()
  }
  const handleSizeChange = (size: number) => {
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
  const handleUploadChange = (file: UploadUserFile) => {
    uploadFileList.value = file.raw ? [file] : []
    uploadRawFile.value = file.raw || null
  }
  const handleUploadRemove = () => resetUpload()
  const resetUpload = () => {
    uploadFileList.value = []
    uploadRawFile.value = null
  }
  const submitUpload = async () => {
    if (!uploadRawFile.value) {
      ElMessage.warning('请先选择文件')
      return
    }
    uploading.value = true
    try {
      await uploadAsset({ file: uploadRawFile.value, folderId: uploadFolderId.value })
      uploadDialogVisible.value = false
      resetUpload()
      await Promise.all([loadAssets(), loadFolders()])
    } finally {
      uploading.value = false
    }
  }

  const submitMove = async () => {
    if (!movingAssetId.value) return
    moving.value = true
    try {
      await moveAsset(movingAssetId.value, { folderId: moveTargetFolderId.value })
      moveDialogVisible.value = false
      await Promise.all([loadAssets(), loadFolders()])
    } finally {
      moving.value = false
    }
  }

  const resetFolderForm = (parentId = 0) => {
    Object.assign(folderForm, { parentId, name: '', sortOrder: 0, status: 'ENABLED' })
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

  .asset-library__grid-wrap,
  .asset-detail,
  .asset-detail__usage {
    display: grid;
    gap: 16px;
  }

  .asset-library__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
    gap: 12px;
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

    &:hover {
      border-color: var(--el-color-primary);
      box-shadow: 0 0 0 1px rgb(64 158 255 / 15%);
    }
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

  .asset-detail__hero {
    width: 100%;
    min-height: 240px;
    max-height: 360px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 10px;

    :deep(img),
    video {
      width: 100%;
      height: 100%;
      max-height: 360px;
      object-fit: contain;
    }
  }

  .asset-detail__usage-title {
    font-weight: 600;
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
