import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as orders from '../miniprogram/features/order-center'
import * as logistics from '../miniprogram/features/order-logistics'
import * as lists from '../miniprogram/features/list-refresh'
import * as afterSales from '../miniprogram/features/after-sale'
import type { AppOrderDetailResponse, AppOrderShipmentResponse } from '../miniprogram/types/order'

function shipment(id = 1, overrides: Partial<AppOrderShipmentResponse> = {}): AppOrderShipmentResponse {
  return { shipmentId: id, orderId: 100, packageNo: id, logisticsType: 1, deliveryMode: 1,
    itemDesc: '商品', expressCompanyCode: 'SF', expressCompanyName: '顺丰速运', trackingNo: `SF${id}`,
    shipmentSource: 'MANUAL', localShipmentStatus: 'SHIPPED', wechatProviderMode: 'REAL',
    wechatUploadStatus: 'UPLOADED', wechatUploadMessage: null, waybillTrackingSupported: true,
    waybillRegistrationKind: 'FOLLOW', waybillRegistrationStatus: 'REGISTERED', waybillRegistrationMessage: null,
    shippedAt: '2026-09-08T01:00:00Z', uploadTime: null, wechatUploadedAt: null, ...overrides }
}
function detail(shipments = [shipment()]): AppOrderDetailResponse {
  return { orderId: 100, orderNo: 'ORD100', status: 'SHIPPED', source: 'DIRECT',
    productOriginalAmountCent: 1000, productAmountCent: 1000, couponDiscountCent: 0, freightCent: 0,
    payableAmountCent: 1000, paidAmountCent: 1000, receiverName: '测试', receiverPhone: '13800138000',
    receiverAddress: '测试地址', createdAt: '2026-09-08T01:00:00Z', items: [], shipments }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => { resolve = res })
  return { resolve, promise }
}
function page(name: 'list' | 'detail', response: AppOrderDetailResponse,
  token: (id: number) => Promise<{ waybillToken: string }> = async (id) => ({ waybillToken: `token-${id}` }),
  loadDetail: () => Promise<AppOrderDetailResponse> = async () => response) {
  const navigations: string[] = [], pluginCalls: string[] = [], toasts: string[] = [], tokenIds: number[] = []
  let instance: any
  const code = ts.transpileModule(readFileSync(resolve(process.cwd(), `miniprogram/pages/order/${name}/${name}.ts`), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  runInNewContext(code, {
    exports: {},
    require: (module: string) => {
      if (module.endsWith('features/order-center')) return orders
      if (module.endsWith('features/list-refresh')) return lists
      if (module.endsWith('features/after-sale')) return afterSales
      if (module.endsWith('features/order-logistics')) return {
        ...logistics,
        openShipmentLogistics: (options: logistics.OpenShipmentLogisticsOptions) => logistics.openShipmentLogistics({
          ...options, choosePackage: async () => 1,
          loadPlugin: () => ({ openWaybillTracking: ({ waybillToken }: { waybillToken: string }) => pluginCalls.push(waybillToken) })
        })
      }
      if (module.endsWith('services/order')) return {
        getOrderDetail: loadDetail,
        getShipmentWaybillToken: async (_orderId: number, id: number) => { tokenIds.push(id); return token(id) }
      }
      if (module.endsWith('utils/api-error')) return { isApiError: () => false }
      return {}
    },
    Page: (definition: any) => { instance = definition; instance.setData = (patch: object) => Object.assign(instance.data, patch) },
    wx: { navigateTo: ({ url }: { url: string }) => navigations.push(url), showToast: ({ title }: { title: string }) => toasts.push(title) }
  })
  if (name === 'detail') {
    instance.data.detail = orders.buildOrderDetailView(response)
    instance.data.deliverySummary = logistics.buildOrderDeliverySummary(instance.data.detail)
  } else {
    instance.data.orders = [orders.buildOrderSummaryView({
      ...response, pendingReviewCount: 0, productTitle: '商品', itemCount: 1
    })]
  }
  return { instance, navigations, pluginCalls, toasts, tokenIds,
    open: () => instance.onLogisticsTap({ currentTarget: { dataset: { id: 100 } } }) }
}

for (const name of ['list', 'detail'] as const) {
  test(`${name}实体快递直接打开官方插件，不进入自建物流详情`, async () => {
    const r = page(name, detail())
    await r.open()
    assert.deepEqual(r.pluginCalls, ['token-1'])
    assert.deepEqual(r.tokenIds, [1])
    assert.deepEqual(r.navigations, [])
  })
  test(`${name}多包裹选择后只申请所选包裹 token`, async () => {
    const r = page(name, detail([shipment(1), shipment(2)]))
    await r.open()
    assert.deepEqual(r.tokenIds, [2])
    assert.deepEqual(r.pluginCalls, ['token-2'])
  })
  test(`${name}等待 token 时离开页面，不会迟到打开插件`, async () => {
    const pending = deferred<{ waybillToken: string }>()
    const r = page(name, detail(), () => pending.promise)
    const opening = r.open()
    await new Promise<void>((resolve) => setImmediate(resolve))
    r.instance.onHide()
    pending.resolve({ waybillToken: 'late-token' })
    await opening
    assert.deepEqual(r.pluginCalls, [])
    assert.deepEqual(r.toasts, [])
  })
  test(`${name}token 失败提示不可用，不再跳自建轨迹页`, async () => {
    const r = page(name, detail(), async () => { throw new Error('unavailable') })
    await r.open()
    assert.deepEqual(r.pluginCalls, [])
    assert.deepEqual(r.navigations, [])
    assert.deepEqual(r.toasts, [logistics.LOGISTICS_UNAVAILABLE_MESSAGE])
  })
}

test('虚拟发货、无需快递和沙盒运单只进入原订单详情', async () => {
  for (const parcel of [
    shipment(1, { logisticsType: 3, expressCompanyCode: null, trackingNo: null, waybillTrackingSupported: false }),
    shipment(1, { logisticsType: 2, waybillTrackingSupported: false }),
    shipment(1, { shipmentSource: 'WECHAT_WAYBILL', waybillRegistrationStatus: 'SKIPPED', waybillTrackingSupported: false })
  ]) {
    const r = page('list', detail([parcel]))
    await r.open()
    assert.deepEqual(r.navigations, ['/pages/order/detail/detail?order_id=100'])
    assert.deepEqual(r.tokenIds, [])
    assert.deepEqual(r.pluginCalls, [])
  }
})

test('整单退款后的旧物流入口不再打开插件', async () => {
  const r = page('list', { ...detail(), status: 'REFUNDED' })
  await r.open()
  assert.deepEqual(r.tokenIds, [])
  assert.deepEqual(r.navigations, ['/pages/order/detail/detail?order_id=100'])
})

test('列表打开物流只反馈所点入口，连续点击不会重复请求', async () => {
  const pending = deferred<{ waybillToken: string }>()
  const r = page('list', detail(), () => pending.promise)
  const opening = r.instance.onLogisticsTap({ currentTarget: { dataset: { id: 100, entry: 'button' } } })
  await new Promise<void>((resolve) => setImmediate(resolve))
  assert.equal(r.instance.data.logisticsEntry, 'button')
  await r.open()
  assert.deepEqual(r.tokenIds, [1])
  const template = readFileSync(resolve(process.cwd(), 'miniprogram/pages/order/list/list.wxml'), 'utf8')
  const disabledExpressions = [...template.matchAll(/disabled="\{\{([^}]+)\}\}"/g)].map((match) => match[1]!)
  assert.ok(disabledExpressions.length > 0)
  for (const expression of disabledExpressions) {
    for (const orderId of [100, 101]) {
      assert.equal(runInNewContext(expression, { ...r.instance.data, item: { orderId } }), false)
    }
  }
  pending.resolve({ waybillToken: 'token-1' })
  await opening
  assert.equal(r.instance.data.logisticsEntry, '')
  assert.equal(r.instance.data.actionOrderId, 0)
})

function withAfterSale(): AppOrderDetailResponse {
  return { ...detail(), latestAfterSale: {
    id: 301, afterSaleNo: 'AS301', orderId: 100, orderNo: 'ORD100', userId: 'TEST',
    afterSaleType: 'REFUND_ONLY', status: 'REQUESTED', reason: '测试', requestedAmountCent: 1000,
    createdAt: '2026-09-08T01:00:00Z', evidenceFileIds: [], evidenceFiles: [], items: [], allowedActions: []
  } }
}

test('列表查看售后进入已有记录，申请售后才进入申请页', async () => {
  const r = page('list', withAfterSale())
  assert.equal(r.instance.data.orders[0].afterSaleActionText, '查看售后')
  await r.instance.onAfterSaleTap({ currentTarget: { dataset: { id: 100 } } })
  assert.deepEqual(r.navigations, [afterSales.buildAfterSaleDetailUrl(301)])
  const applying = page('list', { ...detail(), status: 'COMPLETED' })
  await applying.instance.onAfterSaleTap({ currentTarget: { dataset: { id: 100 } } })
  assert.deepEqual(applying.navigations, [afterSales.buildAfterSaleApplyUrl(100)])
})

test('查看售后等待期间离开页面，不发生迟到跳转且防止重复查询', async () => {
  const pending = deferred<AppOrderDetailResponse>()
  let requests = 0
  const r = page('list', withAfterSale(), undefined, () => { requests++; return pending.promise })
  const event = { currentTarget: { dataset: { id: 100 } } }
  const opening = r.instance.onAfterSaleTap(event)
  await r.instance.onAfterSaleTap(event)
  assert.equal(requests, 1)
  r.instance.onHide()
  pending.resolve(withAfterSale())
  await opening
  assert.deepEqual(r.navigations, [])
  assert.equal(r.instance.data.actionOrderId, 0)
})

test('售后记录已隐藏时查看入口回到订单详情，不误进新的申请页', async () => {
  const r = page('list', withAfterSale(), undefined, async () => detail())
  await r.instance.onAfterSaleTap({ currentTarget: { dataset: { id: 100 } } })
  assert.deepEqual(r.navigations, [orders.buildOrderDetailUrl(100)])
})

test('页面未保留自定义请求序号时，售后和物流仍能完成并释放按钮', async () => {
  const r = page('list', withAfterSale())
  delete r.instance._afterSaleRequest
  await r.instance.onAfterSaleTap({ currentTarget: { dataset: { id: 100 } } })
  assert.deepEqual(r.navigations, [afterSales.buildAfterSaleDetailUrl(301)])
  assert.equal(r.instance.data.actionOrderId, 0)
  const tracking = page('list', detail())
  tracking.instance._logisticsRequest = Number.NaN
  await tracking.open()
  assert.deepEqual(tracking.pluginCalls, ['token-1'])
  assert.equal(tracking.instance.data.actionOrderId, 0)
})

test('七个包裹也能选择末尾包裹，取消选择不会申请 token', async () => {
  const shipments = orders.buildOrderDetailView(detail(Array.from({ length: 7 }, (_, index) => shipment(index + 1)))).shipmentViews
  const tokenIds: number[] = []
  let choice = 0
  const options: logistics.OpenShipmentLogisticsOptions = {
    shipments, isCurrent: () => true,
    choosePackage: async (labels) => { assert.ok(labels.length <= 6); return choice++ === 0 ? 5 : 1 },
    requestWaybillToken: async (id) => { tokenIds.push(id); return { waybillToken: `token-${id}` } },
    loadPlugin: () => ({ openWaybillTracking: () => {} })
  }
  assert.equal(await logistics.openShipmentLogistics(options), 'OPENED')
  assert.deepEqual(tokenIds, [7])
  assert.equal(await logistics.openShipmentLogistics({ ...options, choosePackage: async () => null }), 'CANCELLED')
  assert.deepEqual(tokenIds, [7])
})
