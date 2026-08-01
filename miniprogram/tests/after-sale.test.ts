import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  afterSaleStatusText,
  buildAfterSaleApplyPayload,
  buildAfterSaleApplyUrl,
  buildAfterSaleDetailUrl,
  buildAfterSaleListUrl,
  buildAfterSaleView,
  canApplyAfterSale,
  isActiveAfterSale,
  positiveAfterSaleId
} from "../miniprogram/features/after-sale";
import { buildOrderDetailView } from "../miniprogram/features/order-center";
import type { AfterSaleResponse, AfterSaleStatus } from "../miniprogram/types/after-sale";
import type { AppOrderDetailResponse, OrderStatus } from "../miniprogram/types/order";

function afterSale(status: AfterSaleStatus = "REQUESTED"): AfterSaleResponse {
  return {
    id: 71,
    afterSaleNo: "AS2026072110000000000000000071",
    orderId: 101,
    orderNo: "ORD-101",
    userId: "9001",
    afterSaleType: "REFUND_ONLY",
    status,
    reason: "商品存在问题",
    description: "包装破损",
    requestedAmountCent: 6980,
    approvedAmountCent: status === "REQUESTED" || status === "REJECTED" ? undefined : 6980,
    auditNote: status === "REJECTED" ? "请补充清晰凭证" : undefined,
    reviewedAt: status === "REQUESTED" ? undefined : "2026-07-21T10:30:00Z",
    createdAt: "2026-07-21T10:00:00Z",
    evidenceFileIds: [801],
    evidenceFiles: [{
      fileId: 801,
      originalFilename: "破损照片.png",
      contentType: "image/png",
      sizeBytes: 1024,
      scope: "ATTACHMENT",
      mediaKind: "IMAGE",
      visibility: "PRIVATE",
      status: "ACTIVE"
    }],
    refundOrder: status === "REFUNDING" || status === "REFUNDED" || status === "REFUND_FAILED"
      ? {
          id: 601,
          afterSaleId: 71,
          orderId: 101,
          paymentOrderId: 501,
          outRefundNo: "REF-601",
          refundAmountCent: 6980,
          status: status === "REFUNDED" ? "SUCCESS" : status === "REFUND_FAILED" ? "FAILED" : "PROCESSING",
          callbackStatus: "PENDING",
          requestedAt: "2026-07-21T10:31:00Z",
          successAt: status === "REFUNDED" ? "2026-07-21T10:35:00Z" : undefined
        }
      : undefined
  };
}

function order(status: OrderStatus, latestAfterSale?: AfterSaleResponse): AppOrderDetailResponse {
  return {
    orderId: 101,
    orderNo: "ORD-101",
    status,
    source: "DIRECT",
    productOriginalAmountCent: 7980,
    productAmountCent: 6980,
    couponDiscountCent: 0,
    freightCent: 0,
    payableAmountCent: 6980,
    paidAmountCent: 6980,
    receiverName: "小灶",
    receiverPhone: "13800000000",
    receiverAddress: "四川省成都市灶香路 1 号",
    createdAt: "2026-07-21T09:00:00Z",
    latestAfterSale,
    items: [{
      orderItemId: 201,
      skuId: 301,
      spuId: 401,
      productTitle: "牛油火锅底料",
      skuCode: "SKU-301",
      originalPriceCent: 7980,
      unitPriceCent: 6980,
      retailUnitPriceCent: 6980,
      quantity: 1,
      lineOriginalAmountCent: 7980,
      lineAmountCent: 6980
    }]
  };
}

test("售后状态生成稳定文案、进度和金额", () => {
  const requested = buildAfterSaleView(afterSale("REQUESTED"));
  assert.equal(requested.statusText, "待商家审核");
  assert.equal(requested.statusTone, "warning");
  assert.deepEqual(requested.progressSteps.map((step) => step.state), ["done", "current", "pending"]);
  assert.equal(requested.requestedAmountText, "¥69.80");
  assert.equal(requested.evidenceCountText, "1 张");

  const rejected = buildAfterSaleView(afterSale("REJECTED"));
  assert.equal(rejected.auditNote, "请补充清晰凭证");
  assert.equal(rejected.progressSteps[1]?.state, "error");

  const refunded = buildAfterSaleView(afterSale("REFUNDED"));
  assert.equal(refunded.statusText, "退款已完成");
  assert.equal(refunded.refundAmountText, "¥69.80");
  assert.equal(refunded.refundedAtText, "2026-07-21 10:35");
  assert.deepEqual(refunded.progressSteps.map((step) => step.state), ["done", "done", "done"]);

  assert.equal(afterSaleStatusText("REFUND_FAILED"), "退款处理异常");
});

