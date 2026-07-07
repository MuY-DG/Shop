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
      >
        <template #operation="{ row }">
          <div class="order-actions">
            <ElButton type="primary" link @click="openDetail(row.orderId)">详情</ElButton>
            <ElButton
              v-if="row.status === 'CREATED'"
              v-auth="'order:close'"
              type="danger"
              link
              :loading="closingOrderId === row.orderId"
              @click="handleCloseOrder(row.orderId, row.orderNo)"
            >
              关闭
            </ElButton>
          </div>
        </template>
      </ArtTable>
    </ElCard>

    <ElDrawer
      v-model="drawerVisible"
      title="订单详情"
      size="880px"
      destroy-on-close
      append-to-body
    >
      <div v-loading="drawerLoading" class="order-detail">
        <template v-if="currentDetail">
          <div class="order-detail__header">
            <div>
              <div class="order-detail__title">{{ currentDetail.orderNo }}</div>
              <div class="order-detail__meta">
                下单时间 {{ formatDateTime(currentDetail.createdAt) }}
              </div>
            </div>
            <div class="order-detail__status">
              <ElTag :type="statusMap[currentDetail.status].type">
                {{ statusMap[currentDetail.status].text }}
              </ElTag>
            </div>
          </div>

          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="订单来源">
              {{ formatSource(currentDetail.source) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="商户单号">
              {{ formatText(currentDetail.merchantTradeNo) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="支付流水号">
              {{ formatText(currentDetail.paymentTransactionId) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="优惠券">
              {{ currentDetail.couponName ? currentDetail.couponName : '未使用' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="收货人">
              {{ formatText(currentDetail.receiverName) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="联系电话">
              {{ formatText(currentDetail.receiverPhone) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="收货地址" :span="2">
              {{ formatText(currentDetail.receiverAddress) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="关闭原因">
              {{ formatText(currentDetail.closeReason) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="关闭时间">
              {{ formatDateTime(currentDetail.closedAt) }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <div class="order-amounts">
            <div class="amount-card">
              <div class="label">商品原价</div>
              <div class="value">{{ formatMoney(currentDetail.productOriginalAmountCent) }}</div>
            </div>
            <div class="amount-card">
              <div class="label">商品实付</div>
              <div class="value">{{ formatMoney(currentDetail.productAmountCent) }}</div>
            </div>
            <div class="amount-card">
              <div class="label">优惠金额</div>
              <div class="value amount-card__accent">
                -{{ formatMoney(currentDetail.couponDiscountCent) }}
              </div>
            </div>
            <div class="amount-card">
              <div class="label">订单应付</div>
              <div class="value">{{ formatMoney(currentDetail.payableAmountCent) }}</div>
            </div>
          </div>

          <div class="order-items">
            <div class="order-items__title">商品快照</div>
            <ElTable :data="currentDetail.items" border>
              <ElTableColumn label="商品" min-width="260">
                <template #default="{ row }">
                  <div class="item-cell">
                    <ElImage
                      :src="row.displayImage || row.skuImage || row.mainImage"
                      fit="cover"
                      :preview-src-list="
                        row.displayImage || row.skuImage || row.mainImage
                          ? [row.displayImage || row.skuImage || row.mainImage]
                          : []
                      "
                      preview-teleported
                      class="item-cell__image"
                    />
                    <div class="item-cell__content">
                      <div class="title">{{ row.productTitle }}</div>
                      <div class="subtitle">{{ row.productSubtitle || '-' }}</div>
                    </div>
                  </div>
                </template>
              </ElTableColumn>
              <ElTableColumn prop="skuCode" label="SKU" min-width="130" />
              <ElTableColumn prop="specText" label="规格" min-width="150" />
              <ElTableColumn label="单价" width="110">
                <template #default="{ row }">
                  {{ formatMoney(row.unitPriceCent) }}
                </template>
              </ElTableColumn>
              <ElTableColumn prop="quantity" label="数量" width="80" />
              <ElTableColumn label="小计" width="120">
                <template #default="{ row }">
                  {{ formatMoney(row.lineAmountCent) }}
                </template>
              </ElTableColumn>
            </ElTable>
          </div>
        </template>
      </div>

      <template #footer>
        <div class="order-detail__footer">
          <ElButton @click="drawerVisible = false">关闭</ElButton>
          <ElButton
            v-if="currentDetail?.status === 'CREATED'"
            v-auth="'order:close'"
            type="danger"
            :loading="closingOrderId === currentDetail?.orderId"
            @click="handleCloseOrder(currentDetail.orderId, currentDetail.orderNo)"
          >
            关闭订单
          </ElButton>
        </div>
      </template>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, ref } from 'vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { closeOrder, fetchOrderDetail, fetchOrders } from '@/api/order'
  import { ElImage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'OrderList' })

  const drawerVisible = ref(false)
  const drawerLoading = ref(false)
  const closingOrderId = ref<number | null>(null)
  const currentDetail = ref<Api.Order.OrderDetail | null>(null)

  const searchForm = ref<{
    orderNo?: string
    status?: Api.Order.OrderStatus
  }>({
    orderNo: undefined,
    status: undefined
  })

  const statusMap: Record<
    Api.Order.OrderStatus,
    { type: 'warning' | 'success' | 'info' | 'danger'; text: string }
  > = {
    CREATED: { type: 'warning', text: '待支付' },
    PAID: { type: 'success', text: '已支付' },
    CLOSED: { type: 'info', text: '已关闭' },
    REFUNDED: { type: 'danger', text: '已退款' }
  }

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '订单号',
      key: 'orderNo',
      type: 'input',
      props: {
        clearable: true,
        placeholder: '请输入订单号'
      }
    },
    {
      label: '订单状态',
      key: 'status',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择状态',
        options: [
          { label: '待支付', value: 'CREATED' },
          { label: '已支付', value: 'PAID' },
          { label: '已关闭', value: 'CLOSED' },
          { label: '已退款', value: 'REFUNDED' }
        ]
      }
    }
  ])

  const formatMoney = (cent: number | null | undefined) => `¥${((cent ?? 0) / 100).toFixed(2)}`

  const formatDateTime = (value: string | null | undefined) => {
    if (!value) return '-'
    return value.replace('T', ' ')
  }

  const formatText = (value: string | null | undefined) => value || '-'

  const formatSource = (value: string | null | undefined) => {
    if (!value) return '-'
    if (value === 'MINI_PROGRAM') return '微信小程序'
    return value
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
      apiFn: fetchOrders,
      apiParams: {
        current: 1,
        size: 20
      },
      columnsFactory: () => [
        {
          prop: 'orderNo',
          label: '订单信息',
          minWidth: 220,
          formatter: (row) =>
            h('div', { class: 'order-info-cell' }, [
              h('div', { class: 'title' }, row.orderNo),
              h('div', { class: 'subtitle' }, formatDateTime(row.createdAt))
            ])
        },
        {
          prop: 'productTitle',
          label: '首件商品',
          minWidth: 220,
          showOverflowTooltip: true,
          formatter: (row) =>
            h('div', { class: 'order-product-cell' }, [
              h('div', { class: 'title' }, row.productTitle || '-'),
              h('div', { class: 'subtitle' }, `共 ${row.itemCount} 件商品`)
            ])
        },
        {
          prop: 'status',
          label: '状态',
          width: 110,
          formatter: (row) => {
            const config = statusMap[row.status]
            return h(ElTag, { type: config.type }, () => config.text)
          }
        },
        {
          prop: 'productAmountCent',
          label: '商品金额',
          width: 120,
          formatter: (row) => formatMoney(row.productAmountCent)
        },
        {
          prop: 'couponDiscountCent',
          label: '优惠',
          width: 120,
          formatter: (row) => `-${formatMoney(row.couponDiscountCent)}`
        },
        {
          prop: 'payableAmountCent',
          label: '应付金额',
          width: 120,
          formatter: (row) => formatMoney(row.payableAmountCent)
        },
        {
          prop: 'paidAmountCent',
          label: '实付金额',
          width: 120,
          formatter: (row) => formatMoney(row.paidAmountCent)
        },
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

  const handleSearch = (params: Record<string, any>) => {
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = {
      orderNo: undefined,
      status: undefined
    }
    resetSearchParams()
    getData()
  }

  const openDetail = async (orderId: number) => {
    drawerVisible.value = true
    drawerLoading.value = true
    currentDetail.value = null
    try {
      currentDetail.value = await fetchOrderDetail(orderId)
    } catch (error) {
      currentDetail.value = null
      throw error
    } finally {
      drawerLoading.value = false
    }
  }

  const reloadCurrentDetail = async () => {
    if (!currentDetail.value) return
    currentDetail.value = await fetchOrderDetail(currentDetail.value.orderId)
  }

  const handleCloseOrder = async (orderId: number, orderNo: string) => {
    await ElMessageBox.confirm(`确定关闭订单 ${orderNo} 吗？`, '关闭确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })

    closingOrderId.value = orderId
    try {
      await closeOrder(orderId)
      await refreshData()
      if (drawerVisible.value && currentDetail.value?.orderId === orderId) {
        await reloadCurrentDetail()
      }
    } finally {
      closingOrderId.value = null
    }
  }
</script>

<style scoped lang="scss">
  .order-actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .order-info-cell,
  .order-product-cell,
  .item-cell__content {
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

  .order-detail {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .order-detail__header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  }

  .order-detail__title {
    font-size: 18px;
    line-height: 28px;
    color: var(--el-text-color-primary);
  }

  .order-detail__meta {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .order-amounts {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
  }

  .amount-card {
    padding: 14px 16px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    background: var(--el-fill-color-blank);

    .label {
      font-size: 12px;
      line-height: 18px;
      color: var(--el-text-color-secondary);
    }

    .value {
      margin-top: 6px;
      font-size: 18px;
      line-height: 28px;
      color: var(--el-text-color-primary);
    }
  }

  .amount-card__accent {
    color: var(--el-color-danger);
  }

  .order-items {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .order-items__title {
    font-size: 14px;
    line-height: 22px;
    color: var(--el-text-color-primary);
  }

  .item-cell {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .item-cell__image {
    width: 48px;
    height: 48px;
    border-radius: 6px;
    flex-shrink: 0;
    background: var(--el-fill-color-light);
    overflow: hidden;
  }

  .order-detail__footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    width: 100%;
  }

  @media (max-width: 900px) {
    .order-amounts {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (max-width: 640px) {
    .order-detail__header {
      flex-direction: column;
      align-items: flex-start;
    }

    .order-amounts {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
