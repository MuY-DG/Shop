import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import test from 'node:test'
import {
  CUSTOMER_SERVICE_NOTIFICATION_PREVIEW_KEY,
  customerServiceNotificationBody,
  customerServiceNotificationTitle,
  isCustomerServiceNotificationPreviewEnabled,
  isCustomerServicePageForeground,
  setCustomerServiceNotificationPreviewEnabled
} from './customer-service-notification-state'

const createStorage = () => {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value)
  }
}

test('客服通知按页面焦点区分站内卡片与系统通知', () => {
  assert.equal(
    isCustomerServicePageForeground({ visibilityState: 'visible', hasFocus: () => true }),
    true
  )
  assert.equal(
    isCustomerServicePageForeground({ visibilityState: 'visible', hasFocus: () => false }),
    false
  )
  assert.equal(
    isCustomerServicePageForeground({ visibilityState: 'hidden', hasFocus: () => true }),
    false
  )
  assert.equal(isCustomerServicePageForeground(null), false)
})

test('客服通知文案精简并支持隐藏消息预览', () => {
  assert.equal(customerServiceNotificationTitle(' 用户 959554 '), '客服新消息 · 用户 959554')
  assert.equal(customerServiceNotificationTitle(null), '客服新消息')
  assert.equal(customerServiceNotificationBody('TEXT', '  你好\n  请问在吗  '), '你好 请问在吗')
  assert.equal(customerServiceNotificationBody('IMAGE', ''), '[图片]')
  assert.equal(
    customerServiceNotificationBody('TEXT', '不应显示的顾客消息', false),
    '收到一条新消息，点击查看'
  )
})

test('消息预览偏好默认开启并可在当前浏览器关闭', () => {
  const storage = createStorage()
  assert.equal(isCustomerServiceNotificationPreviewEnabled(storage), true)

  setCustomerServiceNotificationPreviewEnabled(false, storage)
  assert.equal(storage.getItem(CUSTOMER_SERVICE_NOTIFICATION_PREVIEW_KEY), 'hidden')
  assert.equal(isCustomerServiceNotificationPreviewEnabled(storage), false)

  setCustomerServiceNotificationPreviewEnabled(true, storage)
  assert.equal(storage.getItem(CUSTOMER_SERVICE_NOTIFICATION_PREVIEW_KEY), 'visible')
  assert.equal(isCustomerServiceNotificationPreviewEnabled(storage), true)
})

test('客服通知链路接入品牌图标、站内卡片和 macOS PWA 清单', () => {
  const adminRoot = resolve(process.cwd())
  const notifier = readFileSync(
    resolve(adminRoot, 'src/utils/customer-service-notification.ts'),
    'utf8'
  )
  const conversations = readFileSync(
    resolve(adminRoot, 'src/views/customer-service/conversations/index.vue'),
    'utf8'
  )
  const noticeComponent = readFileSync(
    resolve(
      adminRoot,
      'src/views/customer-service/conversations/CustomerServiceNotificationStack.vue'
    ),
    'utf8'
  )
  const settings = readFileSync(
    resolve(adminRoot, 'src/views/customer-service/settings/index.vue'),
    'utf8'
  )
  const manifest = JSON.parse(
    readFileSync(resolve(adminRoot, 'public/manifest.webmanifest'), 'utf8')
  ) as {
    name: string
    display: string
    start_url: string
    icons: Array<{ src: string; sizes: string }>
  }

  assert.ok(
    notifier.indexOf('isCustomerServicePageForeground()') < notifier.indexOf('new Notification')
  )
  assert.match(notifier, /showInAppNotification\(/)
  assert.match(notifier, /pwa\/icon-192\.png/)
  assert.match(conversations, /CustomerServiceNotificationStack/)
  assert.match(conversations, /senderAvatar: conversation\?\.userAvatar/)
  assert.match(noticeComponent, /aria-live="polite"/)
  assert.match(noticeComponent, /查看会话/)
  assert.match(settings, /显示消息预览/)
  assert.match(settings, /setCustomerServiceNotificationPreviewEnabled/)

  assert.equal(manifest.name, '俊祥食品客服工作台')
  assert.equal(manifest.display, 'standalone')
  assert.equal(manifest.start_url, '/customer-service')
  assert.deepEqual(
    manifest.icons.map((icon) => icon.sizes),
    ['192x192', '512x512']
  )
  manifest.icons.forEach((icon) => {
    assert.ok(existsSync(resolve(adminRoot, 'public', icon.src.replace(/^\//, ''))))
  })
})
