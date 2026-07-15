import assert from 'node:assert/strict'
import test from 'node:test'

import { ASSET_LIBRARY_EMPTY_TABLE_HEIGHT } from './asset-library-layout'

test('keeps the empty asset list tall without depending on an auto-sized parent', () => {
  assert.equal(ASSET_LIBRARY_EMPTY_TABLE_HEIGHT, 'clamp(360px, 48vh, 460px)')
  assert.notEqual(ASSET_LIBRARY_EMPTY_TABLE_HEIGHT, '100%')
})
