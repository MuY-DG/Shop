import type { OrderWaybillTokenResponse } from "../types/order";

export const LOGISTICS_UNAVAILABLE_MESSAGE = "当前物流轨迹暂不可用，请稍后再试";

export interface LogisticsPluginRuntime {
  openWaybillTracking(options: { waybillToken: string }): void;
}

export interface OpenOrderLogisticsOptions {
  requestWaybillToken: () => Promise<OrderWaybillTokenResponse>;
  loadPlugin?: () => unknown;
}

function defaultPluginLoader(): unknown {
  return requirePlugin("logisticsPlugin") as unknown;
}

function logisticsPlugin(value: unknown): LogisticsPluginRuntime | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const candidate = value as Partial<LogisticsPluginRuntime>;
  return typeof candidate.openWaybillTracking === "function"
    ? candidate as LogisticsPluginRuntime
    : null;
}

export async function openOrderLogistics(
  options: OpenOrderLogisticsOptions
): Promise<boolean> {
  try {
    const response = await options.requestWaybillToken();
    const waybillToken = typeof response?.waybillToken === "string"
      ? response.waybillToken.trim()
      : "";
    if (!waybillToken) {
      return false;
    }
    const plugin = logisticsPlugin((options.loadPlugin || defaultPluginLoader)());
    if (!plugin) {
      return false;
    }
    plugin.openWaybillTracking({ waybillToken });
    return true;
  } catch {
    return false;
  }
}
