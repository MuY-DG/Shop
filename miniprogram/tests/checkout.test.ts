import assert from "node:assert/strict";
import test from "node:test";

import type { ProductSku } from "../types/api";
import {
  buildCartCheckoutUrl,
  buildDirectBuyUrl,
  buildPreviewRequest,
  buildProductCommand,
  buildSubmitRequest,
  clampQuantity,
  createLatestRequestTracker,
  directSelection,
  parseCheckoutQuery
} from "../features/checkout";

function sku(overrides: Partial<ProductSku> = {}): ProductSku {
  return {
    id: 18,
    skuCode: "SKU-18",
    specJson: "{}",
    specText: "标准装",
    priceCent: 3990,
    originalPriceCent: 4990,
    stockAvailable: 5,
    weightGram: 500,
    image: "",
    status: "ENABLED",
    ...overrides
  };
}

test("clamps quantity to selected sku stock and 999", () => {
  assert.equal(clampQuantity(0, 5), 1);
  assert.equal(clampQuantity(7, 5), 5);
  assert.equal(clampQuantity(1200, 5000), 999);
});

test("builds and decodes explicit CART and DIRECT checkout URLs", () => {
  const directUrl = buildDirectBuyUrl(18, 2);
  assert.deepEqual(parseCheckoutQuery(directUrl), {
    source: "DIRECT",
    skuId: 18,
    quantity: 2
  });
  assert.equal(directUrl.includes("cart_item_ids"), false);

  const cartUrl = buildCartCheckoutUrl([9, 12]);
  assert.deepEqual(parseCheckoutQuery(cartUrl), {
    source: "CART",
    cartItemIds: [9, 12]
  });
  assert.match(cartUrl, /source=CART/);
});

test("rejects mixed, malformed, and out-of-range checkout queries", () => {
  assert.throws(
    () => parseCheckoutQuery("?source=DIRECT&sku_id=18&quantity=2&cart_item_ids=9"),
    /结算参数/
  );
  assert.throws(
    () => parseCheckoutQuery("?source=DIRECT&sku_id=18&quantity=1000"),
    /结算参数/
  );
  assert.throws(
    () => parseCheckoutQuery("?source=CART&cart_item_ids=9%2Cbad"),
    /结算参数/
  );
  assert.throws(
    () => parseCheckoutQuery("?source=CART&source=DIRECT&cart_item_ids=9"),
    /结算参数/
  );
  assert.throws(
    () => parseCheckoutQuery("?source=DIRECT&sku_id=18&quantity=2&unexpected=1"),
    /结算参数/
  );
  assert.throws(
    () => parseCheckoutQuery("?source=CART&cart_item_ids=9&unexpected=1"),
    /结算参数/
  );
  assert.throws(
    () => parseCheckoutQuery("?source=DIRECT&sku_id=18&quantity=2&__proto__=x"),
    /结算参数/
  );
  assert.throws(
    () => parseCheckoutQuery("?source=CART&cart_item_ids=9&__proto__=x"),
    /结算参数/
  );
});

test("only the latest delayed request may commit or clear loading", async () => {
  const tracker = createLatestRequestTracker();
  const commits: string[] = [];
  let loading = false;

  let resolveFirst!: (value: string) => void;
  let resolveSecond!: (value: string) => void;
  const firstResult = new Promise<string>((resolve) => {
    resolveFirst = resolve;
  });
  const secondResult = new Promise<string>((resolve) => {
    resolveSecond = resolve;
  });

  async function load(result: Promise<string>) {
    const token = tracker.begin();
    loading = true;
    try {
      const value = await result;
      if (tracker.isLatest(token)) {
        commits.push(value);
      }
    } finally {
      if (tracker.isLatest(token)) {
        loading = false;
      }
    }
  }

  const first = load(firstResult);
  const second = load(secondResult);
  resolveFirst("old");
  await first;
  assert.deepEqual(commits, []);
  assert.equal(loading, true);

  resolveSecond("new");
  await second;

  assert.deepEqual(commits, ["new"]);
  assert.equal(loading, false);
});

test("direct submit body contains no cartItemIds", () => {
  const body = buildSubmitRequest(
    directSelection(18, 2),
    "2075761422822531074",
    null,
    "idem-1"
  );
  assert.equal(body.source, "DIRECT");
  assert.equal("cartItemIds" in body, false);
  assert.deepEqual(body, {
    source: "DIRECT",
    skuId: 18,
    quantity: 2,
    addressId: "2075761422822531074",
    userCouponId: null,
    idempotencyKey: "idem-1"
  });
});

test("preview and submit preserve one explicit source-specific selection", () => {
  const selection = directSelection(18, 2);
  assert.deepEqual(buildPreviewRequest(selection, null, null), {
    source: "DIRECT",
    skuId: 18,
    quantity: 2,
    userCouponId: null
  });

  const first = buildSubmitRequest(
    selection,
    "2075761422822531074",
    null,
    "stable-key"
  );
  const afterAddressSwitch = buildSubmitRequest(
    selection,
    "2075761422822531075",
    first.userCouponId ?? null,
    first.idempotencyKey
  );
  assert.equal(afterAddressSwitch.idempotencyKey, "stable-key");
  assert.equal("cartItemIds" in afterAddressSwitch, false);
});

test("product commands reject missing, disabled, and sold-out selections", () => {
  assert.equal(buildProductCommand("DIRECT_BUY", undefined, 1).type, "ERROR");

  const disabled = buildProductCommand(
    "DIRECT_BUY",
    sku({ status: "DISABLED" }),
    1
  );
  assert.deepEqual(disabled, { type: "ERROR", message: "该规格已下架" });
  assert.equal("url" in disabled, false);

  const soldOut = buildProductCommand(
    "DIRECT_BUY",
    sku({ stockAvailable: 0 }),
    1
  );
  assert.deepEqual(soldOut, { type: "ERROR", message: "该规格已售罄" });
  assert.equal("url" in soldOut, false);
});

test("stock-one boundaries and SKU switches keep quantity valid", () => {
  assert.equal(clampQuantity(1, 1), 1);
  assert.equal(clampQuantity(2, 1), 1);
  assert.equal(clampQuantity(8, 3), 3);
  assert.equal(clampQuantity(0, 10), 1);
});

test("add-to-cart uses selected quantity while direct buy has no cart operation", () => {
  assert.deepEqual(buildProductCommand("ADD_TO_CART", sku(), 4), {
    type: "ADD_TO_CART",
    payload: { skuId: 18, quantity: 4 }
  });

  const direct = buildProductCommand("DIRECT_BUY", sku(), 4);
  assert.deepEqual(direct, {
    type: "DIRECT_BUY",
    url: "/pages/order/preview/preview?source=DIRECT&sku_id=18&quantity=4"
  });
  assert.equal("payload" in direct, false);
});
