import assert from "node:assert/strict";
import { test } from "node:test";
import {
  DisplayConfigStore,
  normalizeDisplayName,
  privacyContractDisplayName
} from "../miniprogram/features/display-config";
import { APP_CONFIG } from "../miniprogram/config/app-config";
import { bindDisplayName, getDisplayName, refreshDisplayConfig, unbindDisplayName } from "../miniprogram/services/display-config";

test("后台名称异步更新页面并写入缓存，同一时刻的刷新合并", async () => {
  let resolveLoad: (value: unknown) => void = () => {};
  let requests = 0;
  let cached = "旧展示名称";
  let now = 0;
  const seen: string[] = [];
  const store = new DisplayConfigStore({
    defaultName: "蜀香序",
    readCache: () => cached,
    writeCache: (name) => { cached = name; },
    load: () => {
      requests += 1;
      return new Promise((resolve) => { resolveLoad = resolve; });
    },
    now: () => now
  });
  const unsubscribe = store.subscribe((name) => seen.push(name));
  const first = store.refresh();
  assert.equal(store.refresh(), first);
  await Promise.resolve();
  resolveLoad({ displayName: "  蜀香序甄选  " });
  assert.equal(await first, "蜀香序甄选");
  assert.equal(cached, "蜀香序甄选");
  assert.deepEqual(seen, ["旧展示名称", "蜀香序甄选"]);
  await store.refresh();
  assert.equal(requests, 1);
  unsubscribe();
  now = 60_001;
  const later = store.refresh();
  await Promise.resolve();
  resolveLoad({ displayName: "下一名称" });
  await later;
  assert.equal(requests, 2);
  assert.deepEqual(seen, ["旧展示名称", "蜀香序甄选"]);
});

test("首次启动无缓存或请求失败时保留蜀香序，异常响应不覆盖有效名称", async () => {
  let response: unknown = { displayName: "蜀香序" };
  let fail = true;
  const store = new DisplayConfigStore({
    defaultName: "蜀香序",
    readCache: () => { throw new Error("storage unavailable"); },
    writeCache: () => { throw new Error("storage full"); },
    load: async () => {
      if (fail) throw new Error("offline");
      return response;
    }
  });
  assert.equal(store.getDisplayName(), "蜀香序");
  assert.equal(await store.refresh(), "蜀香序");
  fail = false;
  response = { displayName: "后台名称" };
  assert.equal(await store.refresh(true), "后台名称");
  for (response of [null, {}, { displayName: "" }, { displayName: "名".repeat(33) }]) {
    assert.equal(await store.refresh(true), "后台名称");
  }
  fail = true;
  assert.equal(await store.refresh(true), "后台名称");
});

test("本地缓存只用作首屏兜底，重新启动后仍向后台刷新", async () => {
  let requests = 0;
  const store = new DisplayConfigStore({
    defaultName: "蜀香序",
    readCache: () => "上次名称",
    writeCache: () => {},
    load: async () => {
      requests += 1;
      return { displayName: "本次名称" };
    }
  });
  assert.equal(store.getDisplayName(), "上次名称");
  assert.equal(await store.refresh(), "本次名称");
  assert.equal(requests, 1);
});

test("展示名称校验及隐私协议名称优先级", () => {
  assert.equal(APP_CONFIG.appName, "蜀香序");
  assert.equal(normalizeDisplayName(" 蜀香序 "), "蜀香序");
  for (const value of [null, "", " ", "名".repeat(33), "蜀\n香序", "蜀\u0085香序"]) {
    assert.equal(normalizeDisplayName(value), undefined);
  }
  assert.equal(privacyContractDisplayName("蜀香序", ""), "《蜀香序隐私保护指引》");
  assert.equal(privacyContractDisplayName("后台新名称", "《微信返回的隐私指引》"), "《微信返回的隐私指引》");
});

test("公开配置请求无需登录，缓存按环境隔离，页面解绑后不再更新", async () => {
  const runtime = globalThis as unknown as { wx: typeof wx };
  const previousWx = runtime.wx;
  const keys: string[] = [];
  const seen: string[] = [];
  let responseName = "蜀香序在线";
  runtime.wx = {
    getStorageSync: (key: string) => { keys.push(key); return undefined; },
    setStorageSync: (key: string) => { keys.push(key); },
    request: (options: WechatMiniprogram.RequestOption) => {
      assert.equal(options.url, `${APP_CONFIG.apiBaseUrl}/app/display-config`);
      assert.equal(options.header?.Authorization, undefined);
      options.success?.({
        statusCode: 200,
        data: { code: 200, msg: "success", data: { displayName: responseName } },
        header: {}, cookies: [], errMsg: "request:ok"
      } as unknown as WechatMiniprogram.RequestSuccessCallbackResult);
      return {} as WechatMiniprogram.RequestTask;
    }
  } as unknown as typeof wx;
  try {
    const page = { setData: ({ displayName }: { displayName: string }) => { seen.push(displayName); } };
    bindDisplayName(page);
    await refreshDisplayConfig();
    assert.equal(getDisplayName(), "蜀香序在线");
    assert.deepEqual(seen, ["蜀香序", "蜀香序在线"]);
    assert.ok(keys.length >= 2);
    assert.ok(keys.every((key) => key === `${APP_CONFIG.storageNamespace}:display-name:v1`));
    unbindDisplayName(page);
    responseName = "改名后";
    await refreshDisplayConfig(true);
    assert.deepEqual(seen, ["蜀香序", "蜀香序在线"]);
  } finally {
    runtime.wx = previousWx;
  }
});
