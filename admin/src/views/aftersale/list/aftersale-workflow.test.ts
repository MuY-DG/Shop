import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'

import {
  adminAfterSaleActions,
  canManageReturnAddresses,
  returnAddressText
} from './aftersale-workflow'

test('售后操作同时受服务端状态矩阵与审核权限约束', () => {
  assert.deepEqual(adminAfterSaleActions({ status: 'REQUESTED', canAudit: true }), [
    'APPROVE',
    'REJECT'
  ])
  assert.deepEqual(
    adminAfterSaleActions({
      status: 'REQUESTED',
      allowedActions: ['APPROVE'],
      canAudit: true
    }),
    ['APPROVE']
  )
  assert.deepEqual(adminAfterSaleActions({ status: 'RETURNING', canAudit: true }), [
    'RECEIVE_RETURN'
  ])
  assert.deepEqual(
    adminAfterSaleActions({
      status: 'RETURNING',
      allowedActions: ['UPDATE_RETURN_SHIPMENT'],
      canAudit: true
    }),
    ['RECEIVE_RETURN']
  )
  assert.deepEqual(adminAfterSaleActions({ status: 'WAITING_INSPECTION', canAudit: true }), [
    'INSPECT_RETURN'
  ])
  assert.deepEqual(adminAfterSaleActions({ status: 'WAITING_INSPECTION', canAudit: false }), [])
  assert.deepEqual(adminAfterSaleActions({ status: 'REFUNDING', canAudit: true }), [])
})

test('退货地址维护使用独立权限并生成可核对的完整地址', () => {
  assert.equal(canManageReturnAddresses(true), true)
  assert.equal(canManageReturnAddresses(false), false)
  assert.equal(
    returnAddressText({
      contactName: '仓库',
      contactPhone: '13800000000',
      province: '四川省',
      city: '成都市',
      district: '武侯区',
      detailAddress: '仓储路 1 号'
    }),
    '仓库 · 13800000000 · 四川省成都市武侯区仓储路 1 号'
  )
})

test('售后记录覆盖后端 V92 实际退货事件键', () => {
  const source = readFileSync(resolve(process.cwd(), 'src/views/aftersale/list/index.vue'), 'utf8')
  ;[
    'RETURN_AUTHORIZED',
    'RETURN_SHIPMENT_SUBMITTED',
    'RETURN_SHIPMENT_UPDATED',
    'RETURN_RECEIVED',
    'RETURN_INSPECTION_REJECTED',
    'RETURN_EXPIRED'
  ].forEach((eventType) => {
    assert.ok(source.split(`${eventType}:`).length >= 3, `${eventType} 缺少标题或状态文案`)
  })
  assert.match(source, /确认通过并等待寄回/)
  assert.match(source, /target\.afterSaleType === 'RETURN_REFUND'/)
})
