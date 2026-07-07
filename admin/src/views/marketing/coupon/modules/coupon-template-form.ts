export interface CouponTemplateFormState {
  name: string
  description: string
  couponType: Api.Marketing.CouponType
  discountType: Api.Marketing.DiscountType
  thresholdYuan: number
  discountYuan: number
  scopeType: Api.Marketing.CouponScopeType
  scopeValue: string
  strategyKey: string
  totalStock: number
  perUserLimit: number
  validRange: [string, string] | []
  status: Api.Marketing.CouponTemplateStatus
  sortOrder: number
}

const V1_DEFAULTS = {
  discountType: 'AMOUNT_OFF' as const,
  scopeType: 'ALL' as const,
  scopeValue: '',
  strategyKey: 'coupon.amount-off.v1'
}

export const toCent = (value: number) => Math.round(value * 100)

export const createDefaultCouponTemplateForm = (): CouponTemplateFormState => ({
  name: '',
  description: '',
  couponType: 'NO_THRESHOLD',
  discountType: V1_DEFAULTS.discountType,
  thresholdYuan: 0,
  discountYuan: 0.01,
  scopeType: V1_DEFAULTS.scopeType,
  scopeValue: V1_DEFAULTS.scopeValue,
  strategyKey: V1_DEFAULTS.strategyKey,
  totalStock: 1,
  perUserLimit: 1,
  validRange: [],
  status: 'DISABLED',
  sortOrder: 0
})

export const fillCouponTemplateForm = (
  template?: Api.Marketing.CouponTemplate | null
): CouponTemplateFormState => {
  const defaults = createDefaultCouponTemplateForm()
  if (!template) return defaults

  return {
    ...defaults,
    name: template.name,
    description: template.description ?? '',
    couponType: template.couponType,
    discountType: template.discountType || defaults.discountType,
    thresholdYuan: template.thresholdCent / 100,
    discountYuan: template.discountCent / 100,
    scopeType: template.scopeType || defaults.scopeType,
    scopeValue: template.scopeValue ?? defaults.scopeValue,
    strategyKey: template.strategyKey || defaults.strategyKey,
    totalStock: template.totalStock,
    perUserLimit: template.perUserLimit,
    validRange: [template.validStartAt, template.validEndAt],
    status: template.status,
    sortOrder: template.sortOrder
  }
}

export const buildCouponTemplatePayload = (
  formData: CouponTemplateFormState,
  template?: Api.Marketing.CouponTemplate | null
): Api.Marketing.CouponTemplateForm => {
  const [validStartAt = '', validEndAt = ''] = formData.validRange
  const fallbackState = fillCouponTemplateForm(template)

  return {
    name: formData.name.trim(),
    description: formData.description.trim(),
    couponType: formData.couponType,
    discountType: formData.discountType || fallbackState.discountType,
    thresholdCent: formData.couponType === 'NO_THRESHOLD' ? 0 : toCent(formData.thresholdYuan),
    discountCent: toCent(formData.discountYuan),
    scopeType: formData.scopeType || fallbackState.scopeType,
    scopeValue: formData.scopeValue ?? fallbackState.scopeValue,
    strategyKey: formData.strategyKey || fallbackState.strategyKey,
    totalStock: formData.totalStock,
    perUserLimit: formData.perUserLimit,
    validStartAt,
    validEndAt,
    status: formData.status,
    sortOrder: formData.sortOrder
  }
}
