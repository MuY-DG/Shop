import type {
  ProductEditorSku,
  ProductEditorSpecGroup,
  ProductEditorSpecValue
} from './editor-model'

export const MAX_SKU_COMBINATIONS = 100

let keySequence = 0

export const createEditorKey = (prefix: 'group' | 'value') => {
  keySequence += 1
  return `${prefix}_${Date.now().toString(36)}_${keySequence.toString(36)}`
}

export const createEmptySpecValue = (sortOrder = 0): ProductEditorSpecValue => ({
  valueKey: createEditorKey('value'),
  valueName: '',
  image: '',
  imageFileId: null,
  sortOrder
})

export const createEmptySpecGroup = (sortOrder = 0): ProductEditorSpecGroup => ({
  groupKey: createEditorKey('group'),
  name: '',
  imageEnabled: sortOrder === 0,
  sortOrder,
  values: [createEmptySpecValue(0)]
})

export const createEmptySku = (overrides: Partial<ProductEditorSku> = {}): ProductEditorSku => ({
  skuCode: '',
  specJson: '{}',
  specText: '默认规格',
  priceCent: null,
  costPriceCent: null,
  originalPriceCent: null,
  stockAvailable: 0,
  weightGram: null,
  volumeCubicMeter: null,
  image: '',
  imageFileId: null,
  status: 'ENABLED',
  defaultSelected: true,
  combinationKey: 'SINGLE',
  specValueKeys: [],
  sortOrder: 0,
  ...overrides
})

export const combinationCount = (groups: ProductEditorSpecGroup[]) => {
  if (!groups.length) return 0
  return groups.reduce((total, group) => total * group.values.length, 1)
}

interface CombinationPart {
  group: ProductEditorSpecGroup
  value: ProductEditorSpecValue
}

const generateCombinationParts = (groups: ProductEditorSpecGroup[]): CombinationPart[][] => {
  if (!groups.length || groups.some((group) => !group.values.length)) return []

  return groups.reduce<CombinationPart[][]>(
    (rows, group) => rows.flatMap((row) => group.values.map((value) => [...row, { group, value }])),
    [[]]
  )
}

export const buildCombinationKey = (parts: CombinationPart[]) =>
  parts
    .map(({ value }) => value.valueKey)
    .sort((left, right) => left.localeCompare(right))
    .join('|')

const createCompatibilityFields = (parts: CombinationPart[]) => {
  const entries = parts.map(({ group, value }) => [group.name.trim(), value.valueName.trim()])
  return {
    specJson: JSON.stringify(Object.fromEntries(entries)),
    specText: entries.map(([groupName, valueName]) => `${groupName}：${valueName}`).join(' / '),
    specValueKeys: parts.map(({ value }) => value.valueKey)
  }
}

export const resolveSkuFallbackImage = (
  sku: ProductEditorSku,
  groups: ProductEditorSpecGroup[],
  coverImage: string
) => resolveSkuFallbackAsset(sku, groups, coverImage, null).image

const resolveSkuFallbackAsset = (
  sku: ProductEditorSku,
  groups: ProductEditorSpecGroup[],
  coverImage: string,
  coverImageFileId: number | null
) => {
  const imageGroup = groups.find((group) => group.imageEnabled)
  const imageValue = imageGroup?.values.find((value) => sku.specValueKeys.includes(value.valueKey))
  if (imageValue?.image.trim()) {
    return { image: imageValue.image.trim(), imageFileId: imageValue.imageFileId }
  }
  return { image: coverImage.trim(), imageFileId: coverImageFileId }
}

export const imageSpecFallback = (
  sku: ProductEditorSku,
  groups: ProductEditorSpecGroup[],
  coverImage: string
) => sku.image.trim() || resolveSkuFallbackImage(sku, groups, coverImage)

/**
 * The backend persists the resolved cover/spec image in product_sku.image so orders can keep a
 * stable snapshot. On edit, turn that resolved snapshot back into an empty override whenever it
 * can be identified safely. Otherwise changing the cover/spec image would leave every SKU pinned
 * to the old fallback forever.
 */
