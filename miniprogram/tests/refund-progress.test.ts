import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as afterSale from '../miniprogram/features/after-sale'
import * as orderCenter from '../miniprogram/features/order-center'
import * as productCatalog from '../miniprogram/features/product-catalog'
import type { AfterSaleResponse, AfterSaleStatus } from '../miniprogram/types/after-sale'

function record(status: AfterSaleStatus): AfterSaleResponse {
  return {
    id: 71, afterSaleNo: 'AS71', orderId: 101, orderNo: 'ORD101', userId: '1',
    afterSaleType: 'REFUND_ONLY', status, reason: '不想要了', requestedAmountCent: 2000,
    createdAt: '2026-09-08T00:00:00Z', evidenceFileIds: [], evidenceFiles: [], items: [], allowedActions: []
  }
}

function page(name: 'detail' | 'result', fetch: () => Promise<AfterSaleResponse>) {
  const code = ts.transpileModule(readFileSync(resolve(process.cwd(), `miniprogram/pages/after-sale/${name}/${name}.ts`), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  let instance: any
  const timers = new Map<number, () => void>()
  const redirects: string[] = []
  let timerId = 0
  runInNewContext(code, {
    exports: {},
    require: (path: string) => {
      if (path.endsWith('features/after-sale')) return afterSale
      if (path.endsWith('features/order-center')) return orderCenter
      if (path.endsWith('features/product-catalog')) return productCatalog
      if (path.endsWith('features/customer-service')) return { buildCustomerServiceUrl: () => '' }
      if (path.endsWith('utils/api-error')) return { isApiError: () => false }
      if (path.endsWith('services/after-sale')) return { getAfterSaleDetail: fetch }
      throw new Error(`Unexpected import: ${path}`)
    },
    Page: (definition: any) => {
      instance = definition
      instance.setData = (next: any) => Object.assign(instance.data, next)
    },
    wx: { redirectTo: ({ url }: { url: string }) => redirects.push(url) },
    setTimeout: (callback: () => void) => { timers.set(++timerId, callback); return timerId },
    clearTimeout: (id: number) => timers.delete(id)
  })
  return { instance, timers, redirects }
}

test('退款进度仅在真实 REFUNDED 后跳转，处理中和异常都不会假成功', async () => {
  let status: AfterSaleStatus = 'REQUESTED'
  const runtime = page('detail', async () => record(status))
  runtime.instance.onLoad({ after_sale_id: '71', follow_refund: '1' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(runtime.timers.size, 1)
  for (const next of ['REFUNDING', 'REFUND_FAILED'] as const) {
    status = next
    await runtime.instance.loadDetail()
    assert.equal(runtime.redirects.length, 0)
    assert.equal(runtime.timers.size, 1)
  }
  status = 'REFUNDED'
  await runtime.instance.loadDetail()
  assert.deepEqual(runtime.redirects, ['/pages/after-sale/result/result?after_sale_id=71'])
  runtime.instance.onUnload()
  assert.equal(runtime.timers.size, 0)
})

test('隐藏页面清理轮询并忽略晚到的成功响应，重新显示后恢复刷新', async () => {
  let finish!: (value: AfterSaleResponse) => void
  const runtime = page('detail', () => new Promise((resolve) => { finish = resolve }))
  runtime.instance.onLoad({ after_sale_id: '71', follow_refund: '1' })
  runtime.instance.onHide()
  finish(record('REFUNDED'))
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(runtime.redirects.length, 0)
  assert.equal(runtime.timers.size, 0)
  runtime.instance.onShow()
  finish(record('REFUNDING'))
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(runtime.instance.data.detail.status, 'REFUNDING')
  assert.equal(runtime.timers.size, 1)
  runtime.instance.onUnload()
})

test('取消或拒绝后停止轮询，空凭证保持为空', async () => {
  const runtime = page('detail', async () => record('CANCELLED'))
  runtime.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(runtime.timers.size, 0)
  assert.deepEqual(runtime.instance.data.detail.evidenceNames, [])
  assert.equal(afterSale.shouldPollAfterSale('REJECTED'), false)
})

test('结果页不相信 URL，必须重新读取后端确认成功', async () => {
  const pending = page('result', async () => record('REFUNDING'))
  pending.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(pending.instance.data.detail, null)
  assert.deepEqual(pending.redirects, ['/pages/after-sale/detail/detail?after_sale_id=71&follow_refund=1'])
  const success = page('result', async () => record('REFUNDED'))
  success.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(success.instance.data.detail.status, 'REFUNDED')
  assert.equal(success.redirects.length, 0)
})
