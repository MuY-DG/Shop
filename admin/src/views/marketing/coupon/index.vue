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
          <ElButton @click="openEditor()" v-ripple v-auth="'coupon:template:create'">
            新增优惠券
          </ElButton>
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

    <CouponTemplateDialog
      v-model:visible="editorVisible"
      :template="currentTemplate"
      @success="handleEditorSuccess"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, h, ref } from 'vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import {
    disableCouponTemplate,
    enableCouponTemplate,
    fetchCouponTemplates
  } from '@/api/coupon'
  import CouponTemplateDialog from './modules/coupon-template-dialog.vue'
  import { ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'MarketingCoupon' })

  const editorVisible = ref(false)
  const currentTemplate = ref<Api.Marketing.CouponTemplate | null>(null)

  const searchForm = ref<{
    name?: string
    status?: Api.Marketing.CouponTemplateStatus
  }>({
    name: undefined,
    status: undefined
  })

  const statusMap: Record<
    Api.Marketing.CouponTemplateStatus,
    { type: 'success' | 'info'; text: string }
  > = {
    ENABLED: { type: 'success', text: '启用' },
    DISABLED: { type: 'info', text: '禁用' }
  }

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '模板名称',
      key: 'name',
      type: 'input',
      props: {
        clearable: true,
        placeholder: '请输入模板名称'
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
          { label: '启用', value: 'ENABLED' },
          { label: '禁用', value: 'DISABLED' }
        ]
      }
    }
  ])

  const formatMoney = (cent: number | null | undefined) => `¥${((cent ?? 0) / 100).toFixed(2)}`

  const formatDateTime = (value: string | null | undefined) => {
    if (!value) return '-'
    return value.replace('T', ' ')
  }

  const formatCouponType = (row: Api.Marketing.CouponTemplate) => {
    if (row.discountType === 'PERCENT_OFF') {
      return row.couponType === 'NO_THRESHOLD' ? '无门槛折扣券' : '满折券'
    }
    return row.couponType === 'NO_THRESHOLD' ? '无门槛立减券' : '满减券'
  }

  const formatThreshold = (row: Api.Marketing.CouponTemplate) => {
    if (row.couponType === 'NO_THRESHOLD') {
      return '无门槛'
    }
    return formatMoney(row.thresholdCent)
  }

  const formatDiscount = (row: Api.Marketing.CouponTemplate) => {
    if (row.discountType === 'PERCENT_OFF') {
      return `${(row.discountCent / 100).toFixed(2)} 折`
    }
    return formatMoney(row.discountCent)
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
      apiFn: fetchCouponTemplates,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        {
          prop: 'id',
          label: 'ID',
          width: 90
        },
        {
          prop: 'name',
          label: '名称/描述',
          minWidth: 220,
          formatter: (row) =>
            h('div', { class: 'coupon-info-cell' }, [
              h('div', { class: 'title' }, row.name),
              h('div', { class: 'subtitle' }, row.description || '-')
            ])
        },
        {
          prop: 'couponType',
          label: '类型',
          width: 130,
          formatter: (row) => formatCouponType(row)
        },
        {
          prop: 'thresholdCent',
          label: '门槛',
          width: 120,
          formatter: (row) => formatThreshold(row)
        },
        {
          prop: 'discountCent',
          label: '优惠',
          width: 120,
          formatter: (row) => formatDiscount(row)
        },
        {
          prop: 'stock',
          label: '库存',
          width: 130,
          formatter: (row) => `${row.stockRemaining}/${row.totalStock}`
        },
        {
          prop: 'perUserLimit',
          label: '每人限领',
          width: 100
        },
        {
          prop: 'validity',
          label: '有效期',
          minWidth: 220,
          formatter: (row) =>
            h('div', { class: 'coupon-validity-cell' }, [
              h('div', formatDateTime(row.validStartAt)),
              h('div', { class: 'subtitle' }, formatDateTime(row.validEndAt))
            ])
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
          width: 120,
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

  const buildMoreActions = (row: Api.Marketing.CouponTemplate): ButtonMoreItem[] => {
    const actions: ButtonMoreItem[] = [
      {
        key: 'edit',
        label: '编辑',
        icon: 'ri:edit-2-line',
        auth: 'coupon:template:update'
      }
    ]

    if (row.status === 'ENABLED') {
      actions.push({
        key: 'disable',
        label: '禁用',
        icon: 'ri:close-circle-line',
        color: '#909399',
        auth: 'coupon:template:disable'
      })
    } else {
      actions.push({
        key: 'enable',
        label: '启用',
        icon: 'ri:check-double-line',
        color: '#67c23a',
        auth: 'coupon:template:enable'
      })
    }

    return actions
  }

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = {
      name: undefined,
      status: undefined
    }
    resetSearchParams()
    getData()
  }

  const openEditor = (template?: Api.Marketing.CouponTemplate) => {
    currentTemplate.value = template ? { ...template } : null
    editorVisible.value = true
  }

  const handleEditorSuccess = async () => {
    await refreshData()
  }

  const handleMoreAction = (item: ButtonMoreItem, row: Api.Marketing.CouponTemplate) => {
    switch (item.key) {
      case 'edit':
        openEditor(row)
        break
      case 'enable':
        confirmToggleStatus(row, true)
        break
      case 'disable':
        confirmToggleStatus(row, false)
        break
    }
  }

  const confirmToggleStatus = async (row: Api.Marketing.CouponTemplate, enable: boolean) => {
    const actionText = enable ? '启用' : '禁用'
    await ElMessageBox.confirm(`确定${actionText}优惠券“${row.name}”吗？`, `${actionText}确认`, {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })

    if (enable) {
      await enableCouponTemplate(row.id)
    } else {
      await disableCouponTemplate(row.id)
    }

    refreshData()
  }
</script>

<style scoped lang="scss">
  .coupon-info-cell,
  .coupon-validity-cell {
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
