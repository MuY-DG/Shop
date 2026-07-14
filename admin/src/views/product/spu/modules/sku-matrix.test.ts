import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createEmptySku,
  createEmptySpecGroup,
  describeCombinationCount,
  hydrateSkuImageFallbacks,
  imageSpecFallback
} from './sku-matrix'
import type { ProductEditorSpecGroup } from './editor-model'

const createColorGroup = (image: string): ProductEditorSpecGroup => ({
  groupKey: 'color',
  name: '颜色',
  imageEnabled: true,
  sortOrder: 0,
  values: [
    {
      valueKey: 'blue',
      valueName: '蓝色',
      image,
      imageFileId: 12,
      sortOrder: 0
    }
  ]
})

test('rehydrates a persisted cover fallback as an inferred SKU image', () => {
  const persisted = createEmptySku({ image: '/cover-old.jpg', imageFileId: 11 })
  const [hydrated] = hydrateSkuImageFallbacks([persisted], [], '/cover-old.jpg', 11)

  assert.equal(hydrated.image, '')
  assert.equal(imageSpecFallback(hydrated, [], '/cover-new.jpg'), '/cover-new.jpg')
})

test('rehydrates a persisted spec-value fallback and follows later spec image changes', () => {
  const persisted = createEmptySku({
    image: '/blue-old.jpg',
    imageFileId: 12,
    combinationKey: 'blue',
    specValueKeys: ['blue']
  })
  const [hydrated] = hydrateSkuImageFallbacks(
    [persisted],
    [createColorGroup('/blue-old.jpg')],
    '/cover.jpg'
  )

  assert.equal(hydrated.image, '')
  assert.equal(
    imageSpecFallback(hydrated, [createColorGroup('/blue-new.jpg')], '/cover.jpg'),
    '/blue-new.jpg'
  )
})

test('preserves explicit managed and external SKU images', () => {
  const managed = createEmptySku({ image: '/cover.jpg', imageFileId: 99 })
  const external = createEmptySku({ image: 'https://cdn.example.com/sku.jpg', imageFileId: null })
  const [hydratedManaged, hydratedExternal] = hydrateSkuImageFallbacks(
    [managed, external],
    [],
    '/cover.jpg',
    12
  )

  assert.deepEqual(hydratedManaged, managed)
  assert.deepEqual(hydratedExternal, external)
})

test('keeps specification images opt-in and uses a neutral draft label', () => {
  const group = createEmptySpecGroup()

  assert.equal(group.imageEnabled, false)
  assert.equal(describeCombinationCount([group]), '规格 1 1项 = 1 个组合')
})
