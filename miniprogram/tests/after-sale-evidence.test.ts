import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as afterSale from '../miniprogram/features/after-sale'
import type { AfterSaleEvidenceFile } from '../miniprogram/types/after-sale'

function evidence(mediaKind: 'IMAGE' | 'VIDEO'): AfterSaleEvidenceFile {
  return {
    fileId: 82, mediaKind, originalFilename: mediaKind === 'VIDEO' ? 'video.mp4' : 'image.jpg',
    contentType: mediaKind === 'VIDEO' ? 'video/mp4' : 'image/jpeg', sizeBytes: 100,
    scope: 'ATTACHMENT', visibility: 'PRIVATE', status: 'ACTIVE'
  }
}

function code(path: string): string {
  return ts.transpileModule(readFileSync(resolve(process.cwd(), path), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
}

function service(external: (url: string, timeout?: number) => Promise<string>, authenticated: (url: string, timeout?: number) => Promise<string>) {
  const exports: { downloadAfterSaleEvidence?: (id: number, file: AfterSaleEvidenceFile) => Promise<string> } = {}
  runInNewContext(code('miniprogram/services/after-sale-evidence.ts'), {
    exports,
    require: (path: string) => {
      if (path.endsWith('constants/api-endpoints')) {
        return { API_ENDPOINTS: { afterSales: { detail: (id: number) => `/app/after-sales/${id}` } } }
      }
      if (path.endsWith('utils/authenticated-download')) {
        return { downloadExternalFile: external, downloadAuthenticatedFile: authenticated }
      }
      throw new Error(`Unexpected import: ${path}`)
    }
  })
  return exports.downloadAfterSaleEvidence!
}

test('私有图片和视频优先使用无登录凭证的签名下载，过期后回退所属售后鉴权流', async () => {
  const calls: string[] = []
  const timeouts: number[] = []
  let expired = false
  const download = service(async (url, timeout) => {
    timeouts.push(timeout || 0)
    calls.push(`external:${url}`)
    if (expired) throw new Error('expired')
    return 'wxfile://signed'
  }, async (url, timeout) => { timeouts.push(timeout || 0); calls.push(`api:${url}`); return 'wxfile://protected' })
  const file: AfterSaleEvidenceFile = { ...evidence('VIDEO'), accessMode: 'SIGNED_URL', accessUrl: 'https://cos.example/proof' }
  assert.equal(await download(71, file), 'wxfile://signed')
  assert.deepEqual(calls, ['external:https://cos.example/proof'])
  expired = true
  assert.equal(await download(71, file), 'wxfile://protected')
  assert.equal(calls[calls.length - 1], 'api:/app/after-sales/71/evidence/82')
  calls.length = 0
  assert.equal(await download(72, evidence('IMAGE')), 'wxfile://protected')
  assert.deepEqual(calls, ['api:/app/after-sales/72/evidence/82'])
  assert.deepEqual(timeouts, [120_000, 120_000, 120_000, 30_000])
})

test('无效或已失效凭证不发起下载', async () => {
  let calls = 0
  const download = service(async () => { calls++; return '' }, async () => { calls++; return '' })
  await assert.rejects(download(0, evidence('VIDEO')), /暂不可查看/)
  await assert.rejects(download(71, { ...evidence('IMAGE'), fileId: -1 }), /暂不可查看/)
  await assert.rejects(download(71, { ...evidence('VIDEO'), status: 'DELETED' }), /暂不可查看/)
  assert.equal(calls, 0)
})

test('较大凭证下载延长超时且401重试仍仅向API发送登录令牌', async () => {
  const requests: WechatMiniprogram.DownloadFileOption[] = []
  const exports: {
    downloadAuthenticatedFile?: (url: string, timeout?: number) => Promise<string>
    downloadExternalFile?: (url: string, timeout?: number) => Promise<string>
  } = {}
  let token = 'first-token'
  runInNewContext(code('miniprogram/utils/authenticated-download.ts'), {
    exports,
    require: (path: string) => {
      if (path.endsWith('config/app-config')) return {
        APP_CONFIG: { apiBaseUrl: 'https://api.example', requestTimeoutMs: 12_000 }
      }
      if (path.endsWith('services/session')) return {
        ensureSession: async () => {},
        getSessionState: () => ({ accessToken: token }),
        recoverAfterUnauthorized: async () => { token = 'renewed-token' },
        clearSessionIfCurrent: () => {}
      }
      if (path.endsWith('api-error')) return { ApiError: Error }
      throw new Error(`Unexpected import: ${path}`)
    },
    wx: {
      downloadFile: (options: WechatMiniprogram.DownloadFileOption) => {
        requests.push(options)
        options.success?.({
          statusCode: requests.length === 1 ? 401 : 200,
          tempFilePath: 'wxfile://proof',
          errMsg: ''
        } as WechatMiniprogram.DownloadFileSuccessCallbackResult)
      }
    }
  })
  assert.equal(await exports.downloadAuthenticatedFile!('/app/after-sales/71/evidence/82', 120_000), 'wxfile://proof')
  assert.equal(requests[0]?.header?.Authorization, 'Bearer first-token')
  assert.equal(requests[1]?.header?.Authorization, 'Bearer renewed-token')
  assert.equal(requests[0]?.timeout, 120_000)
  assert.equal(requests[1]?.timeout, 120_000)
  await exports.downloadExternalFile!('https://cos.example/proof', 120_000)
  assert.equal(requests[2]?.header, undefined)
  assert.equal(requests[2]?.timeout, 120_000)
  await exports.downloadExternalFile!('https://cos.example/image')
  assert.equal(requests[3]?.timeout, 12_000)
})

function detailPage(download: (id: number, file: AfterSaleEvidenceFile) => Promise<string>) {
  let instance: any
  const previews: WechatMiniprogram.PreviewMediaOption[] = []
  const toasts: string[] = []
  runInNewContext(code('miniprogram/pages/after-sale/detail/detail.ts'), {
    exports: {},
    require: (path: string) => {
      if (path.endsWith('features/after-sale')) return afterSale
      if (path.endsWith('services/after-sale-evidence')) return { downloadAfterSaleEvidence: download }
      if (path.endsWith('utils/api-error')) return { isApiError: () => false }
      return {}
    },
    Page: (definition: any) => {
      instance = definition
      instance.setData = (next: any) => Object.assign(instance.data, next)
    },
    wx: {
      previewMedia: (options: WechatMiniprogram.PreviewMediaOption) => previews.push(options),
      showToast: ({ title }: { title: string }) => toasts.push(title)
    },
    clearTimeout: () => {}
  })
  instance._visible = true
  instance.data.detail = { id: 71, evidenceFiles: [evidence('VIDEO')] }
  return { instance, previews, toasts }
}

const tap = { currentTarget: { dataset: { fileId: 82 } } }

test('详情将已下载视频交给原生媒体播放器，图片使用图片类型', async () => {
  const runtime = detailPage(async () => 'wxfile://private-proof')
  await runtime.instance.onEvidenceTap(tap)
  assert.equal(runtime.previews[0]?.sources[0]?.type, 'video')
  assert.equal(runtime.previews[0]?.sources[0]?.url, 'wxfile://private-proof')
  assert.equal(runtime.previews[0]?.showmenu, false)
  runtime.instance.data.detail.evidenceFiles = [evidence('IMAGE')]
  await runtime.instance.onEvidenceTap(tap)
  assert.equal(runtime.previews[1]?.sources[0]?.type, 'image')
  assert.equal(runtime.instance.data.evidencePreviewFileId, 0)
})

test('凭证下载防止重复点击，页面隐藏后不弹出晚到的预览', async () => {
  let finish!: (value: string) => void
  let downloads = 0
  const runtime = detailPage(() => {
    downloads++
    return new Promise((resolve) => { finish = resolve })
  })
  const pending = runtime.instance.onEvidenceTap(tap)
  await runtime.instance.onEvidenceTap(tap)
  assert.equal(downloads, 1)
  runtime.instance.onHide()
  finish('wxfile://late-proof')
  await pending
  assert.equal(runtime.previews.length, 0)
  assert.equal(runtime.instance.data.evidencePreviewFileId, 0)
})

test('凭证下载失败后给出可重试提示且恢复点击状态', async () => {
  const runtime = detailPage(async () => { throw new Error('network') })
  await runtime.instance.onEvidenceTap(tap)
  assert.deepEqual(runtime.toasts, ['凭证加载失败，请稍后重试'])
  assert.equal(runtime.instance.data.evidencePreviewFileId, 0)
  assert.equal(runtime.previews.length, 0)
})
