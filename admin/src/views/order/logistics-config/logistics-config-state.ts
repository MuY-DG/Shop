export const WECHAT_EXPRESS_MODE_OPTIONS: ReadonlyArray<{
  value: Api.Waybill.ExpressMode
  label: string
}> = [
  { value: 'DISABLED', label: '停用' },
  { value: 'SANDBOX', label: '微信沙箱' },
  { value: 'PRODUCTION', label: '正式环境' }
]

export const SANDBOX_EXPRESS_ACCOUNT = {
  deliveryId: 'TEST',
  deliveryName: '微信官方测试运力',
  bizId: 'test_biz_id',
  serviceType: 1,
  serviceName: 'test_service_name',
  sandbox: true
} as const

export interface EffectiveExpressAccountDisplay {
  deliveryId: string
  deliveryName: string
  bizId: string
  serviceType: number | null
  serviceName: string
  sandbox: boolean
}

const trim = (value: string | null | undefined) => String(value || '').trim()

const cloneSender = (sender: Api.Waybill.Sender): Api.Waybill.Sender => ({
  name: sender.name,
  mobile: sender.mobile,
  company: sender.company,
  province: sender.province,
  city: sender.city,
  district: sender.district,
  detailAddress: sender.detailAddress
})

const cloneParcel = (parcel: Api.Waybill.Parcel): Api.Waybill.Parcel => ({
  count: parcel.count,
  weightKg: parcel.weightKg,
  lengthCm: parcel.lengthCm,
  widthCm: parcel.widthCm,
  heightCm: parcel.heightCm
})

export function createWechatExpressConfigForm(
  config: Api.Waybill.WechatExpressConfig
): Api.Waybill.WechatExpressConfigForm {
  return {
    mode: config.mode,
    messageEnabled: config.messageEnabled,
    sender: cloneSender(config.sender),
    production: {
      deliveryId: config.production.deliveryId,
      deliveryName: config.production.deliveryName,
      bizId: '',
      bizIdMasked: config.production.bizIdMasked,
      clearBizId: false,
      serviceType: config.production.serviceType,
      serviceName: config.production.serviceName
    },
    defaultParcel: cloneParcel(config.defaultParcel),
    revision: config.revision
  }
}

export function resolveEffectiveExpressAccount(
  form: Api.Waybill.WechatExpressConfigForm
): EffectiveExpressAccountDisplay | null {
  if (form.mode === 'DISABLED') return null
  if (form.mode === 'SANDBOX') return { ...SANDBOX_EXPRESS_ACCOUNT }
  return {
    deliveryId: trim(form.production.deliveryId),
    deliveryName: trim(form.production.deliveryName),
    bizId: trim(form.production.bizId) || trim(form.production.bizIdMasked),
    serviceType: form.production.serviceType,
    serviceName: trim(form.production.serviceName),
    sandbox: false
  }
}

export function validateWechatExpressConfig(form: Api.Waybill.WechatExpressConfigForm): string[] {
  if (form.mode === 'DISABLED') return []

  const errors: string[] = []
  const requiredSenderFields: Array<[string, string]> = [
    [form.sender.name, '请输入寄件人姓名'],
    [form.sender.mobile, '请输入寄件人手机'],
    [form.sender.province, '请输入寄件省份'],
    [form.sender.city, '请输入寄件城市'],
    [form.sender.district, '请输入寄件区县'],
    [form.sender.detailAddress, '请输入寄件详细地址']
  ]
  requiredSenderFields.forEach(([value, message]) => {
    if (!trim(value)) errors.push(message)
  })

  if (!Number.isInteger(form.defaultParcel.count) || form.defaultParcel.count < 1) {
    errors.push('默认包裹数量必须是大于 0 的整数')
  }
  const positiveParcelFields: Array<[number, string]> = [
    [form.defaultParcel.weightKg, '默认包裹重量必须大于 0'],
    [form.defaultParcel.lengthCm, '默认包裹长度必须大于 0'],
    [form.defaultParcel.widthCm, '默认包裹宽度必须大于 0'],
    [form.defaultParcel.heightCm, '默认包裹高度必须大于 0']
  ]
  positiveParcelFields.forEach(([value, message]) => {
    if (!Number.isFinite(value) || value <= 0) errors.push(message)
  })

  if (form.mode === 'PRODUCTION') {
    if (!trim(form.production.deliveryId)) errors.push('请输入正式快递公司 ID')
    if (!trim(form.production.deliveryName)) errors.push('请输入正式快递公司名称')
    if (
      form.production.clearBizId ||
      (!trim(form.production.bizId) && !trim(form.production.bizIdMasked))
    ) {
      errors.push('请输入正式快递客户编码')
    }
    if (!Number.isInteger(form.production.serviceType) || Number(form.production.serviceType) < 0) {
      errors.push('请输入有效的正式服务类型 ID')
    }
    if (!trim(form.production.serviceName)) errors.push('请输入正式服务名称')
  }

  return errors
}

export function toWechatExpressConfigUpdate(
  form: Api.Waybill.WechatExpressConfigForm
): Api.Waybill.WechatExpressConfigUpdate {
  const production: Api.Waybill.ProductionAccountUpdate = {
    deliveryId: trim(form.production.deliveryId),
    deliveryName: trim(form.production.deliveryName),
    clearBizId: form.production.clearBizId,
    serviceType: form.production.serviceType,
    serviceName: trim(form.production.serviceName)
  }
  const bizId = trim(form.production.bizId)
  if (bizId) production.bizId = bizId

  return {
    mode: form.mode,
    messageEnabled: form.messageEnabled,
    sender: {
      name: trim(form.sender.name),
      mobile: trim(form.sender.mobile),
      company: trim(form.sender.company),
      province: trim(form.sender.province),
      city: trim(form.sender.city),
      district: trim(form.sender.district),
      detailAddress: trim(form.sender.detailAddress)
    },
    production,
    defaultParcel: cloneParcel(form.defaultParcel),
    revision: form.revision
  }
}

export function wechatExpressConfigSnapshot(form: Api.Waybill.WechatExpressConfigForm): string {
  return JSON.stringify(toWechatExpressConfigUpdate(form))
}

export function canLoadWechatExpressConfig(hasReadPermission: boolean): boolean {
  return hasReadPermission
}

export function canSaveWechatExpressConfig(
  hasWritePermission: boolean,
  formIsValid: boolean
): boolean {
  return hasWritePermission && formIsValid
}

export function isWechatExpressConfigRevisionConflict(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'httpStatus' in error &&
    (error as { httpStatus?: unknown }).httpStatus === 409
  )
}
