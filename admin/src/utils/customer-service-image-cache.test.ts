import assert from 'node:assert/strict'
import test from 'node:test'
import {
  cacheCustomerServiceImageUrl,
  cacheCustomerServiceOriginalImageUrl,
  clearCustomerServiceImageCache,
  getCustomerServiceImageUrl,
  getCustomerServiceOriginalImageUrl
} from './customer-service-image-cache'

test('页面刷新后可从当前标签页恢复客服图片签名地址', () => {
  const values = new Map<string, string>()
  const fakeStorage: Storage = {
    get length() {
      return values.size
    },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value)
  }
  const previousDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
  const expiresAt = Date.now() + 60_000
  const signedUrl = 'https://cos.example.com/private/chat.webp?signature=before-refresh'
  fakeStorage.setItem(
    'customer-service-image-url-cache-v1',
    JSON.stringify({
      'display:100': {
        url: signedUrl,
        expiresAt,
        hardExpiresAt: expiresAt,
        lastAccessedAt: Date.now()
      }
    })
  )
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: fakeStorage
  })

  try {
    assert.equal(getCustomerServiceImageUrl(100), signedUrl)
  } finally {
    clearCustomerServiceImageCache()
    if (previousDescriptor) {
      Object.defineProperty(globalThis, 'sessionStorage', previousDescriptor)
    } else {
      Reflect.deleteProperty(globalThis, 'sessionStorage')
    }
  }
})

test('客服图片签名地址在有效期内按消息 ID 复用', () => {
  const messageId = 101
  const signedUrl = 'https://cos.example.com/private/chat.webp?signature=first'

  cacheCustomerServiceImageUrl(messageId, signedUrl, Date.now() + 60_000)

  assert.equal(getCustomerServiceImageUrl(messageId), signedUrl)
  assert.equal(getCustomerServiceImageUrl(messageId), signedUrl)
  clearCustomerServiceImageCache()
})

test('客服图片签名地址到期后不再复用', () => {
  const messageId = 102

  cacheCustomerServiceImageUrl(
    messageId,
    'https://cos.example.com/private/chat.webp?signature=expired',
    Date.now() - 1
  )

  assert.equal(getCustomerServiceImageUrl(messageId), undefined)
  clearCustomerServiceImageCache()
})

test('客服缩略图与原图使用相互独立的缓存项', () => {
  const messageId = 103
  const expiresAt = Date.now() + 60_000
  const thumbnailUrl = 'https://cos.example.com/private/chat-thumbnail.webp?signature=first'
  const originalUrl = 'https://cos.example.com/private/chat-original.webp?signature=first'

  cacheCustomerServiceImageUrl(messageId, thumbnailUrl, expiresAt)
  cacheCustomerServiceOriginalImageUrl(messageId, originalUrl, expiresAt)

  assert.equal(getCustomerServiceImageUrl(messageId), thumbnailUrl)
  assert.equal(getCustomerServiceOriginalImageUrl(messageId), originalUrl)
  clearCustomerServiceImageCache()
})
