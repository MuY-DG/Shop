export type AppStage = "development" | "trial" | "production";

export interface AppRuntimeConfig {
  appName: string;
  stage: AppStage;
  apiBaseUrl: string;
  requestTimeoutMs: number;
  apiSuccessCode: number;
  storageNamespace: string;
}

export type MiniProgramEnvVersion = "develop" | "trial" | "release";

const DEVELOPMENT_API_BASE_URL = "https://pay-dev.muybaby6.icu";

const RELEASE_API_BASE_URL = "https://api.muybaby6.icu";

function runtimeConfig(
  stage: AppStage,
  apiBaseUrl: string,
  storageNamespace: string
): Readonly<AppRuntimeConfig> {
  return Object.freeze({
    appName: "MuYbaby",
    stage,
    apiBaseUrl,
    requestTimeoutMs: 12_000,
    apiSuccessCode: 200,
    storageNamespace
  });
}

export function resolveRuntimeConfig(
  envVersion: MiniProgramEnvVersion
): Readonly<AppRuntimeConfig> {
  switch (envVersion) {
    case "develop":
      return runtimeConfig(
        "development",
        DEVELOPMENT_API_BASE_URL,
        "zaoxiangji:miniprogram:v1"
      );
    case "trial":
      // 暂无独立预发布域名；体验版显式复用开发 API，但隔离本地会话存储。
      return runtimeConfig(
        "trial",
        DEVELOPMENT_API_BASE_URL,
        "zaoxiangji:miniprogram:trial:v1"
      );
    case "release":
      return runtimeConfig(
        "production",
        RELEASE_API_BASE_URL,
        "zaoxiangji:miniprogram:release:v1"
      );
  }
}

export function detectMiniProgramEnvVersion(): MiniProgramEnvVersion {
  try {
    const envVersion = wx.getAccountInfoSync().miniProgram.envVersion;
    return envVersion === "trial" || envVersion === "release"
      ? envVersion
      : "develop";
  } catch {
    return "develop";
  }
}

export const APP_ENV_VERSION = detectMiniProgramEnvVersion();
export const APP_CONFIG = resolveRuntimeConfig(APP_ENV_VERSION);

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
  return hostname === "localhost"
    || hostname.endsWith(".localhost")
    || hostname === "0.0.0.0"
    || /^127(?:\.\d{1,3}){3}$/.test(hostname)
    || hostname.includes("pay-dev");
}

export function assertRuntimeConfig(
  envVersion: MiniProgramEnvVersion,
  config: Readonly<AppRuntimeConfig> = APP_CONFIG
): void {
  if (envVersion !== "release") {
    return;
  }
  if (config.stage !== "production") {
    throw new Error("正式版禁止使用 development 配置");
  }
  if (!config.apiBaseUrl.trim()) {
    throw new Error("正式版 API 域名尚未配置");
  }
  const hostname = httpsHostname(config.apiBaseUrl);
  if (!hostname) {
    throw new Error("正式版 API 必须使用有效的 HTTPS 地址");
  }
  if (isDevelopmentHostname(hostname)) {
    throw new Error("正式版禁止使用开发 API 域名");
  }
}
