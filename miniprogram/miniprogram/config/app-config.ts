export type AppStage = "development" | "production";

export interface AppRuntimeConfig {
  appName: string;
  stage: AppStage;
  apiBaseUrl: string;
  requestTimeoutMs: number;
  apiSuccessCode: number;
  storageNamespace: string;
}

export type MiniProgramEnvVersion = "develop" | "trial" | "release";

// 上线前只需要在这里切换 stage 和正式 API 域名，业务代码不读取环境常量。
export const APP_CONFIG: Readonly<AppRuntimeConfig> = Object.freeze({
  appName: "灶香集",
  stage: "development",
  apiBaseUrl: "https://pay-dev.muybaby6.icu",
  requestTimeoutMs: 12_000,
  apiSuccessCode: 200,
  storageNamespace: "zaoxiangji:miniprogram:v1"
});

export function assertRuntimeConfig(envVersion: MiniProgramEnvVersion): void {
  if (envVersion !== "release") {
    return;
  }
  if (APP_CONFIG.stage !== "production") {
    throw new Error("正式版禁止使用 development 配置");
  }
  if (!APP_CONFIG.apiBaseUrl.startsWith("https://")) {
    throw new Error("正式版 API 必须使用 HTTPS");
  }
}
