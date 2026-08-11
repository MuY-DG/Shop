export type ServiceCardTagTone = 'success' | 'warning' | 'danger' | 'info' | 'primary'

export interface RuntimeDraft {
  captureEnabled: boolean
  workerEnabled: boolean
}

export interface RuntimeConfirmation {
  title: string
  message: string
  phrase: string
  tone: 'warning' | 'error'
}

export const DELIVERY_STATE_OPTIONS: Array<{
  value: Api.WechatServiceCard.DeliveryState
  label: string
}> = [
  { value: 'PENDING', label: '待处理' },
  { value: 'SENDING', label: '发送中' },
  { value: 'UNKNOWN', label: '结果未知' },
  { value: 'RECONCILING', label: '核对中' },
  { value: 'SUCCEEDED', label: '已生效' },
  { value: 'FAILED', label: '失败' },
  { value: 'SKIPPED', label: '已跳过' }
]

const DELIVERY_STATE_LABELS = Object.fromEntries(
  DELIVERY_STATE_OPTIONS.map((option) => [option.value, option.label])
) as Record<Api.WechatServiceCard.DeliveryState, string>

const TARGET_STATUS_LABELS: Record<number, string> = {
  1: '用户已付款',
  2: '待商家发货',
  3: '部分发货',
  4: '商家已发货',
  5: '商家重新发货',
  6: '用户已签收',
  7: '售后处理中',
  8: '交易完成',
  9: '售后完成',
  10: '交易取消',
  11: '售后关闭'
}

export function deliveryStateLabel(state: string): string {
  return DELIVERY_STATE_LABELS[state as Api.WechatServiceCard.DeliveryState] || state || '未知'
}

export function deliveryStateTone(state: string): ServiceCardTagTone {
  switch (state) {
    case 'SUCCEEDED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PENDING':
    case 'SENDING':
    case 'UNKNOWN':
    case 'RECONCILING':
      return 'warning'
    default:
      return 'info'
  }
}

export function targetStatusLabel(status: number): string {
  return TARGET_STATUS_LABELS[status]
    ? `${status} · ${TARGET_STATUS_LABELS[status]}`
    : `${status} · 未知状态`
}

export function targetStatusTone(status: number): ServiceCardTagTone {
  if ([8, 9].includes(status)) return 'success'
  if (status === 7) return 'warning'
  if ([10, 11].includes(status)) return 'info'
  return 'primary'
}

export function messageResultLabel(state: string): string {
  if (state === 'FAILED') return '消息失败'
  if (state === 'UNKNOWN') return '未收到失败回调'
  return state || '-'
}

export function runtimeDraft(status: Api.WechatServiceCard.Status): RuntimeDraft {
  return {
    captureEnabled: status.captureEnabled,
    workerEnabled: status.workerEnabled
  }
}

export function runtimeChanged(status: Api.WechatServiceCard.Status, draft: RuntimeDraft): boolean {
  return (
    status.captureEnabled !== draft.captureEnabled || status.workerEnabled !== draft.workerEnabled
  )
}

export function runtimeStatusChanged(
  previous: Api.WechatServiceCard.Status,
  current: Api.WechatServiceCard.Status
): boolean {
  return (
    previous.version !== current.version ||
    previous.captureEnabled !== current.captureEnabled ||
    previous.workerEnabled !== current.workerEnabled
  )
}

export function runtimeBlockers(
  status: Api.WechatServiceCard.Status,
  draft: RuntimeDraft
): string[] {
  const blockers: string[] = []
  const enablingCapture = !status.captureEnabled && draft.captureEnabled
  const enablingWorker = !status.workerEnabled && draft.workerEnabled
  if (enablingCapture && !status.imageReady) blockers.push('服务动态兜底图片未就绪')
  if (enablingWorker && !draft.captureEnabled) blockers.push('开启外呼时必须同时开启采集')
  if (enablingWorker && !status.captureEnabled) {
    blockers.push('必须先单独保存“采集开启、外呼关闭”，验收队列后才能开启外呼')
  }
  if (enablingWorker && !status.templateConfigured) blockers.push('微信服务动态模板未配置')
  if (enablingWorker && !status.imageReady) blockers.push('服务动态兜底图片未就绪')
  if (enablingWorker && !status.miniProgramCredentialsReady) {
    blockers.push('小程序调用凭据未就绪')
  }
  if (enablingWorker && !status.callbackReady) blockers.push('微信安全回调未就绪')
  return [...new Set(blockers)]
}

export function validateRuntimeDraft(
  draft: RuntimeDraft,
  status?: Api.WechatServiceCard.Status
): string | null {
  if (status) return runtimeBlockers(status, draft)[0] || null
  return draft.workerEnabled && !draft.captureEnabled ? '开启外呼时必须同时开启采集' : null
}

export function validateRuntimeReason(value: string): string | null {
  const reason = value.trim()
  if (reason.length < 2) return '请输入至少 2 个字符的真实变更原因'
  if (reason.length > 200) return '变更原因不能超过 200 个字符'
  return null
}

export function runtimeConfirmation(
  status: Api.WechatServiceCard.Status,
  draft: RuntimeDraft
): RuntimeConfirmation {
  const enablingCapture = !status.captureEnabled && draft.captureEnabled
  const enablingWorker = !status.workerEnabled && draft.workerEnabled
  const disablingCapture = status.captureEnabled && !draft.captureEnabled
  const disablingWorker = status.workerEnabled && !draft.workerEnabled
  const changes: string[] = []

  if (enablingCapture) {
    changes.push(
      status.repairEligibleCount > 0
        ? `开启采集后，Repair Scanner 会处理 ${status.repairEligibleCount} 笔候选，包括近 24 小时漏建卡支付及有效更新窗口内非终态卡`
        : '开启采集后，新支付与后续 Repair Scanner 候选会写入可靠投递队列'
    )
  }
  if (enablingWorker) {
    const queued = status.pendingDeliveries + status.sendingDeliveries + status.unknownDeliveries
    changes.push(
      `开启外呼后，Worker 会向微信发送或核对投递队列${queued > 0 ? `（当前相关队列 ${queued} 条）` : ''}`
    )
  }
  if (disablingWorker) changes.push('关闭外呼后，已入队任务会保留，但暂停调用微信')
  if (disablingCapture) changes.push('关闭采集后，不再根据新业务事实创建或更新投递意图')

  const highRisk = enablingCapture || enablingWorker
  return {
    title: highRisk ? '确认高风险运行变更' : '确认运行开关变更',
    message: changes.join('；') || '运行开关没有变化',
    phrase: enablingWorker
      ? '确认开启微信外呼'
      : enablingCapture
        ? '确认开启服务动态采集'
        : '确认保存运行变更',
    tone: enablingWorker ? 'error' : 'warning'
  }
}

export function validateConfirmationPhrase(value: string, phrase: string): string | null {
  return value.trim() === phrase ? null : `请输入“${phrase}”`
}
