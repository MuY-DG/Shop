import assert from 'node:assert/strict'
import test from 'node:test'
import { nextParameterOptionCode, supportsParameterFiltering } from './parameter-editor-state'

test('only option-based parameter types support catalog filtering', () => {
  assert.equal(supportsParameterFiltering('SINGLE_SELECT'), true)
  assert.equal(supportsParameterFiltering('MULTI_SELECT'), true)
  assert.equal(supportsParameterFiltering('TEXT'), false)
  assert.equal(supportsParameterFiltering('NUMBER'), false)
  assert.equal(supportsParameterFiltering('BOOLEAN'), false)
})

test('new parameter options receive the first available automatic code', () => {
  assert.equal(nextParameterOptionCode([]), 'OPTION_1')
  assert.equal(
    nextParameterOptionCode([
      { optionCode: 'OPTION_1' },
      { optionCode: 'custom' },
      { optionCode: ' option_2 ' }
    ]),
    'OPTION_3'
  )
})
