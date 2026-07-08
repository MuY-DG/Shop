<template>
  <div class="storage-files art-full-height">
    <div class="storage-files__layout">
      <aside class="storage-files__sidebar">
        <div class="storage-files__sidebar-header">
          <span>素材分类</span>
          <ElButton text @click="resetCategory">全部</ElButton>
        </div>
        <ElTree
          :data="categoryTree"
          node-key="id"
          default-expand-all
          highlight-current
          :expand-on-click-node="false"
          @node-click="handleCategorySelect"
        >
          <template #default="{ data }">
            <span class="storage-files__tree-node">{{ data.name }}</span>
          </template>
        </ElTree>
      </aside>

      <section class="storage-files__main">
        <ArtSearchBar
          v-model="searchForm"
          :items="searchItems"
          :show-expand="false"
          @search="handleSearch"
          @reset="handleReset"
        />

        <ElCard class="art-table-card" :style="{ marginTop: '12px' }">
          <ArtTableHeader :loading="loading" v-model:columns="columnChecks" @refresh="loadFiles">
            <template #left>
              <ElButton type="primary" v-auth="'file:upload'" @click="uploadDialogVisible = true">
                上传文件
              </ElButton>
            </template>
            <template #right>
              <div class="storage-files__toolbar">
                <ElButton :type="viewMode === 'grid' ? 'primary' : 'default'" @click="viewMode = 'grid'">
                  网格
                </ElButton>
                <ElButton :type="viewMode === 'list' ? 'primary' : 'default'" @click="viewMode = 'list'">
                  列表
                </ElButton>
              </div>
            </template>
          </ArtTableHeader>

          <div v-if="viewMode === 'grid'" v-loading="loading" class="storage-files__grid-wrap">
            <div class="storage-files__grid">
              <button
                v-for="file in files"
                :key="file.id"
                type="button"
                class="file-card"
                @click="openDetail(file.id)"
              >
                <div class="file-card__preview">
                  <ElImage
                    v-if="file.visibility !== 'PRIVATE' && resolveFileUrl(file)"
                    :src="resolveFileUrl(file)"
                    fit="cover"
                  />
                  <div v-else class="file-card__private">
                    <ElIcon size="20"><Lock /></ElIcon>
                    <span>{{ file.visibility === 'PRIVATE' ? '私有文件' : '无预览' }}</span>
                  </div>
                </div>
                <div class="file-card__body">
                  <div class="file-card__name">{{ file.originalFilename }}</div>
                  <div class="file-card__meta">
                    <span>ID {{ file.id }}</span>
                    <span>{{ formatPurpose(file.purpose) }}</span>
                  </div>
                  <div class="file-card__meta">
                    <span>{{ categoryNameMap[file.assetCategoryId || 0] || '未分类' }}</span>
                    <span>{{ formatFileSize(file.sizeBytes) }}</span>
                  </div>
                </div>
              </button>
            </div>

            <ElEmpty v-if="!loading && !files.length" description="暂无文件" />

            <div class="storage-files__pagination">
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
            :data="files"
            :columns="columns"
            :pagination="pagination"
            @pagination:current-change="handleCurrentChange"
            @pagination:size-change="handleSizeChange"
          />
        </ElCard>
      </section>
    </div>

    <ElDialog v-model="uploadDialogVisible" title="上传文件" width="560px" align-center>
      <ElForm label-width="92px">
        <ElFormItem label="素材用途">
          <ElSelect v-model="uploadForm.purpose" placeholder="请选择用途">
            <ElOption v-for="item in purposeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="素材分类">
          <ElTreeSelect
            v-model="uploadForm.assetCategoryId"
            :data="categoryOptions"
            node-key="value"
            check-strictly
            clearable
            default-expand-all
            :render-after-expand="false"
            placeholder="请选择分类"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="选择文件">
          <ElUpload
            :auto-upload="false"
            :limit="1"
            :file-list="uploadFileList"
            @change="handleUploadChange"
            @remove="handleUploadRemove"
          >
            <ElButton>选择文件</ElButton>
          </ElUpload>
        </ElFormItem>
      </ElForm>

      <template #footer>
        <div class="dialog-footer">
          <ElButton @click="uploadDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="uploading" @click="submitUpload">上传</ElButton>
        </div>
      </template>
    </ElDialog>

    <ElDialog v-model="moveDialogVisible" title="移动分类" width="420px" align-center>
      <ElTreeSelect
        v-model="moveTargetCategoryId"
        :data="categoryOptions"
        node-key="value"
        check-strictly
        clearable
        default-expand-all
        :render-after-expand="false"
        placeholder="请选择目标分类"
        style="width: 100%"
      />
      <template #footer>
        <div class="dialog-footer">
          <ElButton @click="moveDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="moving" @click="submitMove">保存</ElButton>
        </div>
      </template>
    </ElDialog>

    <ElDrawer v-model="detailDrawerVisible" title="文件详情" size="520px" destroy-on-close>
      <div v-loading="detailLoading" class="storage-detail" v-if="detailFile">
        <div class="storage-detail__hero">
          <ElImage
            v-if="detailFile.visibility !== 'PRIVATE' && resolveFileUrl(detailFile)"
            :src="resolveFileUrl(detailFile)"
            fit="cover"
            :preview-src-list="[resolveFileUrl(detailFile)]"
            preview-teleported
          />
          <div v-else class="storage-detail__private">
            <ElIcon size="24"><Lock /></ElIcon>
            <span>PRIVATE 文件不展示预览</span>
          </div>
        </div>

        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="文件 ID">{{ detailFile.id }}</ElDescriptionsItem>
          <ElDescriptionsItem label="文件名">{{ detailFile.originalFilename }}</ElDescriptionsItem>
          <ElDescriptionsItem label="用途">{{ formatPurpose(detailFile.purpose) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="分类">
            {{ categoryNameMap[detailFile.assetCategoryId || 0] || '未分类' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="可见性">{{ detailFile.visibility }}</ElDescriptionsItem>
          <ElDescriptionsItem label="状态">{{ detailFile.status }}</ElDescriptionsItem>
          <ElDescriptionsItem label="大小">{{ formatFileSize(detailFile.sizeBytes) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="尺寸">
            {{ detailFile.width && detailFile.height ? `${detailFile.width} × ${detailFile.height}` : '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="URL">
            {{ detailFile.visibility === 'PRIVATE' ? '-' : resolveFileUrl(detailFile) || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="创建时间">{{ formatDateTime(detailFile.createdAt) }}</ElDescriptionsItem>
        </ElDescriptions>

        <div class="storage-detail__usage">
          <div class="storage-detail__usage-title">引用记录</div>
          <ElTable :data="detailFile.usages || []" border>
            <ElTableColumn prop="usageType" label="用途" min-width="140" />
            <ElTableColumn prop="ownerType" label="归属类型" min-width="120" />
            <ElTableColumn prop="ownerLabel" label="归属对象" min-width="160" />
            <ElTableColumn prop="status" label="状态" width="100">
              <template #default="{ row }">
                <ElTag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '当前' : '历史' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="protected" label="受保护" width="90">
              <template #default="{ row }">
                <ElTag size="small" :type="row.protected ? 'warning' : 'info'">
                  {{ row.protected ? '是' : '否' }}
                </ElTag>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>
      </div>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, reactive, ref } from 'vue'
  import { Lock } from '@element-plus/icons-vue'
  import { ElImage, ElMessage, ElMessageBox, ElTag, type UploadUserFile } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import type { ColumnOption } from '@/types'
  import { deleteStorageFile, fetchStorageCategories, fetchStorageFileDetail, fetchStorageFiles, moveStorageFile, uploadStorageFile } from '@/api/storage'

  defineOptions({ name: 'StorageFiles' })

  interface TreeOption {
    value: number
    label: string
    children?: TreeOption[]
  }

  const purposeLabelMap: Record<Api.Storage.Purpose, string> = {
    PRODUCT_IMAGE: '商品主图',
    PRODUCT_SKU_IMAGE: 'SKU 图片',
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

  const loading = ref(false)
  const uploading = ref(false)
  const moving = ref(false)
  const files = ref<Api.Storage.FileItem[]>([])
  const categoryTree = ref<Api.Storage.AssetCategory[]>([])
  const selectedCategoryId = ref<number | undefined>()
  const viewMode = ref<'grid' | 'list'>('grid')
  const uploadDialogVisible = ref(false)
  const moveDialogVisible = ref(false)
  const detailDrawerVisible = ref(false)
  const detailLoading = ref(false)
  const detailFile = ref<Api.Storage.FileItem | null>(null)
  const movingFileId = ref<number | null>(null)
  const moveTargetCategoryId = ref<number | undefined>()
  const uploadFileList = ref<UploadUserFile[]>([])
  const uploadRawFile = ref<File | null>(null)

  const pagination = reactive<Api.Common.PaginationParams>({
    current: 1,
    size: 20,
    total: 0
  })

  const searchForm = ref<{
    purpose?: Api.Storage.Purpose
    visibility?: Api.Storage.Visibility
    status?: Api.Storage.FileStatus
  }>({
    purpose: undefined,
    visibility: undefined,
    status: 'ACTIVE'
  })

  const uploadForm = reactive<{
    purpose: Api.Storage.Purpose
    assetCategoryId?: number
  }>({
    purpose: 'PRODUCT_IMAGE',
    assetCategoryId: undefined
  })

  const categoryOptions = computed<TreeOption[]>(() => {
    const walk = (items: Api.Storage.AssetCategory[]): TreeOption[] =>
      items.map((item) => ({
        value: item.id,
        label: item.name,
        children: walk(item.children || [])
      }))

    return walk(categoryTree.value)
  })

  const categoryNameMap = computed<Record<number, string>>(() => {
    const map: Record<number, string> = {}
    const walk = (items: Api.Storage.AssetCategory[]) => {
      items.forEach((item) => {
        map[item.id] = item.name
        walk(item.children || [])
      })
    }
    walk(categoryTree.value)
    return map
  })

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '用途',
      key: 'purpose',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择用途',
        options: purposeOptions
      }
    },
    {
      label: '可见性',
      key: 'visibility',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择可见性',
        options: [
          { label: 'PUBLIC', value: 'PUBLIC' },
          { label: 'PRIVATE', value: 'PRIVATE' }
        ]
      }
    },
    {
      label: '状态',
      key: 'status',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择状态',
        options: [
          { label: 'ACTIVE', value: 'ACTIVE' },
          { label: 'DELETED', value: 'DELETED' }
        ]
      }
    }
  ])

  const formatPurpose = (purpose: Api.Storage.Purpose) => purposeLabelMap[purpose] || purpose

  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')

  const formatFileSize = (sizeBytes?: number) => {
    if (!sizeBytes) return '0 B'
    if (sizeBytes < 1024) return `${sizeBytes} B`
    if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
    return `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
  }

  const resolveFileUrl = (file?: Api.Storage.FileItem | null) => file?.publicUrl || file?.url || ''

  const { columns, columnChecks } = useTableColumns<Api.Storage.FileItem>(() => {
    const tableColumns: ColumnOption<Api.Storage.FileItem>[] = [
      {
        prop: 'preview',
        label: '预览',
        width: 88,
        formatter: (row) =>
          row.visibility !== 'PRIVATE' && resolveFileUrl(row)
            ? h(ElImage, {
                src: resolveFileUrl(row),
                fit: 'cover',
                previewSrcList: [resolveFileUrl(row)],
                previewTeleported: true,
                style: {
                  width: '48px',
                  height: '48px',
                  borderRadius: '6px',
                  backgroundColor: 'var(--el-fill-color-light)'
                }
              })
            : h(
                'div',
                {
                  class: 'storage-files__private-cell'
                },
                'PRIVATE'
              )
      },
      {
        prop: 'originalFilename',
        label: '文件信息',
        minWidth: 260,
        formatter: (row) =>
          h('div', { class: 'storage-files__file-cell' }, [
            h('div', { class: 'title' }, row.originalFilename),
            h('div', { class: 'subtitle' }, `ID ${row.id} / ${formatPurpose(row.purpose)}`)
          ])
      },
      {
        prop: 'assetCategoryId',
        label: '分类',
        minWidth: 120,
        formatter: (row) => categoryNameMap.value[row.assetCategoryId || 0] || '未分类'
      },
      {
        prop: 'visibility',
        label: '可见性',
        width: 100,
        formatter: (row) =>
          h(ElTag, { type: row.visibility === 'PRIVATE' ? 'warning' : 'success' }, () => row.visibility)
      },
      {
        prop: 'sizeBytes',
        label: '大小',
        width: 110,
        formatter: (row) => formatFileSize(row.sizeBytes)
      },
      {
        prop: 'status',
        label: '状态',
        width: 100,
        formatter: (row) => h(ElTag, { type: row.status === 'ACTIVE' ? 'success' : 'info' }, () => row.status)
      },
      {
        prop: 'createdAt',
        label: '创建时间',
        width: 180,
        formatter: (row) => formatDateTime(row.createdAt)
      },
      {
        prop: 'operation',
        label: '操作',
        width: 120,
        fixed: 'right',
        formatter: (row) =>
          h(ArtButtonMore, {
            list: buildActions(row),
            onClick: (item: ButtonMoreItem) => handleAction(item, row)
          })
      }
    ]
    return tableColumns
  })

  const loadCategories = async () => {
    categoryTree.value = await fetchStorageCategories()
  }

  const loadFiles = async () => {
    loading.value = true
    try {
      const response = await fetchStorageFiles({
        current: pagination.current,
        size: pagination.size,
        purpose: searchForm.value.purpose,
        assetCategoryId: selectedCategoryId.value,
        visibility: searchForm.value.visibility,
        status: searchForm.value.status
      })
      files.value = response.records
      pagination.current = response.current
      pagination.size = response.size
      pagination.total = response.total
    } finally {
      loading.value = false
    }
  }

  const openDetail = async (fileId: number) => {
    detailDrawerVisible.value = true
    detailLoading.value = true
    try {
      detailFile.value = await fetchStorageFileDetail(fileId)
    } finally {
      detailLoading.value = false
    }
  }

  const buildActions = (row: Api.Storage.FileItem): ButtonMoreItem[] => [
    {
      key: 'detail',
      label: '详情',
      icon: 'ri:eye-line',
      auth: 'file:read'
    },
    {
      key: 'move',
      label: '移动分类',
      icon: 'ri:folder-transfer-line',
      auth: 'file:category'
    },
    {
      key: 'delete',
      label: '删除',
      icon: 'ri:delete-bin-5-line',
      color: '#f56c6c',
      auth: 'file:delete'
    }
  ]

  const handleAction = (item: ButtonMoreItem, row: Api.Storage.FileItem) => {
    switch (item.key) {
      case 'detail':
        openDetail(row.id)
        break
      case 'move':
        movingFileId.value = row.id
        moveTargetCategoryId.value = row.assetCategoryId ?? undefined
        moveDialogVisible.value = true
        break
      case 'delete':
        confirmDelete(row)
        break
    }
  }

  const confirmDelete = async (row: Api.Storage.FileItem) => {
    await ElMessageBox.confirm(`确定删除文件“${row.originalFilename}”吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })

    try {
      await deleteStorageFile(row.id)
      await loadFiles()
      if (detailFile.value?.id === row.id) {
        detailDrawerVisible.value = false
      }
    } catch (error: any) {
      ElMessage.error(error?.message || '删除失败，文件可能仍被业务引用')
    }
  }

  const handleSearch = () => {
    pagination.current = 1
    loadFiles()
  }

  const handleReset = () => {
    searchForm.value = {
      purpose: undefined,
      visibility: undefined,
      status: 'ACTIVE'
    }
    selectedCategoryId.value = undefined
    pagination.current = 1
    loadFiles()
  }

  const handleCategorySelect = (category: Api.Storage.AssetCategory) => {
    selectedCategoryId.value = category.id
    pagination.current = 1
    loadFiles()
  }

  const resetCategory = () => {
    selectedCategoryId.value = undefined
    pagination.current = 1
    loadFiles()
  }

  const handleCurrentChange = (current: number) => {
    pagination.current = current
    loadFiles()
  }

  const handleSizeChange = (size: number) => {
    pagination.size = size
    pagination.current = 1
    loadFiles()
  }

  const handleUploadChange = (file: UploadUserFile) => {
    uploadFileList.value = file.raw ? [file] : []
    uploadRawFile.value = file.raw || null
  }

  const handleUploadRemove = () => {
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
      await uploadStorageFile({
        purpose: uploadForm.purpose,
        assetCategoryId: uploadForm.assetCategoryId,
        file: uploadRawFile.value
      })
      uploadDialogVisible.value = false
      uploadFileList.value = []
      uploadRawFile.value = null
      await Promise.all([loadFiles(), loadCategories()])
    } finally {
      uploading.value = false
    }
  }

  const submitMove = async () => {
    if (!movingFileId.value || !moveTargetCategoryId.value) {
      ElMessage.warning('请选择目标分类')
      return
    }

    moving.value = true
    try {
      await moveStorageFile(movingFileId.value, {
        assetCategoryId: moveTargetCategoryId.value
      })
      moveDialogVisible.value = false
      await Promise.all([loadFiles(), loadCategories()])
    } finally {
      moving.value = false
    }
  }

  onMounted(async () => {
    await Promise.all([loadCategories(), loadFiles()])
  })
</script>

<style scoped lang="scss">
  .storage-files__layout {
    display: grid;
    grid-template-columns: 220px minmax(0, 1fr);
    gap: 16px;
    height: 100%;
  }

  .storage-files__sidebar,
  .storage-files__main {
    min-height: 0;
  }

  .storage-files__sidebar {
    padding: 16px;
    border: 1px solid var(--el-border-color-light);
    border-radius: 8px;
    background: var(--el-fill-color-blank);
  }

  .storage-files__sidebar-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
    font-weight: 600;
  }

  .storage-files__tree-node {
    font-size: 13px;
  }

  .storage-files__toolbar {
    display: flex;
    gap: 8px;
  }

  .storage-files__grid-wrap {
    display: grid;
    gap: 16px;
  }

  .storage-files__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 12px;
  }

  .file-card {
    display: grid;
    gap: 10px;
    padding: 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-fill-color-blank);
    text-align: left;
    transition: border-color 0.2s ease, box-shadow 0.2s ease;
  }

  .file-card:hover {
    border-color: var(--el-color-primary);
    box-shadow: 0 0 0 1px rgb(64 158 255 / 15%);
  }

  .file-card__preview {
    width: 100%;
    aspect-ratio: 1;
    border-radius: 8px;
    overflow: hidden;
    background: var(--el-fill-color-light);

    :deep(img) {
      width: 100%;
      height: 100%;
    }
  }

  .file-card__private,
  .storage-detail__private {
    width: 100%;
    min-height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    text-align: center;
    background: var(--el-fill-color-light);
  }

  .file-card__body,
  .storage-detail {
    display: grid;
    gap: 10px;
  }

  .file-card__name {
    font-size: 13px;
    font-weight: 600;
    color: var(--el-text-color-primary);
    word-break: break-word;
  }

  .file-card__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .storage-files__pagination {
    display: flex;
    justify-content: flex-end;
  }

  .storage-files__private-cell {
    width: 48px;
    height: 48px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 6px;
    background: var(--el-fill-color-light);
    color: var(--el-text-color-secondary);
    font-size: 11px;
  }

  .storage-files__file-cell {
    display: grid;
    gap: 6px;

    .title {
      font-weight: 600;
      word-break: break-word;
    }

    .subtitle {
      color: var(--el-text-color-secondary);
      font-size: 12px;
    }
  }

  .dialog-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
  }

  .storage-detail__hero {
    width: 100%;
    aspect-ratio: 16 / 9;
    overflow: hidden;
    border-radius: 8px;
    background: var(--el-fill-color-light);

    :deep(img) {
      width: 100%;
      height: 100%;
    }
  }

  .storage-detail__usage {
    display: grid;
    gap: 12px;
  }

  .storage-detail__usage-title {
    font-size: 14px;
    font-weight: 600;
  }

  @media (max-width: 960px) {
    .storage-files__layout {
      grid-template-columns: 1fr;
    }
  }
</style>
