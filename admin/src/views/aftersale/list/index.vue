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
          <div class="aftersale-actions">
            <ElButton type="primary" link @click="openDetail(row.id)">详情</ElButton>
            <ElDropdown @command="(command) => handleMoreCommand(command, row)">
              <ElButton type="primary" link>
                更多<ElIcon class="aftersale-actions__arrow"><ArrowDown /></ElIcon>
              </ElButton>
              <template #dropdown>
                <ElDropdownMenu>
                  <ElDropdownItem command="order">查看关联订单</ElDropdownItem>
                  <template v-if="row.status === 'REQUESTED' && hasAuth('aftersale:audit')">
                    <ElDropdownItem command="approve" divided>审核通过并退款</ElDropdownItem>
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
    >
      <div v-loading="detailLoading" class="aftersale-detail">
        <template v-if="currentDetail">
          <div class="aftersale-summary">
            <div class="aftersale-summary__identity">
              <div class="aftersale-summary__icon">
                <ElIcon><Tickets /></ElIcon>
              </div>
              <div>
                <div class="aftersale-summary__title">售后单 #{{ currentDetail.id }}</div>
                <div class="aftersale-summary__no">订单号：{{ currentDetail.orderNo }}</div>
              </div>
            </div>
            <div class="aftersale-summary__facts">
              <div class="summary-fact">
                <span>售后状态</span>
                <strong :class="`is-${statusConfig(currentDetail.status).type}`">
                  {{ formatStatus(currentDetail.status) }}
                </strong>
              </div>
              <div class="summary-fact">
                <span>售后类型</span>
                <strong>{{ formatAfterSaleType(currentDetail.afterSaleType) }}</strong>
              </div>
              <div class="summary-fact">
                <span>申请金额</span>
                <strong>{{ formatMoney(currentDetail.requestedAmountCent) }}</strong>
              </div>
              <div class="summary-fact">
                <span>创建时间</span>
                <strong>{{ formatDateTime(currentDetail.createdAt) }}</strong>
              </div>
            </div>
          </div>

          <ElTabs v-model="detailActiveTab" class="aftersale-detail-tabs">
            <ElTabPane label="售后信息" name="info">
              <div class="detail-section">
                <div class="detail-section__title">申请信息</div>
                <ElDescriptions :column="3" border>
                  <ElDescriptionsItem label="售后单 ID">{{ currentDetail.id }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="订单 ID">{{
                    currentDetail.orderId
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="用户 ID">{{
                    currentDetail.userId
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="用户名称">{{
                    formatText(currentDetail.userNickname)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="关联订单">
                    <ElButton type="primary" link @click="openRelatedOrder(currentDetail.orderNo)">
                      {{ currentDetail.orderNo }}
                    </ElButton>
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="售后类型">
                    {{ formatAfterSaleType(currentDetail.afterSaleType) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="申请金额">
                    {{ formatMoney(currentDetail.requestedAmountCent) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="申请原因" :span="3">
                    {{ formatText(currentDetail.reason) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="申请说明" :span="3">
                    {{ formatText(currentDetail.description) }}
                  </ElDescriptionsItem>
                </ElDescriptions>
              </div>

              <div class="detail-section">
                <div class="detail-section__title">审核信息</div>
                <ElDescriptions :column="3" border>
                  <ElDescriptionsItem label="审核金额">
                    {{ formatMoneyOrDash(currentDetail.approvedAmountCent) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="审核人">
                    {{ formatText(currentDetail.reviewedBy) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="审核时间">
                    {{ formatDateTime(currentDetail.reviewedAt) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="审核备注" :span="3">
                    {{ formatText(currentDetail.auditNote) }}
                  </ElDescriptionsItem>
                </ElDescriptions>
              </div>
            </ElTabPane>

            <ElTabPane label="售后凭证" name="evidence">
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
              <ElEmpty v-else description="暂无售后凭证" :image-size="72" />
            </ElTabPane>

            <ElTabPane label="退款信息" name="refund">
              <ElDescriptions v-if="currentDetail.refundOrder" :column="3" border>
                <ElDescriptionsItem label="退款单 ID">
                  {{ currentDetail.refundOrder.id }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="支付单 ID">
                  {{ currentDetail.refundOrder.paymentOrderId }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="退款状态">
                  <ElTag
                    :type="refundStatusConfig(currentDetail.refundOrder.status).type"
                    size="small"
                  >
                    {{ formatRefundStatus(currentDetail.refundOrder.status) }}
                  </ElTag>
                </ElDescriptionsItem>
                <ElDescriptionsItem label="商户退款单号">
                  {{ formatText(currentDetail.refundOrder.outRefundNo) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="微信退款单号">
                  {{ formatText(currentDetail.refundOrder.refundId) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="退款金额">
                  {{ formatMoney(currentDetail.refundOrder.refundAmountCent) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="回调状态">
                  {{ formatText(currentDetail.refundOrder.callbackStatus) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="发起时间">
                  {{ formatDateTime(currentDetail.refundOrder.requestedAt) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="完成时间">
                  {{ formatDateTime(currentDetail.refundOrder.successAt) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="错误信息" :span="3">
                  {{ formatRefundError(currentDetail.refundOrder) }}
                </ElDescriptionsItem>
              </ElDescriptions>
              <ElEmpty v-else description="暂无退款单" :image-size="72" />
            </ElTabPane>
          </ElTabs>
        </template>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <ElButton @click="detailDrawerVisible = false">关闭</ElButton>
          <template v-if="currentDetail?.status === 'REQUESTED'">
            <ElButton
              type="success"
              v-auth="'aftersale:audit'"
              @click="openAuditDialog('approve', currentDetail)"
            >
              审核通过并退款
            </ElButton>
            <ElButton
              type="danger"
              v-auth="'aftersale:audit'"
              @click="openAuditDialog('reject', currentDetail)"
            >
              审核拒绝
            </ElButton>
          </template>
          <template v-if="currentDetail?.refundOrder && hasAuth('aftersale:audit')">
            <ElButton
              v-if="canOperateRefund(currentDetail)"
              :loading="refundOperating"
              @click="openRefundOperationDialog('query')"
            >
              查询渠道状态
            </ElButton>
            <ElButton
              v-if="canResubmitRefund(currentDetail)"
              type="warning"
              plain
              :loading="refundOperating"
              @click="openRefundOperationDialog('resubmit')"
            >
              安全查询并重提
            </ElButton>
            <ElButton
              v-if="canMarkRefundManual(currentDetail)"
              type="danger"
              plain
              :loading="refundOperating"
              @click="openRefundOperationDialog('manual')"
            >
              转人工介入
            </ElButton>
            <ElButton
              v-if="canRetryClosedRefund(currentDetail)"
              type="danger"
              :loading="refundOperating"
              @click="handleClosedRefundRetry"
            >
              CLOSED 新单重试
            </ElButton>
          </template>
        </div>
      </template>
    </ElDrawer>

    <ElDialog
      v-model="auditDialogVisible"
      :title="auditMode === 'approve' ? '审核通过并退款' : '审核拒绝'"
      width="500px"
      align-center
    >
      <ElAlert
        v-if="auditMode === 'approve'"
        title="当前按整单全额退款执行；审核通过后将立即向微信发起退款，金额不可修改。"
        type="warning"
        :closable="false"
        show-icon
        class="audit-alert"
      />
      <ElForm ref="auditFormRef" :model="auditForm" :rules="auditRules" label-width="96px">
        <ElFormItem label="售后单">
          <ElInput
            :model-value="auditTarget ? `#${auditTarget.id} / ${auditTarget.orderNo}` : '-'"
            disabled
          />
        </ElFormItem>
        <ElFormItem v-if="auditMode === 'approve'" label="退款金额" prop="approvedAmountYuan">
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
            :placeholder="
              auditMode === 'approve' ? '选填，填写后将在微信退款到账通知中显示' : '请输入拒绝原因'
            "
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
            {{ auditMode === 'approve' ? '确认并发起退款' : '确认拒绝' }}
          </ElButton>
        </div>
      </template>
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
            :model-value="
              currentDetail?.refundOrder
                ? `#${currentDetail.refundOrder.id} / ${currentDetail.refundOrder.outRefundNo}`
                : '-'
            "
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
  import { ArrowDown, Tickets } from '@element-plus/icons-vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useTable } from '@/hooks/core/useTable'
  import { afterSaleStatusGroupFromQuery } from '@/utils/business-route-query'
  import {
    approveAfterSale,
    fetchAfterSaleDetail,
    fetchAfterSaleEvidence,
    fetchAfterSales,
    fetchAfterSaleStatusCounts,
    markRefundManualIntervention,
    queryRefundProvider,
    resubmitRefundProvider,
    retryClosedRefund,
    rejectAfterSale
  } from '@/api/aftersale'
  import { ElMessageBox, ElTag, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'AfterSaleList' })

  type AuditMode = 'approve' | 'reject'
  type RefundOperationMode = 'query' | 'resubmit' | 'manual'
  type TagType = 'success' | 'warning' | 'info' | 'danger'

  interface AfterSaleSearchForm {
    afterSaleId?: string
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
  }

  interface AuditTarget {
    id: number
    orderNo: string
    requestedAmountCent: number
  }

  interface RefundOperationForm {
    note: string
  }

  const route = useRoute()
  const router = useRouter()
  const { hasAuth } = useAuth()
  const detailLoading = ref(false)
  const detailDrawerVisible = ref(false)
  const detailActiveTab = ref<'info' | 'evidence' | 'refund'>('info')
  const evidencePreviewLoading = ref(false)
  const auditDialogVisible = ref(false)
  const auditing = ref(false)
  const refundOperationDialogVisible = ref(false)
  const refundOperating = ref(false)
  const currentDetail = ref<Api.AfterSale.Item | null>(null)
  const evidencePreviewUrls = ref<Record<number, string>>({})
  const auditTarget = ref<AuditTarget | null>(null)
  const auditMode = ref<AuditMode>('approve')
  const detailRequestSeq = ref(0)
  const auditFormRef = ref<FormInstance>()
  const refundOperationFormRef = ref<FormInstance>()
  const refundOperationMode = ref<RefundOperationMode>('query')

  const routeAfterSaleId = () => {
    const value = route.query.afterSaleId
    return typeof value === 'string' && /^\d+$/.test(value) ? value : undefined
  }

  const routeStatusGroup = () => afterSaleStatusGroupFromQuery(route.query.statusGroup)

  const createInitialSearchForm = (): AfterSaleSearchForm => ({
    afterSaleId: routeAfterSaleId(),
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
    { label: '退款中', value: 'REFUNDING', countKey: 'refunding' },
    { label: '已退款', value: 'REFUNDED', countKey: 'refunded' },
    { label: '已拒绝', value: 'REJECTED', countKey: 'rejected' },
    { label: '退款失败', value: 'REFUND_FAILED', countKey: 'refundFailed' }
  ]

  const auditForm = reactive<AuditForm>({
    approvedAmountYuan: 0,
    auditNote: ''
  })

  const refundOperationForm = reactive<RefundOperationForm>({ note: '' })

  const refundOperationCopy: Record<
    RefundOperationMode,
    { title: string; description: string; confirmation: string }
  > = {
    query: {
      title: '查询渠道退款状态',
      description: '立即查询微信退款状态，并通过现有幂等状态机同步本地结果。',
      confirmation: '确定立即查询该退款单的渠道状态吗？'
    },
    resubmit: {
      title: '安全查询并重提退款',
      description: '系统会先查询微信，仅在明确返回 NOT_FOUND 时使用原商户退款单号重提。',
      confirmation: '确定执行“先查询、仅缺失时重提”吗？'
    },
    manual: {
      title: '转人工介入',
      description: '退款将停止自动恢复并标记为人工介入；该动作不会伪造退款成功。',
      confirmation: '确定停止自动恢复并转为人工介入吗？'
    }
  }
  const refundOperationTitle = computed(() => refundOperationCopy[refundOperationMode.value].title)
  const refundOperationDescription = computed(
    () => refundOperationCopy[refundOperationMode.value].description
  )

  const statusMap: Record<string, { type: TagType; text: string }> = {
    REQUESTED: { type: 'warning', text: '待审核' },
    APPROVED: { type: 'warning', text: '退款处理中' },
    REJECTED: { type: 'info', text: '已拒绝' },
    REFUNDING: { type: 'warning', text: '退款中' },
    REFUNDED: { type: 'success', text: '已退款' },
    REFUND_FAILED: { type: 'danger', text: '退款失败' }
  }

  const refundStatusMap: Record<string, { type: TagType; text: string }> = {
    PROCESSING: { type: 'warning', text: '处理中' },
    SUCCESS: { type: 'success', text: '已成功' },
    FAILED: { type: 'danger', text: '失败' }
  }

  const typeMap: Record<string, string> = {
    REFUND_ONLY: '仅退款',
    RETURN_REFUND: '退货退款'
  }

  const searchItems = computed<SearchFormItem[]>(() => [
    {
      label: '售后单号',
      key: 'afterSaleId',
      type: 'input',
      span: 8,
      props: { clearable: true, placeholder: '请输入售后单 ID' }
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
        valueFormat: 'YYYY-MM-DD HH:mm:ss',
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
  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')
  const formatText = (value?: string | number | null) =>
    value === null || value === undefined || value === '' ? '-' : String(value)
  const statusConfig = (value?: string) =>
    statusMap[value || ''] || { type: 'info' as const, text: value || '-' }
  const refundStatusConfig = (value?: string) =>
    refundStatusMap[value || ''] || { type: 'info' as const, text: value || '-' }
  const formatStatus = (value?: string) => statusConfig(value).text
  const formatRefundStatus = (value?: string) => refundStatusConfig(value).text
  const formatAfterSaleType = (value?: string) => (value ? typeMap[value] || value : '-')
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
    return [code, message].filter(Boolean).join(' / ') || '-'
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
          prop: 'id',
          label: '售后单号',
          minWidth: 140,
          formatter: (row) => h('span', { class: 'aftersale-id-cell' }, `#${row.id}`)
        },
        {
          prop: 'orderNo',
          label: '订单号',
          minWidth: 220,
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
            return h(ElTag, { type: config.type }, () => config.text)
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
    Object.values(evidencePreviewUrls.value).forEach((url) => URL.revokeObjectURL(url))
    evidencePreviewUrls.value = {}
    evidencePreviewLoading.value = false
  }

  const loadEvidencePreviews = async (detail: Api.AfterSale.Item, requestId: number) => {
    const files = (detail.evidenceFiles || []).filter(isPreviewableImage)
    if (!files.length) return

    evidencePreviewLoading.value = true
    const previews = await Promise.all(
      files.map(async (file) => {
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

    evidencePreviewUrls.value = Object.fromEntries(
      previews.filter((preview): preview is readonly [number, string] => preview !== null)
    )
    evidencePreviewLoading.value = false
  }

  const openDetail = async (afterSaleId: number) => {
    detailDrawerVisible.value = true
    detailActiveTab.value = 'info'
    const requestId = ++detailRequestSeq.value
    detailLoading.value = true
    clearEvidencePreviews()
    currentDetail.value = null
    try {
      const detail = await fetchAfterSaleDetail(afterSaleId)
      if (requestId !== detailRequestSeq.value) return
      currentDetail.value = detail
      await loadEvidencePreviews(detail, requestId)
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
    void router.push({ path: '/trade/orders', query: { orderNo } })
  }

  const openAuditDialog = (mode: AuditMode, row: AuditTarget) => {
    auditMode.value = mode
    auditTarget.value = row
    auditForm.approvedAmountYuan = (row.requestedAmountCent || 0) / 100
    auditForm.auditNote = ''
    auditFormRef.value?.clearValidate()
    auditDialogVisible.value = true
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
  ) =>
    mode === 'query'
      ? canOperateRefund(item)
      : mode === 'resubmit'
        ? canResubmitRefund(item)
        : canMarkRefundManual(item)

  const canRetryClosedRefund = (item?: Api.AfterSale.Item | null) =>
    Boolean(
      item?.status === 'REFUND_FAILED' &&
        item.refundOrder?.status === 'FAILED' &&
        item.refundOrder.callbackStatus === 'CLOSED'
    )

  const openRefundOperationDialog = (mode: RefundOperationMode) => {
    if (!canRunRefundOperation(currentDetail.value, mode)) return
    refundOperationMode.value = mode
    refundOperationForm.note = ''
    refundOperationFormRef.value?.clearValidate()
    refundOperationDialogVisible.value = true
  }

  const refreshCurrentAfterSale = async (afterSaleId: number) => {
    await handleRefresh()
    if (detailDrawerVisible.value) await openDetail(afterSaleId)
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
      if (refundOperationMode.value === 'query') {
        await queryRefundProvider(detail.id, refundOrder.id, payload)
      } else if (refundOperationMode.value === 'resubmit') {
        await resubmitRefundProvider(detail.id, refundOrder.id, payload)
      } else {
        await markRefundManualIntervention(detail.id, refundOrder.id, payload)
      }
      refundOperationDialogVisible.value = false
      await refreshCurrentAfterSale(detail.id)
    } finally {
      refundOperating.value = false
    }
  }

  const handleClosedRefundRetry = async () => {
    const detail = currentDetail.value
    if (!detail || !canRetryClosedRefund(detail)) return
    const { value } = await ElMessageBox.prompt(
      '微信已明确关闭原退款。本操作会保留原记录，并使用新的商户退款单号重新发起退款。请输入已排除失败原因后的操作说明。',
      'CLOSED 新单重试',
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
    if (command === 'order') openRelatedOrder(row.orderNo)
    if (command === 'approve') openAuditDialog('approve', row)
    if (command === 'reject') openAuditDialog('reject', row)
  }

  const submitAudit = async () => {
    if (!auditTarget.value) return
    await auditFormRef.value?.validate()

    const target = auditTarget.value
    const auditNote = auditForm.auditNote.trim()
    const isApprove = auditMode.value === 'approve'
    await ElMessageBox.confirm(
      isApprove
        ? `确定审核通过售后 #${target.id} 并立即发起退款吗？`
        : `确定拒绝售后 #${target.id} 吗？`,
      '审核确认',
      {
        type: 'warning',
        confirmButtonText: isApprove ? '确认并退款' : '确认拒绝',
        cancelButtonText: '取消'
      }
    )

    auditing.value = true
    try {
      if (isApprove) {
        await approveAfterSale(target.id, {
          approvedAmountCent: target.requestedAmountCent,
          auditNote
        })
      } else {
        await rejectAfterSale(target.id, { auditNote })
      }

      auditDialogVisible.value = false
      await handleRefresh()
      if (detailDrawerVisible.value && currentDetail.value?.id === target.id) {
        await openDetail(target.id)
      }
    } finally {
      auditing.value = false
    }
  }

  onMounted(() => void loadStatusCounts())
  onBeforeUnmount(clearEvidencePreviews)
</script>

<style scoped lang="scss">
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

  .aftersale-id-cell,
  .order-no-cell {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 13px;
    color: var(--el-text-color-primary);
  }

  .aftersale-detail {
    display: flex;
    flex-direction: column;
    min-height: 360px;
  }

  .aftersale-summary {
    display: flex;
    gap: 28px;
    align-items: center;
    justify-content: space-between;
    padding: 20px 24px;
    margin-bottom: 18px;
    background: var(--el-fill-color-lighter);
    border-radius: 10px;
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

  .aftersale-summary__no {
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .aftersale-summary__facts {
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

  .aftersale-detail-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 20px;
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

  .evidence-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: 12px;
  }

  .evidence-file {
    display: flex;
    gap: 14px;
    padding: 14px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-fill-color-blank);
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
    align-items: center;
    gap: 8px 12px;
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

  .audit-alert {
    margin-bottom: 18px;
  }

  .drawer-footer,
  .dialog-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    width: 100%;
  }

  @media (max-width: 1100px) {
    .aftersale-summary {
      align-items: flex-start;
      flex-direction: column;
    }

    .aftersale-summary__facts {
      width: 100%;
    }
  }

  @media (max-width: 768px) {
    .aftersale-summary__facts {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
</style>
