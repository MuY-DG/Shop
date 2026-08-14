import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const pageSource = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')

test('config-only administrators do not request operational service-card endpoints', () => {
  assert.match(
    pageSource,
    /const canReadOperations = computed\(\(\) => hasAuth\('wechat-service-card:read'\)\)/
  )
  assert.match(
    pageSource,
    /const loadStatus = async \(\) => \{\s+if \(!canReadOperations\.value\) return/
  )
  assert.match(
    pageSource,
    /const loadDeliveries = async \(\) => \{\s+if \(!canReadOperations\.value\) return/
  )
  assert.match(pageSource, /<ElCard v-if="canReadOperations" shadow="never"/)
})
