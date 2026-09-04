import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const pageSource = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')
const apiSource = readFileSync(new URL('../../../api/storage.ts', import.meta.url), 'utf8')

test('storage location uses bucket and region options until discovery needs manual fallback', () => {
  assert.match(pageSource, /:label="manualLocation \? '存储桶' : '存储桶 · 地域'"/)
  assert.match(pageSource, /:label="`\$\{option\.bucket\} · \$\{option\.region\}`"/)
  assert.match(pageSource, /<ElFormItem v-if="manualLocation" label="地域" prop="region">/)
  assert.match(pageSource, /catch \{\s+manualLocation\.value = true/)
})

test('selecting a bucket loads default and enabled custom client domains', () => {
  assert.match(pageSource, /await fetchStorageDomains\(/)
  assert.match(pageSource, /option\.type === 'DEFAULT' \? '默认域名' : '自定义域名'/)
  assert.match(apiSource, /url: '\/admin\/storage\/config\/domains'/)
})
