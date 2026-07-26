import assert from 'node:assert/strict'
import test from 'node:test'
import {
  uniqueAssetUploadFiles,
  validateAssetUploadFile,
  validateLibraryAssetUploadFile
} from './asset-upload'

const file = (name: string, type: string, size: number, lastModified = 1) => ({
  name,
  type,
  size,
  lastModified
})

test('validateAssetUploadFile applies image and video-specific rules', () => {
  assert.deepEqual(validateAssetUploadFile(file('cover.webp', 'image/webp', 1024), 'IMAGE'), {
    valid: true
  })
  assert.equal(
    validateAssetUploadFile(file('cover.webp', 'image/webp', 5 * 1024 * 1024 + 1), 'IMAGE').message,
    '图片不能超过 5 MB'
  )
  assert.deepEqual(validateAssetUploadFile(file('demo.mp4', 'video/mp4', 1024), 'VIDEO'), {
    valid: true
  })
  assert.equal(
    validateAssetUploadFile(file('demo.mov', 'video/quicktime', 1024), 'VIDEO').message,
    '视频仅支持 MP4 或 WebM'
  )
})

test('validateLibraryAssetUploadFile accepts supported mixed media only', () => {
  assert.equal(validateLibraryAssetUploadFile(file('gallery.png', 'image/png', 1024)).valid, true)
  assert.equal(validateLibraryAssetUploadFile(file('intro.webm', 'video/webm', 1024)).valid, true)
  assert.equal(
    validateLibraryAssetUploadFile(file('notes.pdf', 'application/pdf', 1024)).valid,
    false
  )
})

test('uniqueAssetUploadFiles preserves order and removes identical selections', () => {
  const first = file('one.png', 'image/png', 100, 10)
  const duplicate = file('one.png', 'image/png', 100, 10)
  const second = file('two.png', 'image/png', 100, 10)

  assert.deepEqual(uniqueAssetUploadFiles([first, duplicate, second]), [first, second])
})
