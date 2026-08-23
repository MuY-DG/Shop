import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const apiSource = readFileSync(new URL('../../api/compliance.ts', import.meta.url), 'utf8')
const merchantSource = readFileSync(new URL('./merchant/index.vue', import.meta.url), 'utf8')
const documentSource = readFileSync(new URL('./documents/index.vue', import.meta.url), 'utf8')
const cancellationSource = readFileSync(
  new URL('./cancellations/index.vue', import.meta.url),
  'utf8'
)

test('compliance admin uses immutable draft and publish endpoints', () => {
  assert.match(apiSource, /\/admin\/compliance\/merchant\/drafts/)
  assert.match(apiSource, /\/admin\/compliance\/merchant\/\$\{id\}\/publish/)
  assert.match(apiSource, /\/admin\/compliance\/documents\/\$\{type\}\/drafts/)
  assert.match(apiSource, /\/admin\/compliance\/documents\/\$\{id\}\/publish/)
  assert.match(apiSource, /\/admin\/compliance\/account-cancellations/)
  assert.doesNotMatch(apiSource, /request\.put/)
})

test('account cancellation records are read-only and expose processing facts', () => {
  assert.match(cancellationSource, /不提供审批、恢复或删除操作/)
  assert.match(cancellationSource, /立即清理/)
  assert.match(cancellationSource, /按规则保留/)
  assert.match(cancellationSource, /noticeContentSha256/)
  assert.doesNotMatch(cancellationSource, /request\.(post|put|patch|delete)/)
})

test('merchant publication allows empty fields, confirms before publishing real assets', () => {
  assert.match(merchantSource, /各项资质字段均可留空保存并发布/)
  assert.match(merchantSource, /AssetPicker[\s\S]*?businessLicenseAsset/)
  assert.match(merchantSource, /AssetPicker[\s\S]*?foodQualificationAsset/)
  assert.match(merchantSource, /发布商家资质/)
  assert.match(merchantSource, /compliance:merchant:write/)
})

test('legal-document publication warns about privacy and cancellation version invalidation', () => {
  assert.match(documentSource, /V105 已创建首版账号注销须知/)
  assert.match(documentSource, /真实删除和保留规则/)
  assert.match(documentSource, /小程序登录只接受此版本/)
  assert.match(documentSource, /重新阅读并勾选新版本/)
  assert.match(documentSource, /SHA-256/)
  assert.match(documentSource, /compliance:document:write/)
})
