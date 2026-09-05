import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";
import { runInNewContext } from "node:vm";
import ts from "typescript";
import * as orderCenter from "../miniprogram/features/order-center";
import * as logistics from "../miniprogram/features/order-logistics";
import type {
  AppOrderDetailResponse,
  AppOrderShipmentResponse,
  ShipmentTrackingResponse
} from "../miniprogram/types/order";

function shipment(id: number): AppOrderShipmentResponse {
  return {
    shipmentId: id,
    orderId: 123,
    packageNo: id,
    logisticsType: 1,
    deliveryMode: 2,
    itemDesc: "商品",
    expressCompanyCode: "SF",
    expressCompanyName: "顺丰速运",
    trackingNo: `SF${id}`,
    shipmentSource: "MANUAL",
    localShipmentStatus: "SHIPPED",
    wechatProviderMode: "REAL",
    wechatUploadStatus: "UPLOADED",
    wechatUploadMessage: null,
    waybillTrackingSupported: true,
    waybillRegistrationKind: "TRACE",
    waybillRegistrationStatus: "REGISTERED",
    waybillRegistrationMessage: null,
    shippedAt: "2026-09-01T12:00:00Z",
    uploadTime: null,
    wechatUploadedAt: null,
    senderAddress: `广东省 深圳市 ${id}号仓库`
  };
}

function order(): AppOrderDetailResponse {
  return {
    orderId: 123,
    orderNo: "ORDER123",
    status: "SHIPPED",
    source: "CART",
    productOriginalAmountCent: 1000,
    productAmountCent: 1000,
    couponDiscountCent: 0,
    freightCent: 0,
    payableAmountCent: 1000,
    paidAmountCent: 1000,
    receiverName: "测试用户",
    receiverPhone: "13800138000",
    receiverAddress: "四川省 成都市 收货地址",
    createdAt: "2026-09-01T12:00:00Z",
    items: [],
    shipments: [shipment(1), shipment(2)]
  };
}

function tracking(id: number): ShipmentTrackingResponse {
  return {
    shipmentId: id,
    orderId: 123,
    carrierCode: "SF",
    carrierName: "顺丰速运",
    trackingNo: `SF${id}`,
    querySupported: true,
    querySyncStatus: "SYNCED",
    logisticsStatus: "IN_TRANSIT",
    logisticsStatusText: "运输中",
    queryErrorCode: null,
    queryErrorMessage: null,
    pathSupported: true,
    pathSyncStatus: "SYNCED",
    pathErrorCode: null,
    pathErrorMessage: null,
    officialViewAvailable: true,
    pathItems: [
      { actionTime: 100, actionType: 1, actionMessage: `包裹${id}已揽收` },
      {
        actionTime: 200,
        actionType: 2,
        actionMessage: `包裹${id}到达成都转运中心`
      }
    ],
    lastAttemptAt: null,
    lastSyncedAt: null
  };
}

test("订单配送摘要从发货快照切换到最新真实轨迹，收货地址保持不变", () => {
  const detail = orderCenter.buildOrderDetailView(order());
  const initial = logistics.buildOrderDeliverySummary(detail)!;
  assert.equal(initial.originLabel, "发货地");
  assert.equal(initial.originText, "广东省 深圳市 2号仓库");
  const updated = logistics.buildOrderDeliverySummary(
    detail,
    undefined,
    tracking(2)
  )!;
  assert.equal(updated.originLabel, "最新进展");
  assert.equal(updated.originText, "包裹2到达成都转运中心");
  assert.equal(updated.destinationText, initial.destinationText);
  assert.equal(updated.packageText, "共 2 个包裹 · 当前包裹 2");
  assert.equal(
    logistics.buildOrderDeliverySummary(detail, undefined, tracking(1))!
      .originText,
    initial.originText
  );
});

test("无轨迹和旧订单无发货快照时降级，不将签收或其他包裹状态编造成位置", () => {
  const detail = orderCenter.buildOrderDetailView(order());
  const signed = {
    ...tracking(2),
    logisticsStatus: "SIGNED" as const,
    logisticsStatusText: "已签收",
    pathItems: []
  };
  const view = logistics.buildOrderDeliverySummary(detail, undefined, signed)!;
  assert.equal(view.statusText, "已签收");
  assert.equal(view.originText, detail.shipmentView!.senderAddress);
  detail.shipmentView!.senderAddress = "";
  assert.equal(
    logistics.buildOrderDeliverySummary(detail)!.originText,
    "商家已发货，等待物流更新"
  );
  assert.equal(
    logistics.buildOrderLogisticsUrl(123, 2),
    "/packages/logistics/detail/detail?order_id=123&shipment_id=2"
  );
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

// Execute the actual Page lifecycle with service doubles to exercise request races.
function page(
  sync: (
    _orderId: number,
    shipmentId: number
  ) => Promise<ShipmentTrackingResponse>
) {
  const code = ts.transpileModule(
    readFileSync(
      resolve(process.cwd(), "miniprogram/packages/logistics/detail/detail.ts"),
      "utf8"
    ),
    {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2020
      }
    }
  ).outputText;
  let instance: any;
  runInNewContext(code, {
    exports: {},
    require: (path: string) => {
      if (path.endsWith("features/order-center")) return orderCenter;
      if (path.endsWith("features/order-logistics")) return logistics;
      if (path.endsWith("utils/api-error")) return { isApiError: () => false };
      if (path.endsWith("services/order"))
        return {
          getOrderDetail: async () => order(),
          syncShipmentTracking: sync
        };
      throw new Error(`Unexpected module: ${path}`);
    },
    Page: (definition: any) => {
      instance = definition;
      instance.setData = (patch: unknown) =>
        Object.assign(instance.data, patch);
    }
  });
  instance.data.orderId = 123;
  return instance;
}

test("快速切换包裹时迟到的成功和失败都不能覆盖当前包裹", async () => {
  const first = deferred<ShipmentTrackingResponse>();
  const second = deferred<ShipmentTrackingResponse>();
  const instance = page((_orderId, id) =>
    id === 1 ? first.promise : second.promise
  );
  await instance.refreshDetail(); // Defaults to package 2.
  instance.onShipmentTap({ currentTarget: { dataset: { shipmentId: 1 } } });
  first.resolve(tracking(1));
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(
    instance.data.deliverySummary.originText,
    "包裹1到达成都转运中心"
  );
  second.reject(new Error("Old package failed"));
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(instance.data.trackingErrorText, "");
  assert.equal(instance.data.activeShipment.shipmentId, 1);
});

test("物流刷新失败保留地址和已有轨迹，页面卸载后丢弃响应", async () => {
  const pending = deferred<ShipmentTrackingResponse>();
  let calls = 0;
  const instance = page(async () => {
    calls += 1;
    if (calls === 1) return tracking(2);
    if (calls === 2) throw new Error("Offline");
    return pending.promise;
  });
  await instance.refreshDetail();
  await new Promise<void>((resolve) => setImmediate(resolve));
  await instance.refreshTracking();
  assert.equal(instance.data.trackingErrorText, "物流更新失败，请稍后重试");
  assert.equal(
    instance.data.deliverySummary.originText,
    "包裹2到达成都转运中心"
  );
  const request = instance.refreshTracking();
  instance.onUnload();
  pending.resolve({ ...tracking(2), logisticsStatusText: "不应出现" });
  await request;
  assert.equal(instance.data.deliverySummary.statusText, "运输中");
});
