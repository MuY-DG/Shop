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

    <ElDrawer v-model="drawerVisible" title="订单详情" size="880px" destroy-on-close append-to-body>
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
            <ElDescriptions v-if="currentDetail.shipment" :column="2" border>
              <ElDescriptionsItem label="履约方式">
                {{ logisticsTypeLabel(currentDetail.shipment.logisticsType) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="配送说明">
                {{ formatShipmentModeDetail(currentDetail.shipment) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="商品描述" :span="2">
                {{ formatText(currentDetail.shipment.itemDesc) }}
              </ElDescriptionsItem>
              <template v-if="currentDetail.shipment.logisticsType === 1">
                <ElDescriptionsItem label="快递公司">
                  {{ formatText(currentDetail.shipment.expressCompanyName) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="快递公司编码">
                  {{ formatText(currentDetail.shipment.expressCompanyCode) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="快递单号" :span="2">
                  {{ formatText(currentDetail.shipment.trackingNo) }}
                </ElDescriptionsItem>
              </template>
              <ElDescriptionsItem label="发货备注">
                {{ formatText(currentDetail.shipment.shipmentNote) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="本地发货状态">
                {{ formatLocalShipmentStatus(currentDetail.shipment.localShipmentStatus) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="本地发货时间">
                {{ formatDateTime(currentDetail.shipment.shippedAt) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="微信提供方">
                {{ formatWechatProviderMode(currentDetail.shipment.wechatProviderMode) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="微信上传状态">
                <ElTag
                  v-if="currentDetail.shipment.wechatUploadStatus"
                  size="small"
                  :type="
                    shippingUploadStatusMap[currentDetail.shipment.wechatUploadStatus]?.type ||
                    'info'
                  "
                >
                  {{ formatShippingUploadStatus(currentDetail.shipment.wechatUploadStatus) }}
                </ElTag>
                <span v-else>-</span>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="本次上传时间">
                {{ formatDateTime(currentDetail.shipment.uploadTime) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="微信上传成功时间">
                {{ formatDateTime(currentDetail.shipment.wechatUploadedAt) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="最近尝试时间">
                {{ formatDateTime(currentDetail.shipment.lastAttemptAt) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="运营重试次数">
                {{ currentDetail.shipment.retryCount }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="微信错误" :span="2">
                {{ formatWechatUploadError(currentDetail.shipment) }}
              </ElDescriptionsItem>
            </ElDescriptions>
            <ElEmpty v-else description="暂无发货信息" :image-size="72" />
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
            v-if="
              currentDetail?.shipment &&
              canRetryWechatUpload(currentDetail.shipment, wechatShippingCapability)
            "
            v-auth="'order:shipping:retry'"
            type="warning"
            :loading="retryingOrderId === currentDetail?.orderId"
            :disabled="retryingOrderId !== null && retryingOrderId !== currentDetail?.orderId"
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

    <ElDialog
      v-model="shipDialogVisible"
      title="订单发货"
      width="640px"
      align-center
      :close-on-click-modal="!shipSubmitting"
      :close-on-press-escape="!shipSubmitting"
      :show-close="!shipSubmitting"
      @close="markShipDialogClosing"
      @closed="handleShipDialogClosed"
    >
      <div v-loading="shipDialogLoading">
        <ElAlert
          :title="shipCapabilityText"
          :type="shipCapabilityAlertType"
          :closable="false"
          show-icon
          class="shipping-capability"
        />
        <div v-if="wechatShippingCapability" class="shipping-capability__meta">
          <span>提供方：{{ formatWechatProviderMode(wechatShippingCapability.providerMode) }}</span>
          <span>能力状态：{{ wechatShippingCapability.state }}</span>
          <span>检查时间：{{ formatDateTime(wechatShippingCapability.checkedAt) }}</span>
          <span v-if="wechatShippingCapability.errorCode || wechatShippingCapability.errorMessage">
            安全错误：{{
              [wechatShippingCapability.errorCode, wechatShippingCapability.errorMessage]
                .filter(Boolean)
                .join(' / ')
            }}
          </span>
        </div>

        <ElForm ref="shipFormRef" :model="shipForm" :rules="shipRules" label-width="106px">
          <ElFormItem label="订单号">
            <ElInput :model-value="shipTargetOrderNo" disabled />
          </ElFormItem>
          <ElFormItem label="履约方式" prop="logisticsType">
            <ElSelect
              v-model="shipForm.logisticsType"
              placeholder="请选择履约方式"
              style="width: 100%"
              @change="handleLogisticsTypeChange"
            >
              <ElOption
                v-for="option in LOGISTICS_TYPE_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="商品描述" prop="itemDesc">
            <div class="shipping-item-desc">
              <ElInput
                v-model="shipForm.itemDesc"
                type="textarea"
                :rows="3"
                placeholder="请输入传给微信的商品描述"
                @input="shipItemDescEdited = true"
              />
              <div
                class="shipping-item-desc__counter"
                :class="{ 'is-over-limit': shipItemDescCount > 120 }"
              >
                {{ shipItemDescCount }} / 120（按 Unicode 字符计数）
              </div>
            </div>
          </ElFormItem>

          <template v-if="shipForm.logisticsType === 1">
            <ElFormItem label="快递公司" prop="expressCompanyCode">
              <div class="shipping-carrier-field">
                <ElSelect
                  v-model="shipForm.expressCompanyCode"
                  filterable
                  clearable
                  :loading="carrierLoading"
                  placeholder="请选择已缓存的快递公司"
                  style="width: 100%"
                >
                  <ElOption
                    v-for="carrier in shippingCarriers"
                    :key="carrier.deliveryId"
                    :label="carrier.deliveryName"
                    :value="carrier.deliveryId"
                  >
                    <span>{{ carrier.deliveryName }}</span>
                    <span class="shipping-carrier-field__code">{{ carrier.deliveryId }}</span>
                  </ElOption>
                </ElSelect>
                <ElButton
                  v-auth="'order:ship'"
                  link
                  type="primary"
                  :loading="carrierSyncing"
                  :disabled="!canStartCarrierSync(carrierLoading, carrierSyncing)"
                  @click="handleSyncCarriers"
                >
                  同步快递公司
                </ElButton>
              </div>
              <div class="shipping-field-help">{{ carrierSyncSummary }}</div>
            </ElFormItem>
            <ElFormItem label="快递单号" prop="trackingNo">
              <ElInput v-model="shipForm.trackingNo" maxlength="80" placeholder="请输入快递单号" />
            </ElFormItem>
            <ElFormItem label="寄件人联系方式" prop="consignorContact">
              <ElInput
                v-model="shipForm.consignorContact"
                maxlength="128"
                placeholder="可选，由后端规范化并脱敏"
              />
              <div class="shipping-field-help">{{ receiverContactHelp }}</div>
            </ElFormItem>
          </template>

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
      </div>

      <template #footer>
        <div class="dialog-footer">
          <ElButton :disabled="shipSubmitting" @click="closeShipDialog()">取消</ElButton>
          <ElButton
            type="primary"
            :loading="shipSubmitting"
            :disabled="shipDialogLoading"
            @click="handleShipOrder"
          >
            确认发货
          </ElButton>
        </div>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, reactive, ref } from 'vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useTable } from '@/hooks/core/useTable'
  import {
    closeOrder,
    fetchOrderDetail,
    fetchOrders,
    retryOrderShippingUpload,
    shipOrder
  } from '@/api/order'
  import {
    fetchWechatShippingCapability,
    fetchWechatShippingCarriers,
    syncWechatShippingCarriers
  } from '@/api/wechat-shipping'
  import {
    canLoadWechatShippingCatalog,
    canRetryWechatUpload,
    canStartCarrierSync,
    clearExpressFields,
    contextualizeRetryOutcome,
    formatOptionalDateTime as formatDateTime,
    formatShipmentModeDetail,
    formatWechatUploadError,
    itemDescLength,
    logisticsTypeLabel,
    LOGISTICS_TYPE_OPTIONS,
    shippingCapabilityMessage,
    shippingOutcomeMessage,
    suggestItemDesc,
    trimItemDesc,
    validateShippingForm
  } from './shipping-form'
  import {
    ElImage,
    ElMessage,
    ElMessageBox,
    ElTag,
    type FormInstance,
    type FormRules
  } from 'element-plus'

  defineOptions({ name: 'OrderList' })

  const { hasAuth } = useAuth()

  const drawerVisible = ref(false)
  const drawerLoading = ref(false)
  const closingOrderId = ref<number | null>(null)
  const retryingOrderId = ref<number | null>(null)
  const currentDetail = ref<Api.Order.OrderDetail | null>(null)
  const detailTargetOrderId = ref<number | null>(null)
  const detailRequestSeq = ref(0)
  const retryRequestGeneration = ref(0)
  const shipDialogVisible = ref(false)
  const shipDialogLoading = ref(false)
  const capabilityLoading = ref(false)
  const carrierLoading = ref(false)
  const shipSubmitting = ref(false)
  const carrierSyncing = ref(false)
  const shipTargetOrderId = ref<number | null>(null)
  const shipTargetOrderNo = ref('')
  const shipOrderDetail = ref<Api.Order.OrderDetail | null>(null)
  const shipItemDescEdited = ref(false)
  const shipFormRef = ref<FormInstance>()
  const shipDialogGeneration = ref(0)
  const shipDialogClosingGeneration = ref<number | null>(null)
  const capabilityRequestGeneration = ref(0)
  const carrierRequestGeneration = ref(0)
  const wechatShippingCapability = ref<Api.Order.WechatShippingCapability | null>(null)
  const shippingCarriers = ref<Api.Order.WechatDeliveryCompany[]>([])

  const shipForm = reactive<Api.Order.ShipOrderForm>({
    logisticsType: 1,
    itemDesc: '',
    expressCompanyCode: undefined,
    trackingNo: undefined,
    consignorContact: undefined,
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

  const shippingUploadStatusMap: Record<
    Api.Order.WechatShippingUploadStatus,
    { type: 'success' | 'warning' | 'info' | 'danger'; text: string }
  > = {
    SKIPPED: { type: 'info', text: '已跳过' },
    UPLOADING: { type: 'warning', text: '上传中' },
    UPLOADED: { type: 'success', text: '已上传' },
    FAILED: { type: 'danger', text: '上传失败' },
    UNAVAILABLE: { type: 'warning', text: '能力不可用' },
    UNKNOWN: { type: 'warning', text: '结果未知' }
  }

  const shipRules: FormRules<Api.Order.ShipOrderForm> = {
    logisticsType: [{ required: true, message: '请选择履约方式', trigger: 'change' }],
    itemDesc: [
      {
        validator: (_rule, value: string, callback) => {
          if (!value?.trim()) return callback(new Error('请输入商品描述'))
          if (itemDescLength(value.trim()) > 120) {
            return callback(new Error('商品描述不能超过 120 个字符'))
          }
          callback()
        },
        trigger: ['blur', 'change']
      }
    ],
    expressCompanyCode: [
      {
        validator: (_rule, value: string | undefined, callback) => {
          if (shipForm.logisticsType === 1 && !value?.trim()) {
            return callback(new Error('请选择快递公司'))
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    trackingNo: [
      {
        validator: (_rule, value: string | undefined, callback) => {
          if (shipForm.logisticsType === 1 && !value?.trim()) {
            return callback(new Error('请输入快递单号'))
          }
          callback()
        },
        trigger: ['blur', 'change']
      }
    ]
  }

  const shipItemDescCount = computed(() => itemDescLength(shipForm.itemDesc))

  const shipCapabilityText = computed(() =>
    capabilityLoading.value
      ? '正在检查微信发货能力；本地发货不受阻断'
      : wechatShippingCapability.value
        ? shippingCapabilityMessage(wechatShippingCapability.value)
        : '微信发货能力状态尚未获取；本地发货仍可保存'
  )

  const shipCapabilityAlertType = computed<'success' | 'warning' | 'info'>(() => {
    const capability = wechatShippingCapability.value
    if (!capability) return 'info'
    if (
      capability.uploadEnabled &&
      capability.providerMode === 'REAL' &&
      capability.state === 'AVAILABLE'
    ) {
      return 'success'
    }
    return capability.state === 'UNKNOWN' ? 'info' : 'warning'
  })

  const carrierSyncSummary = computed(() => {
    if (carrierLoading.value) return '正在读取已缓存的快递公司目录'
    if (shippingCarriers.value.length === 0) return '暂无已启用快递公司，可尝试同步'
    const latestSyncedAt = shippingCarriers.value
      .map((carrier) => carrier.syncedAt)
      .filter(Boolean)
      .sort()
      .at(-1)
    return `已缓存 ${shippingCarriers.value.length} 家快递公司，最近同步 ${formatDateTime(latestSyncedAt)}`
  })

  const receiverContactHelp = computed(() => {
    const receiverDigits = shipOrderDetail.value?.receiverPhone?.replace(/\D/g, '') || ''
    const receiverTail = receiverDigits.length >= 4 ? receiverDigits.slice(-4) : ''
    if (shipForm.expressCompanyCode === 'SF') {
      if (receiverTail) {
        return `顺丰将由后端使用脱敏后的收件联系方式（尾号 ${receiverTail}）；寄件人联系方式如填写也会脱敏`
      }
      return '顺丰需要至少一个有效联系方式；如订单收件手机不可用，请填写寄件人联系方式'
    }
    return '收件联系方式由后端从订单快照按需生成，不由本表单提交'
  })

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

  const formatText = (value: string | null | undefined) => value || '-'

  const formatPaymentStatus = (value: string | null | undefined) => {
    if (!value) return '-'
    return statusMap[value as Api.Order.OrderStatus]?.text || value
  }

  const formatShippingUploadStatus = (
    value: Api.Order.WechatShippingUploadStatus | null | undefined
  ) => {
    if (!value) return '-'
    return shippingUploadStatusMap[value].text
  }

  const formatLocalShipmentStatus = (value: Api.Order.ShipmentStatus | null | undefined) =>
    value === 'SHIPPED' ? '本地已发货' : formatText(value)

  const formatWechatProviderMode = (value: Api.Order.WechatProviderMode | null | undefined) => {
    const labels: Record<Api.Order.WechatProviderMode, string> = {
      REAL: '真实微信',
      MOCK: '模拟环境',
      DISABLED: '未启用',
      UNKNOWN: '未知'
    }
    return value ? labels[value] : '-'
  }

  const formatSource = (value: string | null | undefined) => {
    if (!value) return '-'
    if (value === 'CART') return '微信小程序·购物车'
    if (value === 'DIRECT') return '微信小程序·立即购买'
    if (value === 'MINI_PROGRAM') return '微信小程序（历史数据）'
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
    detailTargetOrderId.value = orderId
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
      if (requestId === detailRequestSeq.value) drawerLoading.value = false
    }
  }

  const openDetail = async (orderId: number) => {
    drawerVisible.value = true
    await Promise.allSettled([loadOrderDetail(orderId), loadWechatShippingCapability()])
  }

  const reloadCurrentDetail = async (orderId: number) => {
    await loadOrderDetail(orderId)
  }

  const isCurrentShipDialog = (generation: number, orderId: number) =>
    generation === shipDialogGeneration.value &&
    shipTargetOrderId.value === orderId &&
    shipDialogVisible.value

  const resetShipForm = (detail: Api.Order.OrderDetail | null = null) => {
    shipForm.logisticsType = 1
    shipForm.itemDesc = detail ? suggestItemDesc(detail.items) : ''
    delete shipForm.expressCompanyCode
    delete shipForm.trackingNo
    delete shipForm.consignorContact
    shipForm.shipmentNote = ''
    shipOrderDetail.value = detail
    shipItemDescEdited.value = false
    shipFormRef.value?.clearValidate()
  }

  const loadWechatShippingCapability = async () => {
    const requestGeneration = ++capabilityRequestGeneration.value
    if (!canLoadWechatShippingCatalog(hasAuth('order:ship'))) {
      wechatShippingCapability.value = null
      capabilityLoading.value = false
      return
    }
    try {
      const capability = await fetchWechatShippingCapability()
      if (requestGeneration !== capabilityRequestGeneration.value) return
      wechatShippingCapability.value = capability
    } catch {
      if (requestGeneration !== capabilityRequestGeneration.value) return
      wechatShippingCapability.value = null
    }
  }

  const openShipDialog = async (orderId: number, orderNo: string) => {
    if (!canLoadWechatShippingCatalog(hasAuth('order:ship'))) return
    const generation = ++shipDialogGeneration.value
    shipDialogClosingGeneration.value = null
    shipTargetOrderId.value = orderId
    shipTargetOrderNo.value = orderNo
    shipSubmitting.value = false
    carrierSyncing.value = false
    const cachedDetail = currentDetail.value?.orderId === orderId ? currentDetail.value : null
    resetShipForm(cachedDetail)
    wechatShippingCapability.value = null
    shippingCarriers.value = []
    shipDialogVisible.value = true
    shipDialogLoading.value = !cachedDetail
    capabilityLoading.value = true
    carrierLoading.value = true

    const capabilityGeneration = ++capabilityRequestGeneration.value
    const carrierGeneration = ++carrierRequestGeneration.value

    const detailRequest = fetchOrderDetail(orderId)
      .then((detail) => {
        if (!isCurrentShipDialog(generation, orderId)) return
        shipOrderDetail.value = detail
        if (!shipItemDescEdited.value) shipForm.itemDesc = suggestItemDesc(detail.items)
      })
      .catch(() => undefined)
      .finally(() => {
        if (!isCurrentShipDialog(generation, orderId)) return
        shipDialogLoading.value = false
      })

    const capabilityRequest = fetchWechatShippingCapability()
      .then((capability) => {
        if (
          !isCurrentShipDialog(generation, orderId) ||
          capabilityGeneration !== capabilityRequestGeneration.value
        ) {
          return
        }
        wechatShippingCapability.value = capability
      })
      .catch(() => {
        if (
          !isCurrentShipDialog(generation, orderId) ||
          capabilityGeneration !== capabilityRequestGeneration.value
        ) {
          return
        }
        wechatShippingCapability.value = null
      })
      .finally(() => {
        if (
          !isCurrentShipDialog(generation, orderId) ||
          capabilityGeneration !== capabilityRequestGeneration.value
        ) {
          return
        }
        capabilityLoading.value = false
      })

    const carrierRequest = fetchWechatShippingCarriers()
      .then((carriers) => {
        if (
          !isCurrentShipDialog(generation, orderId) ||
          carrierGeneration !== carrierRequestGeneration.value
        ) {
          return
        }
        shippingCarriers.value = carriers
      })
      .catch(() => {
        if (
          !isCurrentShipDialog(generation, orderId) ||
          carrierGeneration !== carrierRequestGeneration.value
        ) {
          return
        }
        shippingCarriers.value = []
      })
      .finally(() => {
        if (
          !isCurrentShipDialog(generation, orderId) ||
          carrierGeneration !== carrierRequestGeneration.value
        ) {
          return
        }
        carrierLoading.value = false
      })

    await Promise.allSettled([detailRequest, capabilityRequest, carrierRequest])
  }

  const handleLogisticsTypeChange = () => {
    const cleared = clearExpressFields({ ...shipForm })
    if (cleared.logisticsType !== 1) {
      delete shipForm.expressCompanyCode
      delete shipForm.trackingNo
      delete shipForm.consignorContact
      shipFormRef.value?.clearValidate(['expressCompanyCode', 'trackingNo', 'consignorContact'])
    }
  }

  const markShipDialogClosing = () => {
    shipDialogClosingGeneration.value = shipDialogGeneration.value
  }

  const closeShipDialog = (generation: number = shipDialogGeneration.value) => {
    if (generation !== shipDialogGeneration.value) return
    shipDialogClosingGeneration.value = generation
    shipDialogVisible.value = false
  }

  const handleShipDialogClosed = () => {
    const closingGeneration = shipDialogClosingGeneration.value
    if (
      closingGeneration === null ||
      closingGeneration !== shipDialogGeneration.value ||
      shipDialogVisible.value
    ) {
      return
    }
    shipDialogGeneration.value += 1
    shipDialogClosingGeneration.value = null
    shipDialogLoading.value = false
    capabilityLoading.value = false
    carrierLoading.value = false
    shipSubmitting.value = false
    carrierSyncing.value = false
    shipTargetOrderId.value = null
    shipTargetOrderNo.value = ''
    resetShipForm()
  }

  const handleSyncCarriers = async () => {
    if (!canLoadWechatShippingCatalog(hasAuth('order:ship'))) return
    if (!canStartCarrierSync(carrierLoading.value, carrierSyncing.value)) return
    const orderId = shipTargetOrderId.value
    if (!orderId) return
    const generation = shipDialogGeneration.value
    const requestGeneration = ++carrierRequestGeneration.value
    carrierSyncing.value = true
    carrierLoading.value = true
    try {
      const carriers = await syncWechatShippingCarriers()
      if (
        !isCurrentShipDialog(generation, orderId) ||
        requestGeneration !== carrierRequestGeneration.value
      ) {
        return
      }
      shippingCarriers.value = carriers
      ElMessage.success(`已同步 ${carriers.length} 家快递公司`)
    } finally {
      if (
        isCurrentShipDialog(generation, orderId) &&
        requestGeneration === carrierRequestGeneration.value
      ) {
        carrierSyncing.value = false
        carrierLoading.value = false
      }
    }
  }

  const notifyShippingOutcome = (
    shipment: Api.Order.Shipment,
    message: string = shippingOutcomeMessage(shipment)
  ) => {
    if (shipment.wechatProviderMode === 'REAL' && shipment.wechatUploadStatus === 'UPLOADED') {
      ElMessage.success(message)
      return
    }
    ElMessage.warning({ message, duration: 6000 })
  }

  const handleShipOrder = async () => {
    if (shipSubmitting.value) return
    shipSubmitting.value = true
    const orderId = shipTargetOrderId.value
    if (!orderId) {
      shipSubmitting.value = false
      return
    }
    const generation = shipDialogGeneration.value

    try {
      shipForm.itemDesc = trimItemDesc(shipForm.itemDesc)
      const formValid = (await shipFormRef.value?.validate().catch(() => false)) ?? true
      if (!formValid) return

      const validationErrors = validateShippingForm(shipForm)
      if (validationErrors.length > 0) {
        ElMessage.warning(validationErrors[0])
        return
      }

      const payload: Api.Order.ShipOrderForm = {
        logisticsType: shipForm.logisticsType,
        itemDesc: shipForm.itemDesc
      }
      const shipmentNote = shipForm.shipmentNote?.trim()
      if (shipmentNote) payload.shipmentNote = shipmentNote
      if (shipForm.logisticsType === 1) {
        payload.expressCompanyCode = shipForm.expressCompanyCode?.trim()
        payload.trackingNo = shipForm.trackingNo?.trim()
        const consignorContact = shipForm.consignorContact?.trim()
        if (consignorContact) payload.consignorContact = consignorContact
      }
      const requestPayload = clearExpressFields(payload)
      if (!isCurrentShipDialog(generation, orderId)) return

      const shipment = await shipOrder(orderId, requestPayload)
      notifyShippingOutcome(shipment)
      const dialogStillCurrent = isCurrentShipDialog(generation, orderId)
      if (dialogStillCurrent) {
        closeShipDialog(generation)
        drawerVisible.value = true
      }

      const refreshTasks: Promise<unknown>[] = [Promise.resolve().then(() => refreshData())]
      if (dialogStillCurrent || (drawerVisible.value && currentDetail.value?.orderId === orderId)) {
        refreshTasks.push(loadOrderDetail(orderId))
      }
      await Promise.allSettled(refreshTasks)
    } finally {
      if (generation === shipDialogGeneration.value && shipTargetOrderId.value === orderId) {
        shipSubmitting.value = false
      }
    }
  }

  const handleRetryShippingUpload = async (orderId: number, orderNo: string) => {
    if (retryingOrderId.value !== null) return
    const requestGeneration = ++retryRequestGeneration.value
    const detailGenerationAtStart = detailRequestSeq.value
    retryingOrderId.value = orderId
    try {
      await ElMessageBox.confirm(`确定重试订单 ${orderNo} 的微信发货上传吗？`, '重试确认', {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      })
      if (requestGeneration !== retryRequestGeneration.value || retryingOrderId.value !== orderId) {
        return
      }
      const shipment = await retryOrderShippingUpload(orderId)
      const detailContextChanged =
        detailRequestSeq.value !== detailGenerationAtStart && detailTargetOrderId.value !== orderId
      notifyShippingOutcome(
        shipment,
        contextualizeRetryOutcome(shippingOutcomeMessage(shipment), orderNo, detailContextChanged)
      )
      const refreshTasks: Promise<unknown>[] = [
        Promise.resolve().then(() => refreshData()),
        loadWechatShippingCapability()
      ]
      if (drawerVisible.value && currentDetail.value?.orderId === orderId) {
        refreshTasks.push(loadOrderDetail(orderId))
      }
      await Promise.allSettled(refreshTasks)
    } finally {
      if (requestGeneration === retryRequestGeneration.value && retryingOrderId.value === orderId) {
        retryingOrderId.value = null
      }
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
    gap: 4px;
    align-items: center;
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
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
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
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;

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
    gap: 12px;
    align-items: center;
  }

  .item-cell__image {
    flex-shrink: 0;
    width: 48px;
    height: 48px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  .order-detail__footer {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
    width: 100%;
  }

  .dialog-footer {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
    width: 100%;
  }

  .shipping-capability {
    margin-bottom: 8px;
  }

  .shipping-capability__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 16px;
    margin-bottom: 18px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .shipping-item-desc,
  .shipping-carrier-field {
    width: 100%;
  }

  .shipping-item-desc__counter,
  .shipping-field-help {
    margin-top: 6px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .shipping-item-desc__counter {
    text-align: right;

    &.is-over-limit {
      color: var(--el-color-danger);
    }
  }

  .shipping-carrier-field {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  .shipping-carrier-field__code {
    float: right;
    margin-left: 16px;
    color: var(--el-text-color-secondary);
  }

  @media (width <= 900px) {
    .order-amounts {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (width <= 640px) {
    .order-detail__header {
      flex-direction: column;
      align-items: flex-start;
    }

    .order-amounts {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
