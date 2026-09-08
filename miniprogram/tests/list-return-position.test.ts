import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as afterSale from '../miniprogram/features/after-sale'
import * as account from '../miniprogram/features/account-center'
import * as product from '../miniprogram/features/product-catalog'
import * as order from '../miniprogram/features/order-center'
import * as logistics from '../miniprogram/features/order-logistics'
import * as lists from '../miniprogram/features/list-refresh'

const cases = [
  ['订单和订单搜索', 'pages/order/list/list', 'orders'],
  ['售后', 'components/after-sale-list/after-sale-list', 'records'],
  ['收藏', 'pages/account/favorites/favorites', 'items'],
  ['足迹', 'pages/account/history/history', 'items'],
  ['分类商品', 'components/catalog-browser/catalog-browser', 'sourceProducts']
] as const

function sourceRecord(id: number) {
  return {
    id, orderId: id, spuId: id, orderNo: `ORD${id}`, status: 'SHIPPED',
    afterSaleNo: `AS${id}`, afterSaleType: 'REFUND_ONLY', userId: '1', reason: '不想要了',
    title: `商品${id}`, productTitle: `商品${id}`, available: true,
    minPriceCent: 1000, maxPriceCent: 1000, requestedAmountCent: 1000,
    productAmountCent: 1000, couponDiscountCent: 0, freightCent: 0,
    payableAmountCent: 1000, paidAmountCent: 1000, itemCount: 1, pendingReviewCount: 0,
    items: [], evidenceFiles: [], evidenceFileIds: [], allowedActions: [],
    createdAt: '2026-09-08T01:00:00Z', favoritedAt: '2026-09-08T01:00:00Z',
    firstViewedAt: '2026-09-08T01:00:00Z', lastViewedAt: '2026-09-08T01:00:00Z', viewCount: 1
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => { resolve = res })
  return { promise, resolve }
}
const flush = () => new Promise<void>((resolve) => setImmediate(resolve))

