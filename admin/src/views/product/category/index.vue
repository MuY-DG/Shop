<template>
  <div class="art-full-height">
    <ElCard class="art-table-card">
      <ArtTableHeader :loading="loading" v-model:columns="columnChecks" @refresh="loadCategories">
        <template #left>
          <ElButton @click="openCreateDialog()" v-ripple>新增分类</ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        row-key="id"
        :loading="loading"
        :data="tableData"
        :columns="columns"
        :tree-props="{ children: 'children' }"
        default-expand-all
        :show-table-header="true"
      />
    </ElCard>

    <CategoryDialog
      v-model:visible="dialogVisible"
      :category="currentCategory"
      :initial-parent-id="parentCategoryId"
      :parent-options="parentTreeOptions"
      :submitting="saving"
      @submit="handleSubmit"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onMounted, ref } from 'vue'
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import {
    createProductCategory,
    fetchProductCategories,
    updateProductCategory
  } from '@/api/product'
  import CategoryDialog from './modules/category-dialog.vue'
  import { ElTag } from 'element-plus'

  defineOptions({ name: 'ProductCategory' })

  interface TreeOption {
    value: number
    label: string
    children?: TreeOption[]
  }

  const loading = ref(false)
  const saving = ref(false)
  const tableData = ref<Api.Product.Category[]>([])
  const dialogVisible = ref(false)
  const currentCategory = ref<Api.Product.Category | null>(null)
  const parentCategoryId = ref(0)

  const statusMap: Record<Api.Product.CategoryStatus, { type: 'success' | 'info'; text: string }> =
    {
      ENABLED: { type: 'success', text: '启用' },
      DISABLED: { type: 'info', text: '禁用' }
    }

  const collectNodeIds = (category: Api.Product.Category): number[] => {
    const ids = [category.id]
    category.children?.forEach((child) => ids.push(...collectNodeIds(child)))
    return ids
  }

  const buildTreeOptions = (
    categories: Api.Product.Category[],
    excludedIds: Set<number> = new Set()
  ): TreeOption[] =>
    categories
      .filter((item) => !excludedIds.has(item.id))
      .map((item) => ({
        value: item.id,
        label: item.name,
        children: buildTreeOptions(item.children || [], excludedIds)
      }))

  const parentTreeOptions = computed<TreeOption[]>(() => {
    if (!currentCategory.value) return buildTreeOptions(tableData.value)
    return buildTreeOptions(tableData.value, new Set(collectNodeIds(currentCategory.value)))
  })

  const { columns, columnChecks } = useTableColumns<Api.Product.Category>(() => [
    {
      prop: 'id',
      label: 'ID',
      width: 90
    },
    {
      prop: 'name',
      label: '分类名称',
      minWidth: 220
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
      prop: 'sortOrder',
      label: '排序',
      width: 100
    },
    {
      prop: 'operation',
      label: '操作',
      width: 160,
      fixed: 'right',
      formatter: (row) =>
        h('div', [
          h(ArtButtonTable, {
            type: 'add',
            title: '新增子分类',
            onClick: () => openCreateDialog(row)
          }),
          h(ArtButtonTable, {
            type: 'edit',
            onClick: () => openEditDialog(row)
          })
        ])
    }
  ])

  const loadCategories = async () => {
    loading.value = true
    try {
      tableData.value = await fetchProductCategories()
    } finally {
      loading.value = false
    }
  }

  const openCreateDialog = (parent?: Api.Product.Category) => {
    currentCategory.value = null
    parentCategoryId.value = parent?.id ?? 0
    dialogVisible.value = true
  }

  const openEditDialog = (row: Api.Product.Category) => {
    currentCategory.value = { ...row, children: row.children || [] }
    parentCategoryId.value = row.parentId
    dialogVisible.value = true
  }

  const handleSubmit = async (form: Api.Product.CategoryForm) => {
    saving.value = true
    try {
      if (currentCategory.value?.id) {
        await updateProductCategory(currentCategory.value.id, form)
      } else {
        await createProductCategory(form)
      }
      dialogVisible.value = false
      await loadCategories()
    } finally {
      saving.value = false
    }
  }

  onMounted(() => {
    loadCategories()
  })
</script>
