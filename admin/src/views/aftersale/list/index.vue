<template>
  <div class="aftersale-list art-full-height">
    <ArtSearchBar
      v-model="searchForm"
      :items="searchItems"
      :show-expand="false"
      @search="handleSearch"
      @reset="handleReset"
    />

    <ElCard class="art-table-card" :style="{ marginTop: '12px' }">
      <ArtTableHeader :loading="loading" @refresh="loadAfterSales" />

      <ElTable v-loading="loading" :data="afterSales" border>
        <ElTableColumn prop="id" label="售后 ID" width="110" />
        <ElTableColumn label="订单号" min-width="180">
          <template #default="{ row }">
            <ElButton type="primary" link @click="openDetail(row.id)">
              {{ row.orderNo }}
            </ElButton>
          </template>
        </ElTableColumn>
        <ElTableColumn label="类型" width="120">
          <template #default="{ row }">{{ formatAfterSaleType(row.afterSaleType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="statusMap[row.status]?.type || 'info'" size="small">
              {{ formatStatus(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="申请金额" width="130">
          <template #default="{ row }">{{ formatMoney(row.requestedAmountCent) }}</template>
        </ElTableColumn>
        <ElTableColumn label="审核金额" width="130">
          <template #default="{ row }">{{ formatMoney(row.approvedAmountCent) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        <ElTableColumn label="创建时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <ElButton type="primary" link @click="openDetail(row.id)">详情</ElButton>
              <template v-if="row.status === 'REQUESTED'">
                <ElButton type="success" link v-auth="'aftersale:audit'" @click="openAuditDialog('approve', row)">
                  通过
                </ElButton>
                <ElButton type="danger" link v-auth="'aftersale:audit'" @click="openAuditDialog('reject', row)">
                  拒绝
                </ElButton>
              </template>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="table-pagination">
        <ElPagination
          background
          layout="total, prev, pager, next, sizes"
          :current-page="pagination.current"
          :page-size="pagination.size"
          :total="pagination.total"
          @current-change="handleCurrentChange"
          @size-change="handleSizeChange"
        />
      </div>
    </ElCard>

    <ElDrawer v-model="detailDrawerVisible" title="售后详情" size="820px" destroy-on-close append-to-body>
      <div v-loading="detailLoading" class="aftersale-detail">
        <template v-if="currentDetail">
          <div class="detail-header">
            <div>
              <div class="detail-header__title">售后 #{{ currentDetail.id }}</div>
              <div class="detail-header__subtitle">
                订单 {{ currentDetail.orderNo }} / 创建 {{ formatDateTime(currentDetail.createdAt) }}
              </div>
            </div>
            <ElTag :type="statusMap[currentDetail.status]?.type || 'info'">
              {{ formatStatus(currentDetail.status) }}
            </ElTag>
          </div>

          <div class="detail-section">
            <div class="detail-section__title">订单摘要</div>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="订单 ID">{{ currentDetail.orderId }}</ElDescriptionsItem>
              <ElDescriptionsItem label="订单号">{{ currentDetail.orderNo }}</ElDescriptionsItem>
              <ElDescriptionsItem label="用户 ID">{{ currentDetail.userId }}</ElDescriptionsItem>
              <ElDescriptionsItem label="售后类型">
                {{ formatAfterSaleType(currentDetail.afterSaleType) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="申请金额">
                {{ formatMoney(currentDetail.requestedAmountCent) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="审核金额">
                {{ formatMoney(currentDetail.approvedAmountCent) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="原因" :span="2">
                {{ formatText(currentDetail.reason) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="说明" :span="2">
                {{ formatText(currentDetail.description) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="审核备注" :span="2">
                {{ formatText(currentDetail.auditNote) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="审核人">
                {{ formatText(currentDetail.reviewedBy) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="审核时间">
                {{ formatDateTime(currentDetail.reviewedAt) }}
              </ElDescriptionsItem>
            </ElDescriptions>
          </div>

          <div class="detail-section">
            <div class="detail-section__title">凭证文件</div>
            <div v-if="currentDetail.evidenceFiles?.length" class="evidence-list">
              <div v-for="file in currentDetail.evidenceFiles" :key="file.fileId" class="evidence-file">
                <div class="evidence-file__header">
                  <span>{{ formatText(file.originalFilename) }}</span>
                  <ElTag size="small" :type="file.visibility === 'PRIVATE' ? 'warning' : 'success'">
                    {{ formatText(file.visibility) }}
                  </ElTag>
                </div>
                <div class="evidence-file__meta">
                  <span>ID {{ file.fileId }}</span>
                  <span>{{ formatText(file.purpose) }}</span>
                  <span>{{ formatText(file.contentType) }}</span>
                  <span>{{ formatFileSize(file.sizeBytes) }}</span>
                  <span>{{ formatText(file.status) }}</span>
                </div>
              </div>
            </div>
            <div v-else-if="currentDetail.evidenceFileIds?.length" class="evidence-list">
              <ElTag v-for="fileId in currentDetail.evidenceFileIds" :key="fileId" type="info">
                文件 ID {{ fileId }}
              </ElTag>
            </div>
            <ElEmpty v-else description="暂无凭证" />
          </div>

          <div class="detail-section">
            <div class="detail-section__title">退款单</div>
            <ElDescriptions v-if="currentDetail.refundOrder" :column="2" border>
              <ElDescriptionsItem label="退款单 ID">
                {{ currentDetail.refundOrder.id }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="支付单 ID">
                {{ currentDetail.refundOrder.paymentOrderId }}
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
              <ElDescriptionsItem label="退款状态">
                <ElTag :type="refundStatusMap[currentDetail.refundOrder.status]?.type || 'info'" size="small">
                  {{ formatRefundStatus(currentDetail.refundOrder.status) }}
                </ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="回调状态">
                {{ formatText(currentDetail.refundOrder.callbackStatus) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="申请时间">
                {{ formatDateTime(currentDetail.refundOrder.requestedAt) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="成功时间">
                {{ formatDateTime(currentDetail.refundOrder.successAt) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="错误信息" :span="2">
                {{ formatRefundError(currentDetail.refundOrder) }}
              </ElDescriptionsItem>
            </ElDescriptions>
            <ElEmpty v-else description="暂无退款单" />
          </div>
        </template>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <ElButton @click="detailDrawerVisible = false">关闭</ElButton>
          <template v-if="currentDetail?.status === 'REQUESTED'">
            <ElButton type="success" v-auth="'aftersale:audit'" @click="openAuditDialog('approve', currentDetail)">
              审核通过
            </ElButton>
            <ElButton type="danger" v-auth="'aftersale:audit'" @click="openAuditDialog('reject', currentDetail)">
              审核拒绝
            </ElButton>
          </template>
        </div>
      </template>
    </ElDrawer>

    <ElDialog
      v-model="auditDialogVisible"
      :title="auditMode === 'approve' ? '审核通过' : '审核拒绝'"
      width="480px"
      align-center
    >
      <ElForm ref="auditFormRef" :model="auditForm" :rules="auditRules" label-width="96px">
        <ElFormItem label="售后单">
          <ElInput :model-value="auditTarget ? `#${auditTarget.id} / ${auditTarget.orderNo}` : '-'" disabled />
        </ElFormItem>
        <ElFormItem v-if="auditMode === 'approve'" label="审核金额" prop="approvedAmountYuan">
          <ElInputNumber
            v-model="auditForm.approvedAmountYuan"
            :min="0.01"
            :precision="2"
            :step="1"
            controls-position="right"
            style="width: 100%"
          />
        </ElFormItem>
        <ElFormItem label="审核备注" prop="auditNote">
          <ElInput
            v-model="auditForm.auditNote"
            type="textarea"
            maxlength="255"
            show-word-limit
            :rows="4"
            placeholder="请输入审核备注"
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
            确认
          </ElButton>
        </div>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'
  import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
  import {
    approveAfterSale,
    fetchAfterSaleDetail,
    fetchAfterSales,
    rejectAfterSale
  } from '@/api/aftersale'

  defineOptions({ name: 'AfterSaleList' })

  type AuditMode = 'approve' | 'reject'
  type TagType = 'success' | 'warning' | 'info' | 'danger'

  interface AuditForm {
    approvedAmountYuan: number
    auditNote: string
  }

  const loading = ref(false)
  const detailLoading = ref(false)
  const detailDrawerVisible = ref(false)
  const auditDialogVisible = ref(false)
  const auditing = ref(false)
  const afterSales = ref<Api.AfterSale.Item[]>([])
  const currentDetail = ref<Api.AfterSale.Item | null>(null)
  const auditTarget = ref<Api.AfterSale.Item | null>(null)
  const auditMode = ref<AuditMode>('approve')
  const detailRequestSeq = ref(0)
  const auditFormRef = ref<FormInstance>()

  const pagination = reactive<Api.Common.PaginationParams>({
    current: 1,
    size: 20,
    total: 0
  })

  const searchForm = ref<{
    orderNo?: string
    status?: Api.AfterSale.AfterSaleStatus
  }>({
    orderNo: undefined,
    status: undefined
  })

  const auditForm = reactive<AuditForm>({
    approvedAmountYuan: 0,
    auditNote: ''
  })

  const statusMap: Record<string, { type: TagType; text: string }> = {
    REQUESTED: { type: 'warning', text: '待审核' },
    APPROVED: { type: 'success', text: '已通过' },
    REJECTED: { type: 'info', text: '已拒绝' },
    REFUNDING: { type: 'warning', text: '退款中' },
    REFUNDED: { type: 'success', text: '已退款' },
    REFUND_FAILED: { type: 'danger', text: '退款失败' }
  }

  const refundStatusMap: Record<string, { type: TagType; text: string }> = {
    PROCESSING: { type: 'warning', text: '处理中' },
    SUCCESS: { type: 'success', text: '成功' },
    FAILED: { type: 'danger', text: '失败' }
  }

  const typeMap: Record<string, string> = {
    REFUND_ONLY: '仅退款',
    RETURN_REFUND: '退货退款'
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
      label: '售后状态',
      key: 'status',
      type: 'select',
      props: {
        clearable: true,
        placeholder: '请选择状态',
        options: Object.entries(statusMap).map(([value, item]) => ({
          label: item.text,
          value
        }))
      }
    }
  ])

  const auditRules = computed<FormRules<AuditForm>>(() => ({
    approvedAmountYuan: [
      {
        validator: (_rule, value, callback) => {
          if (auditMode.value === 'approve' && Number(value) <= 0) {
            callback(new Error('请输入审核金额'))
            return
          }
          callback()
        },
        trigger: 'change'
      }
    ],
    auditNote: [{ required: true, message: '请输入审核备注', trigger: 'blur' }]
  }))

  const formatMoney = (cent: number | null | undefined) => `¥${((cent ?? 0) / 100).toFixed(2)}`
  const formatDateTime = (value?: string | null) => (value ? value.replace('T', ' ') : '-')
  const formatText = (value?: string | number | null) => (value === null || value === undefined || value === '' ? '-' : String(value))
  const formatStatus = (value?: string) => (value ? statusMap[value]?.text || value : '-')
  const formatRefundStatus = (value?: string) => (value ? refundStatusMap[value]?.text || value : '-')
  const formatAfterSaleType = (value?: string) => (value ? typeMap[value] || value : '-')
  const formatFileSize = (sizeBytes?: number | null) => {
    if (!sizeBytes) return '0 B'
    if (sizeBytes < 1024) return `${sizeBytes} B`
    if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`
    return `${(sizeBytes / 1024 / 1024).toFixed(1)} MB`
  }

  const formatRefundError = (refundOrder: Api.AfterSale.RefundOrder) => {
    const code = refundOrder.lastErrorCode || ''
    const message = refundOrder.lastErrorMessage || ''
    if (!code && !message) return '-'
    return [code, message].filter(Boolean).join(' / ')
  }

  const loadAfterSales = async () => {
    loading.value = true
    try {
      const response = await fetchAfterSales({
        current: pagination.current,
        size: pagination.size,
        orderNo: searchForm.value.orderNo,
        status: searchForm.value.status
      })
      afterSales.value = response.records
      pagination.current = response.current
      pagination.size = response.size
      pagination.total = response.total
    } finally {
      loading.value = false
    }
  }

  const handleSearch = (params: Record<string, any>) => {
    searchForm.value = {
      orderNo: params.orderNo,
      status: params.status
    }
    pagination.current = 1
    loadAfterSales()
  }

  const handleReset = () => {
    searchForm.value = {
      orderNo: undefined,
      status: undefined
    }
    pagination.current = 1
    loadAfterSales()
  }

  const handleCurrentChange = (current: number) => {
    pagination.current = current
    loadAfterSales()
  }

  const handleSizeChange = (size: number) => {
    pagination.size = size
    pagination.current = 1
    loadAfterSales()
  }

  const openDetail = async (afterSaleId: number) => {
    detailDrawerVisible.value = true
    const requestId = ++detailRequestSeq.value
    detailLoading.value = true
    currentDetail.value = null
    try {
      const detail = await fetchAfterSaleDetail(afterSaleId)
      if (requestId !== detailRequestSeq.value) return
      currentDetail.value = detail
    } finally {
      if (requestId === detailRequestSeq.value) {
        detailLoading.value = false
      }
    }
  }

  const openAuditDialog = (mode: AuditMode, row: Api.AfterSale.Item) => {
    auditMode.value = mode
    auditTarget.value = row
    auditForm.approvedAmountYuan = (row.requestedAmountCent || 0) / 100
    auditForm.auditNote = ''
    auditFormRef.value?.clearValidate()
    auditDialogVisible.value = true
  }

  const submitAudit = async () => {
    if (!auditTarget.value) return
    await auditFormRef.value?.validate()

    const target = auditTarget.value
    const auditNote = auditForm.auditNote.trim()
    const actionText = auditMode.value === 'approve' ? '通过' : '拒绝'
    await ElMessageBox.confirm(`确定${actionText}售后 #${target.id} 吗？`, '审核确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })

    auditing.value = true
    try {
      if (auditMode.value === 'approve') {
        await approveAfterSale(target.id, {
          approvedAmountCent: Math.round(auditForm.approvedAmountYuan * 100),
          auditNote
        })
      } else {
        await rejectAfterSale(target.id, { auditNote })
      }

      auditDialogVisible.value = false
      await loadAfterSales()
      if (detailDrawerVisible.value && currentDetail.value?.id === target.id) {
        await openDetail(target.id)
      }
    } finally {
      auditing.value = false
    }
  }

  onMounted(loadAfterSales)
</script>

<style scoped lang="scss">
  .aftersale-list {
    display: flex;
    flex-direction: column;
  }

  .table-actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .table-pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .aftersale-detail {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .detail-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
  }

  .detail-header__title {
    font-size: 18px;
    line-height: 28px;
    color: var(--el-text-color-primary);
  }

  .detail-header__subtitle {
    margin-top: 4px;
    font-size: 13px;
    line-height: 20px;
    color: var(--el-text-color-secondary);
  }

  .detail-section {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .detail-section__title {
    font-size: 14px;
    line-height: 22px;
    color: var(--el-text-color-primary);
  }

  .evidence-list {
    display: grid;
    gap: 8px;
  }

  .evidence-file {
    display: grid;
    gap: 6px;
    padding: 10px 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 6px;
    background: var(--el-fill-color-blank);
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
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .drawer-footer,
  .dialog-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    width: 100%;
  }

  @media (max-width: 768px) {
    .detail-header {
      flex-direction: column;
    }
  }
</style>
