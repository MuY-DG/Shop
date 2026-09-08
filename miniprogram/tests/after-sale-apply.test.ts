import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as afterSale from '../miniprogram/features/after-sale'
import * as orderCenter from '../miniprogram/features/order-center'
import * as productCatalog from '../miniprogram/features/product-catalog'
import * as model from '../miniprogram/pages/after-sale/apply/model'
import type { AfterSaleEligibilityResponse, AfterSaleQuoteRequest, AfterSaleQuoteResponse } from '../miniprogram/types/after-sale'
function eligibility(): AfterSaleEligibilityResponse {
  return { orderId: 101, orderNo: 'ORD101', orderStatus: 'PAID', paidAmountCent: 1300, refundedAmountCent: 0,
    remainingRefundableAmountCent: 1300, availableTypes: ['REFUND_ONLY', 'RETURN_REFUND'], items: [
      { orderItemId: 11, skuId: 21, productTitle: '商品一', purchasedQuantity: 3, refundedQuantity: 0, availableQuantity: 3, returnableQuantity: 0, paidAmountBasisCent: 1000 },
      { orderItemId: 12, skuId: 22, productTitle: '商品二', purchasedQuantity: 1, refundedQuantity: 0, availableQuantity: 1, returnableQuantity: 0, paidAmountBasisCent: 300 }
    ] }
}
const flush = () => new Promise<void>((resolve) => setImmediate(resolve))
const event = (index: number, value?: string) => ({ currentTarget: { dataset: { index } }, detail: { value } })
const plain = (value: unknown) => JSON.parse(JSON.stringify(value))
function runtime(options: { eligibility?: AfterSaleEligibilityResponse; quote?: (request: AfterSaleQuoteRequest) => Promise<AfterSaleQuoteResponse> } = {}) {
  let instance: any, media: any[] = [], modalCount = 0, actionSheet: any
  const quotes: AfterSaleQuoteRequest[] = [], submissions: any[] = [], uploads: any[] = [], toasts: string[] = [], redirects: string[] = []
  const code = ts.transpileModule(readFileSync(resolve(process.cwd(), 'miniprogram/pages/after-sale/apply/apply.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  runInNewContext(code, { exports: {}, require(path: string) {
    if (path.endsWith('features/after-sale')) return afterSale
    if (path.endsWith('features/order-center')) return orderCenter
    if (path.endsWith('features/product-catalog')) return productCatalog
    if (path === './model') return model
    if (path.endsWith('utils/api-error')) return { isApiError: () => false }
    if (path.endsWith('services/after-sale')) return {
      getAfterSaleEligibility: async () => options.eligibility || eligibility(),
      quoteAfterSale: async (_order: number, request: AfterSaleQuoteRequest) => {
        quotes.push(plain(request)); return options.quote ? options.quote(request) : { orderId: 101, afterSaleType: request.afterSaleType,
          items: plain(request.items), quoteDigest: `digest${quotes.length}`, requestedAmountCent: request.items.reduce((sum, item) => sum + (item.requestedAmountCent || 0), 0) }
      },
      applyAfterSale: async (...args: any[]) => { submissions.push(plain(args)); return { id: 51 } },
      uploadAfterSaleEvidence: async (...args: any[]) => { uploads.push(args); return { id: uploads.length, originalFilename: '凭证' } }
    }; throw new Error(`Unexpected import ${path}`)
  }, Page(definition: any) {
    instance = definition; instance.setData = (updates: any) => {
      for (const [path, value] of Object.entries(updates)) {
        const parts = path.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = instance.data
        for (const part of parts.slice(0, -1)) target = target[part]
        target[parts[parts.length - 1]] = value
      }
    }
  }, wx: {
    showToast: ({ title }: { title: string }) => toasts.push(title),
    showModal: ({ success }: any) => { modalCount++; success({ confirm: true }) },
    showActionSheet: (value: any) => { actionSheet = value },
    chooseMedia: ({ success }: any) => success({ type: 'mix', tempFiles: media }), previewMedia: () => undefined,
    redirectTo: ({ url }: { url: string }) => redirects.push(url)
  } })
  instance.data.orderId = 101; instance.data.requestKey = 'stable-101'
  return { instance, quotes, submissions, uploads, toasts, redirects, modalCount: () => modalCount,
    chooseReason: () => { instance.onChooseReason(); actionSheet.success({ tapIndex: 0 }) }, setMedia: (next: any[]) => { media = next } }
}
test('售后默认值尊重实际发货数量和已开放类型', () => {
  const data = eligibility(); assert.equal(model.defaultAfterSaleType(data), 'REFUND_ONLY')
  data.items[0]!.returnableQuantity = 1; assert.equal(model.defaultAfterSaleType(data), 'REFUND_ONLY')
  data.items.forEach((item) => { item.returnableQuantity = item.availableQuantity })
  assert.equal(model.defaultAfterSaleType(data), 'RETURN_REFUND')
  data.availableTypes = ['REFUND_ONLY']; assert.equal(model.defaultAfterSaleType(data), 'REFUND_ONLY')
})
test('金额按分严格校验，媒体大小检查精确边界', () => {
  for (const value of ['', '0', '-1', '1.234', '1..2', '1a', '1e2']) assert.equal(model.parseRefundAmount(value), null)
  assert.equal(model.parseRefundAmount('0.01'), 1); assert.equal(model.parseRefundAmount('12.30'), 1230)
  assert.equal(model.evidenceValidationError('image', model.MAX_IMAGE_SIZE), '')
  assert.match(model.evidenceValidationError('image', model.MAX_IMAGE_SIZE + 1), /5MB/)
  assert.equal(model.evidenceValidationError('video', model.MAX_VIDEO_SIZE), '')
  assert.match(model.evidenceValidationError('video', model.MAX_VIDEO_SIZE + 1), /50MB/)
  assert.match(model.evidenceValidationError('image', 0), /失效/)
})
test('批量数量和下调金额参与一次报价提交，原因必须手动选择', async () => {
  const r = runtime(); await r.instance.loadEligibility()
  assert.equal(r.instance.data.isBatch, true); assert.equal(r.instance.data.selectedQuantity, 4)
  await r.instance.onSubmitTap(); assert.equal(r.modalCount(), 0); assert.match(r.toasts[0]!, /原因/)
  r.chooseReason(); r.instance.onQuantityMinus(event(0)); await flush()
  assert.equal(r.instance.data.items[0].maxAmountCent, 666)
  r.instance.onItemAmountInput(event(0, '5.00')); assert.equal(r.instance.data.quote, null)
  await r.instance.refreshQuote(); await r.instance.onSubmitTap()
  assert.deepEqual(r.submissions[0][1].items, [ { orderItemId: 11, quantity: 2, requestedAmountCent: 500 }, { orderItemId: 12, quantity: 1, requestedAmountCent: 300 } ])
  assert.equal(r.submissions[0][1].requestedAmountCent, 800); assert.equal(r.submissions[0][1].requestKey, 'stable-101')
  assert.match(r.redirects[0]!, /result.*51/)
})
test('切换退货退款排除未发货商品，全选与单独选择同步报价', async () => {
  const data = eligibility(); data.items[0]!.returnableQuantity = 1
  const r = runtime({ eligibility: data }); await r.instance.loadEligibility()
  r.instance.selectType('RETURN_REFUND'); await flush()
  assert.equal(r.instance.data.items[0].quantity, 1); assert.equal(r.instance.data.items[0].maxAmountCent, 333)
  assert.equal(r.instance.data.items[1].selected, false); assert.equal(r.instance.data.quote.items.length, 1)
  r.instance.selectType('REFUND_ONLY'); await flush(); assert.equal(r.instance.data.items[1].selected, true)
  r.instance.onToggleAll(); await flush(); assert.equal(r.instance.data.quote, null); assert.equal(r.instance.data.selectedCount, 0)
  r.instance.onItemToggle(event(1)); await flush(); assert.equal(r.instance.data.quote.items[0].orderItemId, 12)
})
test('旧报价不能覆盖正在编辑的金额，超限保留输入且阻止报价', async () => {
  let finish!: (quote: AfterSaleQuoteResponse) => void
  const r = runtime({ quote: () => new Promise((resolve) => { finish = resolve }) })
  const loading = r.instance.loadEligibility(); await flush(); r.instance.onItemAmountInput(event(0, '99'))
  finish({ orderId: 101, afterSaleType: 'REFUND_ONLY', items: [], requestedAmountCent: 1300, quoteDigest: 'old' }); await loading
  assert.equal(r.instance.data.quote, null); await r.instance.refreshQuote()
  assert.equal(r.instance.data.items[0].amountText, '99'); assert.match(r.instance.data.items[0].amountError, /最多可输入/)
  assert.equal(r.quotes.length, 1); assert.equal(r.instance.data.quote, null)
})
test('凭证混选过滤超限文件，防重复上传，四个媒体均带入提交', async () => {
  const r = runtime(); await r.instance.loadEligibility()
  r.setMedia([{ tempFilePath: '/1.png', size: 1024, fileType: 'image' },
    { tempFilePath: '/2.mp4', size: 4096, fileType: 'video', thumbTempFilePath: '/2.jpg' },
    { tempFilePath: '/big.mp4', size: model.MAX_VIDEO_SIZE + 1, fileType: 'video' }])
  const uploading = r.instance.onChooseEvidenceTap(); await r.instance.onChooseEvidenceTap(); await uploading
  assert.equal(r.uploads.length, 2); assert.equal(r.uploads[1][2], 'video'); assert.match(r.toasts[0]!, /50MB/)
  r.setMedia([1, 2, 3].map((i) => ({ tempFilePath: `/more${i}.png`, size: 100, fileType: 'image' })))
  await r.instance.onChooseEvidenceTap(); await r.instance.onChooseEvidenceTap()
  assert.equal(r.instance.data.evidenceFiles.length, 4); assert.equal(r.uploads.length, 4)
  r.chooseReason(); await r.instance.onSubmitTap(); assert.deepEqual(r.submissions[0][1].evidenceFileIds, [1, 2, 3, 4])
})
test('单商品不可取消选择，报价失败有可重试状态', async () => {
  const data = eligibility(); data.items = data.items.slice(0, 1)
  const r = runtime({ eligibility: data, quote: async () => { throw new Error('报价不可用') } }); await r.instance.loadEligibility()
  assert.equal(r.instance.data.isBatch, false); assert.equal(r.instance.data.quoting, false); assert.ok(r.instance.data.quoteError)
  r.instance.onItemToggle(event(0)); assert.equal(r.instance.data.items[0].selected, true); assert.equal(r.instance.data.quote, null)
})

test('用户已排除的未发货商品，往返切换售后类型仍保持排除', async () => {
  const data = eligibility(); data.items[0]!.returnableQuantity = 1
  const r = runtime({ eligibility: data }); await r.instance.loadEligibility()
  r.instance.onItemToggle(event(1)); await flush()
  r.instance.selectType('RETURN_REFUND'); await flush()
  r.instance.selectType('REFUND_ONLY'); await flush()
  assert.equal(r.instance.data.items[1].selected, false)
  assert.equal(r.instance.data.quote.items.length, 1)
  assert.equal(r.instance.data.quote.items[0].orderItemId, 11)
})

test('第5个凭证会明确拒绝，不会静默截断用户选择', () => {
  const items = [{ orderItemId: 11, quantity: 1, requestedAmountCent: 100 }]
  assert.throws(() => afterSale.buildAfterSaleApplyPayload({ requestKey: 'stable-101', items, reason: '其他原因',
    quote: { orderId: 101, afterSaleType: 'REFUND_ONLY', items, requestedAmountCent: 100, quoteDigest: 'quote' },
    evidenceFileIds: [1, 2, 3, 4, 5] }), /最多 4 个/)
})
