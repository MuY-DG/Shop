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
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />
      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { formatLocalDateTime as formatDateTime } from '@/utils/date-time'
  import { computed, h, ref } from 'vue'
  import { ElTag } from 'element-plus'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchCouponClaims } from '@/api/coupon'

  defineOptions({ name: 'MarketingCouponClaim' })

  const searchForm = ref<{
    templateName?: string
    userKeyword?: string
    distributionMode?: Api.Marketing.CouponDistributionMode
    issueSource?: Api.Marketing.CouponIssueSource
    status?: Api.Marketing.UserCouponStatus
  }>({})

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '优惠券',
      key: 'templateName',
      type: 'input',
      props: { clearable: true, placeholder: '请输入优惠券名称' }
    },
    {
      label: '用户',
      key: 'userKeyword',
      type: 'input',
      props: { clearable: true, placeholder: '名称、手机号或用户 ID' }
    },
    {
      label: '券类型',
      key: 'distributionMode',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择券类型',
        options: [
          { label: '公开优惠券', value: 'PUBLIC' },
          { label: '专属优惠券', value: 'DIRECT' }
        ]
      }
    },
    {
      label: '领取方式',
      key: 'issueSource',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择领取方式',
        options: [
          { label: '用户领取', value: 'SELF_CLAIM' },
          { label: '后台发送', value: 'ADMIN_ISSUE' },
          { label: '创建专属券', value: 'ADMIN_DIRECT' }
        ]
      }
    },
    {
      label: '当前状态',
      key: 'status',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择当前状态',
        options: [
          { label: '可使用', value: 'CLAIMED' },
          { label: '已锁定', value: 'LOCKED' },
          { label: '已使用', value: 'USED' },
          { label: '已过期', value: 'EXPIRED' }
        ]
      }
    }
  ])

  const issueSourceMap: Record<
    Api.Marketing.CouponIssueSource,
    { text: string; type: 'success' | 'warning' | 'primary' }
  > = {
    SELF_CLAIM: { text: '用户领取', type: 'success' },
    ADMIN_ISSUE: { text: '后台发送', type: 'primary' },
    ADMIN_DIRECT: { text: '创建专属券', type: 'warning' }
  }

  const statusMap: Record<
    Api.Marketing.UserCouponStatus,
    { text: string; type: 'success' | 'warning' | 'info' | 'danger' }
  > = {
    CLAIMED: { text: '可使用', type: 'success' },
    LOCKED: { text: '已锁定', type: 'warning' },
    USED: { text: '已使用', type: 'info' },
    EXPIRED: { text: '已过期', type: 'danger' }
  }

  const formatMoney = (cent: number) => `¥${(cent / 100).toFixed(2)}`

  const formatDiscount = (row: Api.Marketing.CouponClaimRecord) => {
    const discount =
      row.discountType === 'PERCENT_OFF'
        ? `${(row.discountCent / 100).toFixed(2)} 折`
        : `减 ${formatMoney(row.discountCent)}`
    return row.couponType === 'NO_THRESHOLD'
      ? `无门槛 ${discount}`
      : `满 ${formatMoney(row.thresholdCent)} ${discount}`
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
      apiFn: fetchCouponClaims,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: '记录 ID', width: 100 },
        {
          prop: 'templateName',
          label: '优惠券',
          minWidth: 210,
          formatter: (row) =>
            h('div', { class: 'record-cell' }, [
              h('div', { class: 'record-cell__title' }, row.templateName),
              h('div', { class: 'record-cell__sub' }, [
                h(
                  ElTag,
                  {
                    size: 'small',
                    effect: 'plain',
                    type: row.distributionMode === 'DIRECT' ? 'warning' : 'success'
                  },
                  () => (row.distributionMode === 'DIRECT' ? '专属券' : '公开券')
                ),
                h('span', `模板 ${row.templateId} · 用户券 ${row.userCouponId}`)
              ])
            ])
        },
        {
          prop: 'userNickname',
          label: '领取用户',
          minWidth: 180,
          formatter: (row) =>
            h('div', { class: 'record-cell' }, [
              h('div', { class: 'record-cell__title' }, row.userNickname || `用户 ${row.userId}`),
              h(
                'div',
                { class: 'record-cell__sub' },
                `ID ${row.userId}${row.userPhoneNumber ? ` · ${row.userPhoneNumber}` : ''}`
              )
            ])
        },
        {
          prop: 'issueSource',
          label: '领取方式',
          width: 130,
          formatter: (row) => {
            const config = issueSourceMap[row.issueSource]
            return h(ElTag, { type: config.type, effect: 'plain' }, () => config.text)
          }
        },
        {
          prop: 'discountCent',
          label: '优惠内容',
          minWidth: 150,
          formatter: (row) => formatDiscount(row)
        },
        {
          prop: 'status',
          label: '当前状态',
          width: 110,
          formatter: (row) => {
            const config = statusMap[row.status]
            return h(ElTag, { type: config.type }, () => config.text)
          }
        },
        {
          prop: 'validStartAt',
          label: '有效期',
          minWidth: 220,
          formatter: (row) =>
            h('div', { class: 'record-cell' }, [
              h('div', formatDateTime(row.validStartAt)),
              h('div', { class: 'record-cell__sub' }, `至 ${formatDateTime(row.validEndAt)}`)
            ])
        },
        {
          prop: 'claimedAt',
          label: '领取/发放时间',
          minWidth: 170,
          formatter: (row) => formatDateTime(row.claimedAt)
        },
        {
          prop: 'operatorDisplayName',
          label: '操作与备注',
          minWidth: 190,
          formatter: (row) =>
            h('div', { class: 'record-cell' }, [
              h(
                'div',
                { class: 'record-cell__title' },
                row.operatorDisplayName || (row.issueSource === 'SELF_CLAIM' ? '用户自主领取' : '-')
              ),
              h('div', { class: 'record-cell__sub' }, row.issueNote || '无备注')
            ])
        },
        {
          prop: 'usedOrderId',
          label: '使用结果',
          minWidth: 150,
          formatter: (row) =>
            row.usedOrderId ? `订单 ${row.usedOrderId} · ${formatDateTime(row.usedAt)}` : '-'
        }
      ]
    }
  })

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = {}
    resetSearchParams()
    getData()
  }
</script>

<style scoped lang="scss">
  .record-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;

    &__title {
      color: var(--el-text-color-primary);
    }

    &__sub {
      display: flex;
      gap: 6px;
      align-items: center;
      font-size: 12px;
      line-height: 18px;
      color: var(--el-text-color-secondary);
    }
  }
</style>
