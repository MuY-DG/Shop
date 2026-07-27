import assert from "node:assert/strict";
import { test } from "node:test";

import {
  handleWechatReceiptAppShow,
  openWechatReceiptConfirmation,
  type WechatReceiptRuntime
} from "../miniprogram/features/wechat-order-receipt";

type BusinessViewOptions = Parameters<
  NonNullable<WechatReceiptRuntime["openBusinessView"]>
>[0];

test("微信确认收货组件使用支付单号并只接受官方回跳成功结果", async () => {
  let captured: BusinessViewOptions | undefined;
  const confirmation = openWechatReceiptConfirmation(" 4200000000000000001 ", {
    openBusinessView(options) {
      captured = options;
    }
  });

  assert.equal(captured?.businessType, "weappOrderConfirm");
  assert.deepEqual(captured?.extraData, {
    transaction_id: "4200000000000000001"
  });
  assert.equal(handleWechatReceiptAppShow({
    referrerInfo: {
      appId: "wx1183b055aeec94d1",
      extraData: {
        status: "success",
        req_extradata: {
          transaction_id: "4200000000000000001"
        }
      }
    }
  }), true);
  assert.deepEqual(await confirmation, { outcome: "SUCCESS" });
});

test("用户取消微信确认收货时不会继续确认本地订单", async () => {
  const confirmation = openWechatReceiptConfirmation("4200000000000000002", {
    openBusinessView() {
      // 等待 App.onShow 接收微信组件回跳。
    }
  });

  handleWechatReceiptAppShow({
    referrerInfo: {
      appId: "wx1183b055aeec94d1",
      extraData: {
        status: "cancel",
        req_extradata: {
          transaction_id: "4200000000000000002"
        }
      }
    }
  });

  assert.deepEqual(await confirmation, { outcome: "CANCELLED" });
});

test("交易号不匹配或回跳来源异常时交由后台权威查询复核", async () => {
  const mismatch = openWechatReceiptConfirmation("4200000000000000003", {
    openBusinessView() {
      // 等待回跳。
    }
  });
  handleWechatReceiptAppShow({
    referrerInfo: {
      appId: "wx1183b055aeec94d1",
      extraData: {
        status: "success",
        req_extradata: {
          transaction_id: "different-transaction"
        }
      }
    }
  });
  assert.deepEqual(await mismatch, { outcome: "UNKNOWN" });

  const untrustedSource = openWechatReceiptConfirmation("4200000000000000004", {
    openBusinessView() {
      // 等待回跳。
    }
  });
  handleWechatReceiptAppShow({
    referrerInfo: {
      appId: "untrusted-app-id",
      extraData: { status: "success" }
    }
  });
  assert.deepEqual(await untrustedSource, { outcome: "UNKNOWN" });
});

test("不支持组件的微信版本给出可操作提示", async () => {
  const result = await openWechatReceiptConfirmation(
    "4200000000000000005",
    {}
  );

  assert.equal(result.outcome, "FAILED");
  assert.match(result.message || "", /升级微信/);
});
