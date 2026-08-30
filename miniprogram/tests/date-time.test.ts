import assert from "node:assert/strict";
import test from "node:test";
import {
  formatLocalDateTime,
  parseApiDateTime
} from "../miniprogram/utils/date-time";

test("parses offset-aware API values as instants", () => {
  assert.equal(
    parseApiDateTime("2026-08-01T20:00:00+08:00")?.toISOString(),
    "2026-08-01T12:00:00.000Z"
  );
});

test("rejects ambiguous offset-free values", () => {
  assert.equal(parseApiDateTime("2026-08-01T20:00:00"), null);
});

test("rejects invalid API date-time values", () => {
  assert.equal(parseApiDateTime("not-a-date"), null);
});

test("shows the same instant in each device timezone", () => {
  const original = process.env.TZ;
  try {
    process.env.TZ = "Asia/Shanghai";
    assert.equal(formatLocalDateTime("2026-08-01T12:00:00Z"), "2026-08-01 20:00:00");
    assert.equal(
      formatLocalDateTime("2026-08-01T12:00:09Z", "second"),
      "2026-08-01 20:00:09"
    );
    process.env.TZ = "America/Los_Angeles";
    assert.equal(formatLocalDateTime("2026-08-01T12:00:00Z"), "2026-08-01 05:00:00");
  } finally {
    if (original === undefined) delete process.env.TZ;
    else process.env.TZ = original;
  }
});
