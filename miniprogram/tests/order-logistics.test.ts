import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import { API_ENDPOINTS } from "../miniprogram/constants/api-endpoints";
import {
  openOrderLogistics,
  type LogisticsPluginRuntime
} from "../miniprogram/features/order-logistics";

test("小程序声明官方物流查询插件", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(process.cwd(), "miniprogram/app.json"), "utf8")
  ) as {
    plugins?: Record<string, { version?: string; provider?: string }>;
  };

  assert.deepEqual(appConfig.plugins?.logisticsPlugin, {
    version: "2.3.0",
    provider: "wx9ad912bf20548d92"
  });
});

test("物流 token 端点固定为订单所有者路径", () => {
  assert.equal(
    API_ENDPOINTS.orders.waybillToken(123),
    "/app/orders/123/logistics/waybill-token"
  );
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
    loadPlugin: () => plugin
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
