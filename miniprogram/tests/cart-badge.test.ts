import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";
import { runInNewContext } from "node:vm";
import ts from "typescript";
import * as checkout from "../miniprogram/features/checkout";
import * as cartFeedback from "../miniprogram/features/cart-feedback";
import * as quantity from "../miniprogram/features/quantity";
import * as tabBar from "../miniprogram/utils/tab-bar";
import type { CartItemResponse, CartListResponse } from "../miniprogram/types/cart";

function item(id: number, quantity: number, available = true): CartItemResponse {
  return {
    id,
    skuId: id,
    spuId: 10,
    productTitle: `商品 ${id}`,
    priceCent: 100,
    retailPriceCent: 100,
    quantity,
    lineAmountCent: quantity * 100,
    available
  };
}

function cart(items: CartItemResponse[]): CartListResponse {
  return {
    items,
    totalQuantity: items.reduce((sum, item) => sum + item.quantity, 0),
    totalAmountCent: items.reduce((sum, item) => sum + item.lineAmountCent, 0),
    unavailableCount: items.filter((item) => !item.available).length
  };
}

function code(path: string): string {
  return ts.transpileModule(readFileSync(resolve(process.cwd(), "miniprogram", path), "utf8"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText;
}

function badgeRuntime(load: () => Promise<CartListResponse>) {
  let instance: any;
  let userId = "user-1";
  const cachePreferences: boolean[] = [];
  runInNewContext(code("custom-tab-bar/index.ts"), {
    exports: {},
    require(path: string) {
      if (path.endsWith("services/cart")) return {
        getCartItems: (options: { preferCache: boolean }) => {
          cachePreferences.push(options.preferCache);
          return load();
        }
      };
      if (path.endsWith("services/session")) return {
        getSessionState: () => ({ user: userId ? { userId } : undefined, accessToken: userId })
      };
      throw new Error(`Unexpected import ${path}`);
    },
    Component(definition: any) {
      instance = { data: definition.data, ...definition.methods };
      instance.setData = (patch: unknown) => Object.assign(instance.data, patch);
    },
    wx: { getSystemInfoSync: () => ({ platform: "ios" }) }
  });
  return {
    instance,
    cachePreferences,
    cartTab: () => instance.data.list.find((item: { icon: string }) => item.icon === "cart"),
    setUser: (next: string) => { userId = next; }
  };
}

test("底部角标按购物车商品条目计数，同规格多件仍为一，不同规格分别计数", async () => {
  const items = [item(101, 5)];
  const r = badgeRuntime(async () => cart(items));
  await r.instance.refreshCartCount();
  assert.equal(r.cartTab().badge, "1");
  assert.equal(r.cartTab().ariaLabel, "购物车，1种商品");

  items[0]!.quantity = 9;
  await r.instance.refreshCartCount();
  assert.equal(r.cartTab().badge, "1");

  items.push(item(102, 3));
  await r.instance.refreshCartCount();
  assert.equal(r.cartTab().badge, "2");

  items.push({ ...item(103, 6, false), spuId: 11 });
  await r.instance.refreshCartCount();
  assert.equal(r.cartTab().badge, "3", "保留失效商品计入角标的现有规则");
  assert.ok(r.cachePreferences.every(Boolean), "刷新继续复用购物车缓存");
});

test("底部角标为空时隐藏，超过 99 种商品时显示 99+", async () => {
  let items: CartItemResponse[] = [];
  const r = badgeRuntime(async () => cart(items));
  await r.instance.refreshCartCount();
  assert.equal(r.cartTab().badge, "");
  assert.equal(r.cartTab().ariaLabel, "购物车");
  items = Array.from({ length: 100 }, (_, index) => item(index + 1, 2));
  await r.instance.refreshCartCount();
  assert.equal(r.cartTab().badge, "99+");
});

test("登录切换采用新用户的商品个数，退出登录清空角标并忽略旧响应", async () => {
  const pending: Array<(value: CartListResponse) => void> = [];
  const r = badgeRuntime(() => new Promise((resolve) => pending.push(resolve)));
  const first = r.instance.refreshCartCount();
  r.setUser("user-2");
  const second = r.instance.refreshCartCount();
  pending[1]!(cart([item(201, 6), item(202, 7)]));
  await second;
  pending[0]!(cart([item(101, 20)]));
  await first;
  assert.equal(r.cartTab().badge, "2");

  const beforeLogout = r.instance.refreshCartCount();
  r.setUser("");
  await r.instance.refreshCartCount();
  pending[2]!(cart([item(201, 6), item(202, 7)]));
  await beforeLogout;
  assert.equal(r.cartTab().badge, "");
  assert.equal(pending.length, 3, "未登录不请求购物车");
});

test("购物车页加载、修改数量和删除同步商品个数，结算仍使用商品件数", async () => {
  let items = [item(101, 3), item(102, 2)];
  let instance: any;
  const badgeCounts: number[] = [];
  const module: any = {};
  runInNewContext(code("pages/cart/cart-page.ts"), {
    exports: module,
    require(path: string) {
      if (path.endsWith("config/brand-logo")) return { createBrandLogoView: () => ({}) };
      if (path.endsWith("features/checkout")) return checkout;
      if (path.endsWith("features/cart-feedback")) return cartFeedback;
      if (path.endsWith("features/quantity")) return quantity;
      if (path.endsWith("utils/tab-bar")) return tabBar;
      if (path.endsWith("utils/api-error")) return { isApiError: () => false };
      if (path.endsWith("services/order") || path.endsWith("services/session") || path.endsWith("utils/login-navigation")) return {};
      if (path.endsWith("services/cart")) return {
        getCartItems: async () => cart(items),
        updateCartItemQuantity: async (id: number, next: { quantity: number }) => {
          items = items.map((entry) => entry.id === id ? item(id, next.quantity) : entry);
        },
        deleteCartItems: async (ids: number[]) => { items = items.filter((item) => !ids.includes(item.id)); }
      };
      throw new Error(`Unexpected import ${path}`);
    },
    Page(definition: any) {
      instance = definition;
      instance.setData = (patch: unknown) => Object.assign(instance.data, patch);
      instance.getTabBar = () => ({ setCartCount: (count: number) => badgeCounts.push(count) });
      instance.refreshSelectedPricing = () => undefined;
    },
    wx: { showToast: () => undefined }
  });
  module.registerCartPage({ loginRedirect: "/pages/cart/cart", navigationBack: false, syncTabBar: true });
  await instance.loadCart();
  assert.equal(badgeCounts[badgeCounts.length - 1], 2);
  assert.equal(instance.data.cartTotalQuantity, 5);
  assert.equal(instance.data.selectedQuantity, 5);

  await instance.updateQuantity(101, 7);
  assert.equal(badgeCounts[badgeCounts.length - 1], 2);
  assert.equal(instance.data.cartTotalQuantity, 9);
  assert.equal(instance.data.selectedQuantity, 9);

  await instance.deleteConfirmed([102]);
  assert.equal(badgeCounts[badgeCounts.length - 1], 1);
  assert.equal(instance.data.cartTotalQuantity, 7);
  await instance.deleteConfirmed([101]);
  assert.equal(badgeCounts[badgeCounts.length - 1], 0);
  assert.equal(instance.data.cartTotalQuantity, 0);
});
