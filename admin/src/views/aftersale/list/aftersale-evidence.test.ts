import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { evidenceImageUrls, isPreviewableImage, isPreviewableVideo } from './aftersale-evidence'

function evidence(fileId: number, mediaKind: 'IMAGE' | 'VIDEO'): Api.AfterSale.EvidenceFile {
  return {
    fileId,
    mediaKind,
    originalFilename: `${fileId}`,
    scope: 'ATTACHMENT',
    visibility: 'PRIVATE',
    contentType: mediaKind === 'VIDEO' ? 'video/mp4' : 'image/jpeg',
    sizeBytes: 100,
    status: 'ACTIVE'
  }
}

test('图片图库排除视频和失效凭证，顺序保持凭证顺序', () => {
  const image = evidence(81, 'IMAGE')
  const video = evidence(82, 'VIDEO')
  const inactive = { ...evidence(83, 'IMAGE'), status: 'DELETED' }
  assert.equal(isPreviewableImage(video), false)
  assert.equal(isPreviewableVideo(video), true)
  assert.equal(isPreviewableImage(inactive), false)
  assert.equal(isPreviewableVideo({ ...video, contentType: 'image/jpeg' }), false)
  assert.deepEqual(
    evidenceImageUrls([video, image, inactive], {
      81: 'blob:image',
      82: 'blob:video',
      83: 'blob:deleted'
    }),
    ['blob:image']
  )
})

test('售后视频使用原生播放器并沿用鉴权 blob 回退和 URL 清理', () => {
  const source = readFileSync(resolve(process.cwd(), 'src/views/aftersale/list/index.vue'), 'utf8')
  assert.match(
    source,
    /<video[\s\S]*?isPreviewableVideo\(file\)[\s\S]*?controls[\s\S]*?@error="handleEvidencePreviewError\(file\)"/
  )
  assert.match(source, /<ElImage\s+v-if="isPreviewableImage\(file\)/)
  assert.match(source, /isPreviewableImage\(file\) \|\| isPreviewableVideo\(file\)/)
  assert.match(source, /fetchAfterSaleEvidence\(detail\.id, file\.fileId\)/)
  assert.match(source, /URL\.revokeObjectURL/)
})
