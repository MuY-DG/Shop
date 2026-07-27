import assert from 'node:assert/strict'
import test from 'node:test'
import { authenticatedLoginRedirect } from './authRedirect'

test('已登录用户优先恢复站内目标地址', () => {
  assert.equal(
    authenticatedLoginRedirect('/trade/orders?status=PAID#detail'),
    '/trade/orders?status=PAID#detail'
  )
  assert.equal(authenticatedLoginRedirect(['/system/user', '/operations/overview']), '/system/user')
})

test('已登录用户的无效或循环目标回退到首页', () => {
  assert.equal(authenticatedLoginRedirect(undefined), '/')
  assert.equal(authenticatedLoginRedirect('https://example.com'), '/')
  assert.equal(authenticatedLoginRedirect('//example.com/path'), '/')
  assert.equal(authenticatedLoginRedirect('/auth/login'), '/')
  assert.equal(authenticatedLoginRedirect('/auth/login?redirect=/system/user'), '/')
})
