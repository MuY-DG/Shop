export type ShipmentDialogMode = 'manual' | 'electronic'
export type WaybillPanelPhase = 'CLOSED' | 'OPENING' | 'EDITING' | 'READY'
export type WaybillOperation =
  | 'create'
  | 'refresh'
  | 'cancel'
  | 'preview'
  | 'print'
  | 'confirm'
  | 'simulate'

export interface WaybillAccess {
  canManage: boolean
  canPrint: boolean
  canTest: boolean
  canConfirmShipment: boolean
}

export interface WaybillUuidCryptoSource {
  randomUUID?: () => string
  getRandomValues?: (values: Uint8Array) => Uint8Array
}

export interface WaybillCancelFeedback {
  tone: 'success' | 'warning' | 'error'
  manualUnlocked: boolean
  message: string
}

const ACTIVE_WAYBILL_STATUSES = new Set<Api.Waybill.AttemptStatus>([
  'CREATING',
  'CREATED',
  'CANCELING',
  'UNKNOWN'
])

export function isActiveWaybillAttempt(attempt: Api.Waybill.Attempt | null | undefined): boolean {
  return Boolean(attempt && ACTIVE_WAYBILL_STATUSES.has(attempt.status))
}

export function canUseManualShipment(
  canManualShip: boolean,
  attempt: Api.Waybill.Attempt | null | undefined
): boolean {
  return canManualShip && !isActiveWaybillAttempt(attempt)
}

