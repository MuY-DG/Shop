import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { availableAdminActions, validateActionForm } from './account-rights-state'

const apiSource = readFileSync(new URL('../../api/account-rights.ts', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('./index.vue', import.meta.url), 'utf8')

test('account-rights admin action matrix follows the backend state machine', () => {
  assert.deepEqual(availableAdminActions('PENDING'), ['review', 'reject'])
  assert.deepEqual(availableAdminActions('IN_REVIEW'), ['approve', 'reject'])
  assert.deepEqual(availableAdminActions('APPROVED'), ['complete'])
  assert.deepEqual(availableAdminActions('REJECTED'), [])
  assert.deepEqual(availableAdminActions('WITHDRAWN'), [])
  assert.deepEqual(availableAdminActions('COMPLETED'), [])
})

test('every admin transition requires a reason and a truthful retention explanation', () => {
  const base = { version: 1, reason: '', retentionExplanation: '', retainedDataCategories: [] }
  assert.match(validateActionForm(base)!, /处理原因/)
  assert.match(validateActionForm({ ...base, reason: '审核记录' })!, /保留或删除说明/)
  assert.equal(
    validateActionForm({ ...base, reason: '审核记录', retentionExplanation: '按实际情况填写' }),
    null
  )
})

test('admin endpoints are explicit transitions and completion warns about active commerce', () => {
  assert.match(apiSource, /\/admin\/account-rights\/requests/)
  assert.match(apiSource, /\$\{requestId\}\/\$\{action\}/)
  assert.match(pageSource, /完成前后端会再次核对未完结订单、支付、退款和售后/)
  assert.match(pageSource, /account-rights:manage/)
})
