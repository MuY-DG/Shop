<template>
  <div class="art-full-height">
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      :show-expand="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card" :style="{ marginTop: '12px' }">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElButton @click="openEditor()" v-ripple>新增商品</ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      />
    </ElCard>

    <SpuEditor
      v-model:visible="editorVisible"
      :spu-id="currentSpuId"
      :categories="categories"
      @success="handleEditorSuccess"
    />

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
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    adjustSkuStock,
    fetchProductCategories,
    fetchProductSpuDetail,
    fetchProductSpus,
    publishProductSpu,
    unpublishProductSpu
  } from '@/api/product'
  import SpuEditor from './modules/spu-editor.vue'
  import { ElImage, ElTag, ElMessage, ElMessageBox } from 'element-plus'

  defineOptions({ name: 'ProductSpu' })

  interface CategoryOption {
    label: string
    value: number
  }

  const categories = ref<Api.Product.Category[]>([])
  const editorVisible = ref(false)
  const currentSpuId = ref<number | null>(null)

  const stockDialogVisible = ref(false)
  const stockDialogLoading = ref(false)
  const stockSubmittingSkuId = ref<number | null>(null)
  const stockSpuId = ref<number | null>(null)
  const stockSkus = ref<Api.Product.Sku[]>([])
  const stockAdjustmentMap = reactive<Record<number, Api.Product.StockAdjustmentForm>>({})

  const searchForm = ref<{
    title?: string
    categoryId?: number
    status?: Api.Product.ProductStatus
  }>({
    title: undefined,
    categoryId: undefined,
    status: undefined
  })

  const statusMap: Record<
    Api.Product.ProductStatus,
    { type: 'info' | 'warning' | 'success'; text: string }
  > = {
    DRAFT: { type: 'info', text: '草稿' },
    ON_SALE: { type: 'success', text: '上架' },
    OFF_SALE: { type: 'warning', text: '下架' }
  }

  const categoryOptions = computed<CategoryOption[]>(() => {
    const result: CategoryOption[] = []
    const walk = (nodes: Api.Product.Category[], prefix = '') => {
      nodes.forEach((item) => {
        result.push({
          label: prefix ? `${prefix} / ${item.name}` : item.name,
          value: item.id
        })
        walk(item.children || [], prefix ? `${prefix} / ${item.name}` : item.name)
      })
    }
    walk(categories.value)
    return result
  })

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '商品标题',
      key: 'title',
      type: 'input',
      props: {
        clearable: true,
        placeholder: '请输入商品标题'
      }
    },
    {
      label: '商品分类',
      key: 'categoryId',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择分类',
        options: categoryOptions.value
      }
    },
    {
      label: '商品状态',
      key: 'status',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择状态',
        options: [
          { label: '草稿', value: 'DRAFT' },
          { label: '上架', value: 'ON_SALE' },
          { label: '下架', value: 'OFF_SALE' }
        ]
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
    refreshData
  } = useTable({
    core: {
      apiFn: fetchProductSpus,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        {
          prop: 'mainImage',
          label: '主图',
          width: 90,
          formatter: (row) =>
            h(ElImage, {
              src: row.mainImage,
              fit: 'cover',
              previewSrcList: row.mainImage ? [row.mainImage] : [],
              previewTeleported: true,
              style: {
                width: '48px',
                height: '48px',
                borderRadius: '6px',
                backgroundColor: 'var(--el-fill-color-light)'
              }
            })
        },
        {
          prop: 'title',
          label: '商品信息',
          minWidth: 220,
          formatter: (row) =>
            h('div', { class: 'spu-title-cell' }, [
              h('div', { class: 'title' }, row.title),
              h('div', { class: 'subtitle' }, row.subtitle || '-')
            ])
        },
        {
          prop: 'categoryName',
          label: '分类',
          minWidth: 120
        },
        {
          prop: 'priceRange',
          label: '价格区间',
          width: 140,
          formatter: (row) => formatPriceRange(row)
        },
        {
          prop: 'totalStock',
          label: '总库存',
          width: 100
        },
        {
          prop: 'status',
          label: '状态',
          width: 100,
          formatter: (row) => {
            const config = statusMap[row.status]
            return h(ElTag, { type: config.type }, () => config.text)
          }
        },
        {
          prop: 'updatedAt',
          label: '更新时间',
          width: 180
        },
        {
          prop: 'operation',
          label: '操作',
          width: 170,
          fixed: 'right',
          formatter: (row) =>
            h(ArtButtonMore, {
              list: buildMoreActions(row),
              onClick: (item: ButtonMoreItem) => handleMoreAction(item, row)
            })
        }
      ]
    }
  })

  const buildMoreActions = (row: Api.Product.SpuListItem): ButtonMoreItem[] => {
    const actions: ButtonMoreItem[] = [
      {
        key: 'edit',
        label: '编辑',
        icon: 'ri:edit-2-line'
      },
      {
        key: 'stock',
        label: '调库存',
        icon: 'ri:archive-stack-line'
      }
    ]

    if (row.status === 'ON_SALE') {
      actions.push({
        key: 'unpublish',
        label: '下架',
        icon: 'ri:close-circle-line',
        color: '#e6a23c'
      })
    } else {
      actions.push({
        key: 'publish',
        label: '上架',
        icon: 'ri:check-double-line',
        color: '#67c23a'
      })
    }

    return actions
  }

  const loadCategories = async () => {
    categories.value = await fetchProductCategories()
  }

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = {
      title: undefined,
      categoryId: undefined,
      status: undefined
    }
    resetSearchParams()
    getData()
  }

  const openEditor = (spuId?: number) => {
    currentSpuId.value = spuId ?? null
    editorVisible.value = true
  }

  const handleEditorSuccess = async () => {
    await Promise.all([refreshData(), loadCategories()])
  }

  const handleMoreAction = (item: ButtonMoreItem, row: Api.Product.SpuListItem) => {
    switch (item.key) {
      case 'edit':
        openEditor(row.id)
        break
      case 'publish':
        confirmPublish(row, true)
        break
      case 'unpublish':
        confirmPublish(row, false)
        break
      case 'stock':
        openStockDialog(row.id)
        break
    }
  }

  const confirmPublish = async (row: Api.Product.SpuListItem, publish: boolean) => {
    const actionText = publish ? '上架' : '下架'
    await ElMessageBox.confirm(`确定${actionText}商品“${row.title}”吗？`, `${actionText}确认`, {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    if (publish) {
      await publishProductSpu(row.id)
    } else {
      await unpublishProductSpu(row.id)
    }
    refreshData()
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
      refreshData()
    } finally {
      stockSubmittingSkuId.value = null
    }
  }

  onMounted(async () => {
    await loadCategories()
  })
</script>

<style scoped lang="scss">
  .spu-title-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;

    .title {
      line-height: 20px;
      color: var(--el-text-color-primary);
    }

    .subtitle {
      font-size: 12px;
      line-height: 18px;
      color: var(--el-text-color-secondary);
    }
  }
</style>
