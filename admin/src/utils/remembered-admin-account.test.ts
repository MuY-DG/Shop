import assert from 'node:assert/strict'
import test from 'node:test'
import {
  loadRememberedAdminAccount,
  rememberedAdminAccountStorageKey,
  saveRememberedAdminAccount,
  type AdminAccountStorage
} from './remembered-admin-account'

function createStorage(): AdminAccountStorage & { values: Map<string, string> } {
  const values = new Map<string, string>()
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key)
  }
}

test('remembered admin account stores only the trimmed username', () => {
  const storage = createStorage()

  saveRememberedAdminAccount('  merchant-admin  ', true, storage)

  assert.equal(loadRememberedAdminAccount(storage), 'merchant-admin')
  assert.equal(storage.values.size, 1)
  assert.equal(storage.values.get(rememberedAdminAccountStorageKey), 'merchant-admin')
  assert.equal(
    JSON.stringify([...storage.values]),
    JSON.stringify([[rememberedAdminAccountStorageKey, 'merchant-admin']])
  )
})

test('disabling remember account removes the stored username', () => {
  const storage = createStorage()
  saveRememberedAdminAccount('merchant-admin', true, storage)

  saveRememberedAdminAccount('merchant-admin', false, storage)

  assert.equal(loadRememberedAdminAccount(storage), '')
  assert.equal(storage.values.size, 0)
})
