import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  DEVELOPMENT_MINI_PROGRAM_APP_ID,
  PRODUCTION_MINI_PROGRAM_APP_ID,
  assertRuntimeConfig,
  detectMiniProgramRuntimeIdentity,
  resolveRuntimeConfig,
  type AppRuntimeConfig
} from "../miniprogram/config/app-config";

function productionConfig(
  envVersion: "develop" | "trial" | "release" = "release",
  overrides: Partial<AppRuntimeConfig> = {}
): Readonly<AppRuntimeConfig> {
  return Object.freeze({
    ...resolveRuntimeConfig(PRODUCTION_MINI_PROGRAM_APP_ID, envVersion),
    ...overrides
  });
}

test("开发版使用 txcloud 开发 API 和会话命名空间", () => {
  const config = resolveRuntimeConfig(DEVELOPMENT_MINI_PROGRAM_APP_ID, "develop");

  assert.equal(config.appId, DEVELOPMENT_MINI_PROGRAM_APP_ID);
  assert.equal(config.stage, "development");
  assert.equal(config.apiBaseUrl, "https://api.muybaby6.icu");
  assert.equal(config.storageNamespace, "zaoxiangji:miniprogram:v1");
  assert.doesNotThrow(() => assertRuntimeConfig(
    DEVELOPMENT_MINI_PROGRAM_APP_ID,
    "develop",
    config
  ));
});

test("微信运行时身份同时读取 AppID 和版本并安全降级未知版本", () => {
  assert.deepEqual(
    detectMiniProgramRuntimeIdentity(() => ({
      miniProgram: {
        appId: `  ${PRODUCTION_MINI_PROGRAM_APP_ID}  `,
        envVersion: "trial"
      }
    })),
    {
      appId: PRODUCTION_MINI_PROGRAM_APP_ID,
      envVersion: "trial"
    }
  );
  assert.deepEqual(
    detectMiniProgramRuntimeIdentity(() => ({
      miniProgram: {
        appId: DEVELOPMENT_MINI_PROGRAM_APP_ID,
        envVersion: "unexpected"
      }
    })),
    {
      appId: DEVELOPMENT_MINI_PROGRAM_APP_ID,
      envVersion: "develop"
    }
  );
  assert.deepEqual(
    detectMiniProgramRuntimeIdentity(() => {
      throw new Error("unavailable");
    }),
    {
      appId: "",
      envVersion: "develop"
    }
  );
});

test("开发 AppID 的体验版复用 txcloud 但隔离会话存储", () => {
  const develop = resolveRuntimeConfig(DEVELOPMENT_MINI_PROGRAM_APP_ID, "develop");
  const trial = resolveRuntimeConfig(DEVELOPMENT_MINI_PROGRAM_APP_ID, "trial");

  assert.equal(trial.stage, "trial");
  assert.equal(trial.apiBaseUrl, develop.apiBaseUrl);
  assert.notEqual(trial.storageNamespace, develop.storageNamespace);
  assert.doesNotThrow(() => assertRuntimeConfig(
    DEVELOPMENT_MINI_PROGRAM_APP_ID,
    "trial",
    trial
  ));
});

test("开发 AppID 禁止发布正式版", () => {
  assert.throws(
    () => resolveRuntimeConfig(DEVELOPMENT_MINI_PROGRAM_APP_ID, "release"),
    /开发 AppID 禁止发布为正式版/
  );
  assert.throws(
    () => assertRuntimeConfig(
      DEVELOPMENT_MINI_PROGRAM_APP_ID,
      "release",
      resolveRuntimeConfig(DEVELOPMENT_MINI_PROGRAM_APP_ID, "develop")
    ),
    /开发 AppID 禁止发布为正式版/
  );
});

test("生产 AppID 的开发、体验和正式版本都使用 shop API", () => {
  const configs = (["develop", "trial", "release"] as const).map(
    (envVersion) => ({
      envVersion,
      config: resolveRuntimeConfig(PRODUCTION_MINI_PROGRAM_APP_ID, envVersion)
    })
  );

  for (const { envVersion, config } of configs) {
    assert.equal(config.appId, PRODUCTION_MINI_PROGRAM_APP_ID);
    assert.equal(config.stage, "production");
    assert.equal(config.apiBaseUrl, "https://api.junxiangshiping.cn");
    assert.doesNotThrow(() => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      envVersion,
      config
    ));
  }

  assert.equal(
    new Set(configs.map(({ config }) => config.storageNamespace)).size,
    configs.length
  );
});

test("未知 AppID 直接拒绝启动", () => {
  assert.throws(
    () => resolveRuntimeConfig("wx-unknown", "develop"),
    /未知小程序 AppID/
  );
  assert.throws(
    () => assertRuntimeConfig("", "develop", productionConfig()),
    /未知小程序 AppID/
  );
});

test("生产 AppID 拒绝非 HTTPS、开发域名和错配配置", () => {
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { stage: "trial" })
    ),
    /错误的运行配置/
  );
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { apiBaseUrl: "http://api.shop.example.com" })
    ),
    /必须使用有效的 HTTPS 地址/
  );
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { apiBaseUrl: "https://localhost:8443" })
    ),
    /必须使用有效的 HTTPS 地址/
  );
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { apiBaseUrl: "https://user@api.shop.example.com" })
    ),
    /必须使用有效的 HTTPS 地址/
  );
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { apiBaseUrl: "https://api.muybaby6.icu" })
    ),
    /生产 AppID 禁止使用开发 API 域名/
  );
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { apiBaseUrl: "https://localhost" })
    ),
    /生产 AppID 禁止使用开发 API 域名/
  );
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { apiBaseUrl: "https://api.localhost" })
    ),
    /生产 AppID 禁止使用开发 API 域名/
  );
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { apiBaseUrl: "https://127.0.0.2" })
    ),
    /生产 AppID 禁止使用开发 API 域名/
  );
  assert.throws(
    () => assertRuntimeConfig(
      PRODUCTION_MINI_PROGRAM_APP_ID,
      "release",
      productionConfig("release", { appId: DEVELOPMENT_MINI_PROGRAM_APP_ID })
    ),
    /运行配置 AppID 与当前微信账户不一致/
  );
});

test("公共项目配置固定为生产 AppID", () => {
  const projectConfig = JSON.parse(
    readFileSync(resolve(process.cwd(), "project.config.json"), "utf8")
  ) as { appid?: string };

  assert.equal(projectConfig.appid, PRODUCTION_MINI_PROGRAM_APP_ID);
});
