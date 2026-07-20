import assert from "node:assert/strict";
import test from "node:test";
import { resolveWholesalePrice } from "../features/wholesale-pricing";

const sku = {
  priceCent: 1000,
  wholesaleTiers: [
    { minQuantity: 10, unitPriceCent: 880 },
    { minQuantity: 50, unitPriceCent: 760 }
  ]
};

test("wholesale pricing selects the highest reached tier", () => {
  assert.deepEqual(resolveWholesalePrice(sku, 9), {
    unitPriceCent: 1000,
    appliedTier: null,
    nextTier: { minQuantity: 10, unitPriceCent: 880 },
    quantityNeeded: 1
  });
  assert.deepEqual(resolveWholesalePrice(sku, 10), {
    unitPriceCent: 880,
    appliedTier: { minQuantity: 10, unitPriceCent: 880 },
    nextTier: { minQuantity: 50, unitPriceCent: 760 },
    quantityNeeded: 40
  });
  assert.deepEqual(resolveWholesalePrice(sku, 80), {
    unitPriceCent: 760,
    appliedTier: { minQuantity: 50, unitPriceCent: 760 },
    nextTier: null,
    quantityNeeded: null
  });
});
