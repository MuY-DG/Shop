export type ProductSpecType = 'SINGLE' | 'MULTI'
export type ProductSkuStatus = 'ENABLED' | 'DISABLED'

export interface ProductEditorImage {
  url: string
  fileId: number | null
}

export interface ProductEditorSpecValue {
  id?: number
  valueKey: string
  valueName: string
  image: string
  imageFileId: number | null
  sortOrder: number
}

export interface ProductEditorSpecGroup {
  id?: number
  groupKey: string
  name: string
  imageEnabled: boolean
  sortOrder: number
  values: ProductEditorSpecValue[]
}

export interface ProductEditorSku {
  id?: number
  skuCode: string
  specJson: string
  specText: string
  /** 仅编辑器使用：对外规格文案是否由运营手动填写；false 时保存自动按净含量生成。 */
  specTextCustomized?: boolean
  priceCent: number | null
  costPriceCent: number | null
  originalPriceCent: number | null
  stockAvailable: number
  lowStockThreshold: number
  weightGram: number | null
  volumeCubicMeter: number | null
  netContentText: string
  /** 包装单位（袋/盒/箱等），与净含量共同派生对外规格文案。 */
  packUnitText: string
  image: string
  imageFileId: number | null
  status: ProductSkuStatus
  defaultSelected: boolean
  combinationKey: string
  specValueKeys: string[]
  wholesaleTiers: ProductEditorWholesaleTier[]
  sortOrder: number
}

export type ProductComplianceType = 'NON_FOOD' | 'FOOD'

export interface ProductEditorFoodLabelAsset {
  fileId: number | null
  url: string
  sortOrder: number
}

export interface ProductEditorFoodDisclosure {
  complianceType: ProductComplianceType
  foodName: string
  ingredients: string
  allergenInformation: string
  storageConditions: string
  shelfLifeDescription: string
  manufacturerName: string
  manufacturerAddress: string
  productionLicenseNumber: string
  origin: string
  consumerNotice: string
  variableProductionNotice: string
  labelAssets: ProductEditorFoodLabelAsset[]
}

export interface ProductEditorWholesaleTier {
  minQuantity: number
  unitPriceCent: number | null
}

export interface ProductEditorForm {
  categoryId: number | null
  title: string
  subtitle: string
  mainImage: string
  mainImageFileId: number | null
  mainVideo: string
  mainVideoFileId: number | null
  sellingPoints: string
  detailHtml: string
  specType: ProductSpecType
  freightTemplateId: number | null
  virtualSales: number
  displayBadgeText: string
  displayBadgeTone: Api.Product.ProductBadgeTone
  sortOrder: number
  images: ProductEditorImage[]
  skus: ProductEditorSku[]
  specGroups: ProductEditorSpecGroup[]
  guaranteeServiceIds: number[]
  couponTemplateIds: number[]
  parameterValues: Api.Product.SpuParameterValue[]
  foodDisclosure: ProductEditorFoodDisclosure
}

export interface ProductEditorGuaranteeService {
  id: number
  termsName: string
  contentDescription: string
  icon: string
  iconFileId?: number | null
  sortOrder: number
  visible: boolean
}

export interface ProductEditorFreightTemplate {
  id: number
  name: string
  chargeMode: 'FREE' | 'FIXED'
  fixedAmountCent?: number | null
  status: 'ENABLED' | 'DISABLED'
  sortOrder: number
}

export interface ProductEditorSpecTemplateValue {
  id?: number
  valueKey?: string
  valueName: string
  sortOrder?: number
}

export interface ProductEditorSpecTemplateGroup {
  id?: number
  groupKey?: string
  name: string
  imageEnabled: boolean
  sortOrder?: number
  values: ProductEditorSpecTemplateValue[]
}

export interface ProductEditorSpecTemplate {
  id: number
  name: string
  groups?: ProductEditorSpecTemplateGroup[]
}

export interface ProductEditorCoupon {
  id: number
  name: string
  description?: string
  scopeType?: string
  status?: string
  validStartAt?: string
  validEndAt?: string
}

export const createEmptyImage = (): ProductEditorImage => ({
  url: '',
  fileId: null
})

export const createDefaultForm = (): ProductEditorForm => ({
  categoryId: null,
  title: '',
  subtitle: '',
  mainImage: '',
  mainImageFileId: null,
  mainVideo: '',
  mainVideoFileId: null,
  sellingPoints: '',
  detailHtml: '',
  specType: 'SINGLE',
  freightTemplateId: null,
  virtualSales: 0,
  displayBadgeText: '',
  displayBadgeTone: 'NEUTRAL',
  sortOrder: 0,
  images: [],
  skus: [],
  specGroups: [],
  guaranteeServiceIds: [],
  couponTemplateIds: [],
  parameterValues: [],
  foodDisclosure: {
    complianceType: 'NON_FOOD',
    foodName: '',
    ingredients: '',
    allergenInformation: '',
    storageConditions: '',
    shelfLifeDescription: '',
    manufacturerName: '',
    manufacturerAddress: '',
    productionLicenseNumber: '',
    origin: '',
    consumerNotice: '',
    variableProductionNotice: '',
    labelAssets: []
  }
})

export const parseSellingPoints = (value: string): string[] =>
  Array.from(
    new Set(
      value
        .split(/[,，\n]/)
        .map((item) => item.trim())
        .filter(Boolean)
    )
  )

export const serializeSellingPoints = (values: string[]): string =>
  parseSellingPoints(values.join(',')).join(',')

export const yuanToCent = (value: number | null | undefined): number | null => {
  if (value === null || value === undefined || !Number.isFinite(value)) return null
  return Math.round(value * 100)
}

export const centToYuan = (value: number | null | undefined): number | null => {
  if (value === null || value === undefined || !Number.isFinite(value)) return null
  return value / 100
}

export const validateWholesaleTiers = (
  tiers: ProductEditorWholesaleTier[],
  retailPriceCent: number | null
): string | null => {
  if (tiers.length > 5) return '每个规格最多设置 5 档批发价'
  if (tiers.length && (!retailPriceCent || retailPriceCent < 1)) return '请先填写商品售价'
  let previousQuantity = 1
  let previousPriceCent = retailPriceCent ?? 0
  for (const tier of tiers) {
    if (!Number.isInteger(tier.minQuantity) || tier.minQuantity < 2 || tier.minQuantity > 999) {
      return '批发起订数量须为 2 至 999 的整数'
    }
    if (tier.minQuantity <= previousQuantity) return '批发起订数量必须逐档增加'
    if (!tier.unitPriceCent || tier.unitPriceCent < 1) return '请填写有效的批发单价'
    if (tier.unitPriceCent >= previousPriceCent) return '后一档批发价必须低于前一档价格'
    previousQuantity = tier.minQuantity
    previousPriceCent = tier.unitPriceCent
  }
  return null
}
