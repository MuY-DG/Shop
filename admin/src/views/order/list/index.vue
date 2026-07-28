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
              <ElOption label="用户名称" value="USER_NAME" />
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

    <ElDrawer
      v-model="drawerVisible"
      title="订单详情"
      size="86%"
      destroy-on-close
      append-to-body
      class="order-detail-drawer"
    >
      <div v-loading="drawerLoading" class="order-detail">
        <template v-if="currentDetail">
          <div class="order-summary">
            <div class="order-summary__identity">
              <div class="order-summary__icon">
                <ElIcon><Tickets /></ElIcon>
              </div>
              <div>
                <div class="order-summary__title">普通订单</div>
                <div class="order-summary__no">
                  <span>订单号：{{ currentDetail.orderNo }}</span>
                  <ElButton
                    link
                    type="primary"
                    class="order-summary__copy"
                    aria-label="复制顶部订单号"
                    title="复制订单号"
                    @click="copyText(currentDetail.orderNo, '订单号')"
                  >
                    <ElIcon><CopyDocument /></ElIcon>
                  </ElButton>
                </div>
              </div>
            </div>
            <div class="order-summary__facts">
              <div class="summary-fact">
                <span>订单状态</span>
                <strong
                  :class="[
                    'summary-fact__status',
                    'business-status-text',
                    `business-status--${statusMap[currentDetail.status].tone}`
                  ]"
                >
                  {{ statusMap[currentDetail.status].text }}
                </strong>
              </div>
              <div class="summary-fact">
                <span>实际支付</span>
                <strong class="summary-fact__amount">
                  {{ formatPaidAmount(currentDetail) }}
                </strong>
              </div>
              <div class="summary-fact">
                <span>订单来源</span>
                <strong class="summary-fact__with-icon summary-fact__source">
                  <ArtSvgIcon icon="ri:wechat-fill" />
                  {{ formatSource(currentDetail.source) }}
                </strong>
              </div>
              <div class="summary-fact">
                <span>创建时间</span>
                <strong class="summary-fact__with-icon">
                  <ArtSvgIcon icon="ri:time-line" />
                  {{ formatDateTime(currentDetail.createdAt) }}
                </strong>
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
              查看售后单 {{ currentDetail.activeAfterSale.afterSaleNo }}
            </ElButton>
          </ElAlert>

          <div class="order-detail-content">
            <section class="detail-card detail-card--basic">
              <div class="detail-card__header">
                <h3>
                  <ArtSvgIcon icon="ri:file-list-3-line" />
                  <span>订单基本信息</span>
                </h3>
              </div>
              <dl class="detail-facts detail-facts--basic">
                <div class="detail-fact detail-fact--wide detail-fact--order-number">
                  <dt>订单编号</dt>
                  <dd>
                    <span class="detail-fact__mono">{{ currentDetail.orderNo }}</span>
                    <ElButton
                      link
                      type="primary"
                      class="copy-button"
                      aria-label="复制订单编号"
                      @click="copyText(currentDetail.orderNo, '订单编号')"
                    >
                      <ElIcon><CopyDocument /></ElIcon>
                      复制
                    </ElButton>
                  </dd>
                </div>
                <div class="detail-fact">
                  <dt>下单时间</dt>
                  <dd>{{ formatDateTime(currentDetail.createdAt) }}</dd>
                </div>
                <div v-if="currentDetail.paidAt" class="detail-fact">
                  <dt>支付时间</dt>
                  <dd>{{ formatDateTime(currentDetail.paidAt) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>订单来源</dt>
                  <dd>{{ formatSource(currentDetail.source) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>订单类型</dt>
                  <dd>普通订单</dd>
                </div>
                <div class="detail-fact">
                  <dt>支付状态</dt>
                  <dd>
                    {{ formatPaymentStatus(currentDetail.paymentStatus, currentDetail.status) }}
                  </dd>
                </div>
                <div class="detail-fact">
                  <dt>订单状态</dt>
                  <dd>
                    <ElTag
                      :type="statusMap[currentDetail.status].type"
                      effect="light"
                      :class="[
                        'business-status-tag',
                        `business-status--${statusMap[currentDetail.status].tone}`
                      ]"
                    >
                      {{ statusMap[currentDetail.status].text }}
                    </ElTag>
                  </dd>
                </div>
                <div class="detail-fact">
                  <dt>购买用户名</dt>
                  <dd>{{ formatText(currentDetail.userNickname) }}</dd>
                </div>
                <div class="detail-fact detail-fact--wide">
                  <dt>用户 ID</dt>
                  <dd class="detail-fact__mono">{{ currentDetail.userId }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>用户手机号</dt>
                  <dd>{{ maskPhone(currentDetail.userPhone) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>划线价总额</dt>
                  <dd>{{ formatMoney(currentDetail.productOriginalAmountCent) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>商品金额</dt>
                  <dd>{{ formatMoney(currentDetail.productAmountCent) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>商品总数</dt>
                  <dd>{{ currentDetail.itemCount }} 件</dd>
                </div>
                <div class="detail-fact">
                  <dt>运费</dt>
                  <dd>{{ formatMoney(currentDetail.freightCent) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>优惠券</dt>
                  <dd>{{ currentDetail.couponName || '未使用' }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>优惠金额</dt>
                  <dd>{{ formatMoney(currentDetail.couponDiscountCent) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>订单应付</dt>
                  <dd>{{ formatMoney(currentDetail.payableAmountCent) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>实付金额</dt>
                  <dd class="detail-fact__amount">{{ formatPaidAmount(currentDetail) }}</dd>
                </div>
                <div v-if="currentDetail.refundedAmountCent > 0" class="detail-fact">
                  <dt>已退款金额</dt>
                  <dd>{{ formatMoney(currentDetail.refundedAmountCent) }}</dd>
                </div>
                <div
                  v-if="currentDetail.outTradeNo || currentDetail.merchantTradeNo"
                  class="detail-fact detail-fact--wide"
                >
                  <dt>商户订单号</dt>
                  <dd class="detail-fact__mono">
                    {{ currentDetail.outTradeNo || currentDetail.merchantTradeNo }}
                  </dd>
                </div>
                <div
                  v-if="currentDetail.transactionId || currentDetail.paymentTransactionId"
                  class="detail-fact detail-fact--wide"
                >
                  <dt>微信支付单号</dt>
                  <dd class="detail-fact__mono">
                    {{ currentDetail.transactionId || currentDetail.paymentTransactionId }}
                  </dd>
                </div>
                <div v-if="currentDetail.shippedAt" class="detail-fact">
                  <dt>发货时间</dt>
                  <dd>{{ formatDateTime(currentDetail.shippedAt) }}</dd>
                </div>
                <div v-if="currentDetail.completedAt" class="detail-fact">
                  <dt>完成时间</dt>
                  <dd>{{ formatDateTime(currentDetail.completedAt) }}</dd>
                </div>
                <div v-if="currentDetail.closeReason" class="detail-fact detail-fact--wide">
                  <dt>关闭原因</dt>
                  <dd>{{ currentDetail.closeReason }}</dd>
                </div>
                <div v-if="currentDetail.closedAt" class="detail-fact">
                  <dt>关闭时间</dt>
                  <dd>{{ formatDateTime(currentDetail.closedAt) }}</dd>
                </div>
                <div v-if="currentDetail.refundingAt" class="detail-fact">
                  <dt>退款发起时间</dt>
                  <dd>{{ formatDateTime(currentDetail.refundingAt) }}</dd>
                </div>
                <div v-if="currentDetail.refundedAt" class="detail-fact">
                  <dt>退款完成时间</dt>
                  <dd>{{ formatDateTime(currentDetail.refundedAt) }}</dd>
                </div>
              </dl>
            </section>

            <div class="detail-card-grid detail-card-grid--two">
              <section class="detail-card">
                <div class="detail-card__header">
                  <h3>
                    <ArtSvgIcon icon="ri:map-pin-user-line" />
                    <span>收货信息</span>
                  </h3>
                </div>
                <dl class="detail-facts detail-facts--compact">
                  <div class="detail-fact">
                    <dt>收货人</dt>
                    <dd>{{ formatText(currentDetail.receiverName) }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>联系电话</dt>
                    <dd>{{ maskPhone(currentDetail.receiverPhone) }}</dd>
                  </div>
                  <div class="detail-fact detail-fact--full">
                    <dt>收货地址</dt>
                    <dd>{{ formatText(currentDetail.receiverAddress) }}</dd>
                  </div>
                </dl>
              </section>

              <section class="detail-card detail-card--logistics">
                <div class="detail-card__header">
                  <h3>
                    <ArtSvgIcon icon="ri:truck-line" />
                    <span>物流信息</span>
                  </h3>
                </div>
                <template v-if="currentDetail.shipment">
                  <dl class="detail-facts detail-facts--compact">
                    <div class="detail-fact">
                      <dt>履约方式</dt>
                      <dd>{{ logisticsTypeLabel(currentDetail.shipment.logisticsType) }}</dd>
                    </div>
                    <div class="detail-fact">
                      <dt>快递公司</dt>
                      <dd>{{ formatText(currentDetail.shipment.expressCompanyName) }}</dd>
                    </div>
                    <div class="detail-fact">
                      <dt>物流单号</dt>
                      <dd>
                        <span class="detail-fact__mono">
                          {{ formatText(currentDetail.shipment.trackingNo) }}
                        </span>
                        <ElButton
                          v-if="currentDetail.shipment.trackingNo"
                          link
                          type="primary"
                          class="copy-button"
                          aria-label="复制物流单号"
                          @click="copyText(currentDetail.shipment.trackingNo, '物流单号')"
                        >
                          <ElIcon><CopyDocument /></ElIcon>
                          复制
                        </ElButton>
                      </dd>
                    </div>
                    <div class="detail-fact">
                      <dt>发货时间</dt>
                      <dd>{{ formatDateTime(currentDetail.shipment.shippedAt) }}</dd>
                    </div>
                    <div class="detail-fact detail-fact--full">
                      <dt>商品描述</dt>
                      <dd>{{ formatText(currentDetail.shipment.itemDesc) }}</dd>
                    </div>
                    <div
                      v-if="currentDetail.shipment.shipmentNote"
                      class="detail-fact detail-fact--full"
                    >
                      <dt>发货备注</dt>
                      <dd>{{ currentDetail.shipment.shipmentNote }}</dd>
                    </div>
                  </dl>
                </template>
                <ElEmpty v-else description="当前订单暂无发货信息" :image-size="64" />
              </section>
            </div>

            <section class="detail-card detail-card--products">
              <div class="detail-card__header">
                <h3>
                  <ArtSvgIcon icon="ri:shopping-bag-3-line" />
                  <span>商品信息</span>
                </h3>
              </div>
              <ElTable :data="currentDetail.items" class="detail-products-table">
                <ElTableColumn label="商品信息" min-width="300">
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
                          {{ row.productSubtitle || '暂无副标题' }}
                        </div>
                        <div class="subtitle">商品编码：{{ row.skuCode || '-' }}</div>
                      </div>
                    </div>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="单价" width="116">
                  <template #default="{ row }">
                    <div>{{ formatMoney(row.unitPriceCent) }}</div>
                    <ElTag
                      v-if="row.wholesaleTierMinQuantity"
                      size="small"
                      type="danger"
                      effect="plain"
                      class="wholesale-tag"
                    >
                      {{ row.wholesaleTierMinQuantity }} 件起批
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn prop="quantity" label="数量" width="82" />
                <ElTableColumn label="规格" min-width="132">
                  <template #default="{ row }">{{ row.specText || '-' }}</template>
                </ElTableColumn>
                <ElTableColumn label="小计" width="116" align="right">
                  <template #default="{ row }">
                    <strong>{{ formatMoney(row.lineAmountCent) }}</strong>
                  </template>
                </ElTableColumn>
              </ElTable>
              <div class="product-summary">
                <strong>共 {{ currentDetail.itemCount }} 件商品</strong>
                <div class="product-summary__amounts">
                  <span>商品金额：{{ formatMoney(currentDetail.productAmountCent) }}</span>
                  <span>优惠金额：{{ formatMoney(currentDetail.couponDiscountCent) }}</span>
                  <span>运费：{{ formatMoney(currentDetail.freightCent) }}</span>
                  <span class="product-summary__paid">
                    实付金额：<strong>{{ formatPaidAmount(currentDetail) }}</strong>
                  </span>
                </div>
              </div>
            </section>

            <section v-if="currentDetail.shipment" class="detail-card detail-card--diagnostics">
              <ElCollapse class="shipping-diagnostics">
                <ElCollapseItem title="微信发货诊断信息" name="wechat-shipping">
                  <dl class="shipping-diagnostic-grid">
                    <div class="shipping-diagnostic">
                      <dt>配送说明</dt>
                      <dd>{{ formatShipmentModeDetail(currentDetail.shipment) }}</dd>
                    </div>
                    <div class="shipping-diagnostic">
                      <dt>本地发货状态</dt>
                      <dd>
                        {{ formatLocalShipmentStatus(currentDetail.shipment.localShipmentStatus) }}
                      </dd>
                    </div>
                    <div class="shipping-diagnostic">
                      <dt>微信提供方</dt>
                      <dd>
                        {{ formatWechatProviderMode(currentDetail.shipment.wechatProviderMode) }}
                      </dd>
                    </div>
                    <div class="shipping-diagnostic">
                      <dt>微信上传状态</dt>
                      <dd>
                        {{ formatShippingUploadStatus(currentDetail.shipment.wechatUploadStatus) }}
                      </dd>
                    </div>
                    <div class="shipping-diagnostic">
                      <dt>最近尝试时间</dt>
                      <dd>{{ formatDateTime(currentDetail.shipment.lastAttemptAt) }}</dd>
                    </div>
                    <div class="shipping-diagnostic">
                      <dt>运营重试次数</dt>
                      <dd>{{ currentDetail.shipment.retryCount }}</dd>
                    </div>
                    <div class="shipping-diagnostic shipping-diagnostic--full">
                      <dt>微信错误</dt>
                      <dd>{{ formatWechatUploadError(currentDetail.shipment) }}</dd>
                    </div>
                  </dl>
                </ElCollapseItem>
              </ElCollapse>
            </section>
          </div>
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
            :type="formatRecordTimelineType(record)"
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
  import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { ElNotification } from 'element-plus'
  import { ArrowDown, CopyDocument, Tickets } from '@element-plus/icons-vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useTable } from '@/hooks/core/useTable'
  import { orderStatusGroupFromQuery } from '@/utils/business-route-query'
  import { realtimeClient, type RealtimeEvent } from '@/utils/realtime'
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
    ElButton,
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

  const routeStatusGroup = () => orderStatusGroupFromQuery(route.query.statusGroup)

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
  const activeStatusGroup = ref<Api.Order.AdminOrderStatusGroup>(routeStatusGroup() || 'ALL')
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
    { label: '退款处理中', value: 'REFUNDING', countKey: 'refunding' },
    { label: '已退款', value: 'REFUNDED', countKey: 'refunded' }
  ]

  const statusMap: Record<
    Api.Order.OrderStatus,
    {
      type: 'primary' | 'warning' | 'success' | 'info' | 'danger'
      text: string
      tone: 'pending' | 'to-ship' | 'to-receive' | 'completed' | 'closed' | 'refunding' | 'refunded'
    }
  > = {
    CREATED: { type: 'warning', text: '待付款', tone: 'pending' },
    PAYING: { type: 'warning', text: '待付款', tone: 'pending' },
    PAID: { type: 'primary', text: '待发货', tone: 'to-ship' },
    SHIPPED: { type: 'primary', text: '待收货', tone: 'to-receive' },
    COMPLETED: { type: 'success', text: '已完成', tone: 'completed' },
    CLOSED: { type: 'info', text: '已关闭', tone: 'closed' },
    REFUNDING: { type: 'warning', text: '退款处理中', tone: 'refunding' },
    REFUNDED: { type: 'success', text: '已退款', tone: 'refunded' }
  }

  const afterSaleStatusMap: Record<string, string> = {
    REQUESTED: '待审核',
    APPROVED: '退款处理中',
    REFUNDING: '退款处理中',
    REFUND_FAILED: '退款失败'
  }
  const afterSaleStatusToneMap: Record<string, string> = {
    REQUESTED: 'pending',
    APPROVED: 'refunding',
    REFUNDING: 'refunding',
    REFUND_FAILED: 'failed'
  }

  const formatAfterSaleStatus = (status: string) => afterSaleStatusMap[status] || status
  const formatAfterSaleHoldTitle = (orderStatus: Api.Order.OrderStatus) => {
    if (orderStatus === 'PAID') return '订单存在进行中售后，已暂停发货'
    if (orderStatus === 'SHIPPED') return '订单存在进行中售后，已暂停确认收货'
    return '订单存在进行中售后，退款流程处理中'
  }
  const formatAfterSaleHold = (afterSale: Api.Order.ActiveAfterSaleSummary) =>
    `售后单 ${afterSale.afterSaleNo} · 整单仅退款 · ${formatAfterSaleStatus(afterSale.status)} · ${formatMoney(afterSale.requestedAmountCent)}`

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

  const copyText = async (value: string | null | undefined, label: string) => {
    if (!value) return
    try {
      await navigator.clipboard.writeText(value)
      ElMessage.success(`${label}已复制`)
    } catch {
      ElMessage.error(`${label}复制失败`)
    }
  }

  const maskPhone = (value: string | null | undefined) => {
    if (!value) return '-'
    if (value.length < 7) return value
    return `${value.slice(0, 3)}****${value.slice(-4)}`
  }

  const formatPaidAmount = (order: Pick<Api.Order.OrderListItem, 'status' | 'paidAmountCent'>) =>
    order.status === 'CREATED' || order.status === 'PAYING'
      ? '-'
      : formatMoney(order.paidAmountCent)

  const paymentStatusLabels: Record<string, string> = {
    PREPARING: '支付准备中',
    PAYING: '支付中',
    PAID: '已支付',
    CLOSED: '已关闭'
  }

  const formatPaymentStatus = (
    paymentStatus: string | null | undefined,
    orderStatus: Api.Order.OrderStatus
  ) => {
    if (paymentStatus) return paymentStatusLabels[paymentStatus] || paymentStatus
    if (orderStatus === 'CREATED') return '未支付'
    if (orderStatus === 'PAYING') return '支付中'
    if (orderStatus === 'CLOSED') return '已关闭'
    return '已支付'
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
    AFTER_SALE_REQUESTED: '用户申请售后',
    AFTER_SALE_REJECTED: '售后审核拒绝',
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
    REFUNDING: '退款处理中',
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

  const formatRecordTimelineType = (record: Api.Order.OrderStatusLog) => {
    if (record.eventType === 'AFTER_SALE_REQUESTED') return 'warning'
    if (record.eventType === 'AFTER_SALE_REJECTED') return 'danger'
    return statusMap[record.toStatus]?.type || 'primary'
  }

  const formatStatusTransition = (record: Api.Order.OrderStatusLog) => {
    const toStatus = statusRecordLabels[record.toStatus] || record.toStatus
    if (record.eventType === 'AFTER_SALE_REQUESTED') return `${toStatus} · 售后待审核`
    if (record.eventType === 'AFTER_SALE_REJECTED') return `${toStatus} · 售后已拒绝`
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
        statusGroup: activeStatusGroup.value,
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
          prop: 'status',
          label: '订单状态',
          width: 130,
          formatter: (row) => {
            const config = statusMap[row.status]
            return h(
              ElTag,
              {
                type: config?.type || 'info',
                class: ['business-status-tag', `business-status--${config?.tone || 'closed'}`]
              },
              () => config?.text || row.status
            )
          }
        },
        {
          prop: 'activeAfterSale',
          label: '售后状态',
          width: 150,
          formatter: (row) => {
            const activeAfterSale = row.activeAfterSale
            if (!activeAfterSale) return '-'

            return h(
              ElButton,
              {
                link: true,
                class: 'after-sale-status-link',
                'aria-label': `查看售后单 ${activeAfterSale.afterSaleNo}`,
                onClick: () => openActiveAfterSale(activeAfterSale.afterSaleId)
              },
              () =>
                h(
                  ElTag,
                  {
                    type: 'warning',
                    effect: 'plain',
                    class: [
                      'business-status-tag',
                      `business-status--${afterSaleStatusToneMap[activeAfterSale.status] || 'closed'}`
                    ]
                  },
                  () => formatAfterSaleStatus(activeAfterSale.status)
                )
            )
          }
        },
        {
          prop: 'userNickname',
          label: '用户名称',
          minWidth: 140,
          formatter: (row) => row.userNickname || '-'
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
    () => [route.query.orderNo, route.query.statusGroup],
    async () => {
      const orderNo = routeOrderNo()
      const statusGroup = routeStatusGroup() || 'ALL'
      if (
        route.path !== '/trade/orders' ||
        (orderNo === searchForm.value.orderNo && statusGroup === activeStatusGroup.value)
      ) {
        return
      }
      searchForm.value = createInitialSearchForm()
      activeStatusGroup.value = statusGroup
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

  let unsubscribeOrderRealtime: (() => void) | null = null
  let realtimeOrderRefreshTimer: ReturnType<typeof setTimeout> | null = null

  const handleOrderRealtimeEvent = (event: RealtimeEvent) => {
    if (event.type !== 'ORDER_PAID') return
    const data = event.data as unknown as Api.Realtime.OrderPaidData
    ElNotification({
      title: '新订单已支付',
      message: `${data.orderNo} · ¥${(data.paidAmountCent / 100).toFixed(2)}`,
      type: 'success',
      duration: 8000
    })
    if (realtimeOrderRefreshTimer) clearTimeout(realtimeOrderRefreshTimer)
    realtimeOrderRefreshTimer = setTimeout(() => {
      realtimeOrderRefreshTimer = null
      void handleRefresh()
    }, 300)
  }

  onMounted(() => {
    void loadStatusCounts()
    unsubscribeOrderRealtime = realtimeClient.subscribe(handleOrderRealtimeEvent)
  })

  onBeforeUnmount(() => {
    unsubscribeOrderRealtime?.()
    if (realtimeOrderRefreshTimer) clearTimeout(realtimeOrderRefreshTimer)
  })

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

  const reloadCurrentDetail = (orderId: number) => loadOrderDetail(orderId)

  const openDetail = async (orderId: number) => {
    drawerVisible.value = true
    await Promise.allSettled([loadOrderDetail(orderId), loadWechatShippingCapability()])
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

  :deep(.after-sale-status-link.el-button) {
    height: auto;
    padding: 0;
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

  :global(.order-detail-drawer .el-drawer__body) {
    padding: 18px 22px 22px;
    background: var(--el-bg-color-page);
  }

  :global(.order-detail-drawer .el-drawer__footer) {
    background: var(--el-bg-color);
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .order-summary {
    display: flex;
    gap: 28px;
    align-items: center;
    justify-content: space-between;
    padding: 20px 24px;
    margin-bottom: 18px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-extra-light);
    border-radius: 10px;
    box-shadow: 0 4px 18px rgb(31 35 41 / 4%);
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
    display: flex;
    gap: 4px;
    align-items: center;
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-primary);
    white-space: nowrap;
  }

  .order-summary__copy {
    flex-shrink: 0;
    width: 20px;
    height: 20px;
    padding: 0;
    font-size: 14px;
  }

  .order-summary__facts {
    display: grid;
    flex: 1;
    grid-template-columns:
      minmax(120px, 0.8fr) minmax(120px, 0.8fr) minmax(210px, 1.25fr)
      minmax(210px, 1.25fr);
    gap: 0;
    align-self: stretch;
    max-width: 820px;
  }

  .summary-fact {
    display: flex;
    flex-direction: column;
    gap: 7px;
    align-items: center;
    justify-content: center;
    min-width: 0;
    padding: 0 16px;
    text-align: center;
    border-left: 1px solid var(--el-border-color-lighter);

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
      background: var(--el-color-warning-light-9);
      border-color: var(--el-color-warning-light-5);
    }

    .is-success {
      color: var(--el-color-success);
      background: var(--el-color-success-light-9);
      border-color: var(--el-color-success-light-5);
    }

    .is-danger {
      color: var(--el-color-danger);
      background: var(--el-color-danger-light-9);
      border-color: var(--el-color-danger-light-5);
    }

    .is-info {
      color: var(--el-text-color-secondary);
      background: var(--el-fill-color-light);
      border-color: var(--el-border-color-light);
    }
  }

  .summary-fact__status {
    width: fit-content;
    padding: 3px 10px;
    line-height: 20px !important;
    border: 1px solid transparent;
    border-radius: 999px;
  }

  .summary-fact strong.summary-fact__amount {
    font-size: 19px;
    font-weight: 700;
    line-height: 26px;
    color: var(--el-color-primary);
  }

  .summary-fact__with-icon {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    justify-content: center;
    width: 100%;
    min-width: 0;
    white-space: nowrap;

    > .art-svg-icon {
      flex-shrink: 0;
      font-size: 17px;
      color: var(--el-text-color-secondary);
    }
  }

  .summary-fact__source > .art-svg-icon {
    color: var(--el-color-success);
  }

  .aftersale-hold-alert {
    margin-bottom: 16px;

    :deep(.el-alert__content) {
      width: 100%;
    }

    :deep(.el-alert__description) {
      margin-bottom: 4px;
    }
  }

  .order-detail-content {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .detail-card {
    min-width: 0;
    padding: 0 20px 20px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-extra-light);
    border-radius: 10px;
    box-shadow: 0 4px 18px rgb(31 35 41 / 4%);
  }

  .detail-card__header {
    display: flex;
    align-items: center;
    min-height: 52px;
    margin-bottom: 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);

    h3 {
      display: inline-flex;
      gap: 8px;
      align-items: center;
      margin: 0;
      font-size: 16px;
      font-weight: 600;
      line-height: 24px;
      color: var(--el-text-color-primary);

      > .art-svg-icon {
        font-size: 19px;
        color: var(--el-color-primary);
      }
    }
  }

  .detail-card-grid {
    display: grid;
    gap: 16px;
  }

  .detail-card-grid--two {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-facts {
    display: grid;
    padding: 0;
    margin: 0;
  }

  .detail-facts--basic {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 4px 26px;
  }

  .detail-facts--compact {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
  }

  .detail-fact {
    display: grid;
    grid-template-columns: 96px minmax(0, 1fr);
    align-items: start;
    min-height: 34px;
    font-size: 14px;
    line-height: 22px;

    dt {
      color: var(--el-text-color-secondary);
      white-space: nowrap;
    }

    dd {
      display: flex;
      gap: 6px;
      align-items: center;
      min-width: 0;
      margin: 0;
      color: var(--el-text-color-primary);
      overflow-wrap: anywhere;
    }
  }

  .detail-fact--full {
    grid-column: 1 / -1;
  }

  .detail-fact--order-number {
    grid-template-columns: 96px minmax(0, 1fr);

    dd {
      align-items: flex-start;
      transform: translateY(-2px);
    }

    .detail-fact__mono {
      font-size: 12px;
      white-space: nowrap;
    }

    .copy-button {
      height: 22px;
      line-height: 22px;
      transform: translateY(-7px);
    }
  }

  .detail-fact__mono {
    min-width: 0;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 13px;
    overflow-wrap: anywhere;
  }

  .detail-fact__amount {
    font-size: 16px;
    font-weight: 600;
    color: var(--el-color-danger) !important;
  }

  .copy-button {
    flex-shrink: 0;
    gap: 3px;
    padding: 0;
    font-size: 12px;
  }

  .detail-card--logistics {
    :deep(.el-empty) {
      padding: 8px 0 14px;
    }
  }

  .shipping-diagnostics {
    border-top: 0;
    border-bottom: 0;

    :deep(.el-collapse-item__header) {
      min-height: 52px;
      font-size: 15px;
      font-weight: 600;
      color: var(--el-text-color-primary);
    }

    :deep(.el-collapse-item__wrap) {
      border-bottom: 0;
    }

    :deep(.el-collapse-item__content) {
      padding-bottom: 20px;
    }
  }

  .shipping-diagnostic-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 16px;
    padding: 12px 14px;
    margin: 0;
    background: var(--el-fill-color-extra-light);
    border-radius: 8px;
  }

  .shipping-diagnostic {
    display: grid;
    grid-template-columns: 88px minmax(0, 1fr);
    gap: 8px;
    min-width: 0;
    font-size: 13px;
    line-height: 20px;

    dt {
      color: var(--el-text-color-secondary);
      white-space: nowrap;
    }

    dd {
      min-width: 0;
      margin: 0;
      color: var(--el-text-color-primary);
      overflow-wrap: anywhere;
    }
  }

  .shipping-diagnostic--full {
    grid-column: 1 / -1;
  }

  .detail-card--products {
    padding-right: 20px;
    padding-bottom: 0;
    padding-left: 20px;

    .detail-card__header {
      margin: 0;
    }
  }

  .detail-card--diagnostics {
    padding: 0 20px;
  }

  .detail-products-table {
    --el-table-border-color: var(--el-border-color-lighter);
    --el-table-header-bg-color: var(--el-fill-color-lighter);

    :deep(.el-table__header th.el-table__cell) {
      height: 42px;
      font-weight: 500;
      color: var(--el-text-color-secondary);
    }

    :deep(.el-table__cell) {
      padding: 10px 0;
    }

    :deep(.el-table__inner-wrapper::before) {
      display: none;
    }
  }

  .wholesale-tag {
    margin-top: 4px;
  }

  .product-summary {
    display: flex;
    flex-wrap: wrap;
    gap: 14px 24px;
    align-items: center;
    justify-content: space-between;
    min-height: 58px;
    padding: 10px 0;
    color: var(--el-text-color-primary);
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .product-summary__amounts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 24px;
    align-items: center;
    justify-content: flex-end;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .product-summary__paid {
    color: var(--el-text-color-primary);

    strong {
      margin-left: 4px;
      font-size: 18px;
      color: var(--el-color-danger);
    }
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
    width: 58px;
    height: 58px;
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

  @media (width <= 960px) {
    .order-summary {
      align-items: flex-start;
    }

    .order-summary__facts {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .detail-facts--basic,
    .detail-card-grid--two {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (width <= 640px) {
    :global(.order-detail-drawer .el-drawer__body) {
      padding: 14px;
    }

    .order-summary {
      flex-direction: column;
      align-items: flex-start;
    }

    .order-summary__facts {
      grid-template-columns: minmax(0, 1fr);
      width: 100%;
    }

    .detail-facts--basic,
    .detail-card-grid--two,
    .shipping-diagnostic-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .detail-fact--full,
    .shipping-diagnostic--full {
      grid-column: auto;
    }

    .product-summary,
    .product-summary__amounts {
      align-items: flex-start;
      justify-content: flex-start;
    }
  }
</style>
