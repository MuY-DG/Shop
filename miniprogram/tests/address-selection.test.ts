import assert from "node:assert/strict";
import test from "node:test";

import type { AddressResponse } from "../types/api";
import {
  isCheckoutSubmitDisabled,
  replaceAddressFromEvent,
  resolveAddressSelection
} from "../features/checkout";

function address(
  id: number,
  isDefault = false,
  overrides: Partial<AddressResponse> = {}
): AddressResponse {
  return {
    id,
    receiverName: `收货人${id}`,
    receiverPhone: `1380000000${id}`,
    province: "四川省",
    city: "成都市",
    district: "武侯区",
    detailAddress: `${id}号`,
    isDefault,
    formattedAddress: `四川省成都市武侯区${id}号`,
    createdAt: "2026-07-10T00:00:00",
    updatedAt: "2026-07-10T00:00:00",
    ...overrides
  };
}

test("resolves the default address when no selection exists", () => {
  const first = address(1);
  const defaultAddress = address(2, true);
  assert.deepEqual(
    resolveAddressSelection([first, defaultAddress], null),
    defaultAddress
  );
});

test("eventChannel selection replaces the complete current address", () => {
  const current = address(1);
  const selected = address(2, false, {
    receiverName: "新收货人",
    receiverPhone: "13912345678",
    formattedAddress: "四川省成都市锦江区新地址"
  });
  assert.deepEqual(replaceAddressFromEvent(current, selected), selected);
});

test("a deleted selection falls back to the current default", () => {
  const deleted = address(9);
  const fallback = address(2, true);
  assert.deepEqual(
    resolveAddressSelection([address(1), fallback], deleted),
    fallback
  );
  assert.equal(resolveAddressSelection([], deleted), null);
});

test("submit stays disabled without an address or preview", () => {
  const selected = address(1, true);
  assert.equal(isCheckoutSubmitDisabled(false, selected, false), true);
  assert.equal(isCheckoutSubmitDisabled(true, null, false), true);
  assert.equal(isCheckoutSubmitDisabled(true, selected, true), true);
  assert.equal(isCheckoutSubmitDisabled(true, selected, false), false);
});
