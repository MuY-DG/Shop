import type { AppRuntimeConfig } from "../config/app-config";

export interface AppLayoutMetrics {
  statusBarHeight: number;
  navigationBarHeight: number;
  menuButtonWidth: number;
  menuButtonRight: number;
  safeAreaBottom: number;
  windowWidth: number;
}

export interface AppGlobalData {
  config: Readonly<AppRuntimeConfig>;
  layout: AppLayoutMetrics;
}
