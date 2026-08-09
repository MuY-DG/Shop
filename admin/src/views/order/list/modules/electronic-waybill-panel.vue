<template>
  <div class="electronic-waybill-panel" v-loading="loading">
    <ElAlert
      v-if="!canManage"
      title="当前账号没有电子面单管理权限"
      description="不会请求电子面单上下文，也不能生成、刷新、取消或确认发货。"
      type="warning"
      :closable="false"
      show-icon
    />

    <template v-else>
      <ElAlert
        v-if="phase === 'OPENING'"
        title="正在加载电子面单上下文"
        type="info"
        :closable="false"
        show-icon
      />

      <template v-else-if="context">
        <ElAlert
          :title="modeTitle"
          :description="modeDescription"
          :type="context.mode === 'DISABLED' ? 'warning' : 'info'"
          :closable="false"
          show-icon
          class="waybill-panel__mode"
        />

        <ElAlert
          v-if="!canConfirmShipment"
          title="当前账号缺少订单发货权限"
          description="可以管理电子面单，但“确认发货”同时需要 order:ship 权限。"
          type="warning"
          :closable="false"
          show-icon
          class="waybill-panel__mode"
        />

        <ElAlert
          v-if="context.blockers.length > 0"
          title="当前暂不能生成电子面单"
          type="warning"
          :closable="false"
          show-icon
          class="waybill-panel__blockers"
        >
          <ul class="waybill-panel__blocker-list">
            <li v-for="blocker in context.blockers" :key="blocker">{{ blocker }}</li>
          </ul>
        </ElAlert>

        <section class="waybill-panel__section">
          <div class="waybill-panel__section-title">寄收件快照</div>
          <div class="waybill-panel__address-grid">
            <div class="waybill-panel__address">
              <strong>寄件人</strong>
              <span>{{ formatContact(context.sender) }}</span>
              <span>{{ formatAddress(context.sender) }}</span>
            </div>
            <div class="waybill-panel__address">
              <strong>收件人</strong>
              <span>{{ formatContact(context.receiver) }}</span>
              <span>{{ formatAddress(context.receiver) }}</span>
            </div>
          </div>
        </section>

        <section v-if="attempt" class="waybill-panel__section waybill-panel__attempt">
          <div class="waybill-panel__section-header">
            <div class="waybill-panel__section-title">电子面单记录</div>
            <ElTag :type="attemptStatusType(attempt.status)" effect="light">
              {{ attemptStatusLabel(attempt.status) }}
            </ElTag>
          </div>

          <dl class="waybill-panel__facts">
            <div>
              <dt>运行环境</dt>
              <dd>{{ attempt.environment === 'SANDBOX' ? '微信沙箱' : '正式环境' }}</dd>
            </div>
            <div>
              <dt>快递公司</dt>
              <dd>{{ attempt.deliveryName || '-' }}（{{ attempt.deliveryId || '-' }}）</dd>
            </div>
            <div>
              <dt>服务类型</dt>
              <dd>{{ attempt.serviceName || '-' }}（{{ attempt.serviceType }}）</dd>
            </div>
            <div>
              <dt>面单号</dt>
              <dd class="waybill-panel__mono">{{ attempt.waybillNo || '-' }}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>{{ formatDateTime(attempt.createdAt) }}</dd>
            </div>
            <div>
              <dt>打印请求</dt>
              <dd>{{ attempt.printCount }} 次</dd>
            </div>
          </dl>

          <div class="waybill-panel__actions">
            <ElButton
              v-auth="'order:waybill:manage'"
              :loading="operation === 'refresh'"
              :disabled="!actionEnabled('refresh')"
              @click="handleRefresh"
            >
              刷新状态
            </ElButton>
            <ElButton
              v-auth="'order:waybill:print'"
              :loading="operation === 'preview'"
              :disabled="!actionEnabled('preview')"
              @click="handlePrintRequest(0, false)"
            >
              预览面单
            </ElButton>
            <ElButton
              v-auth="'order:waybill:print'"
              type="primary"
              plain
              :loading="operation === 'print'"
              :disabled="!actionEnabled('print')"
              @click="handlePrintRequest(1, true)"
            >
              {{ attempt.printCount > 0 ? '重打面单' : '打印面单' }}
            </ElButton>
            <ElButton
              v-auth="'order:waybill:manage'"
              type="warning"
              plain
              :loading="operation === 'cancel'"
              :disabled="!actionEnabled('cancel')"
              @click="handleCancel"
            >
              取消面单
            </ElButton>
            <ElButton
              v-if="canConfirmShipment"
              v-auth="'order:waybill:manage'"
              type="success"
              :loading="operation === 'confirm'"
              :disabled="!actionEnabled('confirm')"
              @click="handleConfirmShipment"
            >
              确认发货
            </ElButton>
          </div>
        </section>

        <section v-if="context.canCreate" class="waybill-panel__section">
          <div class="waybill-panel__section-title">生成电子面单</div>
          <ElForm label-width="112px" class="waybill-panel__form">
            <div class="waybill-panel__parcel-grid">
              <ElFormItem label="包裹数量" required>
                <ElInputNumber
                  v-model="form.count"
                  :min="1"
                  :precision="0"
                  controls-position="right"
                />
              </ElFormItem>
              <ElFormItem label="重量（kg）" required>
                <ElInputNumber
                  v-model="form.weightKg"
                  :min="0.01"
                  :precision="2"
                  controls-position="right"
                />
              </ElFormItem>
              <ElFormItem label="长度（cm）" required>
                <ElInputNumber
                  v-model="form.lengthCm"
                  :min="0.1"
                  :precision="1"
                  controls-position="right"
                />
              </ElFormItem>
              <ElFormItem label="宽度（cm）" required>
                <ElInputNumber
                  v-model="form.widthCm"
                  :min="0.1"
                  :precision="1"
                  controls-position="right"
                />
              </ElFormItem>
              <ElFormItem label="高度（cm）" required>
                <ElInputNumber
                  v-model="form.heightCm"
                  :min="0.1"
                  :precision="1"
                  controls-position="right"
                />
              </ElFormItem>
            </div>
            <ElFormItem label="预计揽件时间">
              <ElDatePicker
                v-model="expectPickupAt"
                type="datetime"
                placeholder="可选，请选择预计揽件时间"
                style="width: 100%"
              />
            </ElFormItem>
            <ElFormItem label="面单备注">
              <ElInput
                v-model="form.remark"
                type="textarea"
                :rows="2"
                maxlength="255"
                show-word-limit
                placeholder="可选，将作为电子面单备注提交"
              />
            </ElFormItem>
          </ElForm>
          <div class="waybill-panel__create-footer">
            <span>生成面单不会改变订单状态；只有“确认发货”才会创建发货记录。</span>
            <ElButton
              v-auth="'order:waybill:manage'"
              type="primary"
              :loading="operation === 'create'"
              :disabled="!actionEnabled('create')"
              @click="handleCreate"
            >
              生成电子面单
            </ElButton>
          </div>
        </section>

        <section v-if="sandboxActions.length > 0" class="waybill-panel__section">
          <div class="waybill-panel__section-title">沙箱物流轨迹模拟</div>
          <ElAlert
            title="仅显示并提交服务端返回的本次可用动作"
            type="info"
            :closable="false"
            class="waybill-panel__sandbox-alert"
          />
          <div class="waybill-panel__sandbox-controls">
            <ElSelect
              v-model="sandboxActionType"
              placeholder="请选择沙箱动作"
              @change="handleSandboxActionChange"
            >
              <ElOption
                v-for="action in sandboxActions"
                :key="action.actionType"
                :label="`${action.actionType} · ${action.actionMessage}`"
                :value="action.actionType"
              />
            </ElSelect>
            <ElInput v-model="sandboxActionMessage" maxlength="255" placeholder="轨迹描述" />
            <ElButton
              v-auth="'order:waybill:test'"
              type="primary"
              plain
              :loading="operation === 'simulate'"
              :disabled="!actionEnabled('simulate') || sandboxActionType === null"
              @click="handleSimulate"
            >
              提交模拟轨迹
            </ElButton>
          </div>
        </section>
      </template>

      <ElAlert
        v-else-if="!loading"
        title="电子面单上下文加载失败"
        description="请关闭后重新打开订单发货窗口，或稍后重试。"
        type="error"
        :closable="false"
        show-icon
      />
    </template>

    <WaybillPreviewDialog
      v-model="previewVisible"
      :blob="previewBlob"
      :order-key="orderId"
      :content-order-key="previewOrderKey"
      :title="previewTitle"
      :auto-print="previewAutoPrint"
      @printed="previewAutoPrint = false"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    cancelElectronicWaybill,
    confirmElectronicWaybillShipment,
    createElectronicWaybill,
    fetchElectronicWaybillContext,
    fetchElectronicWaybillPrint,
    refreshElectronicWaybill,
    simulateElectronicWaybillEvent
  } from '@/api/waybill'
  import { formatLocalDateTime } from '@/utils/date-time'
  import {
    buildWaybillCreateRequest,
    createWaybillIdempotencyKey,
    isCurrentWaybillResponse,
    resolveWaybillCancelFeedback,
    resolveWaybillPanelPhase,
    visibleSandboxActions,
    waybillActionEnabled,
    type WaybillOperation
  } from '../waybill-workflow'
  import WaybillPreviewDialog from './waybill-preview-dialog.vue'

  const props = defineProps<{
    open: boolean
    orderId: number | null
    orderNo: string
    initialAttempt?: Api.Waybill.Attempt | null
    canManage: boolean
    canPrint: boolean
    canTest: boolean
    canConfirmShipment: boolean
  }>()

  const emit = defineEmits<{
    'attempt-change': [attempt: Api.Waybill.Attempt | null]
    'busy-change': [busy: boolean]
    'shipment-confirmed': [shipment: Api.Order.Shipment]
  }>()

  const context = ref<Api.Waybill.Context | null>(null)
  const attempt = ref<Api.Waybill.Attempt | null>(props.initialAttempt || null)
  const loading = ref(false)
  const operation = ref<WaybillOperation | null>(null)
  const requestGeneration = ref(0)
  const idempotencyKey = ref('')
  const expectPickupAt = ref<Date | null>(null)
  const sandboxActionType = ref<Api.Waybill.SandboxActionType | null>(null)
  const sandboxActionMessage = ref('')
  const previewVisible = ref(false)
  const previewBlob = ref<Blob | null>(null)
  const previewOrderKey = ref<number | null>(null)
  const previewAutoPrint = ref(false)
  const previewTitle = ref('电子面单预览')

  const form = reactive<Api.Waybill.Parcel & { remark: string }>({
    count: 1,
    weightKg: 1,
    lengthCm: 10,
    widthCm: 10,
    heightCm: 10,
    remark: ''
  })

  const access = computed(() => ({
    canManage: props.canManage,
    canPrint: props.canPrint,
    canTest: props.canTest,
    canConfirmShipment: props.canConfirmShipment
  }))
  const phase = computed(() => resolveWaybillPanelPhase(props.open, loading.value, attempt.value))
  const busy = computed(() => loading.value || operation.value !== null)
  const sandboxActions = computed(() =>
    visibleSandboxActions(context.value, attempt.value, props.canTest)
  )

  const modeTitle = computed(() => {
    if (context.value?.mode === 'SANDBOX') return '当前使用微信官方电子面单沙箱'
    if (context.value?.mode === 'PRODUCTION') return '当前使用正式电子面单配置'
    return '电子面单功能当前未启用'
  })

  const modeDescription = computed(() => {
    if (context.value?.mode === 'SANDBOX') {
      return '可在没有月结账号的情况下跑通生成、预览、打印和轨迹模拟；生成后订单仍为待发货。'
    }
    if (context.value?.mode === 'PRODUCTION') {
      return '将使用后台已保存的正式快递账号标识；不会在前端接收账号密码。'
    }
    return '请先在“订单管理 / 电子面单配置”中选择沙箱或正式模式。'
  })

  const resetForm = (parcel: Api.Waybill.Parcel) => {
    form.count = parcel.count
    form.weightKg = parcel.weightKg
    form.lengthCm = parcel.lengthCm
    form.widthCm = parcel.widthCm
    form.heightCm = parcel.heightCm
    form.remark = ''
    expectPickupAt.value = null
    idempotencyKey.value = createWaybillIdempotencyKey()
  }

  const applyAttempt = (nextAttempt: Api.Waybill.Attempt | null) => {
    attempt.value = nextAttempt
    if (context.value) context.value.currentAttempt = nextAttempt
    emit('attempt-change', nextAttempt)
  }

  const closePreview = () => {
    previewVisible.value = false
    previewBlob.value = null
    previewOrderKey.value = null
    previewAutoPrint.value = false
  }

  const currentRequest = (generation: number, orderId: number) =>
    isCurrentWaybillResponse(
      generation,
      requestGeneration.value,
      orderId,
      props.orderId,
      props.open
    )

  const loadContext = async () => {
    const orderId = props.orderId
    const generation = ++requestGeneration.value
    operation.value = null
    closePreview()
    context.value = null
    attempt.value = props.initialAttempt || null
    sandboxActionType.value = null
    sandboxActionMessage.value = ''
    if (!props.open || !orderId || !props.canManage) {
      loading.value = false
      return
    }

    loading.value = true
    try {
      const response = await fetchElectronicWaybillContext(orderId)
      if (!currentRequest(generation, orderId)) return
      context.value = response
      applyAttempt(response.currentAttempt)
      resetForm(response.defaultParcel)
    } catch {
      if (currentRequest(generation, orderId)) context.value = null
    } finally {
      if (currentRequest(generation, orderId)) loading.value = false
    }
  }

  const actionEnabled = (action: WaybillOperation) =>
    waybillActionEnabled(action, context.value, attempt.value, operation.value, access.value)

  const beginOperation = (action: WaybillOperation) => {
    if (!actionEnabled(action)) return null
    const orderId = props.orderId
    if (!orderId) return null
    const request = { orderId, generation: requestGeneration.value }
    operation.value = action
    return request
  }

  const finishOperation = (
    action: WaybillOperation,
    request: { orderId: number; generation: number }
  ) => {
    if (currentRequest(request.generation, request.orderId) && operation.value === action) {
      operation.value = null
    }
  }

  const runOperation = async (
    action: WaybillOperation,
    work: (orderId: number, generation: number) => Promise<void>
  ) => {
    const request = beginOperation(action)
    if (!request) return
    try {
      await work(request.orderId, request.generation)
    } finally {
      finishOperation(action, request)
    }
  }

  const validateParcel = () => {
    if (!Number.isInteger(form.count) || form.count < 1) return '包裹数量必须是正整数'
    if (!Number.isFinite(form.weightKg) || form.weightKg <= 0) return '包裹重量必须大于 0'
    if (!Number.isFinite(form.lengthCm) || form.lengthCm <= 0) return '包裹长度必须大于 0'
    if (!Number.isFinite(form.widthCm) || form.widthCm <= 0) return '包裹宽度必须大于 0'
    if (!Number.isFinite(form.heightCm) || form.heightCm <= 0) return '包裹高度必须大于 0'
    if (expectPickupAt.value && expectPickupAt.value.getTime() <= Date.now()) {
      return '预计揽件时间必须晚于当前时间'
    }
    return ''
  }

  const handleCreate = async () => {
    const validationError = validateParcel()
    if (validationError) {
      ElMessage.warning(validationError)
      return
    }
    await runOperation('create', async (orderId, generation) => {
      const created = await createElectronicWaybill(
        orderId,
        buildWaybillCreateRequest({
          idempotencyKey: idempotencyKey.value,
          parcel: form,
          remark: form.remark,
          expectTime: expectPickupAt.value
            ? Math.floor(expectPickupAt.value.getTime() / 1000)
            : undefined
        })
      )
      if (!currentRequest(generation, orderId)) return
      applyAttempt(created)
      if (context.value) {
        context.value.canCreate =
          (created.status === 'FAILED' || created.status === 'CANCELED') &&
          context.value.blockers.length === 0
      }
      if (created.status === 'FAILED' || created.status === 'CANCELED') {
        idempotencyKey.value = createWaybillIdempotencyKey()
      }
      if (created.status === 'CREATED') {
        ElMessage.success('电子面单已生成；订单尚未发货')
      } else if (created.status === 'UNKNOWN' || created.status === 'CREATING') {
        ElMessage.warning('电子面单结果尚未确定，请使用“刷新状态”恢复，勿重复生成')
      } else {
        ElMessage.error('电子面单生成失败，可检查阻断信息后重新生成')
      }
    })
  }

  const handleRefresh = async () => {
    const recordId = attempt.value?.id
    if (!recordId) return
    await runOperation('refresh', async (orderId, generation) => {
      const refreshed = await refreshElectronicWaybill(orderId, recordId)
      if (!currentRequest(generation, orderId)) return
      applyAttempt(refreshed)
      if (refreshed.status === 'FAILED' || refreshed.status === 'CANCELED') {
        const refreshedContext = await fetchElectronicWaybillContext(orderId)
        if (!currentRequest(generation, orderId)) return
        context.value = refreshedContext
        applyAttempt(refreshedContext.currentAttempt)
        resetForm(refreshedContext.defaultParcel)
      }
      ElMessage.success('电子面单状态已刷新')
    })
  }

  const handleCancel = async () => {
    const recordId = attempt.value?.id
    if (!recordId) return
    const request = beginOperation('cancel')
    if (!request) return
    try {
      const confirmed = await ElMessageBox.confirm(
        `确定取消订单 ${props.orderNo} 的电子面单吗？取消成功后才能重新手动填写运单。`,
        '取消电子面单',
        { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '返回' }
      ).then(
        () => true,
        () => false
      )
      if (!confirmed) return

      const canceled = await cancelElectronicWaybill(request.orderId, recordId)
      if (!currentRequest(request.generation, request.orderId)) return
      applyAttempt(canceled)
      const feedback = resolveWaybillCancelFeedback(canceled.status)
      if (feedback.tone === 'success') ElMessage.success(feedback.message)
      else if (feedback.tone === 'warning') ElMessage.warning(feedback.message)
      else ElMessage.error(feedback.message)
      const refreshedContext = await fetchElectronicWaybillContext(request.orderId)
      if (!currentRequest(request.generation, request.orderId)) return
      context.value = refreshedContext
      applyAttempt(refreshedContext.currentAttempt)
      if (feedback.manualUnlocked) resetForm(refreshedContext.defaultParcel)
    } finally {
      finishOperation('cancel', request)
    }
  }

  const handlePrintRequest = async (printType: Api.Waybill.PrintType, autoPrint: boolean) => {
    const recordId = attempt.value?.id
    const action: WaybillOperation = autoPrint ? 'print' : 'preview'
    if (!recordId) return
    await runOperation(action, async (orderId, generation) => {
      const blob = await fetchElectronicWaybillPrint(orderId, recordId, printType)
      if (!currentRequest(generation, orderId)) return
      previewBlob.value = blob
      previewOrderKey.value = orderId
      previewAutoPrint.value = autoPrint
      previewTitle.value = `${autoPrint ? '打印' : '预览'}电子面单 · ${attempt.value?.waybillNo || props.orderNo}`
      previewVisible.value = true
      if (attempt.value) {
        applyAttempt({
          ...attempt.value,
          printCount: attempt.value.printCount + 1,
          lastPrintedAt: new Date().toISOString()
        })
      }
    })
  }

  const handleConfirmShipment = async () => {
    const recordId = attempt.value?.id
    if (!recordId) return
    const request = beginOperation('confirm')
    if (!request) return
    try {
      const confirmed = await ElMessageBox.confirm(
        `确认使用面单 ${attempt.value?.waybillNo || '-'} 发货吗？确认后订单将进入待收货。`,
        '确认电子面单发货',
        { type: 'warning', confirmButtonText: '确认发货', cancelButtonText: '返回' }
      ).then(
        () => true,
        () => false
      )
      if (!confirmed) return

      const shipment = await confirmElectronicWaybillShipment(request.orderId, recordId)
      if (!currentRequest(request.generation, request.orderId)) return
      applyAttempt(
        attempt.value
          ? {
              ...attempt.value,
              status: 'CONFIRMED',
              confirmedAt: new Date().toISOString(),
              canRefresh: false,
              canCancel: false,
              canPrint: false,
              canConfirmShipment: false,
              canSimulate: false
            }
          : null
      )
      emit('shipment-confirmed', shipment)
    } finally {
      finishOperation('confirm', request)
    }
  }

  const handleSandboxActionChange = (value: Api.Waybill.SandboxActionType) => {
    const selected = sandboxActions.value.find((action) => action.actionType === value)
    sandboxActionMessage.value = selected?.actionMessage || ''
  }

  const handleSimulate = async () => {
    const recordId = attempt.value?.id
    const actionType = sandboxActionType.value
    const actionMessage = sandboxActionMessage.value.trim()
    if (!recordId || actionType === null || !actionMessage) {
      ElMessage.warning('请选择沙箱动作并填写轨迹描述')
      return
    }
    if (!sandboxActions.value.some((action) => action.actionType === actionType)) return

    await runOperation('simulate', async (orderId, generation) => {
      const simulated = await simulateElectronicWaybillEvent(orderId, recordId, {
        actionType,
        actionMessage
      })
      if (!currentRequest(generation, orderId)) return
      applyAttempt(simulated)
      ElMessage.success('沙箱轨迹已提交')
    })
  }

  const formatDateTime = (value: string | null | undefined) => formatLocalDateTime(value)

  const formatContact = (contact: Api.Waybill.Sender | Api.Waybill.Receiver | null) => {
    if (!contact) return '-'
    const mobile = contact.mobile
      ? `${contact.mobile.slice(0, 3)}****${contact.mobile.slice(-4)}`
      : '-'
    return [contact.name, mobile, contact.company].filter(Boolean).join(' / ') || '-'
  }

  const formatAddress = (contact: Api.Waybill.Sender | Api.Waybill.Receiver | null) => {
    if (!contact) return '-'
    const receiver = contact as Api.Waybill.Receiver
    return (
      [
        contact.province,
        contact.city,
        contact.district,
        contact.detailAddress,
        receiver.locationName,
        receiver.doorplate
      ]
        .filter(Boolean)
        .join('') || '-'
    )
  }

  const attemptStatusLabel = (status: Api.Waybill.AttemptStatus) => {
    const labels: Record<Api.Waybill.AttemptStatus, string> = {
      CREATING: '生成中',
      CREATED: '已生成',
      CANCELING: '取消中',
      CANCELED: '已取消',
      UNKNOWN: '结果待恢复',
      FAILED: '生成失败',
      CONFIRMED: '已确认发货'
    }
    return labels[status]
  }

  const attemptStatusType = (
    status: Api.Waybill.AttemptStatus
  ): 'primary' | 'success' | 'warning' | 'danger' | 'info' => {
    if (status === 'CREATED') return 'success'
    if (status === 'CONFIRMED') return 'primary'
    if (status === 'FAILED') return 'danger'
    if (status === 'UNKNOWN' || status === 'CREATING' || status === 'CANCELING') return 'warning'
    return 'info'
  }

  watch(busy, (value) => emit('busy-change', value), { immediate: true })
  watch(
    () => [props.open, props.orderId, props.canManage] as const,
    () => void loadContext(),
    { immediate: true }
  )
  watch(
    () => props.initialAttempt,
    (value) => {
      if (!loading.value && !operation.value && !context.value) attempt.value = value || null
    }
  )

  onBeforeUnmount(() => {
    requestGeneration.value += 1
    closePreview()
    emit('busy-change', false)
  })
