import { formatLocalDateTime } from '@/utils/date-time'

export type ShippingFormField =
  | 'logisticsType'
  | 'itemDesc'
  | 'expressCompanyCode'
  | 'trackingNo'
  | 'consignorContact'
  | 'shipmentNote'

export const LOGISTICS_TYPE_OPTIONS: ReadonlyArray<{
  value: Api.Order.LogisticsType
  label: string
}> = [
  { value: 1, label: '实体快递' },
  { value: 2, label: '同城配送' },
  { value: 3, label: '虚拟商品' },
  { value: 4, label: '用户自提' }
]

const LOGISTICS_TYPE_LABELS: Record<Api.Order.LogisticsType, string> = {
  1: '实体快递',
  2: '同城配送',
  3: '虚拟商品',
  4: '用户自提'
}

const NON_EXPRESS_DETAILS: Record<Exclude<Api.Order.LogisticsType, 1>, string> = {
  2: '同城配送，无快递单号',
  3: '虚拟商品交付',
  4: '用户自提'
}

const ITEM_DESC_LIMIT = 120

export function visibleShippingFields(logisticsType: Api.Order.LogisticsType): ShippingFormField[] {
  if (logisticsType === 1) {
    return [
      'logisticsType',
      'itemDesc',
      'expressCompanyCode',
      'trackingNo',
      'consignorContact',
      'shipmentNote'
    ]
  }
  return ['logisticsType', 'itemDesc', 'shipmentNote']
}

export function itemDescLength(value: string): number {
  return Array.from(value).length
}

export function trimItemDesc(value: string): string {
  return Array.from(value.trim()).slice(0, ITEM_DESC_LIMIT).join('')
}

export function suggestItemDesc(
  items: ReadonlyArray<Pick<Api.Order.OrderItem, 'productTitle' | 'specText' | 'quantity'>>
): string {
  const description = items
    .map(({ productTitle, specText, quantity }) =>
      [productTitle.trim(), specText?.trim(), `x${quantity}`].filter(Boolean).join(' ')
    )
    .filter(Boolean)
    .join('；')
  return trimItemDesc(description)
}

export function validateShippingForm(form: Api.Order.ShipOrderForm): string[] {
  const errors: string[] = []
  if (!form.itemDesc.trim()) {
    errors.push('请输入商品描述')
  } else if (itemDescLength(form.itemDesc.trim()) > ITEM_DESC_LIMIT) {
    errors.push(`商品描述不能超过 ${ITEM_DESC_LIMIT} 个字符`)
  }
  if (form.logisticsType === 1) {
    if (!form.expressCompanyCode?.trim()) errors.push('请选择快递公司')
    if (!form.trackingNo?.trim()) errors.push('请输入快递单号')
  }
  return errors
}

export function clearExpressFields(form: Api.Order.ShipOrderForm): Api.Order.ShipOrderForm {
  if (form.logisticsType === 1) return { ...form }
  const nonExpressForm = { ...form }
  delete nonExpressForm.expressCompanyCode
  delete nonExpressForm.trackingNo
  delete nonExpressForm.consignorContact
  return nonExpressForm
}

export function logisticsTypeLabel(logisticsType: Api.Order.LogisticsType): string {
  return LOGISTICS_TYPE_LABELS[logisticsType]
}

export function formatShipmentModeDetail(shipment: Api.Order.Shipment): string {
  if (shipment.logisticsType !== 1) return NON_EXPRESS_DETAILS[shipment.logisticsType]
  const carrierName = shipment.expressCompanyName || shipment.expressCompanyCode || '未知快递'
  const carrierCode =
    shipment.expressCompanyName && shipment.expressCompanyCode
      ? `（${shipment.expressCompanyCode}）`
      : ''
  return `${carrierName}${carrierCode} / ${shipment.trackingNo || '无快递单号'}`
}

export function formatWechatUploadError(shipment?: Api.Order.Shipment | null): string {
  if (!shipment) return '-'
  const code = shipment.wechatErrorCode || ''
  const message = shipment.wechatErrorMessage || ''
  return [code, message].filter(Boolean).join(' / ') || '-'
}

export function formatOptionalDateTime(value?: string | null): string {
  return formatLocalDateTime(value)
}

export function shippingOutcomeMessage(shipment: Api.Order.Shipment): string {
  if (shipment.wechatProviderMode === 'REAL' && shipment.wechatUploadStatus === 'UPLOADED') {
    return '本地发货成功，真实微信发货信息已上传'
  }
  if (shipment.wechatProviderMode === 'MOCK') {
    return '本地发货成功；当前为模拟环境，未向真实微信平台上传'
  }
  if (shipment.wechatProviderMode === 'DISABLED') {
    return '本地发货成功，微信发货信息上传未启用'
  }
  const statusMessages: Record<Api.Order.WechatShippingUploadStatus, string> = {
    PENDING: '微信平台等待上传',
    SKIPPED: '微信平台上传已跳过',
    UPLOADING: '微信平台上传处理中',
    UPLOADED: '微信平台回传已上传，但当前服务商状态无法确认为真实平台接受',
    FAILED: '微信平台上传失败',
    UNAVAILABLE: '微信发货能力不可用',
    UNKNOWN: '微信平台结果未知，请勿直接重试'
  }
  return `本地发货成功，${statusMessages[shipment.wechatUploadStatus]}`
}

export function canRetryWechatUpload(
  shipment: Api.Order.Shipment,
  capability?: Api.Order.WechatShippingCapability | null
): boolean {
  if (capability && !capability.uploadEnabled) return false
  if (shipment.wechatUploadStatus === 'FAILED' || shipment.wechatUploadStatus === 'UNAVAILABLE') {
    return true
  }
  return shipment.wechatUploadStatus === 'SKIPPED' && capability?.uploadEnabled === true
}

export function canStartCarrierSync(carrierLoading: boolean, carrierSyncing: boolean): boolean {
  return !carrierLoading && !carrierSyncing
}

export function canLoadWechatShippingCatalog(hasOrderShipAuthority: boolean): boolean {
  return hasOrderShipAuthority
}

export function contextualizeRetryOutcome(
  message: string,
  orderNo: string,
  detailContextChanged: boolean
): string {
  return detailContextChanged ? `订单 ${orderNo}：${message}` : message
}

export function shippingCapabilityMessage(capability: Api.Order.WechatShippingCapability): string {
  if (capability.providerMode === 'MOCK') {
    return '当前为模拟模式，不会向真实微信平台上传'
  }
  if (!capability.uploadEnabled || capability.providerMode === 'DISABLED') {
    return '微信发货信息上传未启用；本地发货仍可保存'
  }
  if (capability.state === 'AVAILABLE') return '微信发货信息能力可用'
  if (capability.state === 'UNAVAILABLE') {
    return '微信发货信息能力不可用；本地发货仍可保存'
  }
  return '微信发货信息能力状态未知；本地发货仍可保存'
}
