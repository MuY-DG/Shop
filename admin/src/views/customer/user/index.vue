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
          <span class="table-hint">小程序注册用户</span>
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
  import { ElAvatar, ElTag } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import ArtButtonMore from '@/components/core/forms/art-button-more/index.vue'
  import type { ButtonMoreItem } from '@/components/core/forms/art-button-more/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchCustomers } from '@/api/customer'
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
          { label: '停用', value: 'DISABLED' }
        ]
      }
    }
  ])

  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')

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
          prop: 'nickname',
          label: '用户',
          minWidth: 230,
          formatter: (row: Customer) =>
            h('div', { class: 'customer-cell' }, [
              h(ElAvatar, { size: 38 }, () => row.nickname?.slice(0, 1) || '用'),
              h('div', { class: 'customer-cell__text' }, [
                h('div', { class: 'customer-cell__name' }, row.nickname || '未命名用户'),
                h('div', { class: 'customer-cell__id' }, `ID ${row.id}`)
              ])
            ])
        },
        {
          prop: 'phoneNumber',
          label: '手机号',
          minWidth: 170,
          formatter: (row: Customer) =>
            h('div', { class: 'phone-cell' }, [
              h('div', row.phoneNumber || '-'),
              h(
                'div',
                { class: ['phone-cell__status', row.phoneAuthorized ? 'is-authorized' : ''] },
                row.phoneAuthorized ? '微信已授权' : '未授权'
              )
            ])
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
          formatter: (row: Customer) =>
            h(ElTag, { type: row.status === 'ENABLED' ? 'success' : 'info' }, () =>
              row.status === 'ENABLED' ? '启用' : '停用'
            )
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
          width: 110,
          fixed: 'right',
          formatter: (row: Customer) =>
            h(ArtButtonMore, {
              list: [
                {
                  key: 'issue-coupon',
                  label: '发送优惠券',
                  icon: 'ri:coupon-3-line',
                  auth: 'customer:coupon:issue',
                  disabled: row.status !== 'ENABLED'
                }
              ],
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

  const handleMoreAction = (item: ButtonMoreItem, row: Customer) => {
    if (item.key !== 'issue-coupon') return
    currentCustomer.value = row
    issueDialogVisible.value = true
  }

  const handleIssueSuccess = () => refreshData()
</script>

<style scoped lang="scss">
  .table-hint {
    font-size: 14px;
    color: var(--el-text-color-secondary);
  }

  .customer-cell {
    display: flex;
    gap: 10px;
    align-items: center;

    &__text {
      min-width: 0;
    }

    &__name {
      overflow: hidden;
      font-weight: 500;
      color: var(--el-text-color-primary);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__id {
      margin-top: 3px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .phone-cell {
    &__status {
      margin-top: 3px;
      font-size: 12px;
      color: var(--el-text-color-placeholder);

      &.is-authorized {
        color: var(--el-color-success);
      }
    }
  }

  .coupon-stats {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
</style>
