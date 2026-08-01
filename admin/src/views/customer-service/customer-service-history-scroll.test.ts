import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { preserveCustomerServicePrependScrollTop } from '../../utils/customer-service-scroll'

const source = readFileSync(new URL('./conversations/index.vue', import.meta.url), 'utf8')

test('客服后台滚动到顶部后自动加载更早消息', () => {
  assert.match(source, /@scroll\.passive="handleMessageListScroll"/)
  assert.match(source, /const HISTORY_AUTO_LOAD_THRESHOLD_PX = 48/)
  assert.match(source, /const HISTORY_AUTO_LOAD_REARM_PX = 180/)
  assert.match(
    source,
    /handleMessageListScroll[\s\S]*scrollTop >= HISTORY_AUTO_LOAD_REARM_PX[\s\S]*historyAutoLoadArmed = true[\s\S]*historyAutoLoadArmed = false[\s\S]*loadEarlierMessages\(\)/
  )
  assert.doesNotMatch(source, />\s*加载更早的消息\s*</)
})

test('客服后台 prepend 历史消息时使用一次性 DOM 锚点保持视口', () => {
  assert.match(source, /:data-message-id="message\.messageId"/)
  assert.match(
    source,
    /await fetchCustomerServiceMessages[\s\S]*previousAnchorTop[\s\S]*nextAnchorTop[\s\S]*preserveCustomerServicePrependScrollTop/
  )
  assert.match(source, /overflow-anchor: none/)
  assert.equal(preserveCustomerServicePrependScrollTop(12, -44, 1196), 1252)
})

test('客服图片加载前后使用固定比例避免历史消息发生布局位移', () => {
  assert.match(source, /:style="messageImageStyle\(message\)"/)
  assert.match(source, /aspectRatio: width > 0 && height > 0/)
})
