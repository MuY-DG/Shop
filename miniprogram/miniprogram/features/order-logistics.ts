import type {
  OrderWaybillTokenResponse,
  ShipmentTrackingResponse,
  WechatTrackingSyncStatus
} from "../types/order";
import { formatLocalDateTime } from "../utils/date-time";

export const LOGISTICS_UNAVAILABLE_MESSAGE = "当前物流轨迹暂不可用，请稍后再试";

export interface LogisticsPluginRuntime {
  openWaybillTracking(options: { waybillToken: string }): void;
}

export interface OpenOrderLogisticsOptions {
  requestWaybillToken: () => Promise<OrderWaybillTokenResponse>;
  loadPlugin?: () => unknown | Promise<unknown>;
}

export interface OrderTrackingEventView {
  eventKey: string;
  actionTime: number;
  actionType: number;
  actionTimeText: string;
  actionMessage: string;
}

export interface OrderTrackingView {
  statusText: string;
  statusTone: string;
  pathStateText: string;
  pathStateTone: string;
  hasPathItems: boolean;
  pathItems: OrderTrackingEventView[];
  lastSyncedAtText: string;
}

function syncStateText(status: WechatTrackingSyncStatus): string {
  switch (status) {
    case "NOT_REQUESTED":
      return "物流轨迹正在获取中";
    case "SYNCING":
      return "物流轨迹正在更新中";
    case "SYNCED":
      return "暂无更详细的物流轨迹";
    case "UNSUPPORTED":
      return "当前运单暂不支持详细物流轨迹";
    case "FAILED":
    case "UNKNOWN":
    case "UNAVAILABLE":
      return "详细物流轨迹暂不可用，请稍后再试";
  }
}

function statusTone(response: ShipmentTrackingResponse): string {
  if (response.logisticsStatus === "EXCEPTION") {
    return "warning";
  }
  if (
    response.logisticsStatus === "SIGNED"
    || response.logisticsStatus === "SIGNED_BY_OTHER"
  ) {
    return "success";
  }
  return response.logisticsStatus ? "active" : "muted";
}

function trackingStatusText(response: ShipmentTrackingResponse): string {
  const text = typeof response.logisticsStatusText === "string"
    ? response.logisticsStatusText.trim()
    : "";
  if (text) {
    return text;
  }
  if (!response.querySupported || response.querySyncStatus === "UNSUPPORTED") {
    return "物流状态暂不可查";
  }
  if (
    response.querySyncStatus === "FAILED"
    || response.querySyncStatus === "UNKNOWN"
    || response.querySyncStatus === "UNAVAILABLE"
  ) {
    return "物流状态暂不可用";
  }
  return "物流状态获取中";
}

function actionTimeText(value: unknown): string {
  const seconds = Number(value);
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return "";
  }
  return formatLocalDateTime(new Date(Math.floor(seconds) * 1000).toISOString());
}

export function buildOrderTrackingView(
  response: ShipmentTrackingResponse
): OrderTrackingView {
  const pathItems = Array.isArray(response.pathItems)
    ? response.pathItems
      .filter((item) => item && typeof item.actionMessage === "string")
      .map((item, index) => ({
        eventKey: `${Number(item.actionTime)}-${Number(item.actionType)}-${index}`,
        actionTime: Number(item.actionTime),
        actionType: Number(item.actionType),
        actionTimeText: actionTimeText(item.actionTime),
        actionMessage: item.actionMessage.trim()
      }))
      .filter((item) => item.actionMessage)
    : [];
  return {
    statusText: trackingStatusText(response),
    statusTone: statusTone(response),
    pathStateText: syncStateText(response.pathSyncStatus),
    pathStateTone: response.pathSyncStatus === "SYNCED" ? "normal" : "muted",
    hasPathItems: pathItems.length > 0,
    pathItems,
    lastSyncedAtText: formatLocalDateTime(response.lastSyncedAt)
  };
}

let pluginPromise: Promise<unknown> | null = null;

function defaultPluginLoader(): Promise<unknown> {
  if (!pluginPromise) {
    pluginPromise = requirePlugin.async("logisticsPlugin").catch((error: unknown) => {
      pluginPromise = null;
      throw error;
    });
  }
  return pluginPromise;
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
    const plugin = logisticsPlugin(await (options.loadPlugin || defaultPluginLoader)());
    if (!plugin) {
      return false;
    }
    plugin.openWaybillTracking({ waybillToken });
    return true;
  } catch {
    return false;
  }
}