export function createWaybillIdempotencyKey(source?: WaybillUuidCryptoSource): string {
  const cryptoSource = source ?? globalThis.crypto
  const nativeUuid = cryptoSource?.randomUUID?.()
  if (nativeUuid) return nativeUuid

  const bytes = new Uint8Array(16)
  if (cryptoSource?.getRandomValues) {
    cryptoSource.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0'))
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex
    .slice(6, 8)
    .join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`
}

export function resolveWaybillCancelFeedback(
  status: Api.Waybill.AttemptStatus
): WaybillCancelFeedback {
  switch (status) {
    case 'CANCELED':
      return {
        tone: 'success',
        manualUnlocked: true,
        message: '电子面单已取消，可切换为手动填写运单'
      }
    case 'UNKNOWN':
    case 'CANCELING':
      return {
        tone: 'warning',
        manualUnlocked: false,
        message: '取消结果尚未确定，请刷新面单状态；当前仍不能手动发货'
      }
    case 'CREATED':
      return {
        tone: 'warning',
        manualUnlocked: false,
        message: '电子面单仍为已生成状态，取消未成功；当前仍不能手动发货'
      }
    case 'CONFIRMED':
      return {
        tone: 'warning',
        manualUnlocked: false,
        message: '订单已通过电子面单确认发货，不能再取消面单'
      }
    default:
      return {
        tone: 'error',
        manualUnlocked: false,
        message: '电子面单取消未完成，请刷新状态后再处理'
      }
  }
}

const RETRYABLE_REGISTRATION_STATUSES = new Set<Api.Waybill.RegistrationStatus>([
  'PENDING',
  'FAILED',
  'UNKNOWN',
  'UNAVAILABLE'
])

export function canRetryWaybillRegistration(shipment: {
  waybillRegistrationStatus?: Api.Waybill.RegistrationStatus | null
}): boolean {
  return Boolean(
    shipment.waybillRegistrationStatus &&
      RETRYABLE_REGISTRATION_STATUSES.has(shipment.waybillRegistrationStatus)
  )
}

export function formatWaybillRegistrationKind(
  kind: Api.Waybill.RegistrationKind | null | undefined
): string {
  if (kind === 'TRACE') return '物流查询'
  if (kind === 'FOLLOW') return '物流订阅'
  return '-'
}

export function formatWaybillRegistrationStatus(
  status: Api.Waybill.RegistrationStatus | null | undefined
): string {
  const labels: Record<Api.Waybill.RegistrationStatus, string> = {
    PENDING: '等待登记',
    REGISTERING: '登记中',
    REGISTERED: '登记成功',
    FAILED: '登记失败',
    UNKNOWN: '结果未知',
    UNAVAILABLE: '服务暂不可用',
    SKIPPED: '无需登记'
  }
  return status ? labels[status] : '-'
}

export function initialShipmentMode(
  canManualShip: boolean,
  canManageWaybill: boolean,
  attempt: Api.Waybill.Attempt | null | undefined
): ShipmentDialogMode {
  if (isActiveWaybillAttempt(attempt)) return 'electronic'
  if (canManualShip) return 'manual'
  if (canManageWaybill) return 'electronic'
  return 'manual'
}

export function resolveWaybillPanelPhase(
  opened: boolean,
  loading: boolean,
  attempt: Api.Waybill.Attempt | null | undefined
): WaybillPanelPhase {
  if (!opened) return 'CLOSED'
  if (loading) return 'OPENING'
  if (!attempt || attempt.status === 'FAILED' || attempt.status === 'CANCELED') return 'EDITING'
  return 'READY'
}

export function isCurrentWaybillResponse(
  requestGeneration: number,
  currentGeneration: number,
  requestOrderId: number,
  currentOrderId: number | null,
  opened: boolean
): boolean {
  return opened && requestGeneration === currentGeneration && requestOrderId === currentOrderId
}

export function visibleSandboxActions(
  context: Api.Waybill.Context | null | undefined,
  attempt: Api.Waybill.Attempt | null | undefined,
  canTest: boolean
): Api.Waybill.SandboxEvent[] {
  if (
    !canTest ||
    context?.mode !== 'SANDBOX' ||
    attempt?.environment !== 'SANDBOX' ||
    !attempt.canSimulate
  ) {
    return []
  }
  return [...context.sandboxActions]
}

export function waybillActionEnabled(
  action: WaybillOperation,
  context: Api.Waybill.Context | null | undefined,
  attempt: Api.Waybill.Attempt | null | undefined,
  activeOperation: WaybillOperation | null,
  access: WaybillAccess
): boolean {
  if (activeOperation) return false

  switch (action) {
    case 'create':
      return access.canManage && context?.canCreate === true && !isActiveWaybillAttempt(attempt)
    case 'refresh':
      return access.canManage && attempt?.canRefresh === true
    case 'cancel':
      return access.canManage && attempt?.canCancel === true
    case 'preview':
    case 'print':
      return access.canPrint && attempt?.canPrint === true
    case 'confirm':
      return access.canManage && access.canConfirmShipment && attempt?.canConfirmShipment === true
    case 'simulate':
      return visibleSandboxActions(context, attempt, access.canTest).length > 0
  }
}

export function buildWaybillCreateRequest(input: {
  idempotencyKey: string
  parcel: Api.Waybill.Parcel
  remark?: string | null
  expectTime?: number | null
  items?: Array<{ orderItemId: number; quantity: number }>
}): Api.Waybill.CreateRequest {
  const request: Api.Waybill.CreateRequest = {
    idempotencyKey: input.idempotencyKey.trim(),
    count: input.parcel.count,
    weightKg: input.parcel.weightKg,
    lengthCm: input.parcel.lengthCm,
    widthCm: input.parcel.widthCm,
    heightCm: input.parcel.heightCm
  }
  const remark = input.remark?.trim()
  if (remark) request.remark = remark
  if (input.expectTime != null) request.expectTime = input.expectTime
  if (input.items?.length) request.items = input.items.map((item) => ({ ...item }))
  return request
}

export function logisticsDetailPriority(detail: {
  shipment?: unknown | null
  electronicWaybill?: Api.Waybill.Attempt | null
}): 'shipment' | 'waybill' | 'empty' {
  if (detail.shipment) return 'shipment'
  if (detail.electronicWaybill) return 'waybill'
  return 'empty'
}

export function replaceBlobUrl(
  currentUrl: string | null,
  nextUrl: string,
  revoke: (url: string) => void
): string {
  if (currentUrl && currentUrl !== nextUrl) revoke(currentUrl)
  return nextUrl
}

export function releaseBlobUrl(currentUrl: string | null, revoke: (url: string) => void): null {
  if (currentUrl) revoke(currentUrl)
  return null
}