test("整单退款资格阻止重复申请并允许被拒后重新申请", () => {
  assert.equal(canApplyAfterSale("PAID"), true);
  assert.equal(canApplyAfterSale("SHIPPED", afterSale("REQUESTED")), false);
  assert.equal(canApplyAfterSale("COMPLETED", afterSale("REFUND_FAILED")), false);
  assert.equal(canApplyAfterSale("COMPLETED", afterSale("REJECTED")), true);
  assert.equal(canApplyAfterSale("REFUNDED", afterSale("REFUNDED")), false);
  assert.equal(isActiveAfterSale("APPROVED"), true);
  assert.equal(isActiveAfterSale("REJECTED"), false);

  const freshOrder = buildOrderDetailView(order("PAID"));
  assert.equal(freshOrder.canApplyAfterSale, true);
  assert.equal(freshOrder.hasAfterSale, false);

  const blockedOrder = buildOrderDetailView(order("SHIPPED", afterSale("REQUESTED")));
  assert.equal(blockedOrder.canApplyAfterSale, false);
  assert.equal(blockedOrder.canConfirmReceipt, false);
  assert.equal(blockedOrder.hasAfterSale, true);
  assert.equal(blockedOrder.latestAfterSaleView?.statusText, "待商家审核");

  const retryOrder = buildOrderDetailView(order("COMPLETED", afterSale("REJECTED")));
  assert.equal(retryOrder.canApplyAfterSale, true);
  assert.equal(retryOrder.afterSaleActionText, "重新申请售后");
});

test("售后申请固定为整单全额仅退款并规范用户输入", () => {
  assert.deepEqual(buildAfterSaleApplyPayload({
    reason: "  商品存在问题  ",
    requestedAmountCent: 6980,
    description: "  包装破损  ",
    evidenceFileIds: [801, 801, "802", -1]
  }), {
    afterSaleType: "REFUND_ONLY",
    reason: "商品存在问题",
    requestedAmountCent: 6980,
    description: "包装破损",
    evidenceFileIds: [801, 802]
  });
  assert.throws(() => buildAfterSaleApplyPayload({
    reason: "",
    requestedAmountCent: 6980
  }), /原因/);
  assert.throws(() => buildAfterSaleApplyPayload({
    reason: "其他原因",
    requestedAmountCent: 0
  }), /金额/);
});

test("售后路由拒绝可疑 ID 并注册三个真实页面", () => {
  assert.equal(positiveAfterSaleId("71"), 71);
  assert.equal(positiveAfterSaleId("7e1"), 0);
  assert.equal(buildAfterSaleListUrl(), "/pages/after-sale/list/list");
  assert.equal(buildAfterSaleApplyUrl(101), "/pages/after-sale/apply/apply?order_id=101");
  assert.equal(buildAfterSaleDetailUrl(71), "/pages/after-sale/detail/detail?after_sale_id=71");
  assert.throws(() => buildAfterSaleApplyUrl(0), /无效/);

  const sourceRoot = resolve(process.cwd(), "miniprogram");
  const appConfig = JSON.parse(readFileSync(resolve(sourceRoot, "app.json"), "utf8")) as {
    pages: string[];
  };
  [
    "pages/after-sale/apply/apply",
    "pages/after-sale/list/list",
    "pages/after-sale/detail/detail"
  ].forEach((pagePath) => {
    assert.ok(appConfig.pages.includes(pagePath));
    ["json", "ts", "wxml", "less"].forEach((extension) => {
      assert.equal(existsSync(resolve(sourceRoot, `${pagePath}.${extension}`)), true);
    });
  });

  const profileLogic = readFileSync(resolve(sourceRoot, "pages/profile/profile.ts"), "utf8");
  const orderDetailTemplate = readFileSync(resolve(sourceRoot, "pages/order/detail/detail.wxml"), "utf8");
  assert.match(profileLogic, /退款售后/);
  assert.match(orderDetailTemplate, /onApplyAfterSaleTap/);
  assert.match(orderDetailTemplate, /onAfterSaleDetailTap/);
});

test("小程序售后凭证使用选择器压缩结果并由云端统一处理", () => {
  const sourceRoot = resolve(process.cwd(), "miniprogram");
  const applyLogic = readFileSync(
    resolve(sourceRoot, "pages/after-sale/apply/apply.ts"),
    "utf8"
  );
  const applyTemplate = readFileSync(
    resolve(sourceRoot, "pages/after-sale/apply/apply.wxml"),
    "utf8"
  );

  assert.match(applyLogic, /wx\.chooseMedia\(/);
  assert.match(applyLogic, /sizeType:\s*\["compressed"\]/);
  assert.doesNotMatch(applyLogic, /sizeType:\s*\["original"\]/);
  assert.doesNotMatch(applyLogic, /wx\.compressImage/);
  assert.match(applyTemplate, /最多 3 张清晰图片，单张不超过 5MB/);

  const profileLogic = readFileSync(
    resolve(sourceRoot, "pages/account/profile/profile.ts"),
    "utf8"
  );
  const profileTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/profile/profile.wxml"),
    "utf8"
  );
  assert.doesNotMatch(profileLogic, /wx\.(chooseImage|chooseMedia|compressImage)/);
  assert.doesNotMatch(profileTemplate, /chooseImage|chooseMedia/);
  assert.match(profileTemplate, /open-type="chooseAvatar"/);
});
