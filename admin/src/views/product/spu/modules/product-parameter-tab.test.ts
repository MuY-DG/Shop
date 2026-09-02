import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('./product-parameter-tab.vue', import.meta.url), 'utf8')

test('product parameter label stays inline with the required marker', () => {
  assert.match(source, /<span class="parameter-label">/)
  assert.match(source, /\.parameter-label\s*{[\s\S]*?display:\s*inline-flex;/)
  assert.match(
    source,
    /\.parameter-form :deep\(\.el-form-item__label\)\s*{[\s\S]*?height:\s*auto !important;/
  )
  assert.doesNotMatch(source, /<div class="parameter-label">/)
})
