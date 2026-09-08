import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { ApiError, isApiError } from '../miniprogram/utils/api-error'

interface InitFile { originalFilename: string; contentType: string; sizeBytes: number }
interface UploadOptions { filePath: string; timeoutMs?: number }
type EvidenceUpload = (orderId: number, filePath: string, mediaType?: 'image' | 'video') => Promise<{ id: number }>

function compiled(path: string) {
  return ts.transpileModule(readFileSync(resolve(process.cwd(), path), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
}

function harness(options: {
  path: string
  type: string
  uploadError?: Error
  copyFails?: boolean
  initError?: ApiError
}) {
  const original = Buffer.from('unchanged evidence bytes')
  const files = new Map<string, Buffer>([[options.path, original]])
  const events: string[] = []
  const initializations: InitFile[] = []
  const uploaded: UploadOptions[] = []
  const deleted: string[] = []
  const directExports: Record<string, unknown> = {}
  const fs = {
    getFileInfo(input: { filePath: string; success: (value: { size: number }) => void }) {
      input.success({ size: files.get(input.filePath)!.length })
    },
    copyFile(input: { srcPath: string; destPath: string; success: () => void; fail: (error: { errMsg: string }) => void }) {
      events.push('copy')
      if (options.copyFails) { input.fail({ errMsg: 'copyFile:fail disk full' }); return }
      files.set(input.destPath, Buffer.from(files.get(input.srcPath)!))
      input.success()
    },
    unlink(input: { filePath: string; success: () => void }) {
      events.push('unlink')
      deleted.push(input.filePath)
      files.delete(input.filePath)
      input.success()
    }
  }
  runInNewContext(compiled('miniprogram/utils/direct-upload.ts'), {
    exports: directExports,
    wx: {
      env: { USER_DATA_PATH: 'wxfile://usr' },
      getFileSystemManager: () => fs,
      getVideoInfo: (input: { success: (value: { type: string }) => void }) => input.success({ type: options.type }),
      getImageInfo: (input: { success: (value: { type: string }) => void }) => input.success({ type: options.type })
    },
    require: (path: string) => {
      if (path === './api-error') return { ApiError, isApiError }
      if (path === './request') return { request: async (request: { data: InitFile }) => {
        initializations.push(request.data)
        throw options.initError || new ApiError({ kind: 'API', code: 800009, message: 'direct upload unavailable' })
      } }
      throw new Error(`Unexpected import: ${path}`)
    }
  })
  const serviceExports: { uploadAfterSaleEvidence?: EvidenceUpload } = {}
  runInNewContext(compiled('miniprogram/services/after-sale.ts'), {
    exports: serviceExports,
    require: (path: string) => {
      if (path.endsWith('constants/api-endpoints')) return { API_ENDPOINTS: { afterSales: {
        evidenceUploads: () => '/app/orders/42/after-sale-evidence/upload-sessions',
        evidence: () => '/app/orders/42/after-sale-evidence'
      } } }
      if (path.endsWith('utils/direct-upload')) return directExports
      if (path.endsWith('utils/upload')) return { uploadFile: async (upload: UploadOptions) => {
        events.push('upload')
        uploaded.push(upload)
        // 模拟 multipart 服务端按上传路径扩展名选择格式，且读取的确为原始字节。
        assert.match(upload.filePath, /\.(mp4|webm|png)$/)
        assert.deepEqual(files.get(upload.filePath), original)
        if (options.uploadError) throw options.uploadError
        return { id: 11 }
      } }
      if (path.endsWith('utils/request')) return {}
      throw new Error(`Unexpected import: ${path}`)
    }
  })
  return { upload: serviceExports.uploadAfterSaleEvidence!, files, events, initializations, uploaded, deleted, original }
}

test('无后缀视频在直传不可用时复制为MP4完成兼容上传，并仅清理副本', async () => {
  const source = 'wxfile://tmp/video_without_extension'
  const context = harness({ path: source, type: 'mp4' })
  assert.deepEqual(await context.upload(42, source, 'video'), { id: 11 })
  assert.equal(context.initializations[0]?.originalFilename, 'video_without_extension.mp4')
  assert.equal(context.initializations[0]?.contentType, 'video/mp4')
  assert.match(context.uploaded[0]!.filePath, /^wxfile:\/\/usr\/upload-fallback-.+\.mp4$/)
  assert.equal(context.uploaded[0]?.timeoutMs, 120_000)
  assert.deepEqual(context.events, ['copy', 'upload', 'unlink'])
  assert.deepEqual([...context.files.keys()], [source])
  assert.deepEqual(context.files.get(source), context.original)
  assert.deepEqual(context.deleted, [context.uploaded[0]!.filePath])
})

test('探测修正的图片和WebM后缀均传入兼容上传路径', async () => {
  for (const media of [{ type: 'png', kind: 'image' as const }, { type: 'webm', kind: 'video' as const }]) {
    const source = `wxfile://tmp/proof-${media.type}.tmp`
    const context = harness({ path: source, type: media.type })
    await context.upload(42, source, media.kind)
    assert.equal(context.initializations[0]?.originalFilename, `proof-${media.type}.${media.type}`)
    assert.ok(context.uploaded[0]!.filePath.endsWith(`.${media.type}`))
    assert.deepEqual(context.events, ['copy', 'upload', 'unlink'])
    assert.deepEqual([...context.files.keys()], [source])
  }
})

test('兼容上传失败也删除临时副本并保留原始文件和错误', async () => {
  const source = 'wxfile://tmp/video'
  const error = new Error('network unavailable')
  const context = harness({ path: source, type: 'mp4', uploadError: error })
  await assert.rejects(context.upload(42, source, 'video'), (caught) => caught === error)
  assert.deepEqual(context.events, ['copy', 'upload', 'unlink'])
  assert.deepEqual([...context.files.keys()], [source])
  assert.ok(context.deleted.every((path) => path !== source))
})

test('正常视频文件名直接兼容上传，不复制或删除用户文件', async () => {
  const source = 'wxfile://tmp/video.mp4'
  const context = harness({ path: source, type: 'mp4' })
  await context.upload(42, source, 'video')
  assert.equal(context.uploaded[0]?.filePath, source)
  assert.deepEqual(context.events, ['upload'])
  assert.deepEqual(context.deleted, [])
  assert.deepEqual([...context.files.keys()], [source])
})

test('无法创建兼容副本时不上传、不删除原文件', async () => {
  const source = 'wxfile://tmp/video'
  const context = harness({ path: source, type: 'mp4', copyFails: true })
  await assert.rejects(context.upload(42, source, 'video'), /无法准备兼容上传文件/)
  assert.equal(context.uploaded.length, 0)
  assert.deepEqual([...context.files.keys()], [source])
  assert.ok(context.deleted.every((path) => path !== source))
})

test('服务端格式或大小拒绝不触发兼容副本和重复上传', async () => {
  const source = 'wxfile://tmp/video'
  const error = new ApiError({ kind: 'API', code: 800002, message: 'too large' })
  const context = harness({ path: source, type: 'mp4', initError: error })
  await assert.rejects(context.upload(42, source, 'video'), (caught) => caught === error)
  assert.deepEqual(context.events, [])
  assert.deepEqual([...context.files.keys()], [source])
})
