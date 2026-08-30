import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const readSource = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')

test('absolute timestamp pages use the shared device-local formatter', () => {
  const merchant = readSource('../views/compliance/merchant/index.vue')
  const documents = readSource('../views/compliance/documents/index.vue')
  const finance = readSource('../views/finance/reconciliation/index.vue')
  const productEditor = readSource('../views/product/spu/modules/spu-editor.vue')

  for (const source of [merchant, documents, finance, productEditor]) {
    assert.match(source, /formatLocalDateTime/)
    assert.doesNotMatch(source, /\.replace\(['"]T['"]/)
    assert.doesNotMatch(source, /\.toLocale(?:Date|Time)?String\(/)
  }
})

test('legal document effective time includes the device offset', () => {
  const documents = readSource('../views/compliance/documents/index.vue')
  assert.match(documents, /value-format="YYYY-MM-DDTHH:mm:ssZ"/)
  assert.doesNotMatch(documents, /value-format="YYYY-MM-DDTHH:mm:ss"/)
})
