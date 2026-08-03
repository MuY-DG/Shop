import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const conversationSource = readFileSync(
  new URL('./conversations/index.vue', import.meta.url),
  'utf8'
)

test('ending a conversation returns the workspace to its empty initial state', () => {
  const closeHandler = conversationSource.match(
    /const handleClose = async \(\) => \{[\s\S]*?\n  \}\n\n  const openOrderDialog/
  )?.[0]

  assert.ok(closeHandler)
  assert.match(closeHandler, /await closeCustomerServiceConversation\(closedConversationId\)/)
  assert.match(closeHandler, /detailRequestSequence \+= 1/)
  assert.match(closeHandler, /detailCache\.delete\(closedConversationId\)/)
  assert.match(closeHandler, /detachCurrentImageUrls\(\)/)
  assert.match(closeHandler, /selectedConversationId\.value = null/)
  assert.match(closeHandler, /currentDetail\.value = null/)
  assert.match(closeHandler, /messageDraft\.value = ''/)
  assert.doesNotMatch(
    closeHandler,
    /currentDetail\.value = await closeCustomerServiceConversation/
  )
})
