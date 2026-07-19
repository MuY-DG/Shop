<template>
  <SpuEditor
    v-if="editorMode"
    :key="editorSpuId ?? 'create'"
    :spu-id="editorSpuId"
    :categories="categories"
    @success="handleEditorSuccess"
    @success-and-close="handleEditorSuccessAndClose"
    @cancel="returnToList"
  />

  <div v-else class="product-spu-list art-full-height">
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      :show-expand="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="status-filter-card" shadow="never">
      <div class="status-filter-row">
        <span class="status-filter-label">商品状态</span>
        <ElSegmented
          v-model="selectedStatus"
          :options="statusOptions"
          @change="handleStatusChange"
        />
      </div>
    </ElCard>

    <ElCard class="art-table-card product-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton v-if="canCreateProduct" type="primary" @click="openEditor()" v-ripple>
            新增商品
          </ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="displayedColumns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <template #sales="{ row }">
          <ElTooltip
            :content="`实际销量 ${row.actualSales ?? 0} + 虚拟销量 ${row.virtualSales ?? 0}`"
            placement="top"
          >
            <span class="sales-value">{{ row.displaySales ?? 0 }}</span>
          </ElTooltip>
        </template>

        <template #status="{ row }">
          <div v-if="isRecycleBin" class="status-cell">
            <ElTag type="danger" size="small">回收站</ElTag>
          </div>
          <div v-else class="status-cell">
            <ElSwitch
              v-auth="'product:spu:publish'"
              :model-value="row.status === 'ON_SALE'"
              :loading="statusUpdatingIds.has(row.id)"
              :disabled="statusUpdatingIds.has(row.id)"
              :before-change="() => requestProductStatus(row, row.status !== 'ON_SALE')"
            />
            <ElTag :type="getStatusConfig(row).type" size="small">
              {{ getStatusConfig(row).text }}
            </ElTag>
          </div>
        </template>

        <template #operation="{ row }">
          <div v-if="isRecycleBin" class="table-actions recycle-actions">
            <ElButton
              v-auth="'product:spu:restore'"
              type="success"
              :loading="restoringIds.has(row.id)"
              :disabled="isRecycleActionPending(row.id)"
              link
              @click="restoreSpu(row)"
            >
              恢复
            </ElButton>
            <ElButton
              v-auth="'product:spu:purge'"
              type="danger"
              :loading="purgingIds.has(row.id)"
              :disabled="isRecycleActionPending(row.id)"
              link
              @click="purgeSpu(row)"
            >
              永久删除
            </ElButton>
          </div>
          <div v-else class="table-actions">
            <ElButton v-auth="'product:spu:update'" type="primary" link @click="openEditor(row.id)">
              编辑
            </ElButton>
            <ElButton
              v-auth="'product:sku:stock'"
              type="primary"
              link
              @click="openStockDialog(row.id)"
            >
              调库存
            </ElButton>
            <ElButton
              v-auth="'product:spu:delete'"
              type="danger"
              :loading="deletingIds.has(row.id)"
              link
              @click="deleteSpu(row)"
            >
              删除
            </ElButton>
          </div>
        </template>
      </ArtTable>
    </ElCard>

    <ElDialog v-model="stockDialogVisible" title="调整库存" width="860px" align-center>
      <div v-loading="stockDialogLoading">
        <ElTable :data="stockSkus" border>
          <ElTableColumn prop="skuCode" label="SKU 编码" min-width="120" />
          <ElTableColumn prop="specText" label="规格" min-width="140" />
          <ElTableColumn prop="stockAvailable" label="当前库存" width="100" />
          <ElTableColumn label="调整数量" width="140">
            <template #default="{ row }">
              <ElInputNumber
                v-model="stockAdjustmentMap[row.id!].quantityDelta"
                :step="1"
                :precision="0"
                controls-position="right"
                style="width: 100%"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn label="原因" min-width="220">
            <template #default="{ row }">
              <ElInput
                v-model="stockAdjustmentMap[row.id!].reason"
                maxlength="100"
                placeholder="请输入调整原因"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <ElButton
                type="primary"
                text
                :loading="stockSubmittingSkuId === row.id"
                @click="submitStockAdjustment(row)"
              >
                提交
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, reactive, ref } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { useAuth } from '@/hooks/core/useAuth'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import type { ColumnOption } from '@/types/component'
  import { useTable } from '@/hooks/core/useTable'
  import {
    adjustSkuStock,
    deleteProductSpu,
    fetchProductCategories,
    fetchProductSpuDetail,
    fetchProductSpus,
    publishProductSpu,
    purgeProductSpu,
    restoreProductSpu,
    unpublishProductSpu
  } from '@/api/product'
  import SpuEditor from './modules/spu-editor.vue'
  import { ElImage, ElMessage, ElMessageBox } from 'element-plus'

  defineOptions({ name: 'ProductSpu' })

  type StatusFilter = 'ALL' | 'RECYCLED' | Api.Product.ProductStatus

  interface CategoryOption {
    label: string
    value: number
    children?: CategoryOption[]
  }

  const route = useRoute()
  const router = useRouter()
  const { hasAuth } = useAuth()
  const categories = ref<Api.Product.Category[]>([])
  const selectedStatus = ref<StatusFilter>('ALL')
  const statusUpdatingIds = ref(new Set<number>())
  const deletingIds = ref(new Set<number>())
  const restoringIds = ref(new Set<number>())
  const purgingIds = ref(new Set<number>())
  const isRecycleBin = computed(() => selectedStatus.value === 'RECYCLED')
  const canCreateProduct = computed(() => hasAuth('product:spu:create'))

  const editorSpuId = computed<number | null>(() => {
    if (route.query.mode !== 'edit') return null
    const value = Array.isArray(route.query.id) ? route.query.id[0] : route.query.id
    const parsed = Number(value)
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
  })

  const editorMode = computed(() => {
    if (route.query.mode === 'create') return true
    return route.query.mode === 'edit' && editorSpuId.value !== null
  })

  const stockDialogVisible = ref(false)
  const stockDialogLoading = ref(false)
  const stockSubmittingSkuId = ref<number | null>(null)
  const stockSpuId = ref<number | null>(null)
  const stockSkus = ref<Api.Product.Sku[]>([])
  const stockAdjustmentMap = reactive<Record<number, Api.Product.StockAdjustmentForm>>({})

  const searchForm = ref<{
    title?: string
    categoryId?: number
  }>({
    title: undefined,
    categoryId: undefined
  })

  const canAccessRecycleBin = computed(() => {
    const authList = (route.meta.authList as Array<{ authMark: string }> | undefined) || []
    const recyclePermissions = new Set([
      'product:spu:delete',
      'product:spu:restore',
      'product:spu:purge'
    ])
    return authList.some((permission) => recyclePermissions.has(permission.authMark))
  })

  const statusOptions = computed<Array<{ label: string; value: StatusFilter }>>(() => {
    const options: Array<{ label: string; value: StatusFilter }> = [
      { label: '全部', value: 'ALL' },
      { label: '草稿', value: 'DRAFT' },
      { label: '销售中', value: 'ON_SALE' },
      { label: '已下架', value: 'OFF_SALE' }
    ]
    if (canAccessRecycleBin.value) {
      options.push({ label: '回收站', value: 'RECYCLED' })
    }
    return options
  })

  const statusMap: Record<
    Api.Product.ProductStatus,
    { type: 'info' | 'warning' | 'success'; text: string }
  > = {
    DRAFT: { type: 'info', text: '草稿' },
    ON_SALE: { type: 'success', text: '销售中' },
    OFF_SALE: { type: 'warning', text: '已下架' }
  }

  const getStatusConfig = (row: Api.Product.SpuListItem) => statusMap[row.status]

  const categoryOptions = computed<CategoryOption[]>(() => {
    const toOptions = (nodes: Api.Product.Category[]): CategoryOption[] =>
      nodes.map((item) => ({
        label: item.name,
        value: item.id,
        children: item.children?.length ? toOptions(item.children) : undefined
      }))

    return toOptions(categories.value)
  })

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '商品名称',
      key: 'title',
      type: 'input',
      props: {
        clearable: true,
        placeholder: '请输入商品名称'
      }
    },
    {
      label: '商品分类',
      key: 'categoryId',
      type: 'cascader',
      props: {
        clearable: true,
        placeholder: '请选择分类',
        options: categoryOptions.value,
        showAllLevels: true,
        props: {
          emitPath: false,
          checkStrictly: true,
          expandTrigger: 'hover'
        }
      }
    }
  ])

  const isFiniteCent = (value: number | null | undefined): value is number =>
    typeof value === 'number' && Number.isFinite(value)

  const formatPriceRange = (row: Api.Product.SpuListItem) => {
    if (!isFiniteCent(row.minPriceCent) || !isFiniteCent(row.maxPriceCent)) {
      return '暂无价格'
    }
    const min = (row.minPriceCent / 100).toFixed(2)
    const max = (row.maxPriceCent / 100).toFixed(2)
    return row.minPriceCent === row.maxPriceCent ? `¥${min}` : `¥${min} - ¥${max}`
  }

  const formatDateTime = (value: string | null | undefined) => {
    if (!value) return '-'
    return value.replace('T', ' ')
  }

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData,
    refreshRemove
  } = useTable({
    core: {
      apiFn: fetchProductSpus,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        {
          prop: 'id',
          label: '商品 ID',
          width: 100
        },
        {
          prop: 'mainImage',
          label: '商品主图',
          width: 100,
          formatter: (row) =>
            h(ElImage, {
              src: row.mainImage,
              fit: 'cover',
              previewSrcList: row.mainImage ? [row.mainImage] : [],
              previewTeleported: true,
              style: {
                width: '52px',
                height: '52px',
                borderRadius: '6px',
                backgroundColor: 'var(--el-fill-color-light)'
              }
            })
        },
        {
          prop: 'title',
          label: '商品名称',
          minWidth: 180
        },
        {
          prop: 'skuCount',
          label: '规格',
          width: 110,
          formatter: (row) => `${row.skuCount ?? 0} 个规格`
        },
        {
          prop: 'categoryName',
          label: '分类',
          minWidth: 130
        },
        {
          prop: 'priceRange',
          label: '价格区间',
          width: 160,
          formatter: (row) => formatPriceRange(row)
        },
        {
          prop: 'sales',
          label: '销量',
          width: 100,
          useSlot: true
        },
        {
          prop: 'totalStock',
          label: '库存',
          width: 100
        },
        {
          prop: 'status',
          label: '状态',
          width: 160,
          useSlot: true
        },
        {
          prop: 'sortOrder',
          label: '排序',
          width: 90
        },
        {
          prop: 'createdAt',
          label: '添加时间',
          width: 180,
          formatter: (row) => formatDateTime(row.createdAt)
        },
        {
          prop: 'updatedAt',
          label: '修改时间',
          width: 180,
          formatter: (row) => formatDateTime(row.updatedAt)
        },
        {
          prop: 'deletedAt',
          label: '删除时间',
          width: 180,
          formatter: (row) => formatDateTime(row.deletedAt)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 210,
          fixed: 'right',
          useSlot: true
        }
      ]
    }
  })

  const displayedColumns = computed(() =>
    columns.value.filter(
      (column: ColumnOption<Api.Product.SpuListItem>) =>
        column.prop !== 'deletedAt' || isRecycleBin.value
    )
  )

  const currentStatusParams = () => ({
    ...searchForm.value,
    status:
      selectedStatus.value === 'ALL' || selectedStatus.value === 'RECYCLED'
        ? undefined
        : selectedStatus.value,
    recycled: isRecycleBin.value ? true : undefined
  })

  const loadCategories = async () => {
    categories.value = await fetchProductCategories()
  }

  const handleSearch = (params: Record<string, unknown>) => {
    replaceSearchParams({
      ...params,
      status:
        selectedStatus.value === 'ALL' || selectedStatus.value === 'RECYCLED'
          ? undefined
          : selectedStatus.value,
      recycled: isRecycleBin.value ? true : undefined
    })
    getData()
  }

  const handleReset = async () => {
    searchForm.value = {
      title: undefined,
      categoryId: undefined
    }
    selectedStatus.value = 'ALL'
    await resetSearchParams()
  }

  const handleStatusChange = () => {
    replaceSearchParams(currentStatusParams())
    getData()
  }

  const openEditor = (spuId?: number) => {
    router.push({
      path: '/product/spu',
      query: spuId ? { mode: 'edit', id: String(spuId) } : { mode: 'create' }
    })
  }

  const returnToList = () => router.replace({ path: '/product/spu' })

  const handleEditorSuccess = async (spuId?: number) => {
    if (spuId) {
      await router.replace({
        path: '/product/spu',
        query: { mode: 'edit', id: String(spuId) }
      })
    }
    await Promise.all([refreshData(), loadCategories()])
  }

  const handleEditorSuccessAndClose = async () => {
    await returnToList()
    await Promise.all([refreshData(), loadCategories()])
  }

  const isMessageBoxCancel = (error: unknown) => error === 'cancel' || error === 'close'

  const requestProductStatus = async (
    row: Api.Product.SpuListItem,
    publish: boolean
  ): Promise<boolean> => {
    if (statusUpdatingIds.value.has(row.id)) return false
    const actionText = publish ? '上架' : '下架'
    let apiSucceeded = false

    try {
      await ElMessageBox.confirm(`确定${actionText}商品“${row.title}”吗？`, `${actionText}确认`, {
        type: 'warning',
        confirmButtonText: `确定${actionText}`,
        cancelButtonText: '取消'
      })

      statusUpdatingIds.value.add(row.id)
      if (publish) {
        await publishProductSpu(row.id)
      } else {
        await unpublishProductSpu(row.id)
      }
      apiSucceeded = true
      row.status = publish ? 'ON_SALE' : 'OFF_SALE'
      await refreshData()
      return true
    } catch (error) {
      if (apiSucceeded) return true
      if (!isMessageBoxCancel(error)) {
        ElMessage.error(`${actionText}失败，商品状态未变更`)
      }
      return false
    } finally {
      statusUpdatingIds.value.delete(row.id)
    }
  }

  const deleteSpu = async (row: Api.Product.SpuListItem) => {
    if (deletingIds.value.has(row.id)) return
    deletingIds.value.add(row.id)
    try {
      await ElMessageBox.confirm(
        `商品“${row.title}”将移入回收站并从前台隐藏，之后仍可恢复。确定继续吗？`,
        '移入回收站',
        {
          type: 'warning',
          confirmButtonText: '移入回收站',
          cancelButtonText: '取消'
        }
      )
      await deleteProductSpu(row.id)
      await refreshRemove()
    } catch {
      // 取消确认时静默返回；接口错误由统一 HTTP 层展示，避免重复提示。
    } finally {
      deletingIds.value.delete(row.id)
    }
  }

  const isRecycleActionPending = (spuId: number) =>
    restoringIds.value.has(spuId) || purgingIds.value.has(spuId)

  const restoreSpu = async (row: Api.Product.SpuListItem) => {
    if (isRecycleActionPending(row.id)) return
    restoringIds.value.add(row.id)
    try {
      await ElMessageBox.confirm(
        `确定恢复商品“${row.title}”吗？恢复后商品保持下架，不会自动上架。`,
        '恢复商品',
        {
          type: 'warning',
          confirmButtonText: '确定恢复',
          cancelButtonText: '取消'
        }
      )
      await restoreProductSpu(row.id)
      await refreshRemove()
    } catch {
      // 取消确认时静默返回；接口错误由统一 HTTP 层展示，避免重复提示。
    } finally {
      restoringIds.value.delete(row.id)
    }
  }

  const purgeSpu = async (row: Api.Product.SpuListItem) => {
    if (isRecycleActionPending(row.id)) return
    purgingIds.value.add(row.id)
    try {
      const { value } = await ElMessageBox.prompt(
        `永久删除后商品不可恢复。商品规格、轮播图关系及标签/保障/优惠券绑定会被清除；历史订单和订单图片会保留，分类、模板、保障服务主数据、优惠券模板及素材文件不会被删除。请输入商品名称“${row.title}”确认永久删除。`,
        '永久删除商品',
        {
          type: 'error',
          confirmButtonText: '永久删除',
          cancelButtonText: '取消',
          inputPlaceholder: '请输入完整商品名称',
          inputValidator: (input) => input === row.title || `请输入完整商品名称“${row.title}”`
        }
      )
      await purgeProductSpu(row.id, { confirmationTitle: value })
      await refreshRemove()
    } catch {
      // 取消确认时静默返回；接口错误由统一 HTTP 层展示，避免重复提示。
    } finally {
      purgingIds.value.delete(row.id)
    }
  }

  const openStockDialog = async (spuId: number) => {
    stockDialogVisible.value = true
    stockDialogLoading.value = true
    stockSpuId.value = spuId

    try {
      const detail = await fetchProductSpuDetail(spuId)
      stockSkus.value = detail.skus || []
      Object.keys(stockAdjustmentMap).forEach((key) => delete stockAdjustmentMap[Number(key)])
      stockSkus.value.forEach((sku) => {
        if (!sku.id) return
        stockAdjustmentMap[sku.id] = {
          quantityDelta: 0,
          reason: ''
        }
      })
    } finally {
      stockDialogLoading.value = false
    }
  }

  const submitStockAdjustment = async (sku: Api.Product.Sku) => {
    if (!sku.id) return
    const payload = stockAdjustmentMap[sku.id]
    if (!payload.reason.trim()) {
      ElMessage.error('请输入调整原因')
      return
    }
    if (!payload.quantityDelta) {
      ElMessage.error('调整数量不能为 0')
      return
    }

    stockSubmittingSkuId.value = sku.id
    try {
      await adjustSkuStock(sku.id, {
        quantityDelta: payload.quantityDelta,
        reason: payload.reason.trim()
      })
      payload.quantityDelta = 0
      payload.reason = ''
      if (!stockSpuId.value) return
      const detail = await fetchProductSpuDetail(stockSpuId.value)
      stockSkus.value = detail.skus || []
      await refreshData()
    } finally {
      stockSubmittingSkuId.value = null
    }
  }

  onMounted(loadCategories)
</script>

<style scoped lang="scss">
  .status-filter-card {
    margin-top: 12px;

    :deep(.el-card__body) {
      padding: 14px 20px;
    }
  }

  .status-filter-row {
    display: flex;
    gap: 18px;
    align-items: center;
  }

  .status-filter-label {
    flex: 0 0 auto;
    font-size: 14px;
    font-weight: 500;
    color: var(--el-text-color-regular);
  }

  .product-table-card {
    margin-top: 12px;
  }

  .sales-value {
    font-weight: 600;
    color: var(--el-text-color-primary);
    cursor: help;
  }

  .status-cell,
  .table-actions {
    display: flex;
    align-items: center;
  }

  .recycle-actions {
    gap: 8px;
  }

  .status-cell {
    gap: 10px;
  }

  .table-actions {
    flex-wrap: nowrap;
    gap: 2px;

    :deep(.el-button + .el-button) {
      margin-left: 4px;
    }
  }

  @media (width <= 768px) {
    .status-filter-row {
      flex-direction: column;
      gap: 10px;
      align-items: flex-start;
    }
  }
</style>
