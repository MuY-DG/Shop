<template>
  <div class="art-full-height">
    <ElCard class="order-status-card" shadow="never">
      <ElTabs
        v-model="activeStatusGroup"
        class="order-status-tabs"
        @tab-change="handleStatusChange"
      >
        <ElTabPane
          v-for="tab in statusTabs"
          :key="tab.value"
          :name="tab.value"
          :label="`${tab.label}（${statusCounts[tab.countKey]}）`"
        />
      </ElTabs>
    </ElCard>

    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      label-width="84px"
      :show-expand="true"
      :default-expanded="false"
      :style="{ marginTop: '12px' }"
      @search="handleSearch"
      @reset="handleReset"
    >
      <template #userKeyword>
        <ElInput v-model="searchForm.userKeyword" clearable placeholder="请输入用户信息">
          <template #prepend>
            <ElSelect v-model="searchForm.userSearchType" class="user-search-type">
              <ElOption label="用户 ID" value="USER_ID" />
              <ElOption label="用户手机号" value="USER_PHONE" />
            </ElSelect>
          </template>
        </ElInput>
      </template>
    </ArtSearchBar>

    <ElCard class="art-table-card" :style="{ marginTop: '12px' }">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="handleRefresh" />

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
            <ElDropdown @command="(command) => handleMoreCommand(command, row)">
              <ElButton type="primary" link>
                更多<ElIcon class="order-actions__arrow"><ArrowDown /></ElIcon>
              </ElButton>
              <template #dropdown>
                <ElDropdownMenu>
                  <ElDropdownItem command="records">订单记录</ElDropdownItem>
                </ElDropdownMenu>
              </template>
            </ElDropdown>
          </div>
        </template>
      </ArtTable>
    </ElCard>

    <ElDrawer v-model="drawerVisible" title="订单详情" size="86%" destroy-on-close append-to-body>
      <div v-loading="drawerLoading" class="order-detail">
        <template v-if="currentDetail">
          <div class="order-summary">
            <div class="order-summary__identity">
              <div class="order-summary__icon">
                <ElIcon><Tickets /></ElIcon>
              </div>
              <div>
                <div class="order-summary__title">普通订单</div>
                <div class="order-summary__no">订单号：{{ currentDetail.orderNo }}</div>
              </div>
            </div>
            <div class="order-summary__facts">
              <div class="summary-fact">
                <span>订单状态</span>
                <strong :class="`is-${statusMap[currentDetail.status].type}`">
                  {{ statusMap[currentDetail.status].text }}
                </strong>
              </div>
              <div class="summary-fact">
                <span>实际支付</span>
                <strong>{{ formatPaidAmount(currentDetail) }}</strong>
              </div>
              <div class="summary-fact">
                <span>订单来源</span>
                <strong>{{ formatSource(currentDetail.source) }}</strong>
              </div>
              <div class="summary-fact">
                <span>创建时间</span>
                <strong>{{ formatDateTime(currentDetail.createdAt) }}</strong>
              </div>
            </div>
          </div>

          <ElAlert
            v-if="currentDetail.activeAfterSale"
            :title="formatAfterSaleHoldTitle(currentDetail.status)"
            :description="formatAfterSaleHold(currentDetail.activeAfterSale)"
            type="warning"
            :closable="false"
            show-icon
            class="aftersale-hold-alert"
          >
            <ElButton
              type="warning"
              link
              @click="openActiveAfterSale(currentDetail.activeAfterSale.afterSaleId)"
            >
              查看售后单 #{{ currentDetail.activeAfterSale.afterSaleId }}
            </ElButton>
          </ElAlert>

          <ElTabs v-model="detailActiveTab" class="order-detail-tabs">
            <ElTabPane label="订单信息" name="orderInfo">
              <div class="detail-section">
                <div class="detail-section__title">用户信息</div>
                <ElDescriptions :column="3" border>
                  <ElDescriptionsItem label="用户 ID">
                    {{ currentDetail.userId }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="用户手机号" :span="2">
                    {{ maskPhone(currentDetail.userPhone) }}
                  </ElDescriptionsItem>
                </ElDescriptions>
              </div>

              <div class="detail-section">
                <div class="detail-section__title">收货信息</div>
                <ElDescriptions :column="3" border>
                  <ElDescriptionsItem label="收货人">
                    {{ formatText(currentDetail.receiverName) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="收货手机号">
                    {{ maskPhone(currentDetail.receiverPhone) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="收货地址">
                    {{ formatText(currentDetail.receiverAddress) }}
                  </ElDescriptionsItem>
                </ElDescriptions>
              </div>

              <div class="detail-section">
                <div class="detail-section__title">订单信息</div>
                <ElDescriptions :column="3" border>
                  <ElDescriptionsItem label="商品总价">
                    {{ formatMoney(currentDetail.productOriginalAmountCent) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="商品总数">
                    {{ currentDetail.itemCount }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="优惠券">
                    {{ currentDetail.couponName || '未使用' }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="商品金额">
                    {{ formatMoney(currentDetail.productAmountCent) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="优惠金额">
                    {{ formatMoney(currentDetail.couponDiscountCent) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="支付邮费">
                    {{ formatMoney(currentDetail.freightCent) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="订单应付">
                    {{ formatMoney(currentDetail.payableAmountCent) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="实际支付">
                    {{ formatPaidAmount(currentDetail) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="已退款金额">
                    {{ formatMoney(currentDetail.refundedAmountCent) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="创建时间">
                    {{ formatDateTime(currentDetail.createdAt) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="发货时间">
                    {{ formatDateTime(currentDetail.shippedAt) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="完成时间">
                    {{ formatDateTime(currentDetail.completedAt) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="关闭原因">
                    {{ formatText(currentDetail.closeReason) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="关闭时间">
                    {{ formatDateTime(currentDetail.closedAt) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="退款发起时间">
                    {{ formatDateTime(currentDetail.refundingAt) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="退款完成时间">
                    {{ formatDateTime(currentDetail.refundedAt) }}
                  </ElDescriptionsItem>
                </ElDescriptions>
              </div>

              <div class="detail-section">
                <div class="detail-section__title">支付信息</div>
                <ElDescriptions :column="3" border>
                  <ElDescriptionsItem label="支付状态">
                    {{ formatPaymentStatus(currentDetail.paymentStatus || currentDetail.status) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="商户订单号">
                    {{ formatText(currentDetail.outTradeNo || currentDetail.merchantTradeNo) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="微信支付单号">
                    {{
                      formatText(currentDetail.transactionId || currentDetail.paymentTransactionId)
                    }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="支付时间">
                    {{ formatDateTime(currentDetail.paidAt) }}
                  </ElDescriptionsItem>
                </ElDescriptions>
              </div>

              <div class="detail-section">
                <div class="detail-section__title">发货信息</div>
                <template v-if="currentDetail.shipment">
                  <ElDescriptions :column="3" border>
                    <ElDescriptionsItem label="履约方式">
                      {{ logisticsTypeLabel(currentDetail.shipment.logisticsType) }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="快递公司">
                      {{ formatText(currentDetail.shipment.expressCompanyName) }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="物流单号">
                      {{ formatText(currentDetail.shipment.trackingNo) }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="商品描述">
                      {{ formatText(currentDetail.shipment.itemDesc) }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="发货备注">
                      {{ formatText(currentDetail.shipment.shipmentNote) }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="本地发货时间">
                      {{ formatDateTime(currentDetail.shipment.shippedAt) }}
                    </ElDescriptionsItem>
                  </ElDescriptions>
                  <ElCollapse class="shipping-diagnostics">
                    <ElCollapseItem title="微信发货诊断信息" name="wechat-shipping">
                      <ElDescriptions :column="3" border>
                        <ElDescriptionsItem label="配送说明">
                          {{ formatShipmentModeDetail(currentDetail.shipment) }}
                        </ElDescriptionsItem>
                        <ElDescriptionsItem label="本地发货状态">
                          {{
                            formatLocalShipmentStatus(currentDetail.shipment.localShipmentStatus)
                          }}
                        </ElDescriptionsItem>
                        <ElDescriptionsItem label="微信提供方">
                          {{ formatWechatProviderMode(currentDetail.shipment.wechatProviderMode) }}
                        </ElDescriptionsItem>
                        <ElDescriptionsItem label="微信上传状态">
                          {{
                            formatShippingUploadStatus(currentDetail.shipment.wechatUploadStatus)
                          }}
                        </ElDescriptionsItem>
                        <ElDescriptionsItem label="最近尝试时间">
                          {{ formatDateTime(currentDetail.shipment.lastAttemptAt) }}
                        </ElDescriptionsItem>
                        <ElDescriptionsItem label="运营重试次数">
                          {{ currentDetail.shipment.retryCount }}
                        </ElDescriptionsItem>
                        <ElDescriptionsItem label="微信错误" :span="3">
                          {{ formatWechatUploadError(currentDetail.shipment) }}
                        </ElDescriptionsItem>
                      </ElDescriptions>
                    </ElCollapseItem>
                  </ElCollapse>
                </template>
                <ElEmpty v-else description="暂无发货信息" :image-size="72" />
              </div>
            </ElTabPane>

            <ElTabPane label="商品信息" name="products">
              <ElTable :data="currentDetail.items" border>
                <ElTableColumn label="商品信息" min-width="340">
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
                        <div class="subtitle">
                          {{ row.productSubtitle || '-' }} · 规格：{{ row.specText || '-' }}
                        </div>
                      </div>
                    </div>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="商品售价" width="140">
                  <template #default="{ row }">{{ formatMoney(row.unitPriceCent) }}</template>
                </ElTableColumn>
                <ElTableColumn prop="quantity" label="购买数量" width="120" />
                <ElTableColumn label="小计" width="140">
                  <template #default="{ row }">{{ formatMoney(row.lineAmountCent) }}</template>
                </ElTableColumn>
              </ElTable>
            </ElTabPane>
          </ElTabs>
        </template>
      </div>

      <template #footer>
        <div class="order-detail__footer">
          <ElButton @click="drawerVisible = false">关闭</ElButton>
          <ElButton
            v-if="currentDetail?.canShip"
            v-auth="'order:ship'"
            type="success"
            @click="openShipDialog(currentDetail.orderId, currentDetail.orderNo)"
          >
            发货
          </ElButton>
          <ElButton
            v-else-if="currentDetail?.status === 'PAID' && currentDetail.activeAfterSale"
            type="warning"
            disabled
          >
            售后处理中，暂停发货
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

    <ElDrawer
      v-model="recordsVisible"
      title="订单记录"
      size="520px"
      destroy-on-close
      append-to-body
    >
      <div v-loading="recordsLoading" class="order-records">
        <div v-if="recordsOrderNo" class="order-records__no">订单号：{{ recordsOrderNo }}</div>
        <ElTimeline v-if="statusLogs.length > 0">
          <ElTimelineItem
            v-for="record in statusLogs"
            :key="record.id"
            :timestamp="formatDateTime(record.createdAt)"
            placement="top"
            :type="statusMap[record.toStatus]?.type || 'primary'"
          >
            <div class="order-record">
              <div class="order-record__title">
                {{ formatRecordTitle(record) }}
              </div>
              <div class="order-record__meta">
                {{ formatStatusTransition(record) }} · {{ formatOperator(record) }}
              </div>
            </div>
          </ElTimelineItem>
        </ElTimeline>
        <ElEmpty v-else description="暂无订单记录" :image-size="88" />
      </div>
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
  import { computed, h, onMounted, reactive, ref, watch } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { ArrowDown, Tickets } from '@element-plus/icons-vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useTable } from '@/hooks/core/useTable'
  import {
    closeOrder,
    fetchOrderDetail,
    fetchOrderStatusCounts,
    fetchOrderStatusLogs,
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
  const route = useRoute()
  const router = useRouter()

  const drawerVisible = ref(false)
  const drawerLoading = ref(false)
  const detailActiveTab = ref<'orderInfo' | 'products'>('orderInfo')
  const recordsVisible = ref(false)
  const recordsLoading = ref(false)
  const recordsOrderNo = ref('')
  const statusLogs = ref<Api.Order.OrderStatusLog[]>([])
  const recordsRequestSeq = ref(0)
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

  interface OrderSearchForm {
    orderNo?: string
    userSearchType: Api.Order.UserSearchType
    userKeyword?: string
    receiverName?: string
    receiverPhone?: string
    createdRange?: string[]
    trackingNo?: string
  }

  const routeOrderNo = () => {
    const value = route.query.orderNo
    return typeof value === 'string' && value.trim() ? value.trim() : undefined
  }

  const createInitialSearchForm = (): OrderSearchForm => ({
    orderNo: routeOrderNo(),
    userSearchType: 'USER_ID',
    userKeyword: undefined,
    receiverName: undefined,
    receiverPhone: undefined,
    createdRange: undefined,
    trackingNo: undefined
  })

  const searchForm = ref<OrderSearchForm>(createInitialSearchForm())
  const activeStatusGroup = ref<Api.Order.AdminOrderStatusGroup>('ALL')
  const statusCounts = reactive<Api.Order.OrderStatusCounts>({
    all: 0,
    unpaid: 0,
    toShip: 0,
    toReceive: 0,
    completed: 0,
    closed: 0,
    refunding: 0,
    refunded: 0
  })
  const statusTabs: Array<{
    label: string
    value: Api.Order.AdminOrderStatusGroup
    countKey: keyof Api.Order.OrderStatusCounts
  }> = [
    { label: '全部', value: 'ALL', countKey: 'all' },
    { label: '待付款', value: 'UNPAID', countKey: 'unpaid' },
    { label: '待发货', value: 'TO_SHIP', countKey: 'toShip' },
    { label: '待收货', value: 'TO_RECEIVE', countKey: 'toReceive' },
    { label: '已完成', value: 'COMPLETED', countKey: 'completed' },
    { label: '已关闭', value: 'CLOSED', countKey: 'closed' },
    { label: '退款中', value: 'REFUNDING', countKey: 'refunding' },
    { label: '已退款', value: 'REFUNDED', countKey: 'refunded' }
  ]

  const statusMap: Record<
    Api.Order.OrderStatus,
    { type: 'warning' | 'success' | 'info' | 'danger'; text: string }
  > = {
    CREATED: { type: 'warning', text: '待付款' },
    PAYING: { type: 'warning', text: '待付款' },
    PAID: { type: 'success', text: '待发货' },
    SHIPPED: { type: 'success', text: '待收货' },
    COMPLETED: { type: 'success', text: '已完成' },
    CLOSED: { type: 'info', text: '已关闭' },
    REFUNDING: { type: 'warning', text: '退款中' },
    REFUNDED: { type: 'danger', text: '已退款' }
  }

  const afterSaleStatusMap: Record<string, string> = {
    REQUESTED: '待审核',
    APPROVED: '退款处理中',
    REFUNDING: '退款中',
    REFUND_FAILED: '退款失败'
  }

  const formatAfterSaleStatus = (status: string) => afterSaleStatusMap[status] || status
  const formatAfterSaleHoldTitle = (orderStatus: Api.Order.OrderStatus) => {
    if (orderStatus === 'PAID') return '订单存在进行中售后，已暂停发货'
    if (orderStatus === 'SHIPPED') return '订单存在进行中售后，已暂停确认收货'
    return '订单存在进行中售后，退款流程处理中'
  }
  const formatAfterSaleHold = (afterSale: Api.Order.ActiveAfterSaleSummary) =>
    `售后单 #${afterSale.afterSaleId} · 整单仅退款 · ${formatAfterSaleStatus(afterSale.status)} · ${formatMoney(afterSale.requestedAmountCent)}`

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
      span: 8,
      props: {
        clearable: true,
        placeholder: '请输入订单号'
      }
    },
    {
      label: '用户',
      key: 'userKeyword',
      type: 'input',
      span: 8
    },
    {
      label: '收货人',
      key: 'receiverName',
      type: 'input',
      span: 8,
      props: {
        clearable: true,
        placeholder: '请输入收货人'
      }
    },
    {
      label: '收货手机号',
      key: 'receiverPhone',
      type: 'input',
      span: 8,
      props: {
        clearable: true,
        placeholder: '请输入收货手机号'
      }
    },
    {
      label: '创建时间',
      key: 'createdRange',
      type: 'datetimerange',
      span: 8,
      props: {
        clearable: true,
        style: { width: '100%' },
        valueFormat: 'YYYY-MM-DD HH:mm:ss',
        startPlaceholder: '开始时间',
        endPlaceholder: '结束时间'
      }
    },
    {
      label: '物流单号',
      key: 'trackingNo',
      type: 'input',
      span: 8,
      props: {
        clearable: true,
        placeholder: '请输入物流单号'
      }
    }
  ])

  const formatMoney = (cent: number | null | undefined) => `¥${((cent ?? 0) / 100).toFixed(2)}`

  const formatText = (value: string | null | undefined) => value || '-'

  const maskPhone = (value: string | null | undefined) => {
    if (!value) return '-'
    if (value.length < 7) return value
    return `${value.slice(0, 3)}****${value.slice(-4)}`
  }

  const formatPaidAmount = (order: Pick<Api.Order.OrderListItem, 'status' | 'paidAmountCent'>) =>
    order.status === 'CREATED' || order.status === 'PAYING'
      ? '-'
      : formatMoney(order.paidAmountCent)

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

  const eventTypeLabels: Record<string, string> = {
    ORDER_CREATED: '创建订单',
    PAYMENT_STARTED: '发起支付',
    PAYMENT_SUCCEEDED: '支付成功',
    ORDER_SHIPPED: '订单发货',
    ORDER_COMPLETED: '订单完成',
    ORDER_CLOSED: '关闭订单',
    REFUND_STARTED: '发起退款',
    REFUND_RESTORED: '退款申请回退',
    REFUND_SUCCEEDED: '退款成功'
  }

  const statusRecordLabels: Record<Api.Order.OrderStatus, string> = {
    CREATED: '待付款（待发起支付）',
    PAYING: '待付款（支付处理中）',
    PAID: '待发货',
    SHIPPED: '待收货',
    COMPLETED: '已完成',
    CLOSED: '已关闭',
    REFUNDING: '退款中',
    REFUNDED: '已退款'
  }

  const operatorTypeLabels: Record<string, string> = {
    APP: '用户',
    ADMIN: '管理员',
    SYSTEM: '系统',
    WECHAT: '微信'
  }

  const formatEventType = (value: string) => eventTypeLabels[value] || value

  const formatRecordTitle = (record: Api.Order.OrderStatusLog) =>
    record.description?.trim() || formatEventType(record.eventType)

  const formatOperator = (record: Api.Order.OrderStatusLog) => {
    const operator = operatorTypeLabels[record.operatorType] || record.operatorType
    return record.operatorId ? `${operator} ${record.operatorId}` : operator
  }

  const formatStatusTransition = (record: Api.Order.OrderStatusLog) => {
    const toStatus = statusRecordLabels[record.toStatus] || record.toStatus
    if (!record.fromStatus) return toStatus
    const fromStatus = statusRecordLabels[record.fromStatus] || record.fromStatus
    return fromStatus === toStatus ? toStatus : `${fromStatus} → ${toStatus}`
  }

  const normalizeSearchParams = (
    form: OrderSearchForm = searchForm.value
  ): Api.Order.OrderSearchParams => {
    const params: Api.Order.OrderSearchParams = {
      statusGroup: activeStatusGroup.value
    }
    const assignText = (key: keyof Api.Order.OrderSearchParams, value?: string) => {
      const normalized = value?.trim()
      if (normalized) Object.assign(params, { [key]: normalized })
    }

    assignText('orderNo', form.orderNo)
    assignText('receiverName', form.receiverName)
    assignText('receiverPhone', form.receiverPhone)
    assignText('trackingNo', form.trackingNo)
    if (form.userKeyword?.trim()) {
      params.userSearchType = form.userSearchType
      params.userKeyword = form.userKeyword.trim()
    }
    if (form.createdRange?.length === 2) {
      params.createdStart = form.createdRange[0]
      params.createdEnd = form.createdRange[1]
    }
    return params
  }

  const loadStatusCounts = async () => {
    const params = normalizeSearchParams()
    delete params.statusGroup
    Object.assign(statusCounts, await fetchOrderStatusCounts(params))
  }

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchOrders,
      apiParams: {
        current: 1,
        size: 20,
        statusGroup: 'ALL',
        orderNo: searchForm.value.orderNo
      },
      columnsFactory: () => [
        {
          prop: 'orderNo',
          label: '订单号',
          minWidth: 230,
          formatter: (row) => h('span', { class: 'order-no-cell' }, row.orderNo)
        },
        {
          prop: 'receiverName',
          label: '收货人',
          minWidth: 150,
          formatter: (row) => row.receiverName || '-'
        },
        {
          prop: 'productTitle',
          label: '商品信息',
          minWidth: 330,
          formatter: (row) => {
            const image = row.displayImage || row.skuImage || row.mainImage
            return h(
              'div',
              {
                class: 'order-product-cell',
                style: {
                  display: 'flex',
                  alignItems: 'center',
                  gap: '10px',
                  minWidth: 0
                }
              },
              [
                h(ElImage, {
                  src: image || '',
                  fit: 'cover',
                  lazy: true,
                  class: 'order-product-cell__image',
                  style: {
                    width: '48px',
                    height: '48px',
                    flex: '0 0 48px',
                    overflow: 'hidden',
                    background: 'var(--el-fill-color-light)',
                    borderRadius: '6px'
                  }
                }),
                h(
                  'div',
                  {
                    class: 'order-product-cell__content',
                    style: {
                      display: 'flex',
                      flex: 1,
                      flexDirection: 'column',
                      gap: '3px',
                      minWidth: 0
                    }
                  },
                  [
                    h(
                      'div',
                      {
                        class: 'title',
                        style: {
                          overflow: 'hidden',
                          color: 'var(--el-text-color-primary)',
                          lineHeight: '20px',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap'
                        }
                      },
                      row.productTitle || '-'
                    ),
                    h(
                      'div',
                      {
                        class: 'subtitle',
                        style: {
                          overflow: 'hidden',
                          color: 'var(--el-text-color-secondary)',
                          fontSize: '12px',
                          lineHeight: '18px',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap'
                        }
                      },
                      `规格：${row.specText || '-'} · x${row.firstItemQuantity || 0} · 共 ${row.itemCount} 件`
                    )
                  ]
                )
              ]
            )
          }
        },
        {
          prop: 'paidAmountCent',
          label: '实际支付',
          width: 120,
          formatter: (row) => formatPaidAmount(row)
        },
        {
          prop: 'status',
          label: '订单状态',
          width: 150,
          formatter: (row) => {
            const config = statusMap[row.status]
            const activeAfterSale = row.activeAfterSale
            const tags = [
              h(ElTag, { type: config?.type || 'info' }, () => config?.text || row.status)
            ]
            if (activeAfterSale) {
              tags.push(
                h(
                  ElTag,
                  { type: 'warning', effect: 'plain' },
                  () => `售后${formatAfterSaleStatus(activeAfterSale.status)}`
                )
              )
            }
            return h(
              'div',
              {
                style: {
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'flex-start',
                  gap: '6px'
                }
              },
              tags
            )
          }
        },
        {
          prop: 'createdAt',
          label: '创建时间',
          width: 180,
          formatter: (row) => formatDateTime(row.createdAt)
        },
        {
          prop: 'operation',
          label: '操作',
          width: 130,
          fixed: 'right',
          useSlot: true
        }
      ]
    }
  })

  const applyCurrentSearch = async () => {
    replaceSearchParams(normalizeSearchParams())
    await Promise.all([getData(), loadStatusCounts()])
  }

  const handleSearch = async () => {
    await applyCurrentSearch()
  }

  const handleReset = async () => {
    searchForm.value = createInitialSearchForm()
    await applyCurrentSearch()
  }

  const handleStatusChange = async () => {
    replaceSearchParams(normalizeSearchParams())
    await getData()
  }

  const handleRefresh = async () => {
    await Promise.all([refreshData(), loadStatusCounts()])
  }

  watch(
    () => route.query.orderNo,
    async () => {
      const orderNo = routeOrderNo()
      if (route.path !== '/trade/orders' || !orderNo || orderNo === searchForm.value.orderNo) return
      searchForm.value = createInitialSearchForm()
      activeStatusGroup.value = 'ALL'
      await applyCurrentSearch()
    }
  )

  const openOrderRecords = async (orderId: number, orderNo: string) => {
    const requestId = ++recordsRequestSeq.value
    recordsOrderNo.value = orderNo
    statusLogs.value = []
    recordsVisible.value = true
    recordsLoading.value = true
    try {
      const records = await fetchOrderStatusLogs(orderId)
      if (requestId === recordsRequestSeq.value) statusLogs.value = records
    } finally {
      if (requestId === recordsRequestSeq.value) recordsLoading.value = false
    }
  }

  const handleMoreCommand = (command: string | number | object, row: Api.Order.OrderListItem) => {
    if (command === 'records') void openOrderRecords(row.orderId, row.orderNo)
  }

  const openActiveAfterSale = (afterSaleId: number) => {
    void router.push({ path: '/trade/after-sales', query: { afterSaleId: String(afterSaleId) } })
  }

  onMounted(() => void loadStatusCounts())

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
    detailActiveTab.value = 'orderInfo'
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
    const cachedDetail = currentDetail.value?.orderId === orderId ? currentDetail.value : null
    if (cachedDetail && !cachedDetail.canShip) {
      ElMessage.warning('订单存在进行中售后，已暂停发货')
      return
    }
    const generation = ++shipDialogGeneration.value
    shipDialogClosingGeneration.value = null
    shipTargetOrderId.value = orderId
    shipTargetOrderNo.value = orderNo
    shipSubmitting.value = false
    carrierSyncing.value = false
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

      const refreshTasks: Promise<unknown>[] = [Promise.resolve().then(() => handleRefresh())]
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
      await handleRefresh()
      if (drawerVisible.value && currentDetail.value?.orderId === orderId) {
        await reloadCurrentDetail(orderId)
      }
    } finally {
      closingOrderId.value = null
    }
  }
</script>

<style scoped lang="scss">
  .order-status-card {
    :deep(.el-card__body) {
      padding: 0 20px;
    }
  }

  .order-status-tabs {
    :deep(.el-tabs__header) {
      margin: 0;
    }

    :deep(.el-tabs__nav-wrap::after) {
      height: 1px;
    }

    :deep(.el-tabs__item) {
      height: 52px;
      padding: 0 20px;
    }
  }

  .user-search-type {
    width: 116px;
  }

  .order-actions {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .order-actions__arrow {
    margin-left: 3px;
  }

  .order-no-cell {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 13px;
    color: var(--el-text-color-primary);
  }

  .item-cell__content,
  .order-product-cell__content {
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

  .order-product-cell {
    display: flex;
    gap: 12px;
    align-items: center;
    min-width: 0;
  }

  .order-product-cell__content {
    min-width: 0;

    .title,
    .subtitle {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .order-product-cell__image {
    flex-shrink: 0;
    width: 52px;
    height: 52px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  .order-detail {
    display: flex;
    flex-direction: column;
    min-height: 360px;
  }

  .order-summary {
    display: flex;
    gap: 28px;
    align-items: center;
    justify-content: space-between;
    padding: 20px 24px;
    margin-bottom: 18px;
    background: var(--el-fill-color-lighter);
    border-radius: 10px;
  }

  .order-summary__identity {
    display: flex;
    flex-shrink: 0;
    gap: 14px;
    align-items: center;
  }

  .order-summary__icon {
    display: grid;
    place-items: center;
    width: 54px;
    height: 54px;
    font-size: 28px;
    color: white;
    background: var(--el-color-primary);
    border-radius: 10px;
  }

  .order-summary__title {
    font-size: 18px;
    font-weight: 600;
    line-height: 26px;
    color: var(--el-text-color-primary);
  }

  .order-summary__no {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .order-summary__facts {
    display: grid;
    flex: 1;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 20px;
    max-width: 820px;
  }

  .summary-fact {
    display: flex;
    flex-direction: column;
    gap: 7px;

    span {
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }

    strong {
      font-size: 15px;
      font-weight: 500;
      line-height: 22px;
      color: var(--el-text-color-primary);
    }

    .is-warning {
      color: var(--el-color-warning);
    }

    .is-success {
      color: var(--el-color-success);
    }

    .is-danger {
      color: var(--el-color-danger);
    }

    .is-info {
      color: var(--el-text-color-secondary);
    }
  }

  .order-detail-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 20px;
    }
  }

  .aftersale-hold-alert {
    margin-bottom: 18px;

    :deep(.el-alert__content) {
      width: 100%;
    }

    :deep(.el-alert__description) {
      margin-bottom: 4px;
    }
  }

  .detail-section {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-bottom: 24px;
  }

  .detail-section__title {
    padding-left: 10px;
    font-size: 15px;
    font-weight: 600;
    line-height: 20px;
    color: var(--el-text-color-primary);
    border-left: 4px solid var(--el-color-primary);
  }

  .shipping-diagnostics {
    margin-top: 12px;
  }

  .order-records__no {
    padding: 12px 14px;
    margin-bottom: 24px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  .order-record {
    padding-bottom: 6px;
  }

  .order-record__title {
    font-weight: 600;
    line-height: 22px;
    color: var(--el-text-color-primary);
  }

  .order-record__meta {
    margin-top: 4px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
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
    .order-summary {
      align-items: flex-start;
    }

    .order-summary__facts {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (width <= 640px) {
    .order-summary {
      flex-direction: column;
      align-items: flex-start;
    }

    .order-summary__facts {
      grid-template-columns: minmax(0, 1fr);
      width: 100%;
    }
  }
</style>
