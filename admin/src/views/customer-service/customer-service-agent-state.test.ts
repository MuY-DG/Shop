import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const readView = (fileName: string) =>
  readFileSync(new URL(`./${fileName}`, import.meta.url), 'utf8')

test('workspace and conversation list share the same agent state', () => {
  const workspaceSource = readView('index.vue')
  const conversationsSource = readView('conversations/index.vue')

  assert.match(
    workspaceSource,
    /<CustomerServiceConversations v-else v-model:agent-state="agentState" \/>/
  )
  assert.match(
    conversationsSource,
    /defineModel<Api\.CustomerService\.AgentState \| null>\('agentState'/
  )
  assert.doesNotMatch(
    conversationsSource,
    /const agentState = ref<Api\.CustomerService\.AgentState \| null>/
  )
})

test('customer service workspace explicitly owns realtime presence', () => {
  const workspaceSource = readView('index.vue')
  const realtimeSource = readFileSync(
    new URL('../../utils/realtime/index.ts', import.meta.url),
    'utf8'
  )

  assert.match(workspaceSource, /realtimeClient\.acquireCustomerServicePresence/)
  assert.match(workspaceSource, /realtimeClient\.subscribeConnectionState/)
  assert.match(workspaceSource, /CUSTOMER_SERVICE_PRESENCE_STARTED/)
  assert.match(realtimeSource, /CUSTOMER_SERVICE_PRESENCE_START/)
  assert.match(realtimeSource, /CUSTOMER_SERVICE_PRESENCE_STOP/)
  assert.match(realtimeSource, /this\.startHeartbeat\(socket\)/)
})

test('keep-alive deactivation releases presence and stops fallback polling', () => {
  const workspaceSource = readView('index.vue')
  const conversationsSource = readView('conversations/index.vue')

  assert.match(workspaceSource, /onDeactivated\(deactivateWorkspace\)/)
  assert.match(workspaceSource, /if \(stateTimer\) clearInterval\(stateTimer\)/)
  assert.match(workspaceSource, /unsubscribeRealtime\?\.\(\)/)
  assert.match(conversationsSource, /onDeactivated\(deactivateConversationPage\)/)
  assert.match(conversationsSource, /stopFallbackPolling\(\)/)
  assert.match(conversationsSource, /if \(pageActive && !document\.hidden\) void refreshAll\(\)/)
})