export const hydrateSkuImageFallbacks = (
  skus: ProductEditorSku[],
  groups: ProductEditorSpecGroup[],
  coverImage: string,
  coverImageFileId: number | null = null
) =>
  skus.map((sku) => {
    if (!sku.image.trim()) return sku
    const fallback = resolveSkuFallbackAsset(sku, groups, coverImage, coverImageFileId)
    if (
      !fallback.image ||
      sku.image.trim() !== fallback.image ||
      sku.imageFileId !== fallback.imageFileId
    ) {
      return sku
    }
    return { ...sku, image: '', imageFileId: null }
  })

export const normalizeDefaultSku = (skus: ProductEditorSku[]) => {
  const enabled = skus.filter((sku) => sku.status === 'ENABLED')
  if (!enabled.length) {
    skus.forEach((sku) => {
      sku.defaultSelected = false
    })
    return
  }

  const selected = enabled.find((sku) => sku.defaultSelected) || enabled[0]
  skus.forEach((sku) => {
    sku.defaultSelected = sku === selected
  })
}

export const reconcileSkuMatrix = (
  groups: ProductEditorSpecGroup[],
  existingSkus: ProductEditorSku[]
) => {
  const partsRows = generateCombinationParts(groups)
  if (partsRows.length > MAX_SKU_COMBINATIONS) return existingSkus

  const existingByCombination = new Map(
    existingSkus.map((sku) => [sku.combinationKey, sku] as const)
  )

  const rows = partsRows.map((parts, index) => {
    const combinationKey = buildCombinationKey(parts)
    const existing = existingByCombination.get(combinationKey)
    const compatibility = createCompatibilityFields(parts)
    if (existing) {
      return {
        ...existing,
        ...compatibility,
        combinationKey,
        sortOrder: index
      }
    }
    return createEmptySku({
      ...compatibility,
      defaultSelected: false,
      combinationKey,
      sortOrder: index
    })
  })

  normalizeDefaultSku(rows)
  return rows
}

export const describeCombinationCount = (groups: ProductEditorSpecGroup[]) => {
  const factors = groups.map(
    (group) => `${group.name.trim() || '未命名规格'} ${group.values.length}项`
  )
  return `${factors.join(' × ')} = ${combinationCount(groups)} 个组合`
}

export const validateSpecGroups = (groups: ProductEditorSpecGroup[]): string | null => {
  if (!groups.length) return '请至少添加一个规格'
  if (groups.length > 10) return '规格名称最多 10 个'
  if (groups.some((group) => !group.name.trim())) return '请填写所有规格名称'
  if (groups.some((group) => group.name.trim().length > 30)) return '规格名称最多 30 个字'
  if (new Set(groups.map((group) => group.name.trim())).size !== groups.length) {
    return '规格名称不能重复'
  }
  if (groups.some((group) => !group.values.length)) return '每个规格至少需要一个规格值'
  if (groups.some((group) => group.values.some((value) => !value.valueName.trim()))) {
    return '请填写所有规格值'
  }
  if (
    groups.some(
      (group) =>
        new Set(group.values.map((value) => value.valueName.trim())).size !== group.values.length
    )
  ) {
    return '同一规格下的规格值不能重复'
  }
  if (groups.filter((group) => group.imageEnabled).length !== 1) {
    return '必须且只能选择一个规格名称添加规格图'
  }
  const count = combinationCount(groups)
  if (count > MAX_SKU_COMBINATIONS) {
    return `规格组合不能超过 ${MAX_SKU_COMBINATIONS} 个，当前${describeCombinationCount(groups)}`
  }
  return null
}

export const cloneTemplateGroups = (
  groups: Array<{
    name: string
    imageEnabled: boolean
    values: Array<{ valueName: string }>
  }>
): ProductEditorSpecGroup[] =>
  groups.map((group, groupIndex) => ({
    groupKey: createEditorKey('group'),
    name: group.name,
    imageEnabled: group.imageEnabled,
    sortOrder: groupIndex,
    values: group.values.map((value, valueIndex) => ({
      valueKey: createEditorKey('value'),
      valueName: value.valueName,
      image: '',
      imageFileId: null,
      sortOrder: valueIndex
    }))
  }))
