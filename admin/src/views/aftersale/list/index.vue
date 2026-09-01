<template>
  <div class="art-full-height">
    <ElCard class="aftersale-status-card" shadow="never">
      <ElTabs
        v-model="activeStatusGroup"
        class="aftersale-status-tabs"
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
      <div class="aftersale-table-toolbar">
        <ArtTableHeader
          v-model:columns="columnChecks"
          :loading="loading"
          @refresh="handleRefresh"
        />
        <ElButton
          v-if="hasAuth('aftersale:return-address:write')"
          type="primary"
          plain
          @click="openReturnAddressManager"
        >
          退货地址维护
        </ElButton>
      </div>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
        <template #operation="{ row }">
          <div class="aftersale-actions">
            <ElButton type="primary" link @click="openDetail(row.id)">详情</ElButton>
            <ElDropdown @command="(command) => handleMoreCommand(command, row)">
              <ElButton type="primary" link>
                更多<ElIcon class="aftersale-actions__arrow"><ArrowDown /></ElIcon>
              </ElButton>
              <template #dropdown>
                <ElDropdownMenu>
                  <ElDropdownItem command="records">售后记录</ElDropdownItem>
                  <ElDropdownItem command="order">查看关联订单</ElDropdownItem>
                  <template v-if="row.status === 'REQUESTED' && hasAuth('aftersale:audit')">
                    <ElDropdownItem command="approve" divided>审核通过</ElDropdownItem>
                    <ElDropdownItem command="reject">审核拒绝</ElDropdownItem>
                  </template>
                </ElDropdownMenu>
              </template>
            </ElDropdown>
          </div>
        </template>
      </ArtTable>
    </ElCard>

    <ElDrawer
      v-model="detailDrawerVisible"
      title="售后详情"
      size="86%"
      destroy-on-close
      append-to-body
      class="aftersale-detail-drawer"
      @closed="handleDetailDrawerClosed"
    >
      <div v-loading="detailLoading" class="aftersale-detail">
        <template v-if="currentDetail">
          <div class="aftersale-summary">
            <div class="aftersale-summary__identity">
              <div class="aftersale-summary__icon">
                <ElIcon><Tickets /></ElIcon>
              </div>
              <div>
                <div class="aftersale-summary__title-row">
                  <div class="aftersale-summary__title">
                    售后单 {{ currentDetail.afterSaleNo }}
                  </div>
                  <ElButton
                    link
                    type="primary"
                    class="aftersale-summary__copy"
                    aria-label="复制顶部售后单号"
                    title="复制售后单号"
                    @click="copyText(currentDetail.afterSaleNo, '售后单号')"
                  >
                    <ElIcon><CopyDocument /></ElIcon>
                  </ElButton>
                </div>
                <div class="aftersale-summary__no">
                  <span>订单号：{{ currentDetail.orderNo }}</span>
                  <ElButton
                    link
                    type="primary"
                    class="aftersale-summary__copy"
                    aria-label="复制顶部订单号"
                    title="复制订单号"
                    @click="copyText(currentDetail.orderNo, '订单号')"
                  >
                    <ElIcon><CopyDocument /></ElIcon>
                  </ElButton>
                </div>
              </div>
            </div>
            <div class="aftersale-summary__facts">
              <div class="summary-fact">
                <span>售后状态</span>
                <strong
                  :class="[
                    'summary-fact__status',
                    `business-status--${statusConfig(currentDetail.status).tone}`
                  ]"
                >
                  {{ formatStatus(currentDetail.status) }}
                </strong>
              </div>
              <div class="summary-fact">
                <span>售后类型</span>
                <strong>{{ formatAfterSaleType(currentDetail.afterSaleType) }}</strong>
              </div>
              <div class="summary-fact">
                <span>申请金额</span>
                <strong class="summary-fact__amount">
                  {{ formatMoney(currentDetail.requestedAmountCent) }}
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

          <div class="aftersale-detail-content">
            <div class="aftersale-card-grid aftersale-card-grid--overview">
              <section class="detail-card detail-card--basic">
                <div class="detail-card__header">
                  <h3>
                    <ArtSvgIcon icon="ri:file-list-3-line" />
                    <span>售后基本信息</span>
                  </h3>
                </div>
                <dl class="detail-facts detail-facts--basic">
                  <div class="detail-fact detail-fact--full detail-fact--number">
                    <dt>售后单号</dt>
                    <dd>
                      <span class="detail-fact__mono">{{ currentDetail.afterSaleNo }}</span>
                      <ElButton
                        link
                        type="primary"
                        class="copy-button"
                        aria-label="复制售后单号"
                        @click="copyText(currentDetail.afterSaleNo, '售后单号')"
                      >
                        <ElIcon><CopyDocument /></ElIcon>
                        复制
                      </ElButton>
                    </dd>
                  </div>
                  <div
                    class="detail-fact detail-fact--full detail-fact--number detail-fact--order-number"
                  >
                    <dt>关联订单</dt>
                    <dd>
                      <span class="detail-fact__mono">{{ currentDetail.orderNo }}</span>
                      <ElButton
                        link
                        type="primary"
                        class="copy-button"
                        aria-label="复制关联订单号"
                        @click="copyText(currentDetail.orderNo, '订单号')"
                      >
                        <ElIcon><CopyDocument /></ElIcon>
                        复制
                      </ElButton>
                      <ElButton
                        link
                        type="primary"
                        class="detail-fact__action"
                        @click="openRelatedOrder(currentDetail.orderNo)"
                      >
                        查看订单
                      </ElButton>
                    </dd>
                  </div>
                  <div class="detail-fact">
                    <dt>售后状态</dt>
                    <dd>
                      <span
                        :class="[
                          'detail-fact__status',
                          `business-status--${statusConfig(currentDetail.status).tone}`
                        ]"
                      >
                        {{ formatStatus(currentDetail.status) }}
                      </span>
                    </dd>
                  </div>
                  <div class="detail-fact">
                    <dt>申请金额</dt>
                    <dd class="detail-fact__amount">
                      {{ formatMoney(currentDetail.requestedAmountCent) }}
                    </dd>
                  </div>
                  <div class="detail-fact">
                    <dt>申请原因</dt>
                    <dd>{{ formatText(currentDetail.reason) }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>买家备注</dt>
                    <dd>{{ currentDetail.description || '' }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>售后类型</dt>
                    <dd>{{ formatAfterSaleType(currentDetail.afterSaleType) }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>申请时间</dt>
                    <dd>{{ formatDateTime(currentDetail.createdAt) }}</dd>
                  </div>
                </dl>
              </section>

              <section class="detail-card">
                <div class="detail-card__header">
                  <h3>
                    <ArtSvgIcon icon="ri:map-pin-user-line" />
                    <span>买家与收货信息</span>
                  </h3>
                </div>
                <dl class="detail-facts detail-facts--single">
                  <div class="detail-fact">
                    <dt>买家名称</dt>
                    <dd>{{ formatText(currentDetail.userNickname) }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>用户 ID</dt>
                    <dd class="detail-fact__mono">{{ currentDetail.userId }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>收货人</dt>
                    <dd>{{ formatText(currentDetail.orderContext.receiverName) }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>联系电话</dt>
                    <dd>{{ formatText(currentDetail.orderContext.receiverPhone) }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>收货地址</dt>
                    <dd>{{ formatText(currentDetail.orderContext.receiverAddress) }}</dd>
                  </div>
                </dl>
              </section>
            </div>

            <section class="detail-card detail-card--products">
              <div class="detail-card__header">
                <h3>
                  <ArtSvgIcon icon="ri:shopping-bag-3-line" />
                  <span>本次售后商品</span>
                </h3>
                <span class="detail-card__count">
                  共 {{ currentDetail.items?.length || 0 }} 个明细
                </span>
              </div>
              <ElTable
                v-if="currentDetail.items?.length"
                :data="currentDetail.items"
                class="detail-products-table"
              >
                <ElTableColumn label="商品信息" min-width="300">
                  <template #default="{ row }">
                    <div class="item-cell">
                      <ElImage
                        :src="row.image"
                        fit="cover"
                        :preview-src-list="row.image ? [row.image] : []"
                        preview-teleported
                        class="item-cell__image"
                      >
                        <template #error>
                          <div class="item-cell__image-fallback">
                            <ArtSvgIcon icon="ri:image-line" />
                          </div>
                        </template>
                      </ElImage>
                      <div class="item-cell__content">
                        <div class="title">{{ formatText(row.productTitle) }}</div>
                        <div class="subtitle">{{ row.specText || '默认规格' }}</div>
                        <div class="subtitle">SKU ID：{{ row.skuId }}</div>
                      </div>
                    </div>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="申请数量" width="100">
                  <template #default="{ row }">{{ row.requestedQuantity }}</template>
                </ElTableColumn>
                <ElTableColumn label="批准数量" width="100">
                  <template #default="{ row }">{{ formatText(row.approvedQuantity) }}</template>
                </ElTableColumn>
                <ElTableColumn label="申请金额" width="116" align="right">
                  <template #default="{ row }">
                    <strong>{{ formatMoney(row.requestedAmountCent) }}</strong>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="批准金额" width="116" align="right">
                  <template #default="{ row }">
                    {{ formatMoneyOrDash(row.approvedAmountCent) }}
                  </template>
                </ElTableColumn>
                <ElTableColumn label="验收入库" width="100" align="right">
                  <template #default="{ row }">
                    {{ row.restockQuantity ?? '-' }}
                  </template>
                </ElTableColumn>
              </ElTable>
              <ElEmpty v-else description="暂无商品信息" :image-size="64" />
              <div class="product-summary">
                <strong>按商品与数量售后</strong>
                <div class="product-summary__amounts">
                  <span class="product-summary__refund">
                    申请退款：<strong>{{ formatMoney(currentDetail.requestedAmountCent) }}</strong>
                  </span>
                  <span v-if="currentDetail.approvedAmountCent != null">
                    批准退款：<strong>{{ formatMoney(currentDetail.approvedAmountCent) }}</strong>
                  </span>
                </div>
              </div>
            </section>

            <section v-if="currentDetail.returnInfo" class="detail-card">
              <div class="detail-card__header">
                <h3>
                  <ArtSvgIcon icon="ri:truck-line" />
                  <span>退货与验收</span>
                </h3>
              </div>
              <dl class="detail-facts detail-facts--basic">
                <div class="detail-fact detail-fact--full">
                  <dt>商家退货地址</dt>
                  <dd>{{ returnAddressText(currentDetail.returnInfo) || '-' }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>寄回截止</dt>
                  <dd>{{ formatDateTime(currentDetail.returnInfo.returnDeadlineAt) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>用户寄回</dt>
                  <dd>{{ formatDateTime(currentDetail.returnInfo.userShippedAt) }}</dd>
                </div>
                <div class="detail-fact detail-fact--full">
                  <dt>退货物流</dt>
                  <dd>
                    {{
                      [
                        currentDetail.returnInfo.deliveryCompanyName ||
                          currentDetail.returnInfo.deliveryCompanyCode,
                        currentDetail.returnInfo.trackingNo
                      ]
                        .filter(Boolean)
                        .join(' / ') || '-'
                    }}
                  </dd>
                </div>
                <div class="detail-fact">
                  <dt>商家收货</dt>
                  <dd>{{ formatDateTime(currentDetail.returnInfo.merchantReceivedAt) }}</dd>
                </div>
                <div class="detail-fact">
                  <dt>验收结果</dt>
                  <dd>{{ formatInspectionResult(currentDetail.returnInfo.inspectionResult) }}</dd>
                </div>
                <div
                  v-if="currentDetail.returnInfo.inspectionNote"
                  class="detail-fact detail-fact--full"
                >
                  <dt>验收说明</dt>
                  <dd>{{ currentDetail.returnInfo.inspectionNote }}</dd>
                </div>
              </dl>
            </section>

            <section class="detail-card detail-card--evidence">
              <div class="detail-card__header">
                <h3>
                  <ArtSvgIcon icon="ri:image-line" />
                  <span>售后凭证</span>
                </h3>
                <span v-if="currentDetail.evidenceFiles?.length" class="detail-card__count">
                  共 {{ currentDetail.evidenceFiles.length }} 个文件
                </span>
              </div>
              <div v-if="currentDetail.evidenceFiles?.length" class="evidence-list">
                <div
                  v-for="file in currentDetail.evidenceFiles"
                  :key="file.fileId"
                  class="evidence-file"
                >
                  <ElImage
                    v-if="evidencePreviewUrls[file.fileId]"
                    class="evidence-file__preview"
                    :src="evidencePreviewUrls[file.fileId]"
                    :preview-src-list="evidencePreviewList"
                    :initial-index="evidencePreviewIndex(file.fileId)"
                    fit="cover"
                    preview-teleported
                    @error="handleEvidencePreviewError(file)"
                  />
                  <div v-else-if="isPreviewableImage(file)" class="evidence-file__preview-state">
                    <span v-if="evidencePreviewLoading">图片加载中...</span>
                    <span v-else>图片加载失败</span>
                  </div>
                  <div class="evidence-file__content">
                    <div class="evidence-file__header">
                      <span>{{ formatText(file.originalFilename) }}</span>
                      <ElTag
                        size="small"
                        :type="file.visibility === 'PRIVATE' ? 'warning' : 'success'"
                      >
                        {{ file.visibility === 'PRIVATE' ? '私有凭证' : '公开文件' }}
                      </ElTag>
                    </div>
                    <div class="evidence-file__meta">
                      <span>文件 ID：{{ file.fileId }}</span>
                      <span>{{ formatMediaKind(file.mediaKind) }}</span>
                      <span>{{ formatText(file.contentType) }}</span>
                      <span>{{ formatFileSize(file.sizeBytes) }}</span>
                    </div>
                  </div>
                </div>
              </div>
              <div v-else-if="currentDetail.evidenceFileIds?.length" class="evidence-tags">
                <ElTag v-for="fileId in currentDetail.evidenceFileIds" :key="fileId" type="info">
                  文件 ID {{ fileId }}
                </ElTag>
              </div>
              <ElEmpty v-else description="暂无售后凭证" :image-size="60" />
            </section>

            <div class="aftersale-card-grid aftersale-card-grid--two">
              <section class="detail-card">
                <div class="detail-card__header">
                  <h3>
                    <ArtSvgIcon icon="ri:shield-check-line" />
                    <span>审核信息</span>
                  </h3>
                </div>
                <dl
                  v-if="
                    (currentDetail.approvedAmountCent !== null &&
                      currentDetail.approvedAmountCent !== undefined) ||
                    (currentDetail.reviewedBy !== null && currentDetail.reviewedBy !== undefined) ||
                    currentDetail.reviewedAt ||
                    currentDetail.auditNote
                  "
                  class="detail-facts detail-facts--single"
                >
                  <div
                    v-if="
                      currentDetail.approvedAmountCent !== null &&
                      currentDetail.approvedAmountCent !== undefined
                    "
                    class="detail-fact"
                  >
                    <dt>审核金额</dt>
                    <dd class="detail-fact__amount">
                      {{ formatMoneyOrDash(currentDetail.approvedAmountCent) }}
                    </dd>
                  </div>
                  <div
                    v-if="
                      currentDetail.reviewedBy !== null && currentDetail.reviewedBy !== undefined
                    "
                    class="detail-fact"
                  >
                    <dt>审核人 ID</dt>
                    <dd>{{ formatText(currentDetail.reviewedBy) }}</dd>
                  </div>
                  <div v-if="currentDetail.reviewedAt" class="detail-fact">
                    <dt>审核时间</dt>
                    <dd>{{ formatDateTime(currentDetail.reviewedAt) }}</dd>
                  </div>
                  <div class="detail-fact">
                    <dt>审核备注</dt>
                    <dd>{{ currentDetail.auditNote || '' }}</dd>
                  </div>
                </dl>
                <ElEmpty v-else description="暂未审核" :image-size="60" />
              </section>

              <section class="detail-card">
                <div class="detail-card__header">
                  <h3>
                    <ArtSvgIcon icon="ri:refund-2-line" />
                    <span>退款信息</span>
                  </h3>
                </div>
                <dl v-if="currentDetail.refundOrder" class="detail-facts detail-facts--refund">
                  <div class="detail-fact detail-fact--full detail-fact--number">
                    <dt>商户退款单号</dt>
                    <dd>
                      <span class="detail-fact__mono">
                        {{ currentDetail.refundOrder.outRefundNo }}
                      </span>
                      <ElButton
                        link
                        type="primary"
                        class="copy-button"
                        aria-label="复制商户退款单号"
                        @click="copyText(currentDetail.refundOrder.outRefundNo, '商户退款单号')"
                      >
                        <ElIcon><CopyDocument /></ElIcon>
                        复制
                      </ElButton>
                    </dd>
                  </div>
                  <div
                    v-if="currentDetail.refundOrder.refundId"
                    class="detail-fact detail-fact--full detail-fact--number"
                  >
                    <dt>微信退款单号</dt>
                    <dd>
                      <span class="detail-fact__mono">
                        {{ currentDetail.refundOrder.refundId }}
                      </span>
                      <ElButton
                        link
                        type="primary"
                        class="copy-button"
                        aria-label="复制微信退款单号"
                        @click="copyText(currentDetail.refundOrder.refundId, '微信退款单号')"
                      >
                        <ElIcon><CopyDocument /></ElIcon>
                        复制
                      </ElButton>
                    </dd>
                  </div>
                  <div class="detail-fact">
                    <dt>退款状态</dt>
                    <dd>
                      <span
                        :class="[
                          'detail-fact__status',
                          `business-status--${refundStatusConfig(currentDetail.refundOrder.status).tone}`
                        ]"
                      >
                        {{ formatRefundStatus(currentDetail.refundOrder.status) }}
                      </span>
                    </dd>
                  </div>
                  <div class="detail-fact">
                    <dt>退款金额</dt>
                    <dd class="detail-fact__amount">
                      {{ formatMoney(currentDetail.refundOrder.refundAmountCent) }}
                    </dd>
                  </div>
                  <div v-if="currentDetail.refundOrder.requestedAt" class="detail-fact">
                    <dt>发起时间</dt>
                    <dd>{{ formatDateTime(currentDetail.refundOrder.requestedAt) }}</dd>
                  </div>
                  <div v-if="currentDetail.refundOrder.successAt" class="detail-fact">
                    <dt>完成时间</dt>
                    <dd>{{ formatDateTime(currentDetail.refundOrder.successAt) }}</dd>
                  </div>
                  <div
                    v-if="formatRefundError(currentDetail.refundOrder) !== '-'"
                    class="detail-fact detail-fact--full"
                  >
                    <dt>错误信息</dt>
                    <dd>{{ formatRefundError(currentDetail.refundOrder) }}</dd>
                  </div>
                  <div
                    v-if="currentDetail.refundOrder.callbackStatus"
                    class="detail-fact detail-fact--full"
                  >
                    <dt>回调状态</dt>
                    <dd>
                      {{ formatRefundCallbackStatus(currentDetail.refundOrder.callbackStatus) }}
                    </dd>
                  </div>
                  <div
                    v-if="currentDetail.refundOrder.lastErrorCode === 'NOT_ENOUGH'"
                    class="detail-fact detail-fact--full"
                  >
                    <dt>处理建议</dt>
                    <dd
                      >请先补足微信商户余额，再点击“安全重试退款”；系统会先核对微信状态，并沿用原商户退款单号避免重复退款。</dd
                    >
                  </div>
                </dl>
                <ElEmpty v-else description="暂无退款单" :image-size="60" />
                <div v-if="currentDetail.refundAttempts?.length" class="refund-attempts">
                  <h4>微信渠道处理轨迹</h4>
                  <ElTimeline>
                    <ElTimelineItem
                      v-for="attempt in currentDetail.refundAttempts"
                      :key="attempt.id"
                      :timestamp="formatDateTime(attempt.createdAt)"
                      placement="top"
                    >
                      <div class="refund-attempt">
                        <div class="refund-attempt__title">
                          <strong>{{ formatRefundAttemptType(attempt.attemptType) }}</strong>
                          <ElTag
                            :type="attempt.result === 'SUCCESS' ? 'success' : 'danger'"
                            size="small"
                            effect="plain"
                          >
                            {{ attempt.result === 'SUCCESS' ? '成功' : '失败' }}
                          </ElTag>
                        </div>
                        <p>
                          来源：{{ formatRefundAttemptSource(attempt.source) }} · 决策：{{
                            formatRefundAttemptDecision(attempt.decision)
                          }}
                          <template v-if="attempt.providerStatus">
                            · 渠道状态：{{ attempt.providerStatus }}
                          </template>
                        </p>
                        <p v-if="attempt.providerErrorCode || attempt.providerHttpStatus">
                          微信错误：{{ attempt.providerErrorCode || '-' }}
                          <template v-if="attempt.providerHttpStatus">
                            · HTTP {{ attempt.providerHttpStatus }}
                          </template>
                        </p>
                        <p v-if="attempt.requestId" class="detail-fact__mono">
                          Request ID：{{ attempt.requestId }}
                        </p>
                      </div>
                    </ElTimelineItem>
                  </ElTimeline>
                </div>
              </section>
            </div>
          </div>
        </template>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <ElButton @click="detailDrawerVisible = false">关闭</ElButton>
          <template v-if="currentDetail">
            <ElButton
              v-if="canAdminAction(currentDetail, 'APPROVE')"
              type="success"
              @click="openAuditDialog('approve', currentDetail)"
            >
              审核通过
            </ElButton>
            <ElButton
              v-if="canAdminAction(currentDetail, 'REJECT')"
              type="danger"
              @click="openAuditDialog('reject', currentDetail)"
            >
              审核拒绝
            </ElButton>
            <ElButton
              v-if="canAdminAction(currentDetail, 'RECEIVE_RETURN')"
              type="primary"
              :loading="returnOperating"
              @click="handleReceiveReturn"
            >
              确认收到退货
            </ElButton>
            <ElButton
              v-if="canAdminAction(currentDetail, 'INSPECT_RETURN')"
              type="success"
              :loading="returnOperating"
              @click="openInspectionDialog"
            >
              退货验收
            </ElButton>
          </template>
          <template v-if="currentDetail?.refundOrder && hasAuth('aftersale:audit')">
            <ElButton
              v-if="canOperateRefund(currentDetail)"
              :loading="refundOperating"
              @click="handleRefundStatusRefresh"
            >
              刷新退款状态
            </ElButton>
            <ElButton
              v-if="canResubmitRefund(currentDetail)"
              type="warning"
              plain
              :loading="refundOperating"
              @click="openRefundOperationDialog('resubmit')"
            >
              安全重试退款
            </ElButton>
            <ElButton
              v-if="canMarkRefundManual(currentDetail)"
              type="danger"
              plain
              :loading="refundOperating"
              @click="openRefundOperationDialog('manual')"
            >
              转人工处理
            </ElButton>
            <ElButton
              v-if="canRetryClosedRefund(currentDetail)"
              type="danger"
              :loading="refundOperating"
              @click="handleClosedRefundRetry"
            >
              新单重试退款
            </ElButton>
          </template>
        </div>
      </template>
    </ElDrawer>

    <ElDrawer
      v-model="recordsDrawerVisible"
      title="售后记录"
      size="520px"
      destroy-on-close
      append-to-body
      class="aftersale-records-drawer"
    >
      <div v-loading="recordsLoading" class="aftersale-records">
        <div v-if="recordsTarget" class="aftersale-records__identity">
          <strong>售后单 {{ recordsTarget.afterSaleNo }}</strong>
          <span>订单号：{{ recordsTarget.orderNo }}</span>
        </div>
        <ElTimeline v-if="afterSaleRecords.length">
          <ElTimelineItem
            v-for="record in afterSaleRecords"
            :key="record.id"
            :timestamp="formatDateTime(record.createdAt)"
            placement="top"
            :type="formatRecordTimelineType(record)"
          >
            <div class="aftersale-record">
              <div class="aftersale-record__heading">
                <strong>{{ formatRecordTitle(record) }}</strong>
                <ElTag size="small" effect="plain" :type="formatRecordTimelineType(record)">
                  {{ formatRecordState(record) }}
                </ElTag>
              </div>
              <div class="aftersale-record__meta">{{ formatRecordOperator(record) }}</div>
            </div>
          </ElTimelineItem>
        </ElTimeline>
        <ElEmpty v-else-if="!recordsLoading" description="暂无售后记录" :image-size="88" />
      </div>
    </ElDrawer>

    <ElDialog
      v-model="auditDialogVisible"
      :title="auditMode === 'approve' ? '审核通过' : '审核拒绝'"
      width="680px"
      align-center
    >
      <ElAlert
        v-if="auditMode === 'approve'"
        :title="
          auditTarget?.afterSaleType === 'RETURN_REFUND'
            ? '请确认批准数量并选择退货地址；通过后用户进入待寄回状态。'
            : '请确认批准数量；退款金额由服务端按订单优惠分摊重新计算。'
        "
        type="info"
        :closable="false"
        show-icon
        class="audit-alert"
      />
      <ElForm ref="auditFormRef" :model="auditForm" :rules="auditRules" label-width="96px">
        <ElFormItem label="售后单">
          <ElInput
            :model-value="auditTarget ? `${auditTarget.afterSaleNo} / ${auditTarget.orderNo}` : '-'"
            disabled
          />
        </ElFormItem>
        <ElFormItem v-if="auditMode === 'approve'" label="申请金额">
          <ElInputNumber
            v-model="auditForm.approvedAmountYuan"
            :min="0.01"
            :precision="2"
            :step="1"
            controls-position="right"
            disabled
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem v-if="auditMode === 'approve'" label="批准明细" prop="itemApprovals">
          <div class="audit-item-list">
            <div
              v-for="(item, index) in auditTarget?.items || []"
              :key="item.orderItemId"
              class="audit-item"
            >
              <div class="audit-item__product">
                <strong>{{ item.productTitle }}</strong>
                <span>{{ item.specText || '默认规格' }}</span>
                <span
                  >申请 {{ item.requestedQuantity }} 件 /
                  {{ formatMoney(item.requestedAmountCent) }}</span
                >
              </div>
              <ElInputNumber
                v-model="auditForm.itemApprovals[index].approvedQuantity"
                :min="0"
                :max="item.requestedQuantity"
                :step="1"
                :precision="0"
                controls-position="right"
              />
            </div>
          </div>
        </ElFormItem>
        <ElFormItem
          v-if="auditMode === 'approve' && auditTarget?.afterSaleType === 'RETURN_REFUND'"
          label="退货地址"
          prop="returnAddressId"
        >
          <ElSelect
            v-model="auditForm.returnAddressId"
            placeholder="请选择启用的退货地址"
            filterable
            style="width: 100%"
          >
            <ElOption
              v-for="address in enabledReturnAddresses"
              :key="address.id"
              :label="returnAddressText(address)"
              :value="address.id"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          :label="auditMode === 'approve' ? '退款原因（选填）' : '拒绝原因'"
          prop="auditNote"
        >
          <ElInput
            v-model="auditForm.auditNote"
            type="textarea"
            maxlength="255"
            show-word-limit
            :rows="4"
            :placeholder="auditMode === 'approve' ? '选填，记录本次审核说明' : '请输入拒绝原因'"
          />
        </ElFormItem>
      </ElForm>

      <template #footer>
        <div class="dialog-footer">
          <ElButton @click="auditDialogVisible = false">取消</ElButton>
          <ElButton
            :type="auditMode === 'approve' ? 'success' : 'danger'"
            :loading="auditing"
            @click="submitAudit"
          >
            {{ auditMode === 'approve' ? '确认审核通过' : '确认拒绝' }}
          </ElButton>
        </div>
      </template>
    </ElDialog>

    <ElDialog v-model="inspectionDialogVisible" title="退货验收" width="640px" align-center>
      <ElAlert
        title="验收通过后将按批准金额发起退款；回库数量可以小于批准退货数量。验收拒绝不会发起退款。"
        type="info"
        :closable="false"
        show-icon
        class="audit-alert"
      />
      <ElForm label-width="92px">
        <ElFormItem label="验收结论">
          <ElRadioGroup v-model="inspectionForm.decision">
            <ElRadio value="ACCEPT">验收通过</ElRadio>
            <ElRadio value="REJECT">验收拒绝</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem v-if="inspectionForm.decision === 'ACCEPT'" label="回库明细">
          <div class="audit-item-list">
            <div
              v-for="(item, index) in inspectionItems"
              :key="item.orderItemId"
              class="audit-item"
            >
              <div class="audit-item__product">
                <strong>{{ item.productTitle }}</strong>
                <span>批准退回 {{ item.approvedQuantity }} 件</span>
              </div>
              <ElInputNumber
                v-model="inspectionForm.items[index].restockQuantity"
                :min="0"
                :max="item.approvedQuantity"
                :step="1"
                :precision="0"
                controls-position="right"
              />
            </div>
          </div>
        </ElFormItem>
        <ElFormItem :label="inspectionForm.decision === 'REJECT' ? '拒收原因' : '验收说明'">
          <ElInput
            v-model="inspectionForm.note"
            type="textarea"
            maxlength="255"
            show-word-limit
            :rows="4"
            :placeholder="inspectionForm.decision === 'REJECT' ? '拒收时必须填写原因' : '选填'"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="inspectionDialogVisible = false">取消</ElButton>
        <ElButton
          :type="inspectionForm.decision === 'ACCEPT' ? 'success' : 'danger'"
          :loading="returnOperating"
          @click="submitInspection"
        >
          确认验收
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="returnAddressDialogVisible" title="商家退货地址" width="860px" align-center>
      <div class="return-address-toolbar">
        <span>审核退货退款时，只能选择启用的地址。</span>
        <ElButton type="primary" @click="startCreateReturnAddress">新增地址</ElButton>
      </div>
      <ElTable v-loading="returnAddressLoading" :data="returnAddresses" max-height="360">
        <ElTableColumn label="联系人" prop="contactName" width="100" />
        <ElTableColumn label="电话" prop="contactPhone" width="140" />
        <ElTableColumn label="地址" min-width="310">
          <template #default="{ row }">{{ returnAddressText(row) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="row.enabled ? 'success' : 'info'">{{
              row.enabled ? '启用' : '停用'
            }}</ElTag>
            <ElTag v-if="row.defaultAddress" type="warning" class="address-default-tag">默认</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <ElButton link type="primary" @click="startEditReturnAddress(row)">编辑</ElButton>
            <ElButton
              v-if="row.enabled"
              link
              type="danger"
              @click="handleDisableReturnAddress(row)"
            >
              停用
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElForm v-if="returnAddressEditing" class="return-address-form" label-width="78px">
        <ElRow :gutter="12">
          <ElCol :span="8"
            ><ElFormItem label="联系人"
              ><ElInput v-model="returnAddressForm.contactName" /></ElFormItem
          ></ElCol>
          <ElCol :span="8"
            ><ElFormItem label="电话"
              ><ElInput v-model="returnAddressForm.contactPhone" /></ElFormItem
          ></ElCol>
          <ElCol :span="8"
            ><ElFormItem label="省"><ElInput v-model="returnAddressForm.province" /></ElFormItem
          ></ElCol>
          <ElCol :span="8"
            ><ElFormItem label="市"><ElInput v-model="returnAddressForm.city" /></ElFormItem
          ></ElCol>
          <ElCol :span="8"
            ><ElFormItem label="区县"><ElInput v-model="returnAddressForm.district" /></ElFormItem
          ></ElCol>
          <ElCol :span="8"
            ><ElFormItem label="详细地址"
              ><ElInput v-model="returnAddressForm.detailAddress" /></ElFormItem
          ></ElCol>
        </ElRow>
        <div class="return-address-form__footer">
          <ElCheckbox v-model="returnAddressForm.enabled">启用</ElCheckbox>
          <ElCheckbox v-model="returnAddressForm.defaultAddress">设为默认</ElCheckbox>
          <ElButton @click="returnAddressEditing = false">取消编辑</ElButton>
          <ElButton type="primary" :loading="returnAddressSaving" @click="saveReturnAddress"
            >保存地址</ElButton
          >
        </div>
      </ElForm>
    </ElDialog>

    <ElDialog
      v-model="refundOperationDialogVisible"
      :title="refundOperationTitle"
      width="520px"
      align-center
    >
      <ElAlert
        :title="refundOperationDescription"
        :type="refundOperationMode === 'manual' ? 'warning' : 'info'"
        :closable="false"
        show-icon
        class="audit-alert"
      />
      <ElForm
        ref="refundOperationFormRef"
        :model="refundOperationForm"
        :rules="refundOperationRules"
        label-width="96px"
      >
        <ElFormItem label="退款单">
          <ElInput
            :model-value="currentDetail?.refundOrder ? currentDetail.refundOrder.outRefundNo : '-'"
            disabled
          />
        </ElFormItem>
        <ElFormItem label="操作备注" prop="note">
          <ElInput
            v-model="refundOperationForm.note"
            type="textarea"
            maxlength="180"
            show-word-limit
            :rows="4"
            placeholder="请输入本次异常退款操作原因或核查结论"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="refundOperationDialogVisible = false">取消</ElButton>
        <ElButton
          :type="refundOperationMode === 'manual' ? 'danger' : 'primary'"
          :loading="refundOperating"
          @click="submitRefundOperation"
        >
          确认执行
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { ArrowDown, CopyDocument, Tickets } from '@element-plus/icons-vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useTable } from '@/hooks/core/useTable'
  import { afterSaleStatusGroupFromQuery } from '@/utils/business-route-query'
  import { formatLocalDateTime as formatDateTime } from '@/utils/date-time'
  import {
    realtimeClient,
    type RealtimeConnectionState,
    type RealtimeEvent
  } from '@/utils/realtime'
  import {
    approveAfterSale,
    createAfterSaleReturnAddress,
    disableAfterSaleReturnAddress,
    fetchAfterSaleDetail,
    fetchAfterSaleEvidence,
    fetchAfterSaleRecords,
    fetchAfterSaleReturnAddresses,
    fetchAfterSales,
    fetchAfterSaleStatusCounts,
    inspectAfterSaleReturn,
    markRefundManualIntervention,
    queryRefundProvider,
    receiveAfterSaleReturn,
    resubmitRefundProvider,
    retryClosedRefund,
    rejectAfterSale,
    updateAfterSaleReturnAddress
  } from '@/api/aftersale'
  import { fetchOrderDetail } from '@/api/order'
  import {
    ElMessage,
    ElMessageBox,
    ElNotification,
    ElTag,
    type FormInstance,
    type FormRules
  } from 'element-plus'
  import {
    adminAfterSaleActions,
    afterSaleAuditSuccessMessage,
    canManageReturnAddresses,
    refundOperationDefaultNote,
    refundOperationSuccessMessage,
    returnAddressText,
    type AfterSaleAdminAction
  } from './aftersale-workflow'

  defineOptions({ name: 'AfterSaleList' })

  type AuditMode = 'approve' | 'reject'
  type RefundOperationMode = 'resubmit' | 'manual'
  type TagType = 'success' | 'warning' | 'info' | 'danger'
  type StatusTone = 'pending' | 'refunding' | 'refunded' | 'rejected' | 'failed'

  interface AfterSaleSearchForm {
    afterSaleId?: string
    afterSaleNo?: string
    orderNo?: string
    userSearchType: Api.AfterSale.UserSearchType
    userKeyword?: string
    afterSaleType?: Api.AfterSale.AfterSaleType
    createdRange?: string[]
    refundNo?: string
  }

  interface AuditForm {
    approvedAmountYuan: number
    auditNote: string
    returnAddressId?: number
    itemApprovals: Api.AfterSale.AfterSaleItemApproval[]
  }

  interface AuditTarget {
    id: number
    afterSaleNo: string
    orderNo: string
    requestedAmountCent: number
    afterSaleType: string
    items: Api.AfterSale.AfterSaleItem[]
  }

  interface InspectionForm {
    decision: 'ACCEPT' | 'REJECT'
    note: string
    items: Array<{ orderItemId: number; restockQuantity: number }>
  }

  interface RefundOperationForm {
    note: string
  }

  const route = useRoute()
  const router = useRouter()
  const { hasAuth } = useAuth()
  const detailLoading = ref(false)
  const detailDrawerVisible = ref(false)
  const evidencePreviewLoading = ref(false)
  const auditDialogVisible = ref(false)
  const auditing = ref(false)
  const refundOperationDialogVisible = ref(false)
  const refundOperating = ref(false)
  const returnOperating = ref(false)
  const inspectionDialogVisible = ref(false)
  const returnAddressDialogVisible = ref(false)
  const returnAddressLoading = ref(false)
  const returnAddressSaving = ref(false)
  const returnAddressEditing = ref(false)
  const editingReturnAddressId = ref<number | null>(null)
  const recordsDrawerVisible = ref(false)
  const recordsLoading = ref(false)
  const currentDetail = ref<Api.AfterSale.Detail | null>(null)
  const pendingRelatedOrderNo = ref<string | null>(null)
  const afterSaleRecords = ref<Api.AfterSale.Record[]>([])
  const recordsTarget = ref<Pick<Api.AfterSale.Summary, 'id' | 'afterSaleNo' | 'orderNo'> | null>(
    null
  )
  const evidencePreviewUrls = ref<Record<number, string>>({})
  const auditTarget = ref<AuditTarget | null>(null)
  const auditMode = ref<AuditMode>('approve')
  const detailRequestSeq = ref(0)
  const auditFormRef = ref<FormInstance>()
  const refundOperationFormRef = ref<FormInstance>()
  const refundOperationMode = ref<RefundOperationMode>('resubmit')
  const returnAddresses = ref<Api.AfterSale.ReturnAddress[]>([])

  const routeAfterSaleId = () => {
    const value = route.query.afterSaleId
    return typeof value === 'string' && /^\d+$/.test(value) ? value : undefined
  }

  const routeStatusGroup = () => afterSaleStatusGroupFromQuery(route.query.statusGroup)

  const createInitialSearchForm = (): AfterSaleSearchForm => ({
    afterSaleId: routeAfterSaleId(),
    afterSaleNo: undefined,
    orderNo: undefined,
    userSearchType: 'USER_ID',
    userKeyword: undefined,
    afterSaleType: undefined,
    createdRange: undefined,
    refundNo: undefined
  })

  const searchForm = ref<AfterSaleSearchForm>(createInitialSearchForm())
  const activeStatusGroup = ref<Api.AfterSale.AdminAfterSaleStatusGroup>(
    routeStatusGroup() || 'ALL'
  )
  const statusCounts = reactive<Api.AfterSale.StatusCounts>({
    all: 0,
    pendingReview: 0,
    returnProcessing: 0,
    pendingInspection: 0,
    refunding: 0,
    refunded: 0,
    rejected: 0,
    refundFailed: 0
  })
  const statusTabs: Array<{
    label: string
    value: Api.AfterSale.AdminAfterSaleStatusGroup
    countKey: keyof Api.AfterSale.StatusCounts
  }> = [
    { label: '全部', value: 'ALL', countKey: 'all' },
    { label: '待审核', value: 'PENDING_REVIEW', countKey: 'pendingReview' },
    { label: '退货处理中', value: 'RETURN_PROCESSING', countKey: 'returnProcessing' },
    { label: '待验收', value: 'PENDING_INSPECTION', countKey: 'pendingInspection' },
    { label: '退款中', value: 'REFUNDING', countKey: 'refunding' },
    { label: '已完成', value: 'REFUNDED', countKey: 'refunded' },
    { label: '已关闭', value: 'REJECTED', countKey: 'rejected' },
    { label: '异常', value: 'REFUND_FAILED', countKey: 'refundFailed' }
  ]

  const auditForm = reactive<AuditForm>({
    approvedAmountYuan: 0,
    auditNote: '',
    returnAddressId: undefined,
    itemApprovals: []
  })

  const inspectionForm = reactive<InspectionForm>({
    decision: 'ACCEPT',
    note: '',
    items: []
  })

  const returnAddressForm = reactive<Api.AfterSale.ReturnAddressPayload>({
    contactName: '',
    contactPhone: '',
    province: '',
    city: '',
    district: '',
    detailAddress: '',
    enabled: true,
    defaultAddress: false,
    version: null
  })

  const enabledReturnAddresses = computed(() =>
    returnAddresses.value.filter((address) => address.enabled)
  )
  const inspectionItems = computed(() =>
    (currentDetail.value?.items || []).map((item) => ({
      orderItemId: item.orderItemId,
      productTitle: item.productTitle,
      approvedQuantity: item.approvedQuantity || 0
    }))
  )

  const refundOperationForm = reactive<RefundOperationForm>({ note: '' })

  const refundOperationCopy: Record<
    RefundOperationMode,
    { title: string; description: string; confirmation: string }
  > = {
    resubmit: {
      title: '安全重试退款',
      description: '系统会先查微信；只有微信明确没有这笔退款时，才用原退款单号重试。',
      confirmation: '确定先核对微信状态，并在退款单确实不存在时安全重试吗？'
    },
    manual: {
      title: '转人工处理',
      description: '系统会暂停自动恢复并记录异常，退款不会被标记为成功。',
      confirmation: '确定暂停自动恢复并转人工处理吗？'
    }
  }
  const refundOperationTitle = computed(() => refundOperationCopy[refundOperationMode.value].title)
  const refundOperationDescription = computed(
    () => refundOperationCopy[refundOperationMode.value].description
  )

  const statusMap: Record<string, { type: TagType; text: string; tone: StatusTone }> = {
    REQUESTED: { type: 'warning', text: '待审核', tone: 'pending' },
    APPROVED: { type: 'warning', text: '退款处理中', tone: 'refunding' },
    WAITING_RETURN: { type: 'warning', text: '待用户寄回', tone: 'pending' },
    RETURNING: { type: 'warning', text: '退货运输中', tone: 'refunding' },
    WAITING_INSPECTION: { type: 'warning', text: '待验收', tone: 'pending' },
    REJECTED: { type: 'info', text: '已拒绝', tone: 'rejected' },
    RETURN_REJECTED: { type: 'info', text: '验收拒绝', tone: 'rejected' },
    CANCELLED: { type: 'info', text: '用户已取消', tone: 'rejected' },
    REFUNDING: { type: 'warning', text: '退款处理中', tone: 'refunding' },
    REFUNDED: { type: 'success', text: '已退款', tone: 'refunded' },
    REFUND_FAILED: { type: 'danger', text: '退款失败', tone: 'failed' }
  }

  const refundStatusMap: Record<string, { type: TagType; text: string; tone: StatusTone }> = {
    PROCESSING: { type: 'warning', text: '处理中', tone: 'refunding' },
    SUCCESS: { type: 'success', text: '已成功', tone: 'refunded' },
    FAILED: { type: 'danger', text: '失败', tone: 'failed' }
  }

  const typeMap: Record<string, string> = {
    REFUND_ONLY: '仅退款',
    RETURN_REFUND: '退货退款'
  }

  const recordEventLabels: Record<string, string> = {
    AFTER_SALE_REQUESTED: '用户提交售后申请',
    AFTER_SALE_REJECTED: '管理员拒绝售后申请',
    AFTER_SALE_CANCELLED: '用户取消售后申请',
    AFTER_SALE_WAITING_RETURN: '审核通过，等待用户寄回',
    AFTER_SALE_RETURN_SHIPPED: '用户提交退货物流',
    AFTER_SALE_RETURN_SHIPMENT_UPDATED: '用户修正退货物流',
    AFTER_SALE_RETURN_RECEIVED: '商家确认收到退货',
    AFTER_SALE_RETURN_INSPECTION_ACCEPTED: '退货验收通过',
    AFTER_SALE_RETURN_INSPECTION_REJECTED: '退货验收拒绝',
    RETURN_AUTHORIZED: '审核通过，等待用户寄回',
    RETURN_SHIPMENT_SUBMITTED: '用户提交退货物流',
    RETURN_SHIPMENT_UPDATED: '用户修正退货物流',
    RETURN_RECEIVED: '商家确认收到退货',
    RETURN_INSPECTION_REJECTED: '退货验收拒绝',
    RETURN_EXPIRED: '退货寄回超时',
    REFUND_STARTED: '审核通过并发起退款',
    REFUND_RECOVERY_RESUMED: '退款恢复处理中',
    REFUND_SUCCEEDED: '退款成功',
    REFUND_RESTORED: '退款申请已回退',
    REFUND_RETRIED: '关闭退款后重新发起',
    REFUND_QUERY_REQUESTED: '请求查询渠道退款状态',
    REFUND_QUERY_COMPLETED: '渠道退款状态查询完成',
    REFUND_QUERY_FAILED: '渠道退款状态查询失败',
    REFUND_RESUBMIT_REQUESTED: '请求安全重提退款',
    REFUND_RESUBMIT_COMPLETED: '安全重提退款完成',
    REFUND_RESUBMIT_FAILED: '安全重提退款失败',
    REFUND_MANUAL_INTERVENTION: '退款转人工处理'
  }

  const recordStateLabels: Record<string, string> = {
    AFTER_SALE_REQUESTED: '待审核',
    AFTER_SALE_REJECTED: '已拒绝',
    AFTER_SALE_CANCELLED: '已取消',
    AFTER_SALE_WAITING_RETURN: '待寄回',
    AFTER_SALE_RETURN_SHIPPED: '退货中',
    AFTER_SALE_RETURN_SHIPMENT_UPDATED: '退货中',
    AFTER_SALE_RETURN_RECEIVED: '待验收',
    AFTER_SALE_RETURN_INSPECTION_ACCEPTED: '退款处理中',
    AFTER_SALE_RETURN_INSPECTION_REJECTED: '验收拒绝',
    RETURN_AUTHORIZED: '待寄回',
    RETURN_SHIPMENT_SUBMITTED: '退货中',
    RETURN_SHIPMENT_UPDATED: '退货中',
    RETURN_RECEIVED: '待验收',
    RETURN_INSPECTION_REJECTED: '验收拒绝',
    RETURN_EXPIRED: '已取消',
    REFUND_STARTED: '退款处理中',
    REFUND_RECOVERY_RESUMED: '退款处理中',
    REFUND_SUCCEEDED: '已退款',
    REFUND_RESTORED: '待处理',
    REFUND_RETRIED: '退款处理中',
    REFUND_QUERY_REQUESTED: '查询中',
    REFUND_QUERY_COMPLETED: '已查询',
    REFUND_QUERY_FAILED: '查询失败',
    REFUND_RESUBMIT_REQUESTED: '重提中',
    REFUND_RESUBMIT_COMPLETED: '已重提',
    REFUND_RESUBMIT_FAILED: '重提失败',
    REFUND_MANUAL_INTERVENTION: '人工介入'
  }

  const operatorTypeLabels: Record<string, string> = {
    APP: '用户',
    ADMIN: '管理员',
    SYSTEM: '系统',
    WECHAT: '微信'
  }

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '售后单号',
      key: 'afterSaleNo',
      type: 'input',
      span: 8,
      props: { clearable: true, placeholder: '请输入售后单号' }
    },
    {
      label: '订单号',
      key: 'orderNo',
      type: 'input',
      span: 8,
      props: { clearable: true, placeholder: '请输入订单号' }
    },
    {
      label: '用户',
      key: 'userKeyword',
      type: 'input',
      span: 8
    },
    {
      label: '售后类型',
      key: 'afterSaleType',
      type: 'select',
      span: 8,
      props: {
        clearable: true,
        placeholder: '请选择售后类型',
        options: [
          { label: '仅退款', value: 'REFUND_ONLY' },
          { label: '退货退款', value: 'RETURN_REFUND' }
        ]
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
        valueFormat: 'YYYY-MM-DDTHH:mm:ssZ',
        startPlaceholder: '开始时间',
        endPlaceholder: '结束时间'
      }
    },
    {
      label: '退款单号',
      key: 'refundNo',
      type: 'input',
      span: 8,
      props: { clearable: true, placeholder: '商户或微信退款单号' }
    }
  ])

  const evidencePreviewList = computed(() => Object.values(evidencePreviewUrls.value))

  const auditRules = computed<FormRules<AuditForm>>(() => ({
    approvedAmountYuan: [
      {
        validator: (_rule, value, callback) => {
          if (auditMode.value === 'approve' && Number(value) <= 0) {
            callback(new Error('请输入退款金额'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    itemApprovals: [
      {
        validator: (_rule, value, callback) => {
          const items = Array.isArray(value) ? value : []
          if (auditMode.value === 'approve' && !items.some((item) => item.approvedQuantity > 0)) {
            callback(new Error('至少批准一件商品'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    returnAddressId: [
      {
        validator: (_rule, value, callback) => {
          if (
            auditMode.value === 'approve' &&
            auditTarget.value?.afterSaleType === 'RETURN_REFUND' &&
            !value
          ) {
            callback(new Error('请选择退货地址'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    auditNote:
      auditMode.value === 'reject'
        ? [{ required: true, message: '请输入拒绝原因', trigger: 'blur' }]
        : []
  }))

  const refundOperationRules: FormRules<RefundOperationForm> = {
    note: [
      {
        validator: (_rule, value, callback) => {
          const note = typeof value === 'string' ? value.trim() : ''
          if (!note) {
            callback(new Error('请输入操作备注'))
            return
          }
          if (note.length > 180) {
            callback(new Error('操作备注不能超过 180 个字符'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ]
  }

  const formatMoney = (cent: number | null | undefined) => `¥${((cent ?? 0) / 100).toFixed(2)}`
  const formatMoneyOrDash = (cent: number | null | undefined) =>
    cent === null || cent === undefined ? '-' : formatMoney(cent)
  const formatText = (value?: string | number | null) =>
    value === null || value === undefined || value === '' ? '-' : String(value)
  const copyText = async (value: string | number, label: string) => {
    try {
      await navigator.clipboard.writeText(String(value))
      ElMessage.success(`${label}已复制`)
    } catch {
      ElMessage.error(`${label}复制失败`)
    }
  }
  const statusConfig = (value?: string) =>
    statusMap[value || ''] || {
      type: 'info' as const,
      text: value || '-',
      tone: 'rejected' as const
    }
  const refundStatusConfig = (value?: string) =>
    refundStatusMap[value || ''] || {
      type: 'info' as const,
      text: value || '-',
      tone: 'rejected' as const
    }
  const formatStatus = (value?: string) => statusConfig(value).text
  const formatRefundStatus = (value?: string) => refundStatusConfig(value).text
  const formatAfterSaleType = (value?: string) => (value ? typeMap[value] || value : '-')
  const formatInspectionResult = (value?: string | null) => {
    if (value === 'ACCEPT' || value === 'ACCEPTED') return '验收通过'
    if (value === 'REJECT' || value === 'REJECTED') return '验收拒绝'
    return value || '-'
  }
  const formatMediaKind = (value?: string) => {
    if (value === 'IMAGE') return '图片'
    if (value === 'VIDEO') return '视频'
    if (value === 'DOCUMENT') return '文档'
    return value || '-'
  }
  const formatFileSize = (sizeBytes?: number | null) => {
    if (!sizeBytes) return '0 B'
    if (sizeBytes < 1024) return `${sizeBytes} B`
    if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
    return `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
  }

  const formatRefundError = (refundOrder: Api.AfterSale.RefundOrder) => {
    const code = refundOrder.lastErrorCode || ''
    const message = refundOrder.lastErrorMessage || ''
    if (code === 'NOT_ENOUGH') {
      return 'NOT_ENOUGH / 微信商户余额不足，退款申请未受理'
    }
    return [code, message].filter(Boolean).join(' / ') || '-'
  }

  const formatRefundCallbackStatus = (value?: string | null) => {
    const labels: Record<string, string> = {
      PROCESSING: '微信处理中',
      SUCCESS: '微信退款成功',
      CLOSED: '微信退款关闭',
      ABNORMAL: '微信退款异常',
      REQUEST_UNKNOWN: '提交结果待确认',
      SUBMISSION_REJECTED: '微信未受理',
      MANUAL_INTERVENTION: '人工介入'
    }
    return value ? labels[value] || value : '-'
  }

  const formatRefundAttemptType = (value: string) => {
    const labels: Record<string, string> = {
      ORDER_PREFLIGHT: '支付订单核验',
      SUBMISSION: '申请退款',
      QUERY: '查询退款单',
      RESUBMISSION: '原单重提',
      DECISION: '系统处理决策'
    }
    return labels[value] || value
  }

  const formatRefundAttemptSource = (value: string) => {
    const labels: Record<string, string> = {
      ADMIN: '管理员操作',
      SYSTEM: '系统自动恢复'
    }
    return labels[value] || value
  }

  const formatRefundAttemptDecision = (value: string) => {
    const labels: Record<string, string> = {
      VERIFIED_PAID: '已确认微信支付成功',
      ACCEPTED: '微信已受理',
      QUERY_REQUIRED: '申请非 200，先查询确认',
      PROVIDER_CONFIRMED: '查询已确认渠道状态',
      NOT_FOUND: '微信退款单不存在',
      MERCHANT_ACTION_REQUIRED: '暂停自动重试，等待商户处理',
      ORIGINAL_RETRY: '使用原商户退款单号重试',
      ORIGINAL_REQUEST_ACCEPTED: '原商户退款单号已被微信受理',
      ORIGINAL_REQUEST_REQUIRED: '微信未查到退款单，可以原参数重试',
      APPLY_PROVIDER_RESULT: '按微信查询结果更新退款状态',
      QUERY_LATER: '退款仍在处理中，稍后继续查单',
      BALANCE_REQUIRED: '余额不足，等待商户补足余额',
      CONFIGURATION_FAILURE: '退款配置异常，等待商户处理',
      BUSINESS_REJECTION: '退款请求不符合业务规则，等待核查',
      USER_ACCOUNT_ABNORMAL: '用户微信账户异常，转人工处理',
      RECOVERY_SCHEDULED: '已安排安全查单恢复',
      PREFLIGHT_REJECTED: '支付状态核验未通过'
    }
    return labels[value] || value
  }

  const formatRecordTitle = (record: Api.AfterSale.Record) =>
    record.description?.trim() || recordEventLabels[record.eventType] || record.eventType

  const formatRecordState = (record: Api.AfterSale.Record) =>
    recordStateLabels[record.eventType] || formatStatus(record.toStatus)

  const formatRecordOperator = (record: Api.AfterSale.Record) => {
    const operator = operatorTypeLabels[record.operatorType] || record.operatorType
    return record.operatorId ? `操作人：${operator} ${record.operatorId}` : `操作方：${operator}`
  }

  const formatRecordTimelineType = (record: Api.AfterSale.Record): TagType => {
    if (record.eventType === 'REFUND_SUCCEEDED') return 'success'
    if (
      record.eventType === 'AFTER_SALE_REJECTED' ||
      record.eventType.endsWith('_FAILED') ||
      record.eventType === 'REFUND_MANUAL_INTERVENTION'
    ) {
      return 'danger'
    }
    if (
      record.eventType === 'AFTER_SALE_REQUESTED' ||
      record.eventType.includes('REQUESTED') ||
      record.eventType === 'REFUND_RETRIED'
    ) {
      return 'warning'
    }
    return 'info'
  }

  const normalizeSearchParams = (
    form: AfterSaleSearchForm = searchForm.value
  ): Api.AfterSale.SearchParams => {
    const params: Api.AfterSale.SearchParams = { statusGroup: activeStatusGroup.value }
    const assignText = (key: keyof Api.AfterSale.SearchParams, value?: string) => {
      const normalized = value?.trim()
      if (normalized) Object.assign(params, { [key]: normalized })
    }

    const afterSaleId = Number(form.afterSaleId?.trim())
    if (Number.isSafeInteger(afterSaleId) && afterSaleId > 0) params.afterSaleId = afterSaleId
    assignText('afterSaleNo', form.afterSaleNo)
    assignText('orderNo', form.orderNo)
    assignText('refundNo', form.refundNo)
    if (form.userKeyword?.trim()) {
      params.userSearchType = form.userSearchType
      params.userKeyword = form.userKeyword.trim()
    }
    if (form.afterSaleType) params.afterSaleType = form.afterSaleType
    if (form.createdRange?.length === 2) {
      params.createdStart = form.createdRange[0]
      params.createdEnd = form.createdRange[1]
    }
    return params
  }

  const loadStatusCounts = async () => {
    const params = normalizeSearchParams()
    delete params.statusGroup
    delete params.status
    Object.assign(statusCounts, await fetchAfterSaleStatusCounts(params))
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
      apiFn: fetchAfterSales,
      apiParams: {
        current: 1,
        size: 20,
        statusGroup: activeStatusGroup.value,
        afterSaleId: routeAfterSaleId() ? Number(routeAfterSaleId()) : undefined
      },
      columnsFactory: () => [
        {
          prop: 'afterSaleNo',
          label: '售后单号',
          minWidth: 280,
          formatter: (row) => h('span', { class: 'aftersale-id-cell' }, row.afterSaleNo)
        },
        {
          prop: 'orderNo',
          label: '订单号',
          minWidth: 280,
          formatter: (row) => h('span', { class: 'order-no-cell' }, row.orderNo)
        },
        {
          prop: 'userNickname',
          label: '用户名称',
          minWidth: 140,
          formatter: (row) => row.userNickname || '-'
        },
        {
          prop: 'afterSaleType',
          label: '退款类型',
          width: 120,
          formatter: (row) => formatAfterSaleType(row.afterSaleType)
        },
        {
          prop: 'reason',
          label: '售后信息',
          minWidth: 180,
          formatter: (row) => row.reason || '-'
        },
        {
          prop: 'requestedAmountCent',
          label: '申请金额',
          width: 130,
          formatter: (row) => formatMoney(row.requestedAmountCent)
        },
        {
          prop: 'status',
          label: '售后状态',
          width: 120,
          formatter: (row) => {
            const config = statusConfig(row.status)
            return h(
              ElTag,
              {
                type: config.type,
                class: ['business-status-tag', `business-status--${config.tone}`]
              },
              () => config.text
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
    await Promise.allSettled([getData(), loadStatusCounts()])
  }

  const handleSearch = async () => {
    searchForm.value.afterSaleId = undefined
    await applyCurrentSearch()
  }

  const handleReset = async () => {
    searchForm.value = { ...createInitialSearchForm(), afterSaleId: undefined }
    await applyCurrentSearch()
  }

  const handleStatusChange = async () => {
    replaceSearchParams(normalizeSearchParams())
    await Promise.allSettled([getData(), loadStatusCounts()])
  }

  const handleRefresh = async () => {
    await Promise.allSettled([refreshData(), loadStatusCounts()])
  }

  watch(
    () => [route.query.afterSaleId, route.query.statusGroup],
    async () => {
      const afterSaleId = routeAfterSaleId()
      const statusGroup = routeStatusGroup() || 'ALL'
      if (
        route.path !== '/trade/after-sales' ||
        (afterSaleId === searchForm.value.afterSaleId && statusGroup === activeStatusGroup.value)
      ) {
        return
      }
      searchForm.value = createInitialSearchForm()
      activeStatusGroup.value = statusGroup
      await applyCurrentSearch()
    }
  )

  const isPreviewableImage = (file: Api.AfterSale.EvidenceFile) =>
    file.status === 'ACTIVE' && file.contentType?.toLowerCase().startsWith('image/')

  const evidencePreviewIndex = (fileId: number) => {
    const url = evidencePreviewUrls.value[fileId]
    return url ? evidencePreviewList.value.indexOf(url) : 0
  }

  const clearEvidencePreviews = () => {
    Object.values(evidencePreviewUrls.value).forEach((url) => {
      if (url.startsWith('blob:')) URL.revokeObjectURL(url)
    })
    evidencePreviewUrls.value = {}
    evidencePreviewLoading.value = false
  }

  const toOrderContext = (order: Api.Order.OrderDetail): Api.AfterSale.OrderContext => ({
    orderId: order.orderId,
    orderNo: order.orderNo,
    receiverName: order.receiverName,
    receiverPhone: order.receiverPhone,
    receiverAddress: order.receiverAddress,
    productAmountCent: order.productAmountCent,
    paidAmountCent: order.paidAmountCent,
    itemCount: order.itemCount,
    items: order.items
  })

  const hydrateOrderContext = async (
    detail: Api.AfterSale.Detail
  ): Promise<Api.AfterSale.Detail> => {
    if (detail.orderContext) return detail
    const order = await fetchOrderDetail(detail.orderId)
    return { ...detail, orderContext: toOrderContext(order) }
  }

  const loadEvidencePreviews = async (detail: Api.AfterSale.Item, requestId: number) => {
    const files = (detail.evidenceFiles || []).filter(isPreviewableImage)
    if (!files.length) return

    const signedPreviews = files
      .filter((file) => file.accessMode === 'SIGNED_URL' && file.accessUrl)
      .map((file) => [file.fileId, file.accessUrl as string] as const)
    const protectedFiles = files.filter(
      (file) => !(file.accessMode === 'SIGNED_URL' && file.accessUrl)
    )
    evidencePreviewUrls.value = Object.fromEntries(signedPreviews)
    if (!protectedFiles.length) return

    evidencePreviewLoading.value = true
    const previews = await Promise.all(
      protectedFiles.map(async (file) => {
        try {
          const blob = await fetchAfterSaleEvidence(detail.id, file.fileId)
          return [file.fileId, URL.createObjectURL(blob)] as const
        } catch {
          return null
        }
      })
    )

    if (requestId !== detailRequestSeq.value) {
      previews.forEach((preview) => preview && URL.revokeObjectURL(preview[1]))
      return
    }

    evidencePreviewUrls.value = {
      ...evidencePreviewUrls.value,
      ...Object.fromEntries(
        previews.filter((preview): preview is readonly [number, string] => preview !== null)
      )
    }
    evidencePreviewLoading.value = false
  }

  const handleEvidencePreviewError = (file: Api.AfterSale.EvidenceFile) => {
    const detail = currentDetail.value
    const requestId = detailRequestSeq.value
    const currentUrl = evidencePreviewUrls.value[file.fileId]
    if (!detail || file.accessMode !== 'SIGNED_URL' || currentUrl?.startsWith('blob:')) return

    const nextUrls = { ...evidencePreviewUrls.value }
    delete nextUrls[file.fileId]
    evidencePreviewUrls.value = nextUrls
    evidencePreviewLoading.value = true
    void fetchAfterSaleEvidence(detail.id, file.fileId)
      .then((blob) => {
        const url = URL.createObjectURL(blob)
        if (requestId !== detailRequestSeq.value) {
          URL.revokeObjectURL(url)
          return
        }
        evidencePreviewUrls.value = {
          ...evidencePreviewUrls.value,
          [file.fileId]: url
        }
        evidencePreviewLoading.value = false
      })
      .catch(() => {
        if (requestId === detailRequestSeq.value) evidencePreviewLoading.value = false
      })
  }

  const openDetail = async (afterSaleId: number, preserveCurrent = false) => {
    detailDrawerVisible.value = true
    const requestId = ++detailRequestSeq.value
    detailLoading.value = true
    const preserveVisibleDetail = preserveCurrent && currentDetail.value?.id === afterSaleId
    if (!preserveVisibleDetail) {
      clearEvidencePreviews()
      currentDetail.value = null
    }
    try {
      const detail = await hydrateOrderContext(await fetchAfterSaleDetail(afterSaleId))
      if (requestId !== detailRequestSeq.value) return
      clearEvidencePreviews()
      currentDetail.value = detail
      void loadEvidencePreviews(detail, requestId)
    } finally {
      if (requestId === detailRequestSeq.value) detailLoading.value = false
    }
  }

  watch(detailDrawerVisible, (visible) => {
    if (!visible) {
      detailRequestSeq.value += 1
      clearEvidencePreviews()
      currentDetail.value = null
    }
  })

  const openRelatedOrder = (orderNo: string) => {
    if (!detailDrawerVisible.value) {
      void router.push({ path: '/trade/orders', query: { orderNo } })
      return
    }

    pendingRelatedOrderNo.value = orderNo
    detailDrawerVisible.value = false
  }

  const handleDetailDrawerClosed = () => {
    const orderNo = pendingRelatedOrderNo.value
    pendingRelatedOrderNo.value = null
    if (orderNo) void router.push({ path: '/trade/orders', query: { orderNo } })
  }

  const openRecords = async (
    row: Pick<Api.AfterSale.Summary, 'id' | 'afterSaleNo' | 'orderNo'>
  ) => {
    recordsTarget.value = row
    recordsDrawerVisible.value = true
    recordsLoading.value = true
    afterSaleRecords.value = []
    try {
      afterSaleRecords.value = await fetchAfterSaleRecords(row.id)
    } finally {
      recordsLoading.value = false
    }
  }

  watch(recordsDrawerVisible, (visible) => {
    if (!visible) {
      afterSaleRecords.value = []
      recordsTarget.value = null
    }
  })

  const canAdminAction = (
    item: Pick<Api.AfterSale.Item, 'status' | 'allowedActions'>,
    action: AfterSaleAdminAction
  ) =>
    adminAfterSaleActions({
      status: item.status,
      allowedActions: item.allowedActions,
      canAudit: hasAuth('aftersale:audit')
    }).includes(action)

  const loadReturnAddresses = async () => {
    returnAddressLoading.value = true
    try {
      returnAddresses.value = await fetchAfterSaleReturnAddresses()
    } finally {
      returnAddressLoading.value = false
    }
  }

  const openAuditDialog = async (
    mode: AuditMode,
    row: Pick<Api.AfterSale.Summary, 'id' | 'afterSaleNo' | 'orderNo' | 'requestedAmountCent'> &
      Partial<Pick<Api.AfterSale.Item, 'afterSaleType' | 'items'>>
  ) => {
    auditMode.value = mode
    let detail: Api.AfterSale.Detail
    try {
      detail = row.items
        ? (row as Api.AfterSale.Detail)
        : await hydrateOrderContext(await fetchAfterSaleDetail(row.id))
    } catch {
      ElMessage.error('售后详情加载失败，请稍后重试')
      return
    }
    auditTarget.value = {
      id: detail.id,
      afterSaleNo: detail.afterSaleNo,
      orderNo: detail.orderNo,
      requestedAmountCent: detail.requestedAmountCent,
      afterSaleType: detail.afterSaleType,
      items: detail.items || []
    }
    auditForm.approvedAmountYuan = (detail.requestedAmountCent || 0) / 100
    auditForm.auditNote = ''
    auditForm.returnAddressId = undefined
    auditForm.itemApprovals = (detail.items || []).map((item) => ({
      orderItemId: item.orderItemId,
      approvedQuantity: item.requestedQuantity
    }))
    if (mode === 'approve' && detail.afterSaleType === 'RETURN_REFUND') {
      await loadReturnAddresses()
      auditForm.returnAddressId =
        enabledReturnAddresses.value.find((address) => address.defaultAddress)?.id ||
        enabledReturnAddresses.value[0]?.id
    }
    auditFormRef.value?.clearValidate()
    auditDialogVisible.value = true
  }

  const handleReceiveReturn = async () => {
    const detail = currentDetail.value
    if (!detail || !canAdminAction(detail, 'RECEIVE_RETURN')) return
    await ElMessageBox.confirm(
      '请先核对退货物流和实际包裹，确认已收到后将进入待验收状态。',
      '确认收到退货',
      { type: 'warning', confirmButtonText: '确认收货', cancelButtonText: '取消' }
    )
    returnOperating.value = true
    try {
      await receiveAfterSaleReturn(detail.id, '后台确认收到退货')
      await refreshCurrentAfterSale(detail.id)
    } finally {
      returnOperating.value = false
    }
  }

  const openInspectionDialog = () => {
    const detail = currentDetail.value
    if (!detail || !canAdminAction(detail, 'INSPECT_RETURN')) return
    inspectionForm.decision = 'ACCEPT'
    inspectionForm.note = ''
    inspectionForm.items = detail.items.map((item) => ({
      orderItemId: item.orderItemId,
      restockQuantity: item.approvedQuantity || 0
    }))
    inspectionDialogVisible.value = true
  }

  const submitInspection = async () => {
    const detail = currentDetail.value
    if (!detail || !canAdminAction(detail, 'INSPECT_RETURN')) return
    const note = inspectionForm.note.trim()
    if (inspectionForm.decision === 'REJECT' && !note) {
      ElMessage.warning('验收拒绝时必须填写原因')
      return
    }
    await ElMessageBox.confirm(
      inspectionForm.decision === 'ACCEPT'
        ? '验收通过后将发起退款并按填写数量回库，是否继续？'
        : '验收拒绝后不会发起退款，是否继续？',
      '确认验收结果',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
    returnOperating.value = true
    try {
      await inspectAfterSaleReturn(detail.id, {
        decision: inspectionForm.decision,
        note,
        items: inspectionForm.items
      })
      inspectionDialogVisible.value = false
      await refreshCurrentAfterSale(detail.id)
    } finally {
      returnOperating.value = false
    }
  }

  const resetReturnAddressForm = (address?: Api.AfterSale.ReturnAddress) => {
    editingReturnAddressId.value = address?.id || null
    returnAddressForm.contactName = address?.contactName || ''
    returnAddressForm.contactPhone = address?.contactPhone || ''
    returnAddressForm.province = address?.province || ''
    returnAddressForm.city = address?.city || ''
    returnAddressForm.district = address?.district || ''
    returnAddressForm.detailAddress = address?.detailAddress || ''
    returnAddressForm.enabled = address?.enabled ?? true
    returnAddressForm.defaultAddress = address?.defaultAddress ?? false
    returnAddressForm.version = address?.version ?? null
  }

  const openReturnAddressManager = async () => {
    if (!canManageReturnAddresses(hasAuth('aftersale:return-address:write'))) return
    returnAddressDialogVisible.value = true
    returnAddressEditing.value = false
    await loadReturnAddresses()
  }

  const startCreateReturnAddress = () => {
    resetReturnAddressForm()
    returnAddressEditing.value = true
  }

  const startEditReturnAddress = (address: Api.AfterSale.ReturnAddress) => {
    resetReturnAddressForm(address)
    returnAddressEditing.value = true
  }

  const saveReturnAddress = async () => {
    const required = [
      returnAddressForm.contactName,
      returnAddressForm.contactPhone,
      returnAddressForm.province,
      returnAddressForm.city,
      returnAddressForm.district,
      returnAddressForm.detailAddress
    ]
    if (required.some((value) => !value.trim())) {
      ElMessage.warning('请完整填写联系人、电话和地址')
      return
    }
    returnAddressSaving.value = true
    try {
      if (editingReturnAddressId.value) {
        await updateAfterSaleReturnAddress(editingReturnAddressId.value, { ...returnAddressForm })
      } else {
        await createAfterSaleReturnAddress({ ...returnAddressForm })
      }
      returnAddressEditing.value = false
      await loadReturnAddresses()
    } finally {
      returnAddressSaving.value = false
    }
  }

  const handleDisableReturnAddress = async (address: Api.AfterSale.ReturnAddress) => {
    await ElMessageBox.confirm(
      `确定停用退货地址“${returnAddressText(address)}”吗？历史售后中的地址快照不受影响。`,
      '停用退货地址',
      { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
    )
    await disableAfterSaleReturnAddress(address.id)
    await loadReturnAddresses()
  }

  const canOperateRefund = (item?: Api.AfterSale.Item | null) =>
    Boolean(item?.refundOrder && ['PROCESSING', 'FAILED'].includes(item.refundOrder.status))

  const canResubmitRefund = (item?: Api.AfterSale.Item | null) =>
    Boolean(canOperateRefund(item) && item?.refundOrder?.callbackStatus !== 'CLOSED')

  const canMarkRefundManual = (item?: Api.AfterSale.Item | null) =>
    Boolean(canOperateRefund(item) && item?.refundOrder?.callbackStatus !== 'CLOSED')

  const canRunRefundOperation = (
    item: Api.AfterSale.Item | null | undefined,
    mode: RefundOperationMode
  ) => (mode === 'resubmit' ? canResubmitRefund(item) : canMarkRefundManual(item))

  const canRetryClosedRefund = (item?: Api.AfterSale.Item | null) =>
    Boolean(
      item?.status === 'REFUND_FAILED' &&
        item.refundOrder?.status === 'FAILED' &&
        item.refundOrder.callbackStatus === 'CLOSED'
    )

  const openRefundOperationDialog = (mode: RefundOperationMode) => {
    if (!canRunRefundOperation(currentDetail.value, mode)) return
    refundOperationMode.value = mode
    refundOperationForm.note = refundOperationDefaultNote(
      mode,
      currentDetail.value?.refundOrder?.lastErrorCode
    )
    refundOperationFormRef.value?.clearValidate()
    refundOperationDialogVisible.value = true
  }

  const applyAfterSaleResult = (next: Api.AfterSale.Item) => {
    data.value = data.value.map((row) => (row.id === next.id ? { ...row, ...next } : row))
    if (currentDetail.value?.id === next.id) {
      currentDetail.value = { ...currentDetail.value, ...next }
    }
  }

  const refreshAfterSaleSurface = async (changedAfterSaleId?: number) => {
    const tasks: Promise<unknown>[] = [handleRefresh()]
    if (
      changedAfterSaleId &&
      detailDrawerVisible.value &&
      currentDetail.value?.id === changedAfterSaleId
    ) {
      tasks.push(openDetail(changedAfterSaleId, true))
    }
    await Promise.allSettled(tasks)
  }

  const refreshCurrentAfterSale = async (afterSaleId: number) => {
    await refreshAfterSaleSurface(afterSaleId)
  }

  const handleRefundStatusRefresh = async () => {
    const detail = currentDetail.value
    const refundOrder = detail?.refundOrder
    if (!detail || !refundOrder || !canOperateRefund(detail)) return

    refundOperating.value = true
    try {
      const response = await queryRefundProvider(detail.id, refundOrder.id)
      applyAfterSaleResult(response.afterSale)
      ElMessage.success(
        refundOperationSuccessMessage({
          mode: 'query',
          result: response.result,
          providerStatus: response.providerStatus,
          resubmitted: response.resubmitted
        })
      )
    } finally {
      await refreshAfterSaleSurface(detail.id)
      refundOperating.value = false
    }
  }

  const submitRefundOperation = async () => {
    const detail = currentDetail.value
    const refundOrder = detail?.refundOrder
    if (!detail || !refundOrder || !canRunRefundOperation(detail, refundOperationMode.value)) return
    await refundOperationFormRef.value?.validate()

    const copy = refundOperationCopy[refundOperationMode.value]
    await ElMessageBox.confirm(copy.confirmation, copy.title, {
      type: refundOperationMode.value === 'manual' ? 'warning' : 'info',
      confirmButtonText: '确认执行',
      cancelButtonText: '取消'
    })

    refundOperating.value = true
    try {
      const payload = { note: refundOperationForm.note.trim() }
      const response =
        refundOperationMode.value === 'resubmit'
          ? await resubmitRefundProvider(detail.id, refundOrder.id, payload)
          : await markRefundManualIntervention(detail.id, refundOrder.id, payload)
      applyAfterSaleResult(response.afterSale)
      refundOperationDialogVisible.value = false
      ElMessage.success(
        refundOperationSuccessMessage({
          mode: refundOperationMode.value,
          result: response.result,
          providerStatus: response.providerStatus,
          resubmitted: response.resubmitted
        })
      )
    } finally {
      await refreshAfterSaleSurface(detail.id)
      refundOperating.value = false
    }
  }

  const handleClosedRefundRetry = async () => {
    const detail = currentDetail.value
    if (!detail || !canRetryClosedRefund(detail)) return
    const { value } = await ElMessageBox.prompt(
      '微信已明确关闭原退款。本操作会保留原记录，并使用新的商户退款单号重新发起退款。请输入已排除失败原因后的操作说明。',
      '新单重试退款',
      {
        type: 'warning',
        confirmButtonText: '确认新单重试',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：商户余额已补足，已核对原退款确为 CLOSED',
        inputValidator: (input) => {
          const note = input.trim()
          if (!note) return '请输入操作原因'
          return note.length <= 180 || '操作原因最多 180 个字符'
        }
      }
    )

    refundOperating.value = true
    try {
      await retryClosedRefund(detail.id, { note: value.trim() })
      await refreshCurrentAfterSale(detail.id)
    } finally {
      refundOperating.value = false
    }
  }

  const handleMoreCommand = (command: string | number | object, row: Api.AfterSale.Summary) => {
    if (command === 'records') void openRecords(row)
    if (command === 'order') openRelatedOrder(row.orderNo)
    if (command === 'approve') void openAuditDialog('approve', row)
    if (command === 'reject') void openAuditDialog('reject', row)
  }

  const submitAudit = async () => {
    if (!auditTarget.value) return
    await auditFormRef.value?.validate()

    const target = auditTarget.value
    const auditNote = auditForm.auditNote.trim()
    const isApprove = auditMode.value === 'approve'
    await ElMessageBox.confirm(
      isApprove
        ? target.afterSaleType === 'RETURN_REFUND'
          ? `确定审核通过售后 ${target.afterSaleNo} 并等待用户寄回商品吗？`
          : `确定审核通过售后 ${target.afterSaleNo} 并按批准明细发起退款吗？`
        : `确定拒绝售后 ${target.afterSaleNo} 吗？`,
      '审核确认',
      {
        type: 'warning',
        confirmButtonText: isApprove
          ? target.afterSaleType === 'RETURN_REFUND'
            ? '确认通过并等待寄回'
            : '确认并退款'
          : '确认拒绝',
        cancelButtonText: '取消'
      }
    )

    auditing.value = true
    try {
      let result: Api.AfterSale.Item
      if (isApprove) {
        result = await approveAfterSale(target.id, {
          auditNote,
          returnAddressId:
            target.afterSaleType === 'RETURN_REFUND' ? auditForm.returnAddressId : undefined,
          items: auditForm.itemApprovals
        })
      } else {
        result = await rejectAfterSale(target.id, { auditNote })
      }

      applyAfterSaleResult(result)
      auditDialogVisible.value = false
      ElMessage.success(
        afterSaleAuditSuccessMessage({
          approved: isApprove,
          afterSaleType: target.afterSaleType
        })
      )
    } finally {
      await refreshAfterSaleSurface(target.id)
      auditing.value = false
    }
  }

  let unsubscribeAfterSaleRealtime: (() => void) | null = null
  let unsubscribeAfterSaleConnectionState: (() => void) | null = null
  let realtimeAfterSaleRefreshTimer: ReturnType<typeof setTimeout> | null = null
  let hasConnectedOnce = false

  const scheduleAfterSaleRefresh = (afterSaleId?: number) => {
    if (realtimeAfterSaleRefreshTimer) clearTimeout(realtimeAfterSaleRefreshTimer)
    realtimeAfterSaleRefreshTimer = setTimeout(() => {
      realtimeAfterSaleRefreshTimer = null
      void refreshAfterSaleSurface(afterSaleId)
    }, 300)
  }

  const handleAfterSaleRealtimeEvent = (event: RealtimeEvent) => {
    if (event.type !== 'AFTER_SALE_CHANGED') return
    const eventData = event.data as unknown as Api.Realtime.AfterSaleChangedData
    const afterSaleId = Number(eventData.afterSaleId)
    if (eventData.eventType === 'AFTER_SALE_REQUESTED') {
      ElNotification({
        title: '收到新的售后申请',
        message: `售后单 ID：${afterSaleId}`,
        type: 'warning',
        duration: 6000
      })
    }
    scheduleAfterSaleRefresh(Number.isSafeInteger(afterSaleId) ? afterSaleId : undefined)
  }

  const handleAfterSaleConnectionState = (state: RealtimeConnectionState) => {
    if (state !== 'CONNECTED') return
    if (hasConnectedOnce) scheduleAfterSaleRefresh(currentDetail.value?.id)
    hasConnectedOnce = true
  }

  const handleDocumentVisibility = () => {
    if (document.visibilityState === 'visible') {
      scheduleAfterSaleRefresh(currentDetail.value?.id)
    }
  }

  onMounted(() => {
    void loadStatusCounts()
    unsubscribeAfterSaleRealtime = realtimeClient.subscribe(handleAfterSaleRealtimeEvent)
    unsubscribeAfterSaleConnectionState = realtimeClient.subscribeConnectionState(
      handleAfterSaleConnectionState
    )
    document.addEventListener('visibilitychange', handleDocumentVisibility)
  })

  onBeforeUnmount(() => {
    clearEvidencePreviews()
    unsubscribeAfterSaleRealtime?.()
    unsubscribeAfterSaleConnectionState?.()
    document.removeEventListener('visibilitychange', handleDocumentVisibility)
    if (realtimeAfterSaleRefreshTimer) clearTimeout(realtimeAfterSaleRefreshTimer)
  })
</script>

<style scoped lang="scss">
  .aftersale-table-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  .aftersale-table-toolbar :deep(.art-table-header) {
    flex: 1;
  }

  .audit-item-list {
    display: grid;
    width: 100%;
    gap: 10px;
  }

  .audit-item {
    display: flex;
    padding: 10px 12px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  .audit-item__product {
    display: grid;
    min-width: 0;
    gap: 2px;
  }

  .audit-item__product span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .return-address-toolbar {
    display: flex;
    margin-bottom: 14px;
    align-items: center;
    justify-content: space-between;
    color: var(--el-text-color-secondary);
  }

  .address-default-tag {
    margin-left: 4px;
  }

  .return-address-form {
    margin-top: 18px;
    padding: 16px 16px 4px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
    background: var(--el-fill-color-lighter);
  }

  .return-address-form__footer {
    display: flex;
    padding-bottom: 12px;
    align-items: center;
    justify-content: flex-end;
    gap: 10px;
  }

  .aftersale-status-card {
    :deep(.el-card__body) {
      padding: 0 20px;
    }
  }

  .aftersale-status-tabs {
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

  .aftersale-actions {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .aftersale-actions__arrow {
    margin-left: 3px;
  }

  :deep(.aftersale-id-cell),
  :deep(.order-no-cell) {
    display: inline-block;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 13px;
    color: var(--el-text-color-primary);
    word-break: keep-all;
    overflow-wrap: normal;
    white-space: nowrap;
  }

  .aftersale-detail {
    display: flex;
    flex-direction: column;
    min-height: 360px;
  }

  :global(.aftersale-detail-drawer .el-drawer__body) {
    padding: 16px 18px 24px;
    background: var(--el-fill-color-lighter);
  }

  .aftersale-summary {
    display: flex;
    gap: 28px;
    align-items: center;
    justify-content: space-between;
    min-height: 108px;
    padding: 18px 22px;
    margin-bottom: 16px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
    box-shadow: 0 4px 16px rgb(31 35 41 / 5%);
  }

  .aftersale-summary__identity {
    display: flex;
    flex-shrink: 0;
    gap: 14px;
    align-items: center;
  }

  .aftersale-summary__icon {
    display: grid;
    place-items: center;
    width: 54px;
    height: 54px;
    font-size: 28px;
    color: white;
    background: var(--el-color-primary);
    border-radius: 10px;
  }

  .aftersale-summary__title {
    font-size: 18px;
    font-weight: 600;
    line-height: 26px;
    color: var(--el-text-color-primary);
  }

  .aftersale-summary__title-row,
  .aftersale-summary__no {
    display: flex;
    gap: 7px;
    align-items: center;
  }

  .aftersale-summary__no {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-primary);
  }

  .aftersale-summary__copy {
    width: 20px;
    height: 20px;
    padding: 0;
    font-size: 13px;
  }

  .aftersale-summary__facts {
    display: grid;
    flex: 1;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 0;
    align-self: stretch;
    max-width: 760px;
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
  }

  .summary-fact__status,
  .detail-fact__status {
    width: fit-content;
    padding: 2px 9px;
    font-size: 13px;
    font-weight: 500;
    line-height: 20px;
    border: 1px solid transparent;
    border-radius: 999px;

    &.business-status--refunded {
      color: var(--el-color-success);
      background: var(--el-color-success-light-9);
      border-color: var(--el-color-success-light-5);
    }

    &.business-status--failed {
      color: var(--el-color-danger);
      background: var(--el-color-danger-light-9);
      border-color: var(--el-color-danger-light-5);
    }

    &.business-status--rejected {
      color: var(--el-text-color-secondary);
      background: var(--el-fill-color-light);
      border-color: var(--el-border-color-light);
    }

    &.business-status--pending {
      color: var(--el-color-warning);
      background: var(--el-color-warning-light-9);
      border-color: var(--el-color-warning-light-5);
    }

    &.business-status--refunding {
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary-light-5);
    }
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

  .aftersale-detail-content {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .aftersale-card-grid {
    display: grid;
    gap: 16px;
  }

  .aftersale-card-grid--overview {
    grid-template-columns: minmax(0, 1.6fr) minmax(280px, 0.7fr);
  }

  .aftersale-card-grid--two {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-card {
    min-width: 0;
    padding: 16px 18px 18px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
    box-shadow: 0 4px 16px rgb(31 35 41 / 5%);
  }

  .detail-card__header {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 12px;
    margin-bottom: 14px;
    border-bottom: 1px solid var(--el-border-color-lighter);

    h3 {
      display: flex;
      gap: 8px;
      align-items: center;
      margin: 0;
      font-size: 16px;
      font-weight: 600;
      line-height: 22px;
      color: var(--el-text-color-primary);
    }

    .art-svg-icon {
      font-size: 18px;
      color: var(--el-color-primary);
    }
  }

  .detail-card__count {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .detail-facts {
    display: grid;
    gap: 12px 24px;
    margin: 0;
  }

  .detail-facts--basic,
  .detail-facts--refund {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-facts--single {
    grid-template-columns: minmax(0, 1fr);
  }

  .detail-facts--refund .detail-fact {
    grid-template-columns: 112px minmax(0, 1fr);
    column-gap: 8px;
  }

  .detail-fact {
    display: grid;
    grid-template-columns: 96px minmax(0, 1fr);
    gap: 0;
    align-items: start;
    min-width: 0;
    min-height: 26px;

    dt {
      margin: 0;
      line-height: 22px;
      color: var(--el-text-color-secondary);
    }

    dd {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      align-items: center;
      min-width: 0;
      margin: 0;
      line-height: 22px;
      color: var(--el-text-color-primary);
      overflow-wrap: anywhere;
    }
  }

  .detail-fact--full {
    grid-column: 1 / -1;
  }

  .detail-fact--number {
    dd {
      flex-wrap: nowrap;
      min-height: 22px;
    }

    .detail-fact__mono {
      word-break: keep-all;
      overflow-wrap: normal;
      white-space: nowrap;
    }
  }

  .detail-fact--order-number {
    grid-template-columns: 96px minmax(0, 1fr);

    .detail-fact__mono {
      font-size: 12px;
      white-space: nowrap;
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

  .detail-fact__action {
    padding: 0 2px;
  }

  .copy-button {
    flex-shrink: 0;
    gap: 3px;
    padding: 0;
    font-size: 12px;
    line-height: 22px;
  }

  :global(.aftersale-detail-drawer .copy-button.el-button),
  :global(.aftersale-detail-drawer .detail-fact__action.el-button) {
    width: auto;
    height: 22px !important;
    min-height: 22px;
    padding: 0;
  }

  .detail-card :deep(.el-empty) {
    padding: 6px 0 10px;
  }

  .refund-attempts {
    padding-top: 16px;
    margin-top: 16px;
    border-top: 1px solid var(--el-border-color-lighter);

    h4 {
      margin: 0 0 16px;
      font-size: 14px;
      font-weight: 600;
      color: var(--el-text-color-primary);
    }
  }

  .refund-attempt {
    padding: 10px 12px;
    background: var(--el-fill-color-lighter);
    border-radius: 8px;

    p {
      margin: 6px 0 0;
      font-size: 12px;
      line-height: 18px;
      color: var(--el-text-color-secondary);
    }
  }

  .refund-attempt__title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .evidence-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: 12px;
  }

  .evidence-file {
    display: flex;
    gap: 14px;
    padding: 14px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
  }

  .evidence-file__preview,
  .evidence-file__preview-state {
    flex: 0 0 112px;
    width: 112px;
    height: 112px;
    border-radius: 6px;
  }

  .evidence-file__preview-state {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--el-text-color-secondary);
    background: var(--el-fill-color-light);
    border: 1px dashed var(--el-border-color);
  }

  .evidence-file__content {
    min-width: 0;
  }

  .evidence-file__header,
  .evidence-file__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 12px;
    align-items: center;
  }

  .evidence-file__header {
    justify-content: space-between;
    color: var(--el-text-color-primary);
  }

  .evidence-file__meta {
    margin-top: 10px;
    font-size: 12px;
    line-height: 20px;
    color: var(--el-text-color-secondary);
  }

  .evidence-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .detail-card--products {
    overflow: hidden;
  }

  .detail-products-table {
    width: 100%;

    :deep(.el-table__cell:first-child) {
      padding-left: 10px;
    }

    :deep(.el-table__cell:last-child) {
      padding-right: 10px;
    }
  }

  .item-cell {
    display: flex;
    gap: 12px;
    align-items: center;
    min-width: 0;
  }

  .item-cell__image {
    flex: 0 0 64px;
    width: 64px;
    height: 64px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 7px;
  }

  .item-cell__image-fallback {
    display: grid;
    place-items: center;
    width: 100%;
    height: 100%;
    font-size: 24px;
    color: var(--el-text-color-placeholder);
  }

  .item-cell__content {
    min-width: 0;

    .title,
    .subtitle {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .title {
      font-weight: 500;
      line-height: 22px;
      color: var(--el-text-color-primary);
    }

    .subtitle {
      margin-top: 3px;
      font-size: 12px;
      line-height: 18px;
      color: var(--el-text-color-secondary);
    }
  }

  .product-summary {
    display: flex;
    gap: 18px;
    align-items: center;
    justify-content: space-between;
    min-height: 48px;
    padding: 10px;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .product-summary__amounts {
    display: flex;
    flex-wrap: wrap;
    gap: 10px 24px;
    align-items: center;
    justify-content: flex-end;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .product-summary__refund {
    color: var(--el-text-color-primary);

    strong {
      font-size: 17px;
      color: var(--el-color-danger);
    }
  }

  :global(.aftersale-records-drawer .el-drawer__body) {
    padding: 18px 22px 24px;
    background: var(--el-bg-color-page);
  }

  .aftersale-records__identity {
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding: 14px 16px;
    margin-bottom: 24px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;

    strong {
      color: var(--el-text-color-primary);
    }

    span {
      font-size: 12px;
      color: var(--el-text-color-secondary);
      overflow-wrap: anywhere;
    }
  }

  .aftersale-records {
    min-height: 260px;

    :deep(.el-timeline) {
      padding-left: 5px;
    }

    :deep(.el-timeline-item__timestamp) {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }

  .aftersale-record {
    padding: 12px 14px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    box-shadow: 0 3px 12px rgb(31 35 41 / 4%);
  }

  .aftersale-record__heading {
    display: flex;
    gap: 10px;
    align-items: center;
    justify-content: space-between;

    strong {
      min-width: 0;
      line-height: 22px;
      color: var(--el-text-color-primary);
    }
  }

  .aftersale-record__meta {
    margin-top: 7px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .audit-alert {
    margin-bottom: 18px;
  }

  .drawer-footer,
  .dialog-footer {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
    width: 100%;
  }

  @media (width <= 1100px) {
    .aftersale-summary {
      flex-direction: column;
      align-items: flex-start;
    }

    .aftersale-summary__facts {
      width: 100%;
    }

    .aftersale-card-grid--overview {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  @media (width <= 768px) {
    :global(.aftersale-detail-drawer .el-drawer__body) {
      padding: 14px;
    }

    .aftersale-summary__facts {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .aftersale-card-grid--two {
      grid-template-columns: minmax(0, 1fr);
    }

    .product-summary {
      flex-direction: column;
      align-items: flex-start;
    }

    .product-summary__amounts {
      justify-content: flex-start;
    }
  }

  @media (width <= 560px) {
    .aftersale-summary {
      padding: 16px;
    }

    .aftersale-summary__facts,
    .detail-facts--basic,
    .detail-facts--refund {
      grid-template-columns: minmax(0, 1fr);
    }

    .summary-fact {
      padding: 10px 8px;
    }

    .detail-fact--full {
      grid-column: auto;
    }

    .evidence-list {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
