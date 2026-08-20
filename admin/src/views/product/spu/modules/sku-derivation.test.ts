import assert from 'node:assert/strict'
import test from 'node:test'
import { autoSingleSpecText, resolveSingleSpecText } from './sku-derivation'

test('auto spec text joins net content with the pack unit', () => {
  assert.equal(autoSingleSpecText({ netContentText: ' 500g ', packUnitText: '袋' }), '500g/袋')
  assert.equal(autoSingleSpecText({ netContentText: '500g', packUnitText: ' 盒 ' }), '500g/盒')
})

test('auto spec text defaults to the bag unit when pack unit is empty', () => {
  assert.equal(autoSingleSpecText({ netContentText: '500g', packUnitText: '' }), '500g/袋')
  assert.equal(
    autoSingleSpecText({ netContentText: '500g', packUnitText: undefined as unknown as string }),
    '500g/袋'
  )
})

test('auto spec text stays empty without net content', () => {
  assert.equal(autoSingleSpecText({ netContentText: '', packUnitText: '袋' }), '')
  assert.equal(autoSingleSpecText({ netContentText: '  ', packUnitText: '箱' }), '')
})

test('resolve keeps customized text and falls back to auto derivation', () => {
  const customized = {
    specText: '礼盒装 500g',
    specTextCustomized: true,
    netContentText: '500g',
    packUnitText: '袋'
  }
  assert.equal(resolveSingleSpecText(customized), '礼盒装 500g')
  assert.equal(resolveSingleSpecText({ ...customized, specTextCustomized: false }), '500g/袋')
  assert.equal(
    resolveSingleSpecText({ ...customized, specTextCustomized: false, netContentText: '' }),
    ''
  )
})
