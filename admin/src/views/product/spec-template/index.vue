<template>
  <div class="product-spec-template art-full-height">
    <ElCard class="art-table-card">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="loadTemplates">
        <template #left>
          <ElButton
            v-auth="'product:spec-template:create'"
            type="primary"
            @click="openCreateDialog"
            v-ripple
          >
            新增规格模板
          </ElButton>
        </template>
      </ArtTableHeader>

      <ArtTable
        row-key="id"
        :loading="loading"
        :data="templates"
        :columns="columns"
        :show-table-header="true"
      >
        <template #operation="{ row }">
          <ElButton
            v-if="hasAuth('product:spec-template:update')"
            type="primary"
            link
            @click="openEditDialog(row.id)"
          >
            编辑
          </ElButton>
        </template>
      </ArtTable>
    </ElCard>

    <SpecTemplateDialog
      v-model:visible="dialogVisible"
      :template-id="editingTemplateId"
      @success="handleSaved"
    />
  </div>
</template>

<script setup lang="ts">
  import { onMounted, ref } from 'vue'
  import { fetchProductSpecTemplates } from '@/api/product'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useTableColumns } from '@/hooks/core/useTableColumns'
  import SpecTemplateDialog from './modules/spec-template-dialog.vue'

  defineOptions({ name: 'ProductSpecTemplate' })

  const loading = ref(false)
  const templates = ref<Api.Product.SpecTemplateSummary[]>([])
  const dialogVisible = ref(false)
  const editingTemplateId = ref<number | null>(null)
  const { hasAuth } = useAuth()

  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')

  const { columns, columnChecks } = useTableColumns<Api.Product.SpecTemplateSummary>(() => [
    { prop: 'id', label: 'ID', width: 90 },
    { prop: 'name', label: '规格模板名称', minWidth: 220 },
    { prop: 'groupCount', label: '规格数量', width: 110 },
    { prop: 'valueCount', label: '规格值数量', width: 120 },
    {
      prop: 'createdAt',
      label: '创建时间',
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
      prop: 'operation',
      label: '操作',
      width: 100,
      fixed: 'right',
      useSlot: true,
      disabled: true
    }
  ])

  const loadTemplates = async () => {
    loading.value = true
    try {
      templates.value = await fetchProductSpecTemplates()
    } finally {
      loading.value = false
    }
  }

  const openCreateDialog = () => {
    editingTemplateId.value = null
    dialogVisible.value = true
  }

  const openEditDialog = (templateId: number) => {
    editingTemplateId.value = templateId
    dialogVisible.value = true
  }

  const handleSaved = async () => {
    await loadTemplates()
  }

  onMounted(loadTemplates)
</script>
