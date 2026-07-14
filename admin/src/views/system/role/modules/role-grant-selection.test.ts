import assert from 'node:assert/strict'
import test from 'node:test'
import { buildRoleGrantForm } from './role-grant-selection'

test('selected page includes its parent directories without granting operations', () => {
  assert.deepEqual(
    buildRoleGrantForm([
      {
        id: 201,
        kind: 'menu',
        ancestorMenuIds: [200]
      }
    ]),
    {
      menuIds: [200, 201],
      permissionIds: []
    }
  )
})

test('selected operation includes its owning page and parent directories', () => {
  assert.deepEqual(
    buildRoleGrantForm([
      {
        id: 1000,
        kind: 'permission',
        ancestorMenuIds: [200, 201]
      }
    ]),
    {
      menuIds: [200, 201],
      permissionIds: [1000]
    }
  )
})
