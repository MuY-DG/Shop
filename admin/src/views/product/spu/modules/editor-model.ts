export type ProductSpecType = 'SINGLE' | 'MULTI'
export type ProductSkuStatus = 'ENABLED' | 'DISABLED'
export type ProductTagCode = 'PROMOTION' | 'HOT_SALE' | 'HOT_RANK' | 'PREMIUM' | 'NEW_ARRIVAL'

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
  priceCent: number | null
  costPriceCent: number | null
  originalPriceCent: number | null
  stockAvailable: number
  lowStockThreshold: number
  weightGram: number | null
  volumeCubicMeter: number | null
  image: string
  imageFileId: number | null
  status: ProductSkuStatus
  defaultSelected: boolean
  combinationKey: string
  specValueKeys: string[]
  sortOrder: number
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
  sortOrder: number
  images: ProductEditorImage[]
  skus: ProductEditorSku[]
  specGroups: ProductEditorSpecGroup[]
  tags: ProductTagCode[]
  guaranteeServiceIds: number[]
  couponTemplateIds: number[]
  parameterValues: Api.Product.SpuParameterValue[]
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

export const PRODUCT_TAG_OPTIONS: Array<{ label: string; value: ProductTagCode }> = [
  { label: '促销单品', value: 'PROMOTION' },
  { label: '热卖商品', value: 'HOT_SALE' },
  { label: '热门榜单', value: 'HOT_RANK' },
  { label: '精选好物', value: 'PREMIUM' },
  { label: '新品', value: 'NEW_ARRIVAL' }
]

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
  sortOrder: 0,
  images: [],
  skus: [],
  specGroups: [],
  tags: [],
  guaranteeServiceIds: [],
  couponTemplateIds: [],
  parameterValues: []
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
