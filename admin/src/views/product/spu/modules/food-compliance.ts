import type { ProductEditorFoodDisclosure, ProductEditorSku } from './editor-model'

const PLACEHOLDER_PATTERN = /(示例|待填写|待补充|占位|example|placeholder|todo|tbd|xxx)/i
const FIXED_PRODUCTION_DATE_PATTERN =
  /(?:(?:19|20)\d{2}\s*(?:年|[-/.])\s*\d{1,2}\s*(?:月|[-/.])\s*\d{1,2}\s*日?|(?:19|20)\d{6})/
const PACKAGE_MARKING_PATTERN =
  /(?:(?:详见|见).*(?:包装|标签|瓶盖|封口)|(?:包装|标签|瓶盖|封口).*(?:喷码|标注|标识|标示|所示|详见|为准))/
const POSITIVE_NET_CONTENT_PATTERN =
  /(?:^|\D)(?:0*[1-9]\d*(?:\.\d+)?|0\.\d*[1-9]\d*)\s*(?:kg|g|mg|l|ml|克|千克|公斤|毫升|升|枚|个|袋|包|盒|罐|瓶|支)(?:\D|$)/i

export const createDefaultFoodDisclosure = (): ProductEditorFoodDisclosure => ({
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
})

const normalized = (value: string | null | undefined) => value?.trim() || ''

export const normalizeFoodDisclosureForSave = (
  disclosure: ProductEditorFoodDisclosure
): Api.Product.FoodDisclosure => ({
  complianceType: disclosure.complianceType,
  foodName: normalized(disclosure.foodName),
  ingredients: normalized(disclosure.ingredients),
  allergenInformation: normalized(disclosure.allergenInformation),
  storageConditions: normalized(disclosure.storageConditions),
  shelfLifeDescription: normalized(disclosure.shelfLifeDescription),
  manufacturerName: normalized(disclosure.manufacturerName),
  manufacturerAddress: normalized(disclosure.manufacturerAddress),
  productionLicenseNumber: normalized(disclosure.productionLicenseNumber),
  origin: normalized(disclosure.origin),
  consumerNotice: normalized(disclosure.consumerNotice),
  variableProductionNotice: normalized(disclosure.variableProductionNotice),
  labelAssets: disclosure.labelAssets
    .filter((asset) => asset.fileId !== null && normalized(asset.url))
    .map((asset, index) => ({
      fileId: asset.fileId,
      url: normalized(asset.url),
      sortOrder: index
    }))
})

export const validateFoodDisclosure = (
  disclosure: ProductEditorFoodDisclosure,
  skus: ProductEditorSku[]
): string | null => {
  if (disclosure.complianceType !== 'FOOD') return null

  const requiredFields: Array<[string, string]> = [
    ['食品名称', disclosure.foodName],
    ['配料表', disclosure.ingredients],
    ['贮存条件', disclosure.storageConditions],
    ['保质期说明', disclosure.shelfLifeDescription],
    ['生产者名称', disclosure.manufacturerName],
    ['生产者地址', disclosure.manufacturerAddress],
    ['食品生产许可证编号', disclosure.productionLicenseNumber],
    ['产地', disclosure.origin],
    ['批次与生产日期说明', disclosure.variableProductionNotice]
  ]
  const missing = requiredFields.find(([, value]) => !normalized(value))
  if (missing) return `食品商品请填写${missing[0]}`

  const placeholder = requiredFields.find(([, value]) => PLACEHOLDER_PATTERN.test(value))
  if (placeholder) return `${placeholder[0]}不能使用示例或待填写内容`

  const optionalPlaceholder = [
    ['过敏原信息', disclosure.allergenInformation],
    ['消费提示', disclosure.consumerNotice]
  ].find(([, value]) => normalized(value) && PLACEHOLDER_PATTERN.test(value))
  if (optionalPlaceholder) return `${optionalPlaceholder[0]}不能使用示例或待填写内容`

  if (
    FIXED_PRODUCTION_DATE_PATTERN.test(disclosure.variableProductionNotice) ||
    !PACKAGE_MARKING_PATTERN.test(disclosure.variableProductionNotice)
  ) {
    return '批次与生产日期说明必须指向实际包装或标签，不得填写固定日期'
  }

  if (!disclosure.labelAssets.some((asset) => asset.fileId !== null && normalized(asset.url))) {
    return '食品商品请至少选择一张受管食品标签图片'
  }

  const enabledWithoutNetContent = skus.find(
    (sku) =>
      sku.status === 'ENABLED' &&
      (!POSITIVE_NET_CONTENT_PATTERN.test(normalized(sku.netContentText)) ||
        PLACEHOLDER_PATTERN.test(sku.netContentText))
  )
  if (enabledWithoutNetContent) {
    return `${enabledWithoutNetContent.specText || enabledWithoutNetContent.skuCode || '已启用规格'}请填写带有效单位的真实净含量`
  }
  return null
}
