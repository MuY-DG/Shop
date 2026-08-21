import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const apiSource = readFileSync(new URL('../../../api/customer.ts', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')

test('customer status management distinguishes disabled from cancelled accounts', () => {
  assert.match(apiSource, /\/admin\/customers\/\$\{userId\}\/status/)
  assert.match(apiSource, /method: 'PATCH'/)
  assert.match(pageSource, /管理员停用/)
  assert.match(pageSource, /已注销用户/)
  assert.match(pageSource, /customer:user:status/)
  assert.match(pageSource, /现有登录会立即失效/)
  assert.match(pageSource, /已注销用户需主动筛选/)
  assert.match(pageSource, /row\.status === 'CANCELLED'[\s\S]*?不可操作/)
})
