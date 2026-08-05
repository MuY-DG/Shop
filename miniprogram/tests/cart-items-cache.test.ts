import assert from "node:assert/strict";
import { test } from "node:test";

import { CartItemsCache } from "../miniprogram/features/cart-items-cache";

test("购物车快照在有效期内复用，并合并并发加载", async () => {
  let now = 1_000;
  let loads = 0;
  let resolveFirst: ((value: number) => void) | undefined;
  const cache = new CartItemsCache<number>(30_000, () => now);
  const load = () => {
    loads += 1;
    return new Promise<number>((resolve) => {
      resolveFirst = resolve;
    });
  };

  const first = cache.get("user-1", load, true);
  const concurrent = cache.get("user-1", load, true);
  assert.equal(loads, 1);

  resolveFirst?.(7);
  assert.equal(await first, 7);
  assert.equal(await concurrent, 7);

  now += 29_999;
  assert.equal(await cache.get("user-1", async () => 9, true), 7);
  assert.equal(loads, 1);
});

test("购物车快照过期、强制加载或失效后重新请求", async () => {
  let now = 1_000;
  let loads = 0;
  const cache = new CartItemsCache<number>(30_000, () => now);
  const load = async () => {
    loads += 1;
    return loads;
  };

  assert.equal(await cache.get("user-1", load, true), 1);
  assert.equal(await cache.get("user-1", load, false), 2);

  now += 30_001;
  assert.equal(await cache.get("user-1", load, true), 3);

  cache.invalidate();
  assert.equal(await cache.get("user-1", load, true), 4);
});

test("购物车快照按登录用户隔离", async () => {
  let loads = 0;
  const cache = new CartItemsCache<number>(30_000);
  const load = async () => {
    loads += 1;
    return loads;
  };

  assert.equal(await cache.get("user-1", load, true), 1);
  assert.equal(await cache.get("user-2", load, true), 2);
  assert.equal(loads, 2);
});

test("失效前的延迟响应不会覆盖新的购物车快照", async () => {
  let resolveOld: ((value: number) => void) | undefined;
  let fallbackLoads = 0;
  const cache = new CartItemsCache<number>(30_000);
  const oldRequest = cache.get("user-1", () => new Promise<number>((resolve) => {
    resolveOld = resolve;
  }), true);

  cache.invalidate();
  assert.equal(await cache.get("user-1", async () => 2, true), 2);

  resolveOld?.(1);
  assert.equal(await oldRequest, 1);
  assert.equal(await cache.get("user-1", async () => {
    fallbackLoads += 1;
    return 3;
  }, true), 2);
  assert.equal(fallbackLoads, 0);
});
