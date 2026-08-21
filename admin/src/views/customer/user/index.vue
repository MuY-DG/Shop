<template>
  <div class="customer-user-page art-full-height">
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
          <span class="table-hint">默认展示正常与管理员停用的用户；已注销用户需主动筛选</span>
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

    <CouponIssueDialog
      v-model:visible="issueDialogVisible"
      :customer="currentCustomer"
      @success="handleIssueSuccess"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, h, ref } from 'vue'
  import { ElAvatar, ElMessageBox, ElTag } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { formatLocalDateTime as formatDateTime } from '@/utils/date-time'
  import { fetchCustomers, updateCustomerStatus } from '@/api/customer'
  import CouponIssueDialog from './modules/coupon-issue-dialog.vue'

  defineOptions({ name: 'CustomerUser' })

  type Customer = Api.Customer.CustomerListItem

  const issueDialogVisible = ref(false)
  const currentCustomer = ref<Customer | null>(null)
  const searchForm = ref<Api.Customer.CustomerSearchParams>({
    keyword: undefined,
    status: undefined
  })

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '用户关键字',
      labelWidth: '84px',
      key: 'keyword',
      type: 'input',
      props: {
        clearable: true,
        placeholder: '用户名称 / 手机号 / 用户 ID'
      }
    },
    {
      label: '账号状态',
      key: 'status',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择状态',
        options: [
          { label: '启用', value: 'ENABLED' },
          { label: '管理员停用', value: 'DISABLED' },
          { label: '已注销', value: 'CANCELLED' }
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
    refreshData
  } = useTable({
    core: {
      apiFn: fetchCustomers,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        {
          prop: 'id',
          label: '用户 ID',
          minWidth: 190,
          formatter: (row: Customer) => h('span', { class: 'customer-id' }, row.id)
        },
        {
          prop: 'avatar',
          label: '头像',
          width: 80,
          formatter: (row: Customer) =>
            h(ElAvatar, { size: 38 }, () => row.nickname?.slice(0, 1) || '用')
        },
        {
          prop: 'nickname',
          label: '用户名',
          minWidth: 150,
          formatter: (row: Customer) =>
            row.status === 'CANCELLED' ? '已注销用户' : row.nickname || '未命名用户'
        },
        {
          prop: 'phoneNumber',
          label: '手机号',
          minWidth: 170,
          formatter: (row: Customer) => row.phoneNumber || '-'
        },
        {
          prop: 'couponTotalCount',
          label: '优惠券',
          minWidth: 235,
          formatter: (row: Customer) =>
            h('div', { class: 'coupon-stats' }, [
              h(
                ElTag,
                { type: 'success', size: 'small' },
                () => `可用 ${row.couponAvailableCount}`
              ),
              h(ElTag, { type: 'info', size: 'small' }, () => `已使用 ${row.couponUsedCount}`),
              h(ElTag, { size: 'small', effect: 'plain' }, () => `总计 ${row.couponTotalCount}`)
            ])
        },
        {
          prop: 'status',
          label: '状态',
          width: 100,
          formatter: (row: Customer) => {
            const status = customerStatusDisplay(row.status)
            return h(ElTag, { type: status.type }, () => status.label)
          }
        },
        {
          prop: 'lastLoginAt',
          label: '最后登录',
          width: 180,
          formatter: (row: Customer) => formatDateTime(row.lastLoginAt)
        },
        {
          prop: 'createdAt',
          label: '注册时间',
          width: 180,
          formatter: (row: Customer) => formatDateTime(row.createdAt)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 130,
          fixed: 'right',
          formatter: (row: Customer) =>
            row.status === 'CANCELLED'
              ? h('span', { class: 'table-hint' }, '不可操作')
              : h(ArtButtonMore, {
                  list: customerActions(row),
                  onClick: (item: ButtonMoreItem) => handleMoreAction(item, row)
                })
        }
      ]
    }
  })

  const handleSearch = (params: Api.Customer.CustomerSearchParams) => {
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = {
      keyword: undefined,
      status: undefined
    }
    resetSearchParams()
    getData()
  }

  const customerStatusDisplay = (status: Api.Customer.CustomerStatus) => {
    if (status === 'ENABLED') return { label: '启用', type: 'success' as const }
    if (status === 'DISABLED') return { label: '管理员停用', type: 'warning' as const }
    return { label: '已注销', type: 'info' as const }
  }

  const customerActions = (row: Customer): ButtonMoreItem[] => [
    {
      key: 'issue-coupon',
      label: '发送优惠券',
      icon: 'ri:coupon-3-line',
      auth: 'customer:coupon:issue',
      disabled: row.status !== 'ENABLED'
    },
    row.status === 'ENABLED'
      ? {
          key: 'disable',
          label: '停用账号',
          icon: 'ri:user-forbid-line',
          color: '#f56c6c',
          auth: 'customer:user:status'
        }
      : {
          key: 'enable',
          label: '重新启用',
          icon: 'ri:user-follow-line',
          auth: 'customer:user:status'
        }
  ]

  const changeCustomerStatus = async (row: Customer, status: 'ENABLED' | 'DISABLED') => {
    const disabling = status === 'DISABLED'
    try {
      const { value } = await ElMessageBox.prompt(
        disabling
          ? '停用后该用户的现有登录会立即失效，重新启用前无法登录。请输入操作原因。'
          : '重新启用后用户仍需重新登录。请输入操作原因。',
        disabling ? '停用用户账号' : '重新启用用户账号',
        {
          type: disabling ? 'warning' : 'info',
          confirmButtonText: disabling ? '确认停用' : '确认启用',
          cancelButtonText: '取消',
          inputType: 'textarea',
          inputPlaceholder: '请输入可追溯的操作原因',
          inputValidator: (input) => {
            const reason = input.trim()
            if (!reason) return '请输入操作原因'
            return reason.length <= 200 || '操作原因最多 200 个字符'
          }
        }
      )
      await updateCustomerStatus(row.id, { status, reason: value.trim() })
      await refreshData()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      throw error
    }
  }

  const handleMoreAction = (item: ButtonMoreItem, row: Customer) => {
    if (item.key === 'issue-coupon') {
      currentCustomer.value = row
      issueDialogVisible.value = true
    }
    if (item.key === 'disable') void changeCustomerStatus(row, 'DISABLED')
    if (item.key === 'enable') void changeCustomerStatus(row, 'ENABLED')
  }

  const handleIssueSuccess = () => refreshData()
</script>

<style scoped lang="scss">
  .table-hint {
    font-size: 14px;
    color: var(--el-text-color-secondary);
  }

  .customer-id {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .coupon-stats {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
</style>
