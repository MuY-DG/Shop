import assert from 'node:assert/strict'
import test from 'node:test'
import {
  formatDateInTimeZone,
  formatLocalDateTime,
  localDateBoundaryToOffsetDateTime,
  parseApiDateTime
} from './date-time'

test('parses offset-aware API values as instants', () => {
  assert.equal(
    parseApiDateTime('2026-08-01T20:00:00+08:00')?.toISOString(),
    '2026-08-01T12:00:00.000Z'
  )
})

test('rejects ambiguous offset-free values', () => {
  assert.equal(parseApiDateTime('2026-08-01T20:00:00'), null)
})

test('rejects invalid API date-time values', () => {
  assert.equal(parseApiDateTime('not-a-date'), null)
})

test('adds an explicit device offset to local date boundaries', () => {
  const value = localDateBoundaryToOffsetDateTime('2026-08-01')
  assert.match(value, /^2026-08-01T00:00:00[+-]\d{2}:\d{2}$/)
})

test('formats report dates in the declared business timezone', () => {
  assert.equal(formatDateInTimeZone('2026-07-31T16:30:00Z', 'Asia/Shanghai'), '2026-08-01')
})

test('shows the same instant in each device timezone', () => {
  const original = process.env.TZ
  try {
    process.env.TZ = 'Asia/Shanghai'
    assert.equal(formatLocalDateTime('2026-08-01T12:00:00Z'), '2026-08-01 20:00:00')
    process.env.TZ = 'America/Los_Angeles'
    assert.equal(formatLocalDateTime('2026-08-01T12:00:00Z'), '2026-08-01 05:00:00')
  } finally {
    if (original === undefined) delete process.env.TZ
    else process.env.TZ = original
  }
})
