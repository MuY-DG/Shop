import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as orders from '../miniprogram/features/order-center'
import * as logistics from '../miniprogram/features/order-logistics'
import * as lists from '../miniprogram/features/list-refresh'
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
  token: (id: number) => Promise<{ waybillToken: string }> = async (id) => ({ waybillToken: `token-${id}` })) {
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
      if (module.endsWith('features/order-logistics')) return {
        ...logistics,
        openShipmentLogistics: (options: logistics.OpenShipmentLogisticsOptions) => logistics.openShipmentLogistics({
          ...options, choosePackage: async () => 1,
          loadPlugin: () => ({ openWaybillTracking: ({ waybillToken }: { waybillToken: string }) => pluginCalls.push(waybillToken) })
        })
      }
      if (module.endsWith('services/order')) return {
        getOrderDetail: async () => response,
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
