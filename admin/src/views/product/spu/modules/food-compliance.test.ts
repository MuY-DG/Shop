import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import type { ProductEditorSku } from './editor-model'
import {
  createDefaultFoodDisclosure,
  normalizeFoodDisclosureForSave,
  validateFoodDisclosure
} from './food-compliance'

const enabledSku = (): ProductEditorSku => ({
  id: 1,
  skuCode: 'SKU-1',
  specJson: '{}',
  specText: '500g/袋',
  priceCent: 100,
  costPriceCent: null,
  originalPriceCent: null,
  stockAvailable: 1,
  lowStockThreshold: 0,
  weightGram: 500,
  volumeCubicMeter: null,
  netContentText: '500g',
  packUnitText: '袋',
  image: '',
  imageFileId: null,
  status: 'ENABLED',
  defaultSelected: true,
  combinationKey: 'SINGLE',
  specValueKeys: [],
  wholesaleTiers: [],
  sortOrder: 0
})

const completeFood = () => ({
  ...createDefaultFoodDisclosure(),
  complianceType: 'FOOD' as const,
  foodName: '真实食品名称',
  ingredients: '真实配料表',
  storageConditions: '真实贮存条件',
  shelfLifeDescription: '真实标签保质期说明',
  manufacturerName: '真实生产者',
  manufacturerAddress: '真实生产地址',
  productionLicenseNumber: 'SC12345678901234',
  origin: '真实产地',
  variableProductionNotice: '生产批次与日期以收到商品包装标签标示为准',
  labelAssets: [{ fileId: 91, url: 'https://assets.example.test/label.jpg', sortOrder: 8 }]
})

test('compliance classification offers only non-food and food', () => {
  const source = readFileSync(new URL('./product-food-compliance-tab.vue', import.meta.url), 'utf8')
  const options = Array.from(
    source.matchAll(/<ElRadioButton value="([^"]+)">([^<]+)</g),
    (match) => [match[1], match[2]]
  )
  assert.deepEqual(options, [
    ['NON_FOOD', '非食品'],
    ['FOOD', '食品']
  ])
})

test('default non-food disclosure saves without food facts or SKU net content', () => {
  const disclosure = createDefaultFoodDisclosure()
  assert.equal(normalizeFoodDisclosureForSave(disclosure).complianceType, 'NON_FOOD')
  assert.equal(validateFoodDisclosure(disclosure, [{ ...enabledSku(), netContentText: '' }]), null)
})

test('food disclosure rejects missing, placeholder, unmanaged-label and net-content gaps', () => {
  assert.match(
    validateFoodDisclosure({ ...completeFood(), ingredients: '' }, [enabledSku()])!,
    /配料表/
  )
  assert.match(
    validateFoodDisclosure({ ...completeFood(), foodName: '待填写示例' }, [enabledSku()])!,
    /不能使用示例/
  )
  assert.match(
    validateFoodDisclosure({ ...completeFood(), labelAssets: [] }, [enabledSku()])!,
    /标签图片/
  )
  assert.match(
    validateFoodDisclosure(completeFood(), [{ ...enabledSku(), netContentText: '' }])!,
    /净含量/
  )
  assert.match(
    validateFoodDisclosure(
      { ...completeFood(), variableProductionNotice: '生产日期为 2026-08-09' },
      [enabledSku()]
    )!,
    /实际包装/
  )
  assert.match(
    validateFoodDisclosure({ ...completeFood(), variableProductionNotice: '每个批次可能不同' }, [
      enabledSku()
    ])!,
    /实际包装/
  )
  assert.match(
    validateFoodDisclosure({ ...completeFood(), consumerNotice: '待填写' }, [enabledSku()])!,
    /消费提示/
  )
  assert.match(
    validateFoodDisclosure(completeFood(), [{ ...enabledSku(), netContentText: '一袋' }])!,
    /有效单位/
  )
})

test('food disclosure normalization trims facts and only sends managed assets in display order', () => {
  const payload = normalizeFoodDisclosureForSave({
    ...completeFood(),
    foodName: ' 真实食品名称 ',
    labelAssets: [
      { fileId: null, url: '', sortOrder: 0 },
      { fileId: 92, url: ' https://assets.example.test/two.jpg ', sortOrder: 9 }
    ]
  })
  assert.equal(payload.foodName, '真实食品名称')
  assert.deepEqual(payload.labelAssets, [
    { fileId: 92, url: 'https://assets.example.test/two.jpg', sortOrder: 0 }
  ])
})
