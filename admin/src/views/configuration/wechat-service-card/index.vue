<template>
  <div class="service-card-page">
    <ElAlert
      title="微信 2001 服务动态是生产消息链路，不是普通营销订阅消息。"
      description="采集会为新业务事实与 Repair Scanner 候选建立可靠队列；外呼会真实调用微信。此页不展示或修改 Token、EncodingAESKey，也不提供强推和改库操作。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard v-loading="statusLoading" shadow="never" class="runtime-card">
      <template #header>
        <div class="section-header">
          <div>
            <h1>服务动态运行控制</h1>
            <p>运行开关保存在数据库中并采用版本校验；部署环境默认值只用于尚无覆盖记录时。</p>
          </div>
          <div class="section-header__actions">
            <ElButton :disabled="statusLoading || runtimeSaving" @click="reloadAll">
              刷新状态
            </ElButton>
            <ElButton
              v-auth="'wechat-service-card:runtime:write'"
              type="primary"
              :disabled="!runtimeDirty || Boolean(runtimeValidation) || !canWrite"
              :loading="runtimeSaving"
              @click="openRuntimeConfirmation"
            >
              保存运行开关
            </ElButton>
          </div>
        </div>
      </template>

      <template v-if="status">
        <div class="runtime-grid">
          <section class="runtime-item">
            <div class="runtime-item__heading">
              <div>
                <h2>业务采集</h2>
                <p>把支付、发货、签收与售后事实写入可靠投递队列。</p>
              </div>
              <ElSwitch
                :model-value="draft.captureEnabled"
                :disabled="captureSwitchDisabled"
                inline-prompt
                active-text="开"
                inactive-text="关"
                aria-label="服务动态业务采集开关"
                @update:model-value="handleCaptureChange"
              />
            </div>
            <div class="runtime-item__tags">
              <ElTag :type="status.captureEnabled ? 'success' : 'info'">
                当前{{ status.captureEnabled ? '已开启' : '已关闭' }}
              </ElTag>
              <ElTag :type="status.captureReady ? 'success' : 'warning'" effect="plain">
                {{ status.captureReady ? '采集已就绪' : '采集未就绪' }}
              </ElTag>
            </div>
            <p class="runtime-item__note">
              开启采集不会直接调用微信，但修复扫描可能补建漏建卡片或更新有效窗口内的非终态卡片。
            </p>
          </section>

          <section class="runtime-item runtime-item--danger">
            <div class="runtime-item__heading">
              <div>
                <h2>微信外呼 Worker</h2>
                <p>领取队列并调用微信 set/get 接口，可能触达真实用户。</p>
              </div>
              <ElSwitch
                :model-value="draft.workerEnabled"
                :disabled="workerSwitchDisabled"
                inline-prompt
                active-text="开"
                inactive-text="关"
                aria-label="服务动态微信外呼开关"
                @update:model-value="handleWorkerChange"
              />
            </div>
            <div class="runtime-item__tags">
              <ElTag :type="status.workerEnabled ? 'danger' : 'info'">
                当前{{ status.workerEnabled ? '正在外呼' : '已停止外呼' }}
              </ElTag>
              <ElTag :type="status.workerReady ? 'success' : 'warning'" effect="plain">
                {{ status.workerReady ? '外呼已就绪' : '外呼未就绪' }}
              </ElTag>
            </div>
            <p class="runtime-item__note">
              必须先单独保存“采集开启、外呼关闭”并验收队列，下一版配置才能开启；每次领取及外呼前，后端都会重新检查门禁。
            </p>
          </section>

          <section class="runtime-item">
            <div class="runtime-item__heading">
              <div>
                <h2>微信安全回调</h2>
                <p>接收微信失败通知并完成签名、时间窗、AES 与 AppID 校验。</p>
              </div>
              <ElTag :type="status.callbackEnabled ? 'success' : 'info'">
                {{ status.callbackEnabled ? '已启用' : '未启用' }}
              </ElTag>
            </div>
            <div class="runtime-item__tags">
              <ElTag :type="status.callbackReady ? 'success' : 'danger'" effect="plain">
                {{ status.callbackReady ? '安全回调就绪' : '安全回调未就绪' }}
              </ElTag>
            </div>
            <p class="runtime-item__note">
              回调凭据只在服务器和微信后台维护，本页面不可读取或修改。
            </p>
          </section>
        </div>

        <ElAlert
          v-if="status.repairEligibleCount > 0"
          class="repair-alert"
          :title="`Repair Scanner 当前存在 ${status.repairEligibleCount} 笔候选`"
          :description="repairCandidateDescription"
          type="error"
          :closable="false"
          show-icon
        />

        <ElAlert
          v-if="runtimeValidation"
          class="runtime-validation"
          :title="runtimeValidation"
          type="warning"
          :closable="false"
          show-icon
        />

        <div class="readiness-grid">
          <div v-for="item in readinessItems" :key="item.label" class="readiness-item">
            <span>{{ item.label }}</span>
            <ElTag :type="item.ready ? 'success' : 'danger'" size="small" effect="plain">
              {{ item.ready ? '就绪' : '未就绪' }}
            </ElTag>
          </div>
        </div>

        <ElDescriptions :column="3" border class="runtime-meta">
          <ElDescriptionsItem label="运行配置来源">
            {{ status.runtimePersisted ? '数据库运行覆盖' : '部署环境默认值' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="配置版本">{{ status.version }}</ElDescriptionsItem>
          <ElDescriptionsItem label="部署默认值">
            采集 {{ enabledText(status.defaultCaptureEnabled) }} / 外呼
            {{ enabledText(status.defaultWorkerEnabled) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="最近修改人">
            {{ status.updatedBy ? `管理员 #${status.updatedBy}` : '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="最近修改时间">
            {{ formatTime(status.updatedAt) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="修改原因">
            {{ status.reason || '-' }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </template>

      <ElEmpty v-else-if="!statusLoading" description="未能读取服务动态运行状态" />
    </ElCard>

    <div v-if="status" class="metric-grid">
      <button type="button" class="metric-card" @click="applyStateFilter('PENDING')">
        <span>待处理</span><strong>{{ status.pendingDeliveries }}</strong>
      </button>
      <button type="button" class="metric-card" @click="applyStateFilter('SENDING')">
        <span>发送中</span><strong>{{ status.sendingDeliveries }}</strong>
      </button>
      <div class="metric-card metric-card--static">
        <span>未知/核对中</span><strong>{{ status.unknownDeliveries }}</strong>
      </div>
      <button
        type="button"
        class="metric-card metric-card--danger"
        @click="applyStateFilter('FAILED')"
      >
        <span>失败</span><strong>{{ status.failedDeliveries }}</strong>
      </button>
      <div class="metric-card metric-card--static">
        <span>用户拒收卡片</span><strong>{{ status.blockedCards }}</strong>
      </div>
    </div>

    <ElCard shadow="never" class="delivery-card">
      <template #header>
        <div class="section-header">
          <div>
            <h2>投递队列</h2>
            <p>仅展示持久化投递与微信失败回调结果，不提供绕过状态机的强制发送。</p>
          </div>
          <ElButton :disabled="deliveriesLoading" @click="loadDeliveries">刷新列表</ElButton>
        </div>
      </template>

      <div class="filters">
        <ElInput
          v-model="filters.orderId"
          clearable
          maxlength="20"
          placeholder="订单 ID"
          @keyup.enter="searchDeliveries"
        />
        <ElSelect v-model="filters.state" clearable placeholder="投递状态">
          <ElOption
            v-for="option in DELIVERY_STATE_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
        <ElButton type="primary" @click="searchDeliveries">查询</ElButton>
        <ElButton @click="resetFilters">重置</ElButton>
      </div>

      <ElTable
        v-loading="deliveriesLoading"
        :data="deliveries"
        row-key="id"
        empty-text="暂无投递记录"
      >
        <ElTableColumn type="expand" width="44">
          <template #default="{ row }">
            <div class="delivery-detail">
              <ElDescriptions :column="3" border size="small">
                <ElDescriptionsItem label="投递 ID">{{ row.id }}</ElDescriptionsItem>
                <ElDescriptionsItem label="卡片 ID">{{ row.cardId }}</ElDescriptionsItem>
                <ElDescriptionsItem label="顺序号">{{ row.sequenceNo }}</ElDescriptionsItem>
                <ElDescriptionsItem label="下次动作">
                  {{ formatTime(row.nextActionAt) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="微信生效时间">
                  {{ formatTime(row.appliedAt) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="最近更新时间">
                  {{ formatTime(row.updatedAt) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="消息回调结果">
                  {{ messageResultLabel(row.messageResultState) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="消息失败码">
                  {{ row.messageFailureCode ?? '-' }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="消息结果时间">
                  {{ formatTime(row.messageResultAt) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem label="卡片阻断" :span="3">
                  {{
                    row.cardSendBlocked
                      ? `${row.cardSendBlockReason || '已阻断'} · ${formatTime(row.cardSendBlockedAt)}`
                      : '否'
                  }}
                </ElDescriptionsItem>
                <ElDescriptionsItem
                  v-if="row.errorCode || row.errorMessage"
                  label="投递诊断"
                  :span="3"
                >
                  {{ diagnosticText(row.errorCode, row.errorMessage) }}
                </ElDescriptionsItem>
                <ElDescriptionsItem v-if="row.messageFailureMessage" label="消息失败说明" :span="3">
                  {{ row.messageFailureMessage }}
                </ElDescriptionsItem>
              </ElDescriptions>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="订单" min-width="190">
          <template #default="{ row }">
            <div class="order-cell">
              <ElButton link type="primary" @click="openOrder(row.orderNo)">
                {{ row.orderNo || `订单 #${row.orderId}` }}
              </ElButton>
              <span>ID：{{ row.orderId }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="目标状态" min-width="145">
          <template #default="{ row }">
            <ElTag :type="targetStatusTone(row.targetStatus)" effect="plain">
              {{ targetStatusLabel(row.targetStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="投递状态" width="115">
          <template #default="{ row }">
            <ElTag :type="deliveryStateTone(row.state)">
              {{ deliveryStateLabel(row.state) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="尝试次数" width="120">
          <template #default="{ row }">
            set {{ row.setAttempts }} / 核对 {{ row.reconciliationAttempts }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="未生效观察" width="105" prop="notAppliedObservations" />
        <ElTableColumn label="阻断" width="90">
          <template #default="{ row }">
            <ElTag :type="row.cardSendBlocked ? 'danger' : 'info'" effect="plain">
              {{ row.cardSendBlocked ? '已阻断' : '否' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="下次动作" width="180">
          <template #default="{ row }">{{ formatTime(row.nextActionAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </ElTableColumn>
      </ElTable>

      <div class="pagination">
        <ElPagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="page.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadDeliveries"
          @size-change="handlePageSizeChange"
        />
      </div>
    </ElCard>

    <ElDialog
      v-model="confirmationVisible"
      :title="confirmation?.title"
      width="min(600px, 92vw)"
      :close-on-click-modal="false"
      destroy-on-close
      @closed="resetConfirmationForm"
    >
      <template v-if="confirmation">
        <ElAlert
          :title="confirmation.message"
          :type="confirmation.tone"
          :closable="false"
          show-icon
        />
        <ElForm label-position="top" class="confirmation-form">
          <ElFormItem label="变更原因（会写入审计记录）" required>
            <ElInput
              v-model="confirmationForm.reason"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="说明本次开启或关闭的真实原因、验收范围和责任边界"
            />
            <div v-if="reasonError" class="form-error">{{ reasonError }}</div>
          </ElFormItem>
          <ElFormItem :label="`输入“${confirmation.phrase}”继续`" required>
            <ElInput
              v-model="confirmationForm.phrase"
              autocomplete="off"
              :placeholder="confirmation.phrase"
            />
            <div v-if="phraseError" class="form-error">{{ phraseError }}</div>
          </ElFormItem>
        </ElForm>
      </template>
      <template #footer>
        <ElButton :disabled="runtimeSaving" @click="confirmationVisible = false">取消</ElButton>
        <ElButton
          :type="confirmation?.tone === 'error' ? 'danger' : 'warning'"
          :disabled="Boolean(reasonError || phraseError)"
          :loading="runtimeSaving"
          @click="submitRuntimeUpdate"
        >
          确认并保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue'
  import { useRouter } from 'vue-router'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    fetchWechatServiceCardDeliveries,
    fetchWechatServiceCardStatus,
    updateWechatServiceCardRuntime
  } from '@/api/wechat-service-card'
  import { useAuth } from '@/hooks/core/useAuth'
  import { formatLocalDateTime } from '@/utils/date-time'
  import { isHttpError } from '@/utils/http/error'
  import {
    DELIVERY_STATE_OPTIONS,
    deliveryStateLabel,
    deliveryStateTone,
    messageResultLabel,
    runtimeChanged,
    runtimeConfirmation,
    runtimeDraft,
    runtimeStatusChanged,
    targetStatusLabel,
    targetStatusTone,
    validateConfirmationPhrase,
    validateRuntimeDraft,
    validateRuntimeReason,
    type RuntimeConfirmation,
    type RuntimeDraft
  } from './service-card-state'

  defineOptions({ name: 'WechatServiceCard' })

  const router = useRouter()
  const { hasAuth } = useAuth()
  const status = ref<Api.WechatServiceCard.Status | null>(null)
  const statusLoading = ref(false)
  const runtimeSaving = ref(false)
  const deliveriesLoading = ref(false)
  const deliveries = ref<Api.WechatServiceCard.Delivery[]>([])
  const draft = reactive<RuntimeDraft>({ captureEnabled: false, workerEnabled: false })
  const filters = reactive<{
    orderId: string
    state: Api.WechatServiceCard.DeliveryState | ''
  }>({ orderId: '', state: '' })
  const page = reactive({ current: 1, size: 20, total: 0 })
  const confirmationVisible = ref(false)
  const confirmation = ref<RuntimeConfirmation | null>(null)
  const confirmationForm = reactive({ reason: '', phrase: '' })

  const canWrite = computed(() => hasAuth('wechat-service-card:runtime:write'))
  const runtimeDirty = computed(() => Boolean(status.value && runtimeChanged(status.value, draft)))
  const runtimeValidation = computed(() =>
    status.value ? validateRuntimeDraft(draft, status.value) : null
  )
  const workerConfigurationReady = computed(
    () =>
      Boolean(status.value?.templateConfigured) &&
      Boolean(status.value?.imageReady) &&
      Boolean(status.value?.miniProgramCredentialsReady) &&
      Boolean(status.value?.callbackReady)
  )
  const interactionDisabled = computed(
    () => statusLoading.value || runtimeSaving.value || !canWrite.value
  )
  const captureSwitchDisabled = computed(
    () => interactionDisabled.value || (!draft.captureEnabled && status.value?.imageReady === false)
  )
  const workerSwitchDisabled = computed(
    () =>
      interactionDisabled.value ||
      (!draft.workerEnabled &&
        (!draft.captureEnabled ||
          status.value?.captureEnabled !== true ||
          !workerConfigurationReady.value))
  )
  const readinessItems = computed(() => [
    { label: '服务动态模板', ready: Boolean(status.value?.templateConfigured) },
    { label: '公开兜底图片', ready: Boolean(status.value?.imageReady) },
    { label: '小程序调用凭据', ready: Boolean(status.value?.miniProgramCredentialsReady) },
    { label: '安全消息回调', ready: Boolean(status.value?.callbackReady) }
  ])
  const repairCandidateDescription = computed(() => {
    if (!status.value) return ''
    const earliest = formatTime(status.value.repairEligibleEarliestPaidAt)
    const latest = formatTime(status.value.repairEligibleLatestPaidAt)
    return `候选支付时间范围：${earliest} 至 ${latest}。候选包括近 24 小时漏建卡支付，以及有效更新窗口内的非终态卡；开启采集前请先确认范围。`
  })
  const reasonError = computed(() => validateRuntimeReason(confirmationForm.reason))
  const phraseError = computed(() =>
    confirmation.value
      ? validateConfirmationPhrase(confirmationForm.phrase, confirmation.value.phrase)
      : '缺少确认上下文'
  )

  const enabledText = (enabled: boolean) => (enabled ? '开启' : '关闭')
  const formatTime = (value?: string | null) => formatLocalDateTime(value, 'second')
  const diagnosticText = (code: string, message: string) =>
    [code, message].filter((item) => item?.trim()).join('：') || '-'

  const applyStatus = (value: Api.WechatServiceCard.Status) => {
    status.value = value
    Object.assign(draft, runtimeDraft(value))
  }

  const loadStatus = async () => {
    statusLoading.value = true
    try {
      applyStatus(await fetchWechatServiceCardStatus())
    } finally {
      statusLoading.value = false
    }
  }

  const normalizedOrderId = (): string | undefined => {
    const value = filters.orderId.trim()
    if (!value) return undefined
    if (
      !/^\d{1,20}$/.test(value) ||
      BigInt(value) <= 0n ||
      BigInt(value) > 9_223_372_036_854_775_807n
    ) {
      ElMessage.error('订单 ID 必须是正整数')
      return ''
    }
    return value
  }

  const loadDeliveries = async () => {
    const orderId = normalizedOrderId()
    if (orderId === '') return
    deliveriesLoading.value = true
    try {
      const result = await fetchWechatServiceCardDeliveries({
        current: page.current,
        size: page.size,
        ...(orderId ? { orderId } : {}),
        ...(filters.state ? { state: filters.state } : {})
      })
      deliveries.value = result.records
      page.current = result.current
      page.size = result.size
      page.total = result.total
    } finally {
      deliveriesLoading.value = false
    }
  }

  const reloadAll = async () => {
    if (runtimeDirty.value) {
      try {
        await ElMessageBox.confirm('刷新会丢弃尚未保存的运行开关修改，是否继续？', '刷新状态', {
          type: 'warning',
          confirmButtonText: '丢弃并刷新',
          cancelButtonText: '取消'
        })
      } catch {
        return
      }
    }
    await Promise.all([loadStatus(), loadDeliveries()])
  }

  const handleCaptureChange = (value: string | number | boolean) => {
    draft.captureEnabled = Boolean(value)
    if (!draft.captureEnabled) draft.workerEnabled = false
  }

  const handleWorkerChange = (value: string | number | boolean) => {
    draft.workerEnabled = Boolean(value)
  }

  const openRuntimeConfirmation = () => {
    if (!status.value || !runtimeDirty.value || runtimeValidation.value) return
    confirmation.value = runtimeConfirmation(status.value, draft)
    confirmationForm.reason = ''
    confirmationForm.phrase = ''
    confirmationVisible.value = true
  }

  const resetConfirmationForm = () => {
    confirmation.value = null
    confirmationForm.reason = ''
    confirmationForm.phrase = ''
  }

  const submitRuntimeUpdate = async () => {
    if (!status.value || !confirmation.value || reasonError.value || phraseError.value) return
    const previousStatus = status.value
    const attemptedDraft = { ...draft }
    runtimeSaving.value = true
    try {
      const updated = await updateWechatServiceCardRuntime({
        captureEnabled: draft.captureEnabled,
        workerEnabled: draft.workerEnabled,
        version: status.value.version,
        reason: confirmationForm.reason.trim()
      })
      applyStatus(updated)
      confirmationVisible.value = false
      await loadDeliveries()
    } catch (error) {
      if (isHttpError(error) && error.httpStatus === 409) {
        confirmationVisible.value = false
        ElMessage.warning('运行配置已被其他管理员修改，已刷新为最新状态')
        await loadStatus()
        return
      }
      try {
        await loadStatus()
        if (status.value && runtimeStatusChanged(previousStatus, status.value)) {
          confirmationVisible.value = false
          ElMessage.warning('保存响应异常但配置可能已生效，已刷新，请核对')
          return
        }
        Object.assign(draft, attemptedDraft)
      } catch {
        Object.assign(draft, attemptedDraft)
      }
      throw error
    } finally {
      runtimeSaving.value = false
    }
  }

  const searchDeliveries = () => {
    page.current = 1
    void loadDeliveries()
  }

  const resetFilters = () => {
    filters.orderId = ''
    filters.state = ''
    page.current = 1
    void loadDeliveries()
  }

  const applyStateFilter = (stateValue: Api.WechatServiceCard.DeliveryState) => {
    filters.state = stateValue
    page.current = 1
    void loadDeliveries()
  }

  const handlePageSizeChange = () => {
    page.current = 1
    void loadDeliveries()
  }

  const openOrder = (orderNo: string) => {
    if (!orderNo) return
    void router.push({ path: '/trade/orders', query: { orderNo } })
  }

  onMounted(() => Promise.all([loadStatus(), loadDeliveries()]))
</script>

<style scoped lang="scss">
  .service-card-page {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .section-header,
  .runtime-item__heading {
    display: flex;
    gap: 16px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .section-header h1,
  .section-header h2,
  .runtime-item h2 {
    margin: 0;
    font-size: 16px;
    font-weight: 600;
    line-height: 24px;
    color: var(--el-text-color-primary);
  }

  .section-header p,
  .runtime-item__heading p,
  .runtime-item__note {
    margin: 3px 0 0;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .section-header__actions {
    display: flex;
    flex-shrink: 0;
    gap: 8px;
  }

  .runtime-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
  }

  .runtime-item {
    min-width: 0;
    padding: 16px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .runtime-item--danger {
    border-color: var(--el-color-danger-light-7);
  }

  .runtime-item__tags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 16px;
  }

  .repair-alert,
  .runtime-validation,
  .runtime-meta,
  .readiness-grid {
    margin-top: 14px;
  }

  .readiness-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px;
  }

  .readiness-item {
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
    min-width: 0;
    padding: 10px 12px;
    font-size: 13px;
    color: var(--el-text-color-regular);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
  }

  .metric-grid {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 12px;
  }

  .metric-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-width: 0;
    padding: 16px 18px;
    font: inherit;
    color: var(--el-text-color-regular);
    text-align: left;
    cursor: pointer;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    transition: border-color 0.2s ease;
  }

  .metric-card:hover {
    border-color: var(--el-color-primary-light-5);
  }

  .metric-card strong {
    font-size: 23px;
    line-height: 28px;
    color: var(--el-text-color-primary);
  }

  .metric-card--danger strong {
    color: var(--el-color-danger);
  }

  .metric-card--static {
    cursor: default;
  }

  .filters {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-bottom: 14px;
  }

  .filters :deep(.el-input),
  .filters :deep(.el-select) {
    width: 220px;
  }

  .order-cell {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
  }

  .order-cell span {
    font-size: 11px;
    line-height: 17px;
    color: var(--el-text-color-secondary);
  }

  .delivery-detail {
    padding: 12px 54px 18px;
  }

  .pagination {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .confirmation-form {
    margin-top: 18px;
  }

  .form-error {
    margin-top: 4px;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-color-danger);
  }

  @media (width <= 1200px) {
    .runtime-grid {
      grid-template-columns: 1fr;
    }

    .readiness-grid,
    .metric-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (width <= 720px) {
    .section-header {
      flex-direction: column;
    }

    .section-header__actions,
    .filters,
    .filters :deep(.el-input),
    .filters :deep(.el-select) {
      width: 100%;
    }

    .readiness-grid,
    .metric-grid {
      grid-template-columns: 1fr;
    }

    .delivery-detail {
      padding-right: 8px;
      padding-left: 8px;
    }
  }
</style>
