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
              v-if="row.status === 'PAID'"
              v-auth="'order:ship'"
              type="success"
              link
              @click="openShipDialog(row.orderId, row.orderNo)"
            >
              发货
            </ElButton>
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
            <ElDescriptionsItem label="支付状态">
              {{ formatPaymentStatus(currentDetail.paymentStatus || currentDetail.status) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="商户订单号">
              {{ formatText(currentDetail.outTradeNo || currentDetail.merchantTradeNo) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="微信支付单号">
              {{ formatText(currentDetail.transactionId || currentDetail.paymentTransactionId) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="支付时间">
              {{ formatDateTime(currentDetail.paidAt) }}
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

          <div class="order-section">
            <div class="order-section__title">发货信息</div>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="快递公司">
                {{ formatText(currentDetail.shipment?.expressCompany) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="快递单号">
                {{ formatText(currentDetail.shipment?.trackingNo) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="发货备注">
                {{ formatText(currentDetail.shipment?.shipmentNote) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="发货时间">
                {{ formatDateTime(currentDetail.shipment?.shippedAt) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="微信上传状态">
                <ElTag
                  v-if="currentDetail.shipment?.wechatUploadStatus"
                  size="small"
                  :type="shippingUploadStatusMap[currentDetail.shipment.wechatUploadStatus]?.type || 'info'"
                >
                  {{ formatShippingUploadStatus(currentDetail.shipment.wechatUploadStatus) }}
                </ElTag>
                <span v-else>-</span>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="重试次数">
                {{ currentDetail.shipment?.retryCount ?? '-' }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="微信错误" :span="2">
                {{ formatWechatUploadError(currentDetail.shipment) }}
              </ElDescriptionsItem>
            </ElDescriptions>
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
            v-if="currentDetail?.status === 'PAID'"
            v-auth="'order:ship'"
            type="success"
            @click="openShipDialog(currentDetail.orderId, currentDetail.orderNo)"
          >
            发货
          </ElButton>
          <ElButton
            v-if="currentDetail?.shipment?.wechatUploadStatus === 'FAILED'"
            v-auth="'order:shipping:retry'"
            type="warning"
            :loading="retryingOrderId === currentDetail?.orderId"
            @click="handleRetryShippingUpload(currentDetail.orderId, currentDetail.orderNo)"
          >
            重试微信上传
          </ElButton>
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

    <ElDialog v-model="shipDialogVisible" title="订单发货" width="520px" align-center>
      <ElForm ref="shipFormRef" :model="shipForm" :rules="shipRules" label-width="92px">
        <ElFormItem label="订单号">
          <ElInput :model-value="shipTargetOrderNo" disabled />
        </ElFormItem>
        <ElFormItem label="快递公司" prop="expressCompany">
          <ElInput v-model="shipForm.expressCompany" maxlength="80" placeholder="请输入快递公司" />
        </ElFormItem>
        <ElFormItem label="快递单号" prop="trackingNo">
          <ElInput v-model="shipForm.trackingNo" maxlength="80" placeholder="请输入快递单号" />
        </ElFormItem>
        <ElFormItem label="发货备注" prop="shipmentNote">
          <ElInput
            v-model="shipForm.shipmentNote"
            type="textarea"
            maxlength="255"
            show-word-limit
            :rows="3"
            placeholder="可选，给运营记录使用"
          />
        </ElFormItem>
      </ElForm>

      <template #footer>
        <div class="dialog-footer">
          <ElButton @click="shipDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="shipSubmitting" @click="handleShipOrder">确认发货</ElButton>
        </div>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, reactive, ref } from 'vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useTable } from '@/hooks/core/useTable'
  import { closeOrder, fetchOrderDetail, fetchOrders, retryOrderShippingUpload, shipOrder } from '@/api/order'
  import { ElImage, ElMessageBox, ElTag, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'OrderList' })

  const drawerVisible = ref(false)
  const drawerLoading = ref(false)
  const closingOrderId = ref<number | null>(null)
  const retryingOrderId = ref<number | null>(null)
  const currentDetail = ref<Api.Order.OrderDetail | null>(null)
  const detailRequestSeq = ref(0)
  const shipDialogVisible = ref(false)
  const shipSubmitting = ref(false)
  const shipTargetOrderId = ref<number | null>(null)
  const shipTargetOrderNo = ref('')
  const shipFormRef = ref<FormInstance>()

  const shipForm = reactive<Api.Order.ShipOrderForm>({
    expressCompany: '',
    trackingNo: '',
    shipmentNote: ''
  })

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
    PAYING: { type: 'warning', text: '支付中' },
    PAID: { type: 'success', text: '已支付' },
    SHIPPED: { type: 'success', text: '已发货' },
    COMPLETED: { type: 'success', text: '已完成' },
    CLOSED: { type: 'info', text: '已关闭' },
    REFUNDING: { type: 'warning', text: '退款中' },
    REFUNDED: { type: 'danger', text: '已退款' }
  }

  const shippingUploadStatusMap: Record<string, { type: 'success' | 'info' | 'danger'; text: string }> = {
    SKIPPED: { type: 'info', text: '已跳过' },
    UPLOADED: { type: 'success', text: '已上传' },
    FAILED: { type: 'danger', text: '上传失败' }
  }

  const shipRules: FormRules<Api.Order.ShipOrderForm> = {
    expressCompany: [{ required: true, message: '请输入快递公司', trigger: 'blur' }],
    trackingNo: [{ required: true, message: '请输入快递单号', trigger: 'blur' }]
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
          { label: '支付中', value: 'PAYING' },
          { label: '已支付', value: 'PAID' },
          { label: '已发货', value: 'SHIPPED' },
          { label: '已完成', value: 'COMPLETED' },
          { label: '已关闭', value: 'CLOSED' },
          { label: '退款中', value: 'REFUNDING' },
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

  const formatPaymentStatus = (value: string | null | undefined) => {
    if (!value) return '-'
    return statusMap[value as Api.Order.OrderStatus]?.text || value
  }

  const formatShippingUploadStatus = (value: string | null | undefined) => {
    if (!value) return '-'
    return shippingUploadStatusMap[value]?.text || value
  }

  const formatWechatUploadError = (shipment?: Api.Order.Shipment | null) => {
    if (!shipment) return '-'
    const code = shipment.wechatErrorCode || ''
    const message = shipment.wechatErrorMessage || ''
    if (!code && !message) return '-'
    return [code, message].filter(Boolean).join(' / ')
  }

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
            return h(ElTag, { type: config?.type || 'info' }, () => config?.text || row.status)
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

  const loadOrderDetail = async (orderId: number) => {
    const requestId = ++detailRequestSeq.value
    drawerLoading.value = true
    currentDetail.value = null
    try {
      const detail = await fetchOrderDetail(orderId)
      if (requestId !== detailRequestSeq.value) return
      currentDetail.value = detail
    } catch (error) {
      if (requestId !== detailRequestSeq.value) return
      currentDetail.value = null
      throw error
    } finally {
      if (requestId !== detailRequestSeq.value) return
      drawerLoading.value = false
    }
  }

  const openDetail = async (orderId: number) => {
    drawerVisible.value = true
    await loadOrderDetail(orderId)
  }

  const reloadCurrentDetail = async (orderId: number) => {
    await loadOrderDetail(orderId)
  }

  const resetShipForm = () => {
    shipForm.expressCompany = ''
    shipForm.trackingNo = ''
    shipForm.shipmentNote = ''
    shipFormRef.value?.clearValidate()
  }

  const openShipDialog = (orderId: number, orderNo: string) => {
    shipTargetOrderId.value = orderId
    shipTargetOrderNo.value = orderNo
    resetShipForm()
    shipDialogVisible.value = true
  }

  const handleShipOrder = async () => {
    if (!shipTargetOrderId.value) return
    await shipFormRef.value?.validate()

    shipSubmitting.value = true
    const orderId = shipTargetOrderId.value
    try {
      await shipOrder(orderId, {
        expressCompany: shipForm.expressCompany.trim(),
        trackingNo: shipForm.trackingNo.trim(),
        shipmentNote: shipForm.shipmentNote?.trim()
      })
      shipDialogVisible.value = false
      await refreshData()
      if (drawerVisible.value && currentDetail.value?.orderId === orderId) {
        await reloadCurrentDetail(orderId)
      }
    } finally {
      shipSubmitting.value = false
    }
  }

  const handleRetryShippingUpload = async (orderId: number, orderNo: string) => {
    await ElMessageBox.confirm(`确定重试订单 ${orderNo} 的微信发货上传吗？`, '重试确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })

    retryingOrderId.value = orderId
    try {
      await retryOrderShippingUpload(orderId)
      await refreshData()
      if (drawerVisible.value && currentDetail.value?.orderId === orderId) {
        await reloadCurrentDetail(orderId)
      }
    } finally {
      retryingOrderId.value = null
    }
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
        await reloadCurrentDetail(orderId)
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

  .order-section {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .order-section__title,
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

  .dialog-footer {
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
