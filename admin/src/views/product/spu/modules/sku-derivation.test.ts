import assert from 'node:assert/strict'
import test from 'node:test'
import { autoSingleSpecText, parseNetContentGrams, resolveSingleSpecText } from './sku-derivation'

test('parses plain mass units into grams', () => {
  assert.equal(parseNetContentGrams('500g'), 500)
  assert.equal(parseNetContentGrams(' 1.5 kg '), 1500)
  assert.equal(parseNetContentGrams('300克'), 300)
  assert.equal(parseNetContentGrams('2公斤'), 2000)
  assert.equal(parseNetContentGrams('0.5千克'), 500)
})

test('rejects non-mass, composite, or empty net content', () => {
  assert.equal(parseNetContentGrams('500ml'), null)
  assert.equal(parseNetContentGrams('500g×2袋'), null)
  assert.equal(parseNetContentGrams('约500g'), null)
  assert.equal(parseNetContentGrams('净含量 500g'), null)
  assert.equal(parseNetContentGrams(''), null)
  assert.equal(parseNetContentGrams('0g'), null)
})

test('auto spec text follows net content and falls back to empty', () => {
  assert.equal(autoSingleSpecText({ netContentText: ' 500g ' }), '500g')
  assert.equal(autoSingleSpecText({ netContentText: '' }), '')
})

test('resolve keeps customized text and falls back to auto derivation', () => {
  const customized = {
    specText: '礼盒装 500g/袋',
    specTextCustomized: true,
    netContentText: '500g'
  }
  assert.equal(resolveSingleSpecText(customized), '礼盒装 500g/袋')
  assert.equal(resolveSingleSpecText({ ...customized, specTextCustomized: false }), '500g')
  assert.equal(
    resolveSingleSpecText({ ...customized, specTextCustomized: false, netContentText: '' }),
    ''
  )
})
