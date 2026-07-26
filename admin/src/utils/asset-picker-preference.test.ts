import assert from 'node:assert/strict'
import test from 'node:test'
import {
  assetPickerFolderPreferenceKey,
  readAssetPickerFolderPreference,
  resolveAssetPickerFolderPreference,
  writeAssetPickerFolderPreference
} from './asset-picker-preference'

const createStorage = () => {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    values
  }
}

test('asset picker folder preferences are scoped by user and media kind', () => {
  const storage = createStorage()
  writeAssetPickerFolderPreference(storage, 7, 'IMAGE', 12)
  writeAssetPickerFolderPreference(storage, 7, 'VIDEO', undefined)

  assert.equal(readAssetPickerFolderPreference(storage, 7, 'IMAGE'), 12)
  assert.equal(readAssetPickerFolderPreference(storage, 7, 'VIDEO'), null)
  assert.equal(readAssetPickerFolderPreference(storage, 8, 'IMAGE'), undefined)
  assert.notEqual(
    assetPickerFolderPreferenceKey(7, 'IMAGE'),
    assetPickerFolderPreferenceKey(7, 'VIDEO')
  )
})

test('resolveAssetPickerFolderPreference falls back when a saved folder is unavailable', () => {
  const folders = [
    {
      id: 10,
      status: 'ENABLED',
      children: [{ id: 11, status: 'DISABLED', children: [] }]
    }
  ]

  assert.equal(resolveAssetPickerFolderPreference(10, 0, folders), 10)
  assert.equal(resolveAssetPickerFolderPreference(11, 0, folders), 0)
  assert.equal(resolveAssetPickerFolderPreference(99, 10, folders), 10)
  assert.equal(resolveAssetPickerFolderPreference(null, 10, folders), undefined)
})
