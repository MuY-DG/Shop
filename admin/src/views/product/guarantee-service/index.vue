<template>
  <div class="product-guarantee-service art-full-height">
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
          <ElButton
            v-auth="'product:guarantee:create'"
            type="primary"
            @click="openEditor()"
            v-ripple
          >
            添加保障服务
          </ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        row-key="id"
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <template #icon="{ row }">
          <ElImage
            v-if="row.icon"
            class="service-icon"
            :src="row.icon"
            fit="cover"
            :preview-src-list="[row.icon]"
            preview-teleported
          >
            <template #error>
              <div class="icon-placeholder">加载失败</div>
            </template>
          </ElImage>
          <span v-else>-</span>
        </template>

        <template #contentDescription="{ row }">
          <ElTooltip :content="row.contentDescription" placement="top" :show-after="300">
            <span class="description-text">{{ row.contentDescription }}</span>
          </ElTooltip>
        </template>

        <template #visible="{ row }">
          <div class="visibility-cell">
            <ElSwitch
              v-auth="'product:guarantee:visibility'"
              :model-value="row.visible"
              :loading="visibilityUpdatingIds.has(row.id)"
              :disabled="visibilityUpdatingIds.has(row.id)"
              :before-change="() => requestVisibility(row, !row.visible)"
            />
            <ElTag :type="row.visible ? 'success' : 'info'" size="small">
              {{ row.visible ? '显示' : '隐藏' }}
            </ElTag>
          </div>
        </template>

        <template #operation="{ row }">
          <div class="operation-cell">
            <ElButton
              v-auth="'product:guarantee:update'"
              type="primary"
              link
              @click="openEditor(row)"
            >
              编辑
            </ElButton>
            <ElButton
              v-auth="'product:guarantee:delete'"
              type="danger"
              link
              :loading="deletingIds.has(row.id)"
              @click="deleteService(row)"
            >
              删除
            </ElButton>
          </div>
        </template>
      </ArtTable>
    </ElCard>

    <GuaranteeServiceDialog
      v-model:visible="editorVisible"
      :service="currentService"
      @success="handleSaved"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { formatLocalDateTime as formatDateTime } from '@/utils/date-time'
  import {
    deleteProductGuaranteeService,
    fetchProductGuaranteeServices,
    updateProductGuaranteeServiceVisibility
  } from '@/api/product'
  import GuaranteeServiceDialog from './modules/guarantee-service-dialog.vue'

  defineOptions({ name: 'ProductGuaranteeService' })

  const editorVisible = ref(false)
  const currentService = ref<Api.Product.GuaranteeService | null>(null)
  const visibilityUpdatingIds = ref(new Set<number>())
  const deletingIds = ref(new Set<number>())

  const searchForm = ref<{
    name?: string
    visible?: boolean
  }>({
    name: undefined,
    visible: undefined
  })

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '服务条款',
      key: 'name',
      type: 'input',
      props: {
        clearable: true,
        placeholder: '请输入服务条款名称'
      }
    },
    {
      label: '是否显示',
      key: 'visible',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择显示状态',
        options: [
          { label: '显示', value: true },
          { label: '隐藏', value: false }
        ]
      }
    }
  ])

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
    refreshCreate,
    refreshUpdate,
    refreshRemove
  } = useTable({
    core: {
      apiFn: fetchProductGuaranteeServices,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 90 },
        { prop: 'termsName', label: '服务条款', minWidth: 180 },
        { prop: 'icon', label: '服务条款图标', width: 130, useSlot: true },
        {
          prop: 'contentDescription',
          label: '服务内容描述',
          minWidth: 260,
          useSlot: true
        },
        { prop: 'sortOrder', label: '排序', width: 90 },
        {
          prop: 'createdAt',
          label: '创建时间',
          width: 180,
          formatter: (row) => formatDateTime(row.createdAt)
        },
        { prop: 'visible', label: '是否显示', width: 150, useSlot: true },
        {
          prop: 'operation',
          label: '操作',
          width: 150,
          fixed: 'right',
          useSlot: true
        }
      ]
    }
  })

  const handleSearch = (params: Record<string, unknown>) => {
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = { name: undefined, visible: undefined }
    resetSearchParams()
    getData()
  }

  const openEditor = (service?: Api.Product.GuaranteeService) => {
    currentService.value = service ? { ...service } : null
    editorVisible.value = true
  }

  const handleSaved = async (mode: 'create' | 'update') => {
    if (mode === 'create') {
      await refreshCreate()
    } else {
      await refreshUpdate()
    }
  }

  const isMessageBoxCancel = (error: unknown) => error === 'cancel' || error === 'close'

  const requestVisibility = async (
    row: Api.Product.GuaranteeService,
    visible: boolean
  ): Promise<boolean> => {
    if (visibilityUpdatingIds.value.has(row.id)) return false
    const actionText = visible ? '显示' : '隐藏'

    try {
      await ElMessageBox.confirm(
        `确定${actionText}保障服务“${row.termsName}”吗？`,
        `${actionText}确认`,
        {
          type: 'warning',
          confirmButtonText: `确定${actionText}`,
          cancelButtonText: '取消'
        }
      )
      visibilityUpdatingIds.value.add(row.id)
      await updateProductGuaranteeServiceVisibility(row.id, { visible })
      await refreshUpdate()
      return true
    } catch (error) {
      if (!isMessageBoxCancel(error)) {
        ElMessage.error(`${actionText}失败，显示状态未变更`)
      }
      return false
    } finally {
      visibilityUpdatingIds.value.delete(row.id)
    }
  }

  const deleteService = async (row: Api.Product.GuaranteeService) => {
    if (deletingIds.value.has(row.id)) return

    try {
      await ElMessageBox.confirm(
        `删除后将自动解除所有商品对“${row.termsName}”的引用，且不可恢复，确定继续吗？`,
        '删除保障服务',
        {
          type: 'warning',
          confirmButtonText: '确定删除',
          cancelButtonText: '取消'
        }
      )
      deletingIds.value.add(row.id)
      await deleteProductGuaranteeService(row.id)
      await refreshRemove()
    } catch (error) {
      if (!isMessageBoxCancel(error)) {
        ElMessage.error('删除失败，保障服务未被删除')
      }
    } finally {
      deletingIds.value.delete(row.id)
    }
  }
</script>

<style scoped lang="scss">
  .service-icon {
    width: 48px;
    height: 48px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .icon-placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    font-size: 11px;
    color: var(--el-text-color-secondary);
    background: var(--el-fill-color-light);
  }

  .description-text {
    display: -webkit-box;
    overflow: hidden;
    line-height: 20px;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  .visibility-cell,
  .operation-cell {
    display: flex;
    gap: 8px;
    align-items: center;
  }
</style>
