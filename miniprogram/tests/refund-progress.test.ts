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

function page(name: 'detail' | 'result', fetch: () => Promise<AfterSaleResponse>, fetchOrder: () => Promise<unknown> = async () => ({ items: [] })) {
  const code = ts.transpileModule(readFileSync(resolve(process.cwd(), `miniprogram/pages/after-sale/${name}/${name}.ts`), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  let instance: any
  const timers = new Map<number, () => void>()
  const redirects: string[] = []
  const navigations: string[] = []
  const toasts: string[] = []
  let timerId = 0
  runInNewContext(code, {
    exports: {},
    require: (path: string) => {
      if (path.endsWith('features/after-sale')) return afterSale
      if (path.endsWith('features/order-center')) return orderCenter
      if (path.endsWith('features/product-catalog')) return productCatalog
      if (path.endsWith('features/customer-service')) return { buildCustomerServiceUrl: () => '' }
      if (path.endsWith('utils/api-error')) return { isApiError: () => false }
      if (path.endsWith('services/order')) return { getOrderDetail: fetchOrder }
      if (path.endsWith('services/after-sale')) return { getAfterSaleDetail: fetch }
      throw new Error(`Unexpected import: ${path}`)
    },
    Page: (definition: any) => {
      instance = definition
      instance.setData = (next: any) => Object.assign(instance.data, next)
    },
    wx: {
      redirectTo: ({ url }: { url: string }) => redirects.push(url),
      navigateTo: ({ url }: { url: string }) => navigations.push(url),
      showToast: ({ title }: { title: string }) => toasts.push(title)
    },
    setTimeout: (callback: () => void) => { timers.set(++timerId, callback); return timerId },
    clearTimeout: (id: number) => timers.delete(id)
  })
  return { instance, timers, redirects, navigations, toasts }
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
  assert.equal(pending.instance.data.phase, 'processing')
  assert.deepEqual(pending.redirects, [])
  assert.equal(pending.timers.size, 1)
  pending.instance.onUnload()
  const success = page('result', async () => record('REFUNDED'))
  success.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(success.instance.data.detail.status, 'REFUNDED')
  assert.equal(success.redirects.length, 0)
})


test('简约页面从审核切换退款处理中，再原地展示成功，后台刷新不会恢复加载页', async () => {
  let status: AfterSaleStatus = 'REQUESTED'
  const runtime = page('result', async () => record(status))
  runtime.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(runtime.instance.data.processingTitle, '正在审核')
  assert.equal(runtime.instance.data.phase, 'processing')
  for (const next of ['APPROVED', 'REFUNDING'] as const) {
    status = next
    await runtime.instance.loadResult()
    assert.equal(runtime.instance.data.processingTitle, '退款处理中')
    assert.equal(runtime.instance.data.phase, 'processing')
    assert.equal(runtime.timers.size, 1)
  }
  status = 'REFUNDED'
  await runtime.instance.loadResult()
  assert.equal(runtime.instance.data.phase, 'success')
  assert.equal(runtime.timers.size, 0)
  assert.deepEqual(runtime.redirects, [])
})

test('简约审核页面暂停轮询并忽略隐藏后的返回，重新显示时恢复', async () => {
  let finish!: (value: AfterSaleResponse) => void
  const runtime = page('result', () => new Promise((resolve) => { finish = resolve }))
  runtime.instance.onLoad({ after_sale_id: '71' })
  runtime.instance.onHide()
  finish(record('REFUNDED'))
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(runtime.instance.data.phase, 'processing')
  assert.equal(runtime.instance.data.detail, null)
  assert.equal(runtime.timers.size, 0)
  runtime.instance.onShow()
  finish(record('REFUNDING'))
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(runtime.timers.size, 1)
  runtime.instance.onUnload()
  assert.equal(runtime.timers.size, 0)
})

test('退款异常和关闭展示真实提示，退货流程仍可进入详情填写物流', async () => {
  for (const status of ['REFUND_FAILED', 'REJECTED', 'CANCELLED', 'RETURN_REJECTED'] as const) {
    const runtime = page('result', async () => record(status))
    runtime.instance.onLoad({ after_sale_id: '71' })
    await new Promise<void>((resolve) => setImmediate(resolve))
    assert.equal(runtime.instance.data.phase, 'attention')
    assert.equal(runtime.timers.size, status === 'REFUND_FAILED' ? 1 : 0)
    runtime.instance.onUnload()
  }
  const returning = page('result', async () => ({ ...record('WAITING_RETURN'), afterSaleType: 'RETURN_REFUND' }))
  returning.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.deepEqual(returning.redirects, ['/pages/after-sale/detail/detail?after_sale_id=71'])
})

test('网络失败不会产生成功状态，后续刷新恢复服务器进度', async () => {
  let fails = false
  const runtime = page('result', async () => {
    if (fails) throw new Error('offline')
    return record('REQUESTED')
  })
  runtime.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  fails = true
  await runtime.instance.loadResult()
  assert.ok(runtime.instance.data.errorText)
  assert.equal(runtime.instance.data.phase, 'processing')
  assert.equal(runtime.timers.size, 1)
  fails = false
  await runtime.instance.loadResult()
  assert.equal(runtime.instance.data.errorText, '')
  runtime.instance.onUnload()
})

function productRecord(approvedQuantity?: number, approvedAmountCent?: number): AfterSaleResponse {
  return { ...record('REFUNDED'), items: [{
    id: 1, orderItemId: 201, skuId: 301, productTitle: '花椒油', specText: '麻辣 / 500g',
    requestedQuantity: 2, requestedAmountCent: 2000, approvedQuantity, approvedAmountCent
  }] }
}

test('商品数量和金额区分待审、全额同意与部分同意，不丢失零数量', async () => {
  for (const [quantity, amount, quantityText, amountText] of [
    [undefined, undefined, '申请 2 件', '¥20.00'],
    [2, 2000, '×2', '¥20.00'],
    [1, 1000, '申请 2 件 · 同意 1 件', '¥10.00'],
    [0, 0, '申请 2 件 · 同意 0 件', '¥0.00']
  ] as const) {
    const runtime = page('detail', async () => productRecord(quantity, amount))
    runtime.instance.onLoad({ after_sale_id: '71' })
    await new Promise<void>((resolve) => setImmediate(resolve))
    assert.equal(runtime.instance.data.displayItems[0].quantityText, quantityText)
    assert.equal(runtime.instance.data.displayItems[0].amountText, amountText)
    runtime.instance.onUnload()
  }
})

test('商品点击使用原订单的 SPU ID 并缓存，不能把 SKU ID 当商品 ID', async () => {
  let requests = 0
  const runtime = page('detail', async () => productRecord(2, 2000), async () => {
    requests++
    return { items: [{ orderItemId: 201, skuId: 301, spuId: 401 }] }
  })
  runtime.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  for (let index = 0; index < 2; index++) {
    await runtime.instance.onProductTap({ currentTarget: { dataset: { orderItemId: '201' } } })
  }
  assert.equal(requests, 1)
  assert.deepEqual(runtime.navigations, Array(2).fill('/pages/product/detail/detail?id=401'))
  await runtime.instance.onProductTap({ currentTarget: { dataset: { orderItemId: '999' } } })
  assert.equal(runtime.navigations.length, 2)
  runtime.instance.onUnload()
})

test('页面离开后的商品查询响应不会触发跳转', async () => {
  let finish!: (value: unknown) => void
  const runtime = page('detail', async () => productRecord(), () => new Promise((resolve) => { finish = resolve }))
  runtime.instance.onLoad({ after_sale_id: '71' })
  await new Promise<void>((resolve) => setImmediate(resolve))
  const opening = runtime.instance.onProductTap({ currentTarget: { dataset: { orderItemId: 201 } } })
  runtime.instance.onHide()
  finish({ items: [{ orderItemId: 201, spuId: 401 }] })
  await opening
  assert.deepEqual(runtime.navigations, [])
})

test('仅展示实际商家说明，空白和系统自动审核记录不显示为说明', () => {
  for (const note of [undefined, '', '   ', '未发货商品，系统自动审核通过']) {
    assert.equal(afterSale.buildAfterSaleView({ ...record('REFUNDED'), auditNote: note }).merchantNote, '')
  }
  const actual = afterSale.buildAfterSaleView({ ...record('REFUNDED'), auditNote: '  运费一并退回  ', reviewedBy: 9 })
  assert.equal(actual.merchantNote, '运费一并退回')
  const automatic = afterSale.buildAfterSaleView({ ...record('REFUNDED'), auditNote: '未发货商品，系统自动审核通过' })
  assert.equal(automatic.auditNote, '未发货商品，系统自动审核通过')
})