function runtime(path: string) {
  const calls: number[] = []
  let total = 200
  let failPage = 0
  let holdPage = 0
  let held: ReturnType<typeof deferred<void>> | null = null
  const fetch = async (current: number, size: number) => {
    calls.push(current)
    if (current === holdPage) { held = deferred<void>(); await held.promise }
    if (current === failPage) throw new Error('offline')
    const start = (current - 1) * size
    return { current, size, total, hasMore: current * size < total,
      records: Array.from({ length: Math.max(0, Math.min(size, total - start)) }, (_, index) => ({ ...sourceRecord(start + index + 1), status: path.includes('after-sale-list') ? 'REQUESTED' : 'SHIPPED' })) }
  }
  let instance: any
  let definition: any
  const make = (config: any) => {
    definition = config
    instance = { ...config, ...config.methods, data: structuredClone(config.data),
      setData: (patch: object) => { Object.assign(instance.data, patch) } }
    for (const [key, value] of Object.entries(config.properties || {}) as Array<[string, any]>) {
      instance.data[key] = value.value
    }
  }
  const code = ts.transpileModule(readFileSync(resolve(process.cwd(), `miniprogram/${path}.ts`), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  runInNewContext(code, {
    exports: {}, Page: make, Component: make,
    require: (module: string) => {
      if (module.endsWith('features/order-search')) return { normalizeOrderRouteKeyword: () => '' }
      if (module.endsWith('features/list-refresh')) return lists
      if (module.endsWith('features/order-center')) return order
      if (module.endsWith('features/order-logistics')) return logistics
      if (module.endsWith('features/after-sale')) return afterSale
      if (module.endsWith('features/account-center')) return account
      if (module.endsWith('features/product-catalog')) return product
      if (module.endsWith('services/order')) return { getOrders: (query: any) => fetch(query.current, query.size) }
      if (module.endsWith('services/after-sale')) return { getAfterSales: fetch }
      if (module.endsWith('services/product-preference')) return { getFavorites: fetch, getBrowseHistory: fetch }
      if (module.endsWith('services/product')) return { getProductList: (query: any) => fetch(query.current, query.size), getProductCategories: async () => [], getProductFilterFacets: async () => [] }
      if (module.endsWith('utils/api-error')) return { isApiError: () => false }
      return {}
    },
    wx: { showToast: () => {} }
  })
  const loadMore = () => path.includes('after-sale-list') ? instance.loadRecords(false)
    : path.includes('order/list') ? instance.loadMoreOrders() : instance.loadMore()
  return { instance, calls, loadMore,
    start: async () => {
      if (definition.lifetimes) definition.lifetimes.attached.call(instance)
      else instance.onLoad({})
      await flush()
    },
    show: async () => {
      if (path.includes('catalog-browser')) await instance.silentRefresh()
      else if (definition.pageLifetimes) definition.pageLifetimes.show.call(instance)
      else instance.onShow()
      await flush()
    },
    hide: () => {
      if (definition.pageLifetimes?.hide) definition.pageLifetimes.hide.call(instance)
      else instance.onHide?.()
    },
    detach: () => {
      if (definition.lifetimes) definition.lifetimes.detached.call(instance)
      else instance.onUnload()
    },
    fail: (page: number) => { failPage = page },
    shrink: (count: number) => { total = count },
    hold: (page: number) => { holdPage = page },
    release: () => { holdPage = 0; held?.resolve() }
  }
}

for (const [name, path, field] of cases) {
  test(`${name}列表返回保留已加载三页和滚动位置，继续从第四页加载`, async () => {
    const r = runtime(path)
    await r.start(); await r.loadMore(); await r.loadMore()
    const count = r.instance.data[field].length
    r.instance.onListScroll({ detail: { scrollTop: 1800 } })
    r.hide(); r.calls.length = 0; await r.show()
    assert.equal(r.instance.data[field].length, count)
    assert.equal(r.instance.data.current, 3)
    assert.equal(r.instance.data.scrollTop, 1800)
    assert.deepEqual(r.calls, [1, 2, 3])
    await r.loadMore()
    assert.equal(r.calls[r.calls.length - 1], 4)
    assert.equal(r.instance.data.current, 4)
    assert.ok(r.instance.data[field].length > count)
    r.detach()
  })

  test(`${name}列表后续页刷新失败时保留整份列表，不展示残缺的第一页`, async () => {
    const r = runtime(path)
    await r.start(); await r.loadMore(); await r.loadMore()
    const before = JSON.stringify(r.instance.data[field])
    r.fail(2); await r.show()
    assert.equal(JSON.stringify(r.instance.data[field]), before)
    assert.equal(r.instance.data.current, 3)
    assert.equal(r.instance.data.loaded, true)
    assert.equal(r.instance.data.loadingMore, false)
    assert.equal(r.instance.data.errorText, '')
    r.detach()
  })

  test(`${name}列表刷新期间不抢先加载下一页，离开后丢弃晚到的数据`, async () => {
    const r = runtime(path)
    await r.start(); await r.loadMore()
    const before = JSON.stringify(r.instance.data[field])
    r.hold(2)
    const showing = r.show(); await flush()
    const requests = r.calls.length
    await r.loadMore()
    assert.equal(r.calls.length, requests)
    r.detach(); r.release(); await showing
    assert.equal(JSON.stringify(r.instance.data[field]), before)
  })
}

test('手动下拉刷新仍回到第一页，不复用返回位置', async () => {
  for (const [, path, field] of cases) {
    const r = runtime(path)
    await r.start(); await r.loadMore()
    r.instance.onListScroll({ detail: { scrollTop: 1000 } })
    r.instance.data.tabPage = true
    await r.instance.onContentRefresh()
    assert.equal(r.instance.data.current, 1, path)
    assert.equal(r.instance.data.scrollTop, 0, path)
    assert.ok(r.instance.data[field].length > 0)
    r.detach()
  }
})

test('后台真实删除记录时保留剩余页面，并按最新总数停止翻页', async () => {
  const r = runtime('pages/order/list/list')
  await r.start(); await r.loadMore(); await r.loadMore()
  r.shrink(17); r.calls.length = 0; await r.show()
  assert.equal(r.instance.data.orders.length, 17)
  assert.equal(r.instance.data.current, 2)
  assert.equal(r.instance.data.hasMore, false)
  assert.deepEqual(r.calls, [1, 2])
  r.detach()
})

test('不同订单列表实例的加载不互相取消', async () => {
  const first = runtime('pages/order/list/list')
  const second = runtime('pages/order/list/list')
  first.hold(1); await first.start(); await second.start()
  first.release(); await flush()
  assert.equal(first.instance.data.orders.length, 10)
  assert.equal(second.instance.data.orders.length, 10)
  first.detach(); second.detach()
})


test('后台刷新期间切换到售后分组，旧订单响应不能覆盖新分组', async () => {
  const r = runtime('pages/order/list/list')
  await r.start(); await r.loadMore()
  r.hold(2)
  const showing = r.show(); await flush()
  r.instance.onTabTap({ currentTarget: { dataset: { group: 'AFTER_SALE' } } })
  r.release(); await showing
  assert.equal(r.instance.data.activeGroup, 'AFTER_SALE')
  assert.equal(r.instance.data.orders.length, 0)
  assert.equal(r.instance.data.refreshing, false)
  assert.equal(r.instance.data.scrollTop, 0)
  r.detach()
})