</script>

<style scoped lang="scss">
  .electronic-waybill-panel {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-height: 220px;
  }

  .waybill-panel__mode,
  .waybill-panel__blockers {
    margin-bottom: 0;
  }

  .waybill-panel__blocker-list {
    padding-left: 20px;
    margin: 8px 0 0;
  }

  .waybill-panel__section {
    padding: 16px;
    background: var(--el-fill-color-extra-light);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  .waybill-panel__section-header {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
  }

  .waybill-panel__section-title {
    margin-bottom: 14px;
    font-size: 15px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .waybill-panel__section-header .waybill-panel__section-title {
    margin-bottom: 0;
  }

  .waybill-panel__address-grid,
  .waybill-panel__parcel-grid,
  .waybill-panel__facts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px 18px;
  }

  .waybill-panel__address {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;
    font-size: 13px;
    line-height: 20px;
    color: var(--el-text-color-regular);

    strong {
      color: var(--el-text-color-primary);
    }
  }

  .waybill-panel__facts {
    padding: 0;
    margin: 14px 0 0;

    > div {
      display: grid;
      grid-template-columns: 82px minmax(0, 1fr);
      gap: 8px;
      font-size: 13px;
      line-height: 21px;
    }

    dt {
      color: var(--el-text-color-secondary);
    }

    dd {
      min-width: 0;
      margin: 0;
      color: var(--el-text-color-primary);
      overflow-wrap: anywhere;
    }
  }

  .waybill-panel__mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  }

  .waybill-panel__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 16px;
  }

  .waybill-panel__form {
    :deep(.el-form-item) {
      margin-bottom: 14px;
    }

    :deep(.el-input-number) {
      width: 100%;
    }
  }

  .waybill-panel__create-footer {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    font-size: 12px;
    line-height: 18px;
    color: var(--el-text-color-secondary);
  }

  .waybill-panel__sandbox-alert {
    margin-bottom: 12px;
  }

  .waybill-panel__sandbox-controls {
    display: grid;
    grid-template-columns: minmax(150px, 0.7fr) minmax(220px, 1.3fr) auto;
    gap: 10px;
  }

  @media (width <= 720px) {
    .waybill-panel__address-grid,
    .waybill-panel__parcel-grid,
    .waybill-panel__facts,
    .waybill-panel__sandbox-controls {
      grid-template-columns: minmax(0, 1fr);
    }

    .waybill-panel__create-footer {
      flex-direction: column;
      align-items: stretch;
    }
  }
</style>
