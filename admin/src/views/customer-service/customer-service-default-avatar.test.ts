import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test } from 'node:test'

const sourceRoot = resolve(process.cwd(), 'src')

test('客服未上传头像时统一展示机器人默认头像并支持恢复默认', () => {
  const settings = readFileSync(
    resolve(sourceRoot, 'views/customer-service/settings/index.vue'),
    'utf8'
  )
  const members = readFileSync(
    resolve(sourceRoot, 'views/customer-service-management/members/index.vue'),
    'utf8'
  )
  const assetPicker = readFileSync(
    resolve(sourceRoot, 'components/business/asset-picker/index.vue'),
    'utf8'
  )
  const workspace = readFileSync(resolve(sourceRoot, 'views/customer-service/index.vue'), 'utf8')
  const defaultAvatarPath = resolve(sourceRoot, 'assets/images/customer-service/default-avatar.jpg')

  assert.match(settings, /defaultCustomerServiceAvatar/)
  assert.match(settings, /:fallback-url="defaultCustomerServiceAvatar"/)
  assert.match(settings, /恢复默认头像/)
  assert.match(settings, /avatarAsset\.value = \{ fileId: null, url: '' \}/)
  assert.match(settings, /未上传时使用默认机器人头像；上传后，小程序与后台将展示自定义头像。/)
  assert.match(members, /row\.serviceAvatar \|\| defaultCustomerServiceAvatar/)
  assert.match(assetPicker, /fallbackUrl\?: string/)
  assert.match(assetPicker, /hasExplicitValue/)
  assert.match(workspace, /agentProfile\.value\?\.avatar \|\| defaultCustomerServiceAvatar/)
  assert.doesNotMatch(workspace, /userStore\.info\.avatar/)
  assert.doesNotMatch(workspace, /profile-menu__avatar/)
  assert.ok(existsSync(defaultAvatarPath))
  assert.deepEqual(readFileSync(defaultAvatarPath).subarray(0, 2), Buffer.from([0xff, 0xd8]))
})
