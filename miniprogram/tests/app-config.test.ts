import assert from "node:assert/strict";
import { test } from "node:test";

import {
  assertRuntimeConfig,
  resolveRuntimeConfig,
  type AppRuntimeConfig
} from "../miniprogram/config/app-config";

function releaseConfig(
  overrides: Partial<AppRuntimeConfig> = {}
): Readonly<AppRuntimeConfig> {
  return Object.freeze({
    ...resolveRuntimeConfig("release"),
    apiBaseUrl: "https://api.shop.example.com",
    ...overrides
  });
}

test("开发版使用 txcloud 开发 API 和会话命名空间", () => {
  const config = resolveRuntimeConfig("develop");

  assert.equal(config.stage, "development");
  assert.equal(config.apiBaseUrl, "https://api.muybaby6.icu");
  assert.equal(config.storageNamespace, "zaoxiangji:miniprogram:v1");
  assert.doesNotThrow(() => assertRuntimeConfig("develop", config));
});

test("体验版显式复用开发 API 但隔离会话存储", () => {
  const develop = resolveRuntimeConfig("develop");
  const trial = resolveRuntimeConfig("trial");

  assert.equal(trial.stage, "trial");
  assert.equal(trial.apiBaseUrl, develop.apiBaseUrl);
  assert.notEqual(trial.storageNamespace, develop.storageNamespace);
  assert.doesNotThrow(() => assertRuntimeConfig("trial", trial));
});

test("正式版使用独立生产 API 和会话命名空间", () => {
  const config = resolveRuntimeConfig("release");

  assert.equal(config.stage, "production");
  assert.equal(config.apiBaseUrl, "https://api.junxiangshiping.cn");
  assert.equal(config.storageNamespace, "zaoxiangji:miniprogram:release:v1");
  assert.doesNotThrow(() => assertRuntimeConfig("release", config));
});

test("正式版拒绝非 production、非 HTTPS 和开发域名", () => {
  assert.throws(
    () => assertRuntimeConfig("release", releaseConfig({ stage: "trial" })),
    /正式版禁止使用 development 配置/
  );
  assert.throws(
    () => assertRuntimeConfig("release", releaseConfig({ apiBaseUrl: "http://api.shop.example.com" })),
    /正式版 API 必须使用有效的 HTTPS 地址/
  );
  assert.throws(
    () => assertRuntimeConfig("release", releaseConfig({ apiBaseUrl: "https://localhost:8443" })),
    /正式版 API 必须使用有效的 HTTPS 地址/
  );
  assert.throws(
    () => assertRuntimeConfig("release", releaseConfig({ apiBaseUrl: "https://user@api.shop.example.com" })),
    /正式版 API 必须使用有效的 HTTPS 地址/
  );
  assert.throws(
    () => assertRuntimeConfig("release", releaseConfig({ apiBaseUrl: "https://api.muybaby6.icu" })),
    /正式版禁止使用开发 API 域名/
  );
  assert.throws(
    () => assertRuntimeConfig("release", releaseConfig({ apiBaseUrl: "https://localhost" })),
    /正式版禁止使用开发 API 域名/
  );
  assert.throws(
    () => assertRuntimeConfig("release", releaseConfig({ apiBaseUrl: "https://api.localhost" })),
    /正式版禁止使用开发 API 域名/
  );
  assert.throws(
    () => assertRuntimeConfig("release", releaseConfig({ apiBaseUrl: "https://127.0.0.2" })),
    /正式版禁止使用开发 API 域名/
  );
  assert.doesNotThrow(() => assertRuntimeConfig("release", releaseConfig()));
});
