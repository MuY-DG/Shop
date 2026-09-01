import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import { API_ENDPOINTS } from "../miniprogram/constants/api-endpoints";
import {
  buildOrderTrackingView,
  openOrderLogistics,
  type LogisticsPluginRuntime
} from "../miniprogram/features/order-logistics";
import type { ShipmentTrackingResponse } from "../miniprogram/types/order";

test("官方物流查询插件只声明在预下载分包中", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(process.cwd(), "miniprogram/app.json"), "utf8")
  ) as {
    plugins?: Record<string, { version?: string; provider?: string }>;
    subPackages?: Array<{
      name?: string;
      root: string;
      pages: string[];
      plugins?: Record<string, { version?: string; provider?: string }>;
    }>;
    preloadRule?: Record<string, { network?: string; packages?: string[] }>;
  };

  const logisticsPackage = appConfig.subPackages?.find(({ name }) => name === "logistics");
  assert.equal(appConfig.plugins, undefined);
  assert.equal(logisticsPackage?.root, "packages/logistics");
  assert.deepEqual(logisticsPackage?.pages, ["loader/loader"]);
  ["json", "less", "ts", "wxml"].forEach((extension) => {
    assert.equal(
      existsSync(resolve(
        process.cwd(),
        `miniprogram/packages/logistics/loader/loader.${extension}`
      )),
      true
    );
  });
  assert.deepEqual(logisticsPackage?.plugins?.logisticsPlugin, {
    version: "2.3.0",
    provider: "wx9ad912bf20548d92"
  });
  assert.deepEqual(appConfig.preloadRule?.["pages/order/list/list"], {
    network: "all",
    packages: ["logistics"]
  });
  assert.deepEqual(appConfig.preloadRule?.["pages/order/detail/detail"], {
    network: "all",
    packages: ["logistics"]
  });
});

test("物流插件通过分包异步接口加载", () => {
  const feature = readFileSync(
    resolve(process.cwd(), "miniprogram/features/order-logistics.ts"),
    "utf8"
  );

  assert.match(feature, /requirePlugin\.async\("logisticsPlugin"\)/);
  assert.doesNotMatch(feature, /requirePlugin\("logisticsPlugin"\)/);
});

test("物流 token 端点定位到订单下的指定包裹", () => {
  assert.equal(
    API_ENDPOINTS.orders.shipmentWaybillToken(123, 456),
    "/app/orders/123/shipments/456/logistics/waybill-token"
  );
});

test("物流主动同步端点按包裹隔离", () => {
  assert.equal(
    API_ENDPOINTS.orders.syncShipmentTracking(123, 456),
    "/app/orders/123/shipments/456/logistics/tracking/sync"
  );
  assert.equal(
    API_ENDPOINTS.orders.syncShipmentTracking(123, 789),
    "/app/orders/123/shipments/789/logistics/tracking/sync"
  );
});

test("自定义物流视图格式化摘要状态和 getPath 时间线", () => {
  const view = buildOrderTrackingView(trackingResponse({
    logisticsStatus: "IN_TRANSIT",
    logisticsStatusText: "运输中",
    pathSyncStatus: "SYNCED",
    pathItems: [
      {
        actionTime: 1_786_000_000,
        actionType: 200001,
        actionMessage: " 快件正在运输中 "
      }
    ],
    lastSyncedAt: "2026-08-09T12:30:00Z"
  }));

  assert.equal(view.statusText, "运输中");
  assert.equal(view.statusTone, "active");
  assert.equal(view.hasPathItems, true);
  assert.equal(view.pathItems[0]?.actionMessage, "快件正在运输中");
  assert.equal(view.pathItems[0]?.actionTimeText, "2026-08-06 07:06:40");
  assert.equal(view.lastSyncedAtText, "2026-08-09 12:30:00");
});

test("getPath 无节点或失败时仍返回可展示的空态", () => {
  const empty = buildOrderTrackingView(trackingResponse({
    pathSyncStatus: "SYNCED",
    pathItems: []
  }));
  assert.equal(empty.hasPathItems, false);
  assert.equal(empty.pathStateText, "暂无更详细的物流轨迹");

  const failed = buildOrderTrackingView(trackingResponse({
    pathSyncStatus: "FAILED",
    pathItems: []
  }));
  assert.equal(failed.hasPathItems, false);
  assert.equal(failed.pathStateText, "详细物流轨迹暂不可用，请稍后再试");
});

test("订单详情始终保留自定义轨迹区域和官方全部物流入口", () => {
  const template = readFileSync(
    resolve(process.cwd(), "miniprogram/pages/order/detail/detail.wxml"),
    "utf8"
  );
  assert.match(template, /class="tracking-panel"/);
  assert.match(template, /物流轨迹/);
  assert.match(template, /查看全部物流/);
  assert.match(template, /trackingView && trackingView\.hasPathItems/);
});

test("有效 token 只以规范后的值调起一次官方物流插件", async () => {
  const calls: string[] = [];
  const plugin: LogisticsPluginRuntime = {
    openWaybillTracking({ waybillToken }) {
      calls.push(waybillToken);
    }
  };

  const opened = await openOrderLogistics({
    requestWaybillToken: async () => ({ waybillToken: "  token-123  " }),
    loadPlugin: async () => plugin
  });

  assert.equal(opened, true);
  assert.deepEqual(calls, ["token-123"]);
});

test("空 token 和 token 接口失败时不加载插件", async () => {
  let pluginLoads = 0;
  const loadPlugin = () => {
    pluginLoads += 1;
    return {};
  };

  assert.equal(await openOrderLogistics({
    requestWaybillToken: async () => ({ waybillToken: "   " }),
    loadPlugin
  }), false);
  assert.equal(await openOrderLogistics({
    requestWaybillToken: async () => {
      throw new Error("token request failed");
    },
    loadPlugin
  }), false);
  assert.equal(pluginLoads, 0);
});

test("插件加载或调用异常统一安全降级", async () => {
  const requestWaybillToken = async () => ({ waybillToken: "token-456" });

  assert.equal(await openOrderLogistics({
    requestWaybillToken,
    loadPlugin: () => {
      throw new Error("plugin unavailable");
    }
  }), false);
  assert.equal(await openOrderLogistics({
    requestWaybillToken,
    loadPlugin: () => ({})
  }), false);
  assert.equal(await openOrderLogistics({
    requestWaybillToken,
    loadPlugin: () => ({
      openWaybillTracking() {
        throw new Error("open failed");
      }
    })
  }), false);
});

function trackingResponse(
  overrides: Partial<ShipmentTrackingResponse> = {}
): ShipmentTrackingResponse {
  return {
    shipmentId: 1,
    orderId: 123,
    carrierCode: "SF",
    carrierName: "顺丰速运",
    trackingNo: "SF123",
    querySupported: true,
    querySyncStatus: "SYNCED",
    logisticsStatus: "PICKED_UP",
    logisticsStatusText: "已揽件",
    queryErrorCode: null,
    queryErrorMessage: null,
    pathSupported: true,
    pathSyncStatus: "SYNCED",
    pathErrorCode: null,
    pathErrorMessage: null,
    officialViewAvailable: true,
    pathItems: [],
    lastAttemptAt: "2026-08-09T12:30:00Z",
    lastSyncedAt: "2026-08-09T12:30:00Z",
    ...overrides
  };
}
