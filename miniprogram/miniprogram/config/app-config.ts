export type AppStage = "development" | "trial" | "production";

export interface AppRuntimeConfig {
  appName: string;
  appId: string;
  stage: AppStage;
  apiBaseUrl: string;
  requestTimeoutMs: number;
  apiSuccessCode: number;
  storageNamespace: string;
}

export type MiniProgramEnvVersion = "develop" | "trial" | "release";

export interface MiniProgramRuntimeIdentity {
  appId: string;
  envVersion: MiniProgramEnvVersion;
}

interface MiniProgramAccountSnapshot {
  miniProgram: {
    appId: string;
    envVersion: string;
  };
}

export const DEVELOPMENT_MINI_PROGRAM_APP_ID = "wx2c59f00275b9057a";

export const PRODUCTION_MINI_PROGRAM_APP_ID = "wxd2c02e4864389d80";

const DEVELOPMENT_API_BASE_URL = "https://api.muybaby6.icu";

const RELEASE_API_BASE_URL = "https://api.junxiangshiping.cn";

const DEVELOPMENT_API_HOSTNAME = httpsHostname(DEVELOPMENT_API_BASE_URL);

function runtimeConfig(
  appId: string,
  stage: AppStage,
  apiBaseUrl: string,
  storageNamespace: string
): Readonly<AppRuntimeConfig> {
  return Object.freeze({
    appName: "MuYbaby",
    appId,
    stage,
    apiBaseUrl,
    requestTimeoutMs: 12_000,
    apiSuccessCode: 200,
    storageNamespace
  });
}

export function resolveRuntimeConfig(
  appId: string,
  envVersion: MiniProgramEnvVersion
): Readonly<AppRuntimeConfig> {
  if (appId === DEVELOPMENT_MINI_PROGRAM_APP_ID) {
    switch (envVersion) {
      case "develop":
        return runtimeConfig(
          appId,
          "development",
          DEVELOPMENT_API_BASE_URL,
          "zaoxiangji:miniprogram:v1"
        );
      case "trial":
        return runtimeConfig(
          appId,
          "trial",
          DEVELOPMENT_API_BASE_URL,
          "zaoxiangji:miniprogram:trial:v1"
        );
      case "release":
        throw new Error("开发 AppID 禁止发布为正式版");
    }
  }

  if (appId === PRODUCTION_MINI_PROGRAM_APP_ID) {
    const storageNamespace = envVersion === "release"
      ? "zaoxiangji:miniprogram:release:v1"
      : `zaoxiangji:miniprogram:production:${envVersion}:v1`;
    return runtimeConfig(
      appId,
      "production",
      RELEASE_API_BASE_URL,
      storageNamespace
    );
  }

  throw new Error(`未知小程序 AppID：${appId || "<empty>"}`);
}

export function detectMiniProgramRuntimeIdentity(
  getAccountInfo: () => MiniProgramAccountSnapshot = () => wx.getAccountInfoSync()
): MiniProgramRuntimeIdentity {
  try {
    const miniProgram = getAccountInfo().miniProgram;
    const envVersion = miniProgram.envVersion;
    return {
      appId: miniProgram.appId.trim(),
      envVersion: envVersion === "trial" || envVersion === "release"
        ? envVersion
        : "develop"
    };
  } catch {
    return {
      appId: "",
      envVersion: "develop"
    };
  }
}

export const APP_RUNTIME_IDENTITY = detectMiniProgramRuntimeIdentity();
export const APP_MINI_PROGRAM_APP_ID = APP_RUNTIME_IDENTITY.appId;
export const APP_ENV_VERSION = APP_RUNTIME_IDENTITY.envVersion;

// Node 测试环境没有 wx；实际小程序启动时仍会由 assertRuntimeConfig 拒绝空 AppID。
const RESOLUTION_APP_ID = APP_MINI_PROGRAM_APP_ID || DEVELOPMENT_MINI_PROGRAM_APP_ID;
export const APP_CONFIG = resolveRuntimeConfig(RESOLUTION_APP_ID, APP_ENV_VERSION);

function httpsHostname(value: string): string {
  const match = /^https:\/\/([^/?#]+)(?:[/?#]|$)/i.exec(value.trim());
  if (!match) {
    return "";
  }
  const authority = match[1]?.toLowerCase() || "";
  if (!authority || authority.includes("@") || authority.includes(":")) {
    return "";
  }
  if (authority === "localhost") {
    return authority;
  }
  const labels = authority.split(".");
  if (labels.length < 2 || labels.some((label) => (
    !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label)
  ))) {
    return "";
  }
  return authority;
}

function isDevelopmentHostname(hostname: string): boolean {
  return hostname === DEVELOPMENT_API_HOSTNAME
    || hostname === "localhost"
    || hostname.endsWith(".localhost")
    || hostname === "0.0.0.0"
    || /^127(?:\.\d{1,3}){3}$/.test(hostname);
}

export function assertRuntimeConfig(
  appId: string,
  envVersion: MiniProgramEnvVersion,
  config: Readonly<AppRuntimeConfig> = APP_CONFIG
): void {
  const expected = resolveRuntimeConfig(appId, envVersion);
  if (!config.apiBaseUrl.trim()) {
    throw new Error("当前小程序 API 域名尚未配置");
  }
  const hostname = httpsHostname(config.apiBaseUrl);
  if (!hostname) {
    throw new Error("当前小程序 API 必须使用有效的 HTTPS 地址");
  }
  if (
    appId === PRODUCTION_MINI_PROGRAM_APP_ID
    && isDevelopmentHostname(hostname)
  ) {
    throw new Error("生产 AppID 禁止使用开发 API 域名");
  }
  if (config.appId !== expected.appId) {
    throw new Error("运行配置 AppID 与当前微信账户不一致");
  }
  if (
    config.stage !== expected.stage
    || config.apiBaseUrl !== expected.apiBaseUrl
    || config.storageNamespace !== expected.storageNamespace
  ) {
    throw new Error("当前 AppID 与版本使用了错误的运行配置");
  }
}
