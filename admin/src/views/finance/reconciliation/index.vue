<template>
  <div class="finance-reconciliation-page art-full-height">
    <ElAlert
      title="这里核对微信支付交易账单与本地支付、退款记录，不代表银行账户到账已核对。"
      description="差异只记录证据和人工处理结论，不会直接篡改订单、支付或退款状态。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard shadow="never">
      <div class="filters">
        <ElDatePicker
          v-model="batchDateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="账单开始日期"
          end-placeholder="账单结束日期"
          unlink-panels
          :disabled-date="isFutureBusinessDate"
        />
        <ElInput v-model="filters.mchId" clearable maxlength="32" placeholder="微信商户号" />
        <ElSelect v-model="filters.status" clearable placeholder="批次状态">
          <ElOption
            v-for="option in batchStatusOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
        <ElButton type="primary" @click="search">查询</ElButton>
        <ElButton @click="reset">重置</ElButton>
        <span class="filters__spacer" />
        <ElButton v-auth="'finance:export'" @click="exportCsv">导出 CSV</ElButton>
        <ElButton v-auth="'finance:reconciliation:run'" type="primary" @click="openRun">
          人工补跑
        </ElButton>
      </div>

      <ElTable v-loading="loading" :data="rows" row-key="id">
        <ElTableColumn prop="billDate" label="账单日期" width="120" />
        <ElTableColumn prop="mchId" label="商户号" min-width="150" />
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="batchStatusTone(row.status)">
              {{ batchStatusLabel(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="账单记录" min-width="170">
          <template #default="{ row }">
            {{ row.totalRows }} 条（支付 {{ row.paymentRows }} / 退款 {{ row.refundRows }}）
          </template>
        </ElTableColumn>
        <ElTableColumn label="差异" width="130">
          <template #default="{ row }">
            <span :class="{ 'difference-count--open': row.openDifferenceCount > 0 }">
              {{ row.openDifferenceCount }} 待处理 / {{ row.differenceCount }} 总计
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="摘要校验" width="105">
          <template #default="{ row }">
            <ElTag :type="row.providerHashVerified ? 'success' : 'info'">
              {{ row.providerHashVerified ? '已通过' : '未完成' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <ElButton link type="primary" @click="openDetail(row.id)">详情</ElButton>
            <ElButton
              v-if="row.sourceAvailable"
              v-auth="'finance:reconciliation:source-download'"
              link
              type="primary"
              @click="downloadSource(row)"
            >
              原始账单
            </ElButton>
            <ElButton
              v-if="canRetryBatch(row.status)"
              v-auth="'finance:reconciliation:run'"
              link
              type="warning"
              @click="retryBatch(row)"
            >
              重新取证
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="pagination">
        <ElPagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="page.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadRows"
          @size-change="handlePageSizeChange"
        />
      </div>
    </ElCard>

    <ElDrawer
      v-model="detailVisible"
      title="微信交易账单对账详情"
      size="min(1120px, 94vw)"
      destroy-on-close
    >
      <template v-if="detail">
        <ElDescriptions :column="3" border>
          <ElDescriptionsItem label="账单日期">{{ detail.billDate }}</ElDescriptionsItem>
          <ElDescriptionsItem label="商户号">{{ detail.mchId }}</ElDescriptionsItem>
          <ElDescriptionsItem label="状态">
            <ElTag :type="batchStatusTone(detail.status)">
              {{ batchStatusLabel(detail.status) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="微信支付金额">
            {{ formatCentAmount(detail.channelPaymentAmountCent) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="本地支付金额">
            {{ formatCentAmount(detail.localPaymentAmountCent) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="支付差额">
            {{ formatCentAmount(detail.channelPaymentAmountCent - detail.localPaymentAmountCent) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="微信退款金额">
            {{ formatCentAmount(detail.channelRefundAmountCent) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="本地退款金额">
            {{ formatCentAmount(detail.localRefundAmountCent) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="退款差额">
            {{ formatCentAmount(detail.channelRefundAmountCent - detail.localRefundAmountCent) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="微信摘要校验">
            {{ detail.providerHashVerified ? '通过' : '未完成' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="原始文件大小">
            {{ formatBytes(detail.sourceSizeBytes) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="执行次数">{{ detail.attemptCount }}</ElDescriptionsItem>
          <ElDescriptionsItem v-if="detail.lastErrorMessage" label="最近错误" :span="3">
            {{ detail.lastErrorCode || 'UNKNOWN' }}：{{ detail.lastErrorMessage }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <ElTabs v-model="detailTab" class="detail-tabs" @tab-change="handleDetailTabChange">
          <ElTabPane label="账单明细" name="entries">
            <div class="sub-filters">
              <ElSelect v-model="entryFilters.entryType" clearable placeholder="记录类型">
                <ElOption label="支付" value="PAYMENT" />
                <ElOption label="退款" value="REFUND" />
              </ElSelect>
              <ElInput v-model="entryFilters.keyword" clearable placeholder="商户单号/微信单号" />
              <ElButton type="primary" @click="searchEntries">查询</ElButton>
            </div>
            <ElTable v-loading="entryLoading" :data="entries" row-key="id" max-height="520">
              <ElTableColumn prop="rowNo" label="行" width="70" />
              <ElTableColumn label="类型" width="85">
                <template #default="{ row }">{{
                  row.entryType === 'PAYMENT' ? '支付' : '退款'
                }}</template>
              </ElTableColumn>
              <ElTableColumn label="商户单号" min-width="185">
                <template #default="{ row }">{{
                  row.outRefundNo || row.outTradeNo || '-'
                }}</template>
              </ElTableColumn>
              <ElTableColumn label="微信单号" min-width="185">
                <template #default="{ row }">{{
                  row.refundId || row.transactionId || '-'
                }}</template>
              </ElTableColumn>
              <ElTableColumn label="金额" width="120">
                <template #default="{ row }">{{ formatCentAmount(row.amountCent) }}</template>
              </ElTableColumn>
              <ElTableColumn prop="channelStatus" label="微信状态" width="120" />
              <ElTableColumn label="发生时间" width="180">
                <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
              </ElTableColumn>
            </ElTable>
            <div class="pagination">
              <ElPagination
                v-model:current-page="entryPage.current"
                v-model:page-size="entryPage.size"
                :total="entryPage.total"
                layout="total, prev, pager, next"
                @current-change="loadEntries"
              />
            </div>
          </ElTabPane>

          <ElTabPane :label="`对账差异（${detail.openDifferenceCount} 待处理）`" name="differences">
            <div class="sub-filters sub-filters--differences">
              <ElSelect v-model="differenceFilters.status" clearable placeholder="处理状态">
                <ElOption
                  v-for="option in differenceStatusOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </ElSelect>
              <ElSelect v-model="differenceFilters.type" clearable placeholder="差异类型">
                <ElOption
                  v-for="option in differenceTypeOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </ElSelect>
              <ElInput
                v-model="differenceFilters.keyword"
                clearable
                placeholder="商户单号/微信单号"
              />
              <ElButton type="primary" @click="searchDifferences">查询</ElButton>
            </div>
            <ElTable
              v-loading="differenceLoading"
              :data="differences"
              row-key="id"
              max-height="520"
            >
              <ElTableColumn label="风险" width="85">
                <template #default="{ row }">
                  <ElTag :type="differenceSeverityTone(row.severity)">{{ row.severity }}</ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="差异类型" min-width="150">
                <template #default="{ row }">{{ differenceTypeLabel(row.type) }}</template>
              </ElTableColumn>
              <ElTableColumn label="业务标识" min-width="245">
                <template #default="{ row }">
                  <div>商户：{{ row.outRefundNo || row.outTradeNo || '-' }}</div>
                  <div>微信：{{ row.refundId || row.transactionId || '-' }}</div>
                  <div v-if="row.orderId">订单：{{ row.orderId }}</div>
                  <div v-if="row.candidateContentSha256">
                    候选摘要：{{ shortDigest(row.candidateContentSha256) }}（{{
                      formatBytes(row.candidateSizeBytes)
                    }}）
                  </div>
                </template>
              </ElTableColumn>
              <ElTableColumn label="微信 / 本地金额" min-width="165">
                <template #default="{ row }">
                  {{ formatCentAmount(row.providerAmountCent) }} /
                  {{ formatCentAmount(row.localAmountCent) }}
                </template>
              </ElTableColumn>
              <ElTableColumn label="微信 / 本地状态" min-width="155">
                <template #default="{ row }">
                  {{ row.providerStatus || '-' }} / {{ row.localStatus || '-' }}
                </template>
              </ElTableColumn>
              <ElTableColumn label="处理状态" width="105">
                <template #default="{ row }">
                  <ElTag :type="differenceStatusTone(row.status)">
                    {{ differenceStatusLabel(row.status) }}
                  </ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="操作" min-width="210" fixed="right">
                <template #default="{ row }">
                  <ElButton link type="primary" @click="showAudits(row)">轨迹</ElButton>
                  <ElButton
                    v-if="row.candidateSourceAvailable"
                    v-auth="'finance:reconciliation:source-download'"
                    link
                    type="primary"
                    @click="downloadCandidateSource(row)"
                  >
                    候选账单
                  </ElButton>
                  <ElButton
                    v-if="canInvestigateDifference(row.status)"
                    v-auth="'finance:reconciliation:resolve'"
                    link
                    type="warning"
                    @click="openDifferenceAction(row, 'investigate')"
                  >
                    开始调查
                  </ElButton>
                  <ElButton
                    v-if="canResolveDifference(row.status)"
                    v-auth="'finance:reconciliation:resolve'"
                    link
                    type="success"
                    @click="openDifferenceAction(row, 'resolve')"
                  >
                    记录解决
                  </ElButton>
                </template>
              </ElTableColumn>
            </ElTable>
            <div class="pagination">
              <ElPagination
                v-model:current-page="differencePage.current"
                v-model:page-size="differencePage.size"
                :total="differencePage.total"
                layout="total, prev, pager, next"
                @current-change="loadDifferences"
              />
            </div>
          </ElTabPane>
        </ElTabs>
      </template>
    </ElDrawer>

    <ElDialog v-model="runVisible" title="人工补跑微信交易账单" width="520px" destroy-on-close>
      <ElAlert
        title="只允许补跑今天以前、近 90 天的账单；不填写商户号时会为所有可用商户创建批次。"
        type="info"
        :closable="false"
        show-icon
      />
      <ElForm label-position="top" class="dialog-form">
        <ElFormItem label="账单日期" required>
          <ElDatePicker
            v-model="runForm.billDate"
            value-format="YYYY-MM-DD"
            type="date"
            :disabled-date="isRunDateDisabled"
          />
        </ElFormItem>
        <ElFormItem label="微信商户号">
          <ElInput
            v-model="runForm.mchId"
            clearable
            maxlength="32"
            placeholder="留空则运行全部可用商户"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="runVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitRun">确认补跑</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="actionVisible" :title="actionTitle" width="560px" destroy-on-close>
      <ElForm label-position="top">
        <ElFormItem v-if="differenceAction === 'resolve'" label="解决代码" required>
          <ElInput
            v-model="differenceActionForm.resolutionCode"
            maxlength="64"
            placeholder="例如 PROVIDER_CONFIRMED、LOCAL_RECORD_CORRECTED"
          />
        </ElFormItem>
        <ElFormItem label="真实处理依据" required>
          <ElInput
            v-model="differenceActionForm.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="记录实际核验依据；不要填写虚假模板说明"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="actionVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitDifferenceAction">
          确认
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="auditsVisible" title="差异处理轨迹" width="680px" destroy-on-close>
      <ElTimeline v-if="audits.length">
        <ElTimelineItem
          v-for="audit in audits"
          :key="audit.id"
          :timestamp="formatTime(audit.createdAt)"
        >
          <b>{{ auditActionLabel(audit.action) }}</b>
          <div>
            {{ audit.fromStatus ? differenceStatusLabel(audit.fromStatus) : '初始' }} →
            {{ differenceStatusLabel(audit.toStatus) }}
          </div>
          <div v-if="audit.resolutionCode">解决代码：{{ audit.resolutionCode }}</div>
          <div v-if="audit.operatorId">操作人 ID：{{ audit.operatorId }}</div>
          <div>依据：{{ audit.reason }}</div>
        </ElTimelineItem>
      </ElTimeline>
      <ElEmpty v-else description="暂无处理轨迹" />
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    downloadReconciliationCandidateSource,
    downloadReconciliationSource,
    exportReconciliationCsv,
    fetchReconciliationBatch,
    fetchReconciliationBatches,
    fetchReconciliationDifferenceAudits,
    fetchReconciliationDifferences,
    fetchReconciliationEntries,
    investigateReconciliationDifference,
    resolveReconciliationDifference,
    retryReconciliationBatch,
    runReconciliation
  } from '@/api/finance-reconciliation'
  import { isHttpError } from '@/utils/http/error'
  import {
    auditActionLabel,
    batchStatusLabel,
    batchStatusOptions,
    batchStatusTone,
    canInvestigateDifference,
    canRetryBatch,
    canResolveDifference,
    differenceSeverityTone,
    differenceStatusLabel,
    differenceStatusOptions,
    differenceStatusTone,
    differenceTypeLabel,
    differenceTypeOptions,
    formatCentAmount,
    isBillDateWithinLookback,
    isInclusiveDateRangeWithinDays,
    validateReason,
    validateResolution
  } from './reconciliation-state'

  defineOptions({ name: 'FinanceReconciliation' })

  type DifferenceAction = 'investigate' | 'resolve'
  const formatBusinessDate = (value: Date) =>
    new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).format(value)
  const businessDate = (offsetDays = 0) => {
    const value = new Date(Date.now() + offsetDays * 86_400_000)
    return formatBusinessDate(value)
  }

  const loading = ref(false)
  const submitting = ref(false)
  const rows = ref<Api.FinanceReconciliation.Batch[]>([])
  const batchDateRange = ref<[string, string] | null>(null)
  const filters = reactive<{
    mchId: string
    status?: Api.FinanceReconciliation.BatchStatus
  }>({ mchId: '' })
  const page = reactive({ current: 1, size: 20, total: 0 })

  const detailVisible = ref(false)
  const detail = ref<Api.FinanceReconciliation.BatchDetail | null>(null)
  const detailTab = ref('entries')
  const entries = ref<Api.FinanceReconciliation.TradeBillEntry[]>([])
  const entryLoading = ref(false)
  const entryFilters = reactive<{
    entryType?: Api.FinanceReconciliation.EntryType
    keyword: string
  }>({ keyword: '' })
  const entryPage = reactive({ current: 1, size: 20, total: 0 })
  const differences = ref<Api.FinanceReconciliation.Difference[]>([])
  const differenceLoading = ref(false)
  const differenceFilters = reactive<{
    status?: Api.FinanceReconciliation.DifferenceStatus
    type?: Api.FinanceReconciliation.DifferenceType
    keyword: string
  }>({ keyword: '' })
  const differencePage = reactive({ current: 1, size: 20, total: 0 })

  const runVisible = ref(false)
  const runForm = reactive({ billDate: businessDate(-1), mchId: '' })
  const actionVisible = ref(false)
  const differenceAction = ref<DifferenceAction>('investigate')
  const activeDifference = ref<Api.FinanceReconciliation.Difference | null>(null)
  const differenceActionForm = reactive({ reason: '', resolutionCode: '' })
  const auditsVisible = ref(false)
  const audits = ref<Api.FinanceReconciliation.ResolutionAudit[]>([])

  const actionTitle = computed(() =>
    differenceAction.value === 'resolve' ? '记录差异解决结论' : '开始调查差异'
  )

  const isFutureBusinessDate = (value: Date) => formatBusinessDate(value) > businessDate()
  const isRunDateDisabled = (value: Date) =>
    !isBillDateWithinLookback(formatBusinessDate(value), businessDate(), 90)
  const isRevisionConflict = (error: unknown) => isHttpError(error) && error.httpStatus === 409

  const formatTime = (value?: string | null) => (value ? new Date(value).toLocaleString() : '-')
  const formatBytes = (value?: number | null) => {
    if (value == null) return '-'
    if (value < 1024) return `${value} B`
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
    return `${(value / 1024 / 1024).toFixed(1)} MB`
  }
  const shortDigest = (value: string) =>
    value.length > 20 ? `${value.slice(0, 10)}…${value.slice(-10)}` : value

  const loadRows = async () => {
    loading.value = true
    try {
      const dateRange = batchDateRange.value
      const result = await fetchReconciliationBatches({
        current: page.current,
        size: page.size,
        ...(dateRange?.[0] ? { billDateFrom: dateRange[0] } : {}),
        ...(dateRange?.[1] ? { billDateTo: dateRange[1] } : {}),
        ...(filters.mchId.trim() ? { mchId: filters.mchId.trim() } : {}),
        ...(filters.status ? { status: filters.status } : {})
      })
      rows.value = result.records
      Object.assign(page, { current: result.current, size: result.size, total: result.total })
    } finally {
      loading.value = false
    }
  }

  const search = () => {
    page.current = 1
    loadRows()
  }
  const reset = () => {
    batchDateRange.value = null
    filters.mchId = ''
    filters.status = undefined
    search()
  }
  const handlePageSizeChange = () => {
    page.current = 1
    loadRows()
  }

  const loadEntries = async () => {
    if (!detail.value) return
    entryLoading.value = true
    try {
      const result = await fetchReconciliationEntries(detail.value.id, {
        current: entryPage.current,
        size: entryPage.size,
        ...(entryFilters.entryType ? { entryType: entryFilters.entryType } : {}),
        ...(entryFilters.keyword.trim() ? { keyword: entryFilters.keyword.trim() } : {})
      })
      entries.value = result.records
      Object.assign(entryPage, {
        current: result.current,
        size: result.size,
        total: result.total
      })
    } finally {
      entryLoading.value = false
    }
  }

  const loadDifferences = async () => {
    if (!detail.value) return
    differenceLoading.value = true
    try {
      const result = await fetchReconciliationDifferences(detail.value.id, {
        current: differencePage.current,
        size: differencePage.size,
        ...(differenceFilters.status ? { status: differenceFilters.status } : {}),
        ...(differenceFilters.type ? { type: differenceFilters.type } : {}),
        ...(differenceFilters.keyword.trim() ? { keyword: differenceFilters.keyword.trim() } : {})
      })
      differences.value = result.records
      Object.assign(differencePage, {
        current: result.current,
        size: result.size,
        total: result.total
      })
    } finally {
      differenceLoading.value = false
    }
  }

  const openDetail = async (batchId: Api.FinanceReconciliation.Identifier) => {
    detail.value = await fetchReconciliationBatch(batchId)
    detailVisible.value = true
    detailTab.value = detail.value.openDifferenceCount > 0 ? 'differences' : 'entries'
    entryPage.current = 1
    differencePage.current = 1
    await (detailTab.value === 'differences' ? loadDifferences() : loadEntries())
  }

  const handleDetailTabChange = (name: string | number) => {
    if (name === 'entries') loadEntries()
    if (name === 'differences') loadDifferences()
  }
  const searchEntries = () => {
    entryPage.current = 1
    loadEntries()
  }
  const searchDifferences = () => {
    differencePage.current = 1
    loadDifferences()
  }

  const openRun = () => {
    runForm.billDate = businessDate(-1)
    runForm.mchId = filters.mchId.trim()
    runVisible.value = true
  }
  const submitRun = async () => {
    if (!runForm.billDate || !isBillDateWithinLookback(runForm.billDate, businessDate(), 90)) {
      ElMessage.error('请选择今天以前、近 90 天内的账单日期')
      return
    }
    submitting.value = true
    try {
      await runReconciliation({
        billDate: runForm.billDate,
        ...(runForm.mchId.trim() ? { mchId: runForm.mchId.trim() } : {})
      })
      runVisible.value = false
      await loadRows()
    } finally {
      submitting.value = false
    }
  }

  const retryBatch = async (row: Api.FinanceReconciliation.Batch) => {
    try {
      const result = await ElMessageBox.prompt(
        '重新下载时会保留旧证据；微信来源发生变化会新增高风险差异。请输入真实原因。',
        '重新取证',
        {
          confirmButtonText: '确认重试',
          cancelButtonText: '取消',
          inputType: 'textarea',
          inputValidator: (value) => validateReason(value) ?? true
        }
      )
      await retryReconciliationBatch(row.id, {
        version: row.version,
        reason: result.value.trim()
      })
      await loadRows()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      if (isRevisionConflict(error)) {
        await loadRows()
        return
      }
      throw error
    }
  }

  const openDifferenceAction = (
    row: Api.FinanceReconciliation.Difference,
    action: DifferenceAction
  ) => {
    activeDifference.value = row
    differenceAction.value = action
    differenceActionForm.reason = ''
    differenceActionForm.resolutionCode = ''
    actionVisible.value = true
  }

  const submitDifferenceAction = async () => {
    if (!activeDifference.value) return
    const error =
      differenceAction.value === 'resolve'
        ? validateResolution(differenceActionForm.resolutionCode, differenceActionForm.reason)
        : validateReason(differenceActionForm.reason)
    if (error) {
      ElMessage.error(error)
      return
    }
    submitting.value = true
    try {
      if (differenceAction.value === 'resolve') {
        await resolveReconciliationDifference(activeDifference.value.id, {
          version: activeDifference.value.version,
          resolutionCode: differenceActionForm.resolutionCode.trim(),
          reason: differenceActionForm.reason.trim()
        })
      } else {
        await investigateReconciliationDifference(activeDifference.value.id, {
          version: activeDifference.value.version,
          reason: differenceActionForm.reason.trim()
        })
      }
      actionVisible.value = false
      if (detail.value) detail.value = await fetchReconciliationBatch(detail.value.id)
      await loadDifferences()
      await loadRows()
    } catch (error) {
      if (isRevisionConflict(error)) {
        actionVisible.value = false
        if (detail.value) detail.value = await fetchReconciliationBatch(detail.value.id)
        await Promise.all([loadDifferences(), loadRows()])
        return
      }
      throw error
    } finally {
      submitting.value = false
    }
  }

  const showAudits = async (row: Api.FinanceReconciliation.Difference) => {
    audits.value = await fetchReconciliationDifferenceAudits(row.id)
    auditsVisible.value = true
  }

  const saveBlob = (blob: Blob, fileName: string) => {
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }

  const downloadSource = async (row: Api.FinanceReconciliation.Batch) => {
    const blob = await downloadReconciliationSource(row.id)
    saveBlob(blob, `wechat-trade-bill-${row.mchId}-${row.billDate}.csv`)
  }

  const downloadCandidateSource = async (row: Api.FinanceReconciliation.Difference) => {
    const blob = await downloadReconciliationCandidateSource(row.id)
    saveBlob(blob, `wechat-trade-bill-candidate-${row.id}.csv`)
  }

  const exportCsv = async () => {
    const dateRange = batchDateRange.value
    if (!dateRange?.[0] || !dateRange[1]) {
      ElMessage.error('导出前请选择不超过 31 天的账单日期范围')
      return
    }
    if (!isInclusiveDateRangeWithinDays(dateRange[0], dateRange[1], 31)) {
      ElMessage.error('导出日期范围最多为 31 个自然日')
      return
    }
    const blob = await exportReconciliationCsv({
      from: dateRange[0],
      to: dateRange[1],
      ...(filters.mchId.trim() ? { mchId: filters.mchId.trim() } : {}),
      ...(filters.status ? { batchStatus: filters.status } : {})
    })
    saveBlob(blob, `wechat-reconciliation-${dateRange[0]}-${dateRange[1]}.csv`)
  }

  onMounted(loadRows)
</script>

<style scoped lang="scss">
  .finance-reconciliation-page {
    display: grid;
    gap: 16px;
  }

  .filters {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    margin-bottom: 16px;
  }

  .filters__spacer {
    flex: 1;
  }

  .difference-count--open {
    font-weight: 600;
    color: var(--el-color-danger);
  }

  .pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .detail-tabs {
    margin-top: 18px;
  }

  .sub-filters {
    display: grid;
    grid-template-columns: 160px minmax(240px, 1fr) auto;
    gap: 12px;
    margin-bottom: 14px;
  }

  .sub-filters--differences {
    grid-template-columns: 150px 190px minmax(220px, 1fr) auto;
  }

  .dialog-form {
    margin-top: 16px;
  }

  @media (width <= 900px) {
    .filters,
    .sub-filters,
    .sub-filters--differences {
      display: grid;
      grid-template-columns: 1fr;
    }

    .filters__spacer {
      display: none;
    }
  }
</style>
