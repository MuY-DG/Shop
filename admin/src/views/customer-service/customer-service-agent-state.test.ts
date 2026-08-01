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
