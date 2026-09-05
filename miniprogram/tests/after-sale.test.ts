import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  afterSaleItemRefundCeilingCent,
  afterSaleItemSelectableQuantity,
  afterSaleStatusText,
  afterSaleTypeText,
  buildAfterSaleApplyPayload,
  buildAfterSaleApplyUrl,
  buildAfterSaleDetailUrl,
  buildAfterSaleListUrl,
  buildAfterSaleView,
  canApplyAfterSale,
  createAfterSaleRequestKey,
  isActiveAfterSale,
  positiveAfterSaleId
} from "../miniprogram/features/after-sale";
import { buildOrderDetailView } from "../miniprogram/features/order-center";
import type {
  AfterSaleResponse,
  AfterSaleStatus,
  AfterSaleType
} from "../miniprogram/types/after-sale";
import type { AppOrderDetailResponse, OrderStatus } from "../miniprogram/types/order";

test("return refund selection uses shipped availability while refund only includes unshipped units", () => {
  const partial = { availableQuantity: 2, returnableQuantity: 1 };
  assert.equal(afterSaleItemSelectableQuantity(partial, "REFUND_ONLY"), 2);
  assert.equal(afterSaleItemSelectableQuantity(partial, "RETURN_REFUND"), 1);
  assert.equal(afterSaleItemSelectableQuantity({ ...partial, returnableQuantity: 0 }, "RETURN_REFUND"), 0);
  assert.equal(afterSaleItemSelectableQuantity({ ...partial, returnableQuantity: 9 }, "RETURN_REFUND"), 2);
});

function afterSale(
  status: AfterSaleStatus = "REQUESTED",
  afterSaleType: AfterSaleType = "REFUND_ONLY"
): AfterSaleResponse {
  return {
    id: 71,
    afterSaleNo: "AS2026072110000000000000000071",
    orderId: 101,
    orderNo: "ORD-101",
    userId: "9001",
    afterSaleType,
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
      : undefined,
    items: [{
      id: 91,
      orderItemId: 201,
      skuId: 301,
      productTitle: "牛油火锅底料",
      specText: "500g",
      requestedQuantity: 1,
      approvedQuantity: status === "REQUESTED" ? undefined : 1,
      requestedAmountCent: 6980,
      approvedAmountCent: status === "REQUESTED" ? undefined : 6980
    }],
    returnInfo: afterSaleType === "RETURN_REFUND"
      ? {
          contactName: "售后仓",
          contactPhone: "13800000000",
          province: "四川省",
          city: "成都市",
          district: "武侯区",
          detailAddress: "仓储路 1 号",
          returnDeadlineAt: "2026-07-28T10:00:00Z",
          merchantReceivedAt: "2026-07-29T11:15:30Z",
          deliveryCompanyCode: status === "RETURNING" ? "SF" : undefined,
          deliveryCompanyName: status === "RETURNING" ? "顺丰速运" : undefined,
          trackingNo: status === "RETURNING" ? "SF123" : undefined
        }
      : undefined,
    allowedActions: status === "REQUESTED"
      ? ["CANCEL"]
      : status === "WAITING_RETURN"
        ? ["CANCEL", "SUBMIT_RETURN_SHIPMENT"]
        : status === "RETURNING"
          ? ["UPDATE_RETURN_SHIPMENT"]
          : []
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
      lineAmountCent: 6980,
      reviewed: false,
      reviewable: true
    }]
  };
}

test("完整售后状态生成稳定文案、操作、进度和金额", () => {
  const requested = buildAfterSaleView(afterSale("REQUESTED"));
  assert.equal(requested.statusText, "待商家审核");
  assert.equal(requested.statusTone, "warning");
  assert.deepEqual(requested.progressSteps.map((step) => step.state), ["done", "current", "pending"]);
  assert.equal(requested.requestedAmountText, "¥69.80");
  assert.equal(requested.evidenceCountText, "1 张");
  assert.equal(requested.canCancel, true);
  assert.equal(requested.listTypeText, "退款");
  assert.equal(requested.cardStatusText, "售后处理中");
  assert.equal(requested.cardStatusDescription, "后台客服正在加速审核");
  assert.equal(requested.items[0]?.titleText, "牛油火锅底料");
  assert.equal(requested.items[0]?.specificationText, "500g");
  assert.equal(requested.items[0]?.requestedQuantityText, "申请数量 x1");
  assert.equal(requested.items[0]?.requestedAmountText, "¥69.80");
  assert.equal(requested.canDelete, false);

  const rejected = buildAfterSaleView(afterSale("REJECTED"));
  assert.equal(rejected.auditNote, "请补充清晰凭证");
  assert.equal(rejected.progressSteps[1]?.state, "error");
  assert.equal(rejected.cardStatusText, "退款申请已关闭");
  assert.equal(rejected.cardStatusDescription, "因审核不通过");

  const cancelled = buildAfterSaleView(afterSale("CANCELLED"));
  assert.equal(cancelled.cardStatusText, "退款申请已关闭");
  assert.equal(cancelled.cardStatusDescription, "因您主动取消退款");

  const refunded = buildAfterSaleView(afterSale("REFUNDED"));
  assert.equal(refunded.statusText, "退款已完成");
  assert.equal(refunded.refundAmountText, "¥69.80");
  assert.equal(refunded.refundedAtText, "2026-07-21 10:35:00");
  assert.equal(refunded.cardStatusText, "退款成功");
  assert.equal(refunded.cardStatusDescription, "原路返回支付金额¥69.80");
  assert.deepEqual(refunded.progressSteps.map((step) => step.state), ["done", "done", "done"]);
  assert.equal(refunded.canDelete, true);
  assert.equal(refunded.canReapply, false);

  const waitingReturn = buildAfterSaleView(afterSale("WAITING_RETURN", "RETURN_REFUND"));
  assert.equal(waitingReturn.statusText, "待寄回商品");
  assert.equal(waitingReturn.listTypeText, "退货");
  assert.equal(waitingReturn.cardStatusText, "退货处理中");
  assert.equal(waitingReturn.canSubmitReturnShipment, true);
  assert.equal(waitingReturn.returnAddressText, "售后仓 13800000000 四川省成都市武侯区仓储路 1 号");
  assert.equal(waitingReturn.returnDeadlineAtText, "2026-07-28 10:00:00");
  assert.equal(waitingReturn.merchantReceivedAtText, "2026-07-29 11:15:30");
  assert.deepEqual(
    waitingReturn.progressSteps.map((step) => step.state),
    ["done", "done", "current", "pending", "pending"]
  );

  const returning = buildAfterSaleView(afterSale("RETURNING", "RETURN_REFUND"));
  assert.equal(returning.canUpdateReturnShipment, true);
  assert.equal(returning.returnShipmentText, "顺丰速运 SF123");

  assert.equal(afterSaleStatusText("WAITING_INSPECTION"), "待商家验收");
  assert.equal(afterSaleStatusText("RETURN_REJECTED"), "退货验收未通过");
  assert.equal(afterSaleStatusText("CANCELLED"), "申请已取消");
  assert.equal(afterSaleStatusText("APPROVED"), "审核已通过");

  assert.equal(afterSaleStatusText("REFUND_FAILED"), "退款处理异常");
  assert.equal(afterSaleTypeText("REFUND_ONLY"), "退款（无需退货）");
  assert.equal(afterSaleTypeText("RETURN_REFUND"), "退货退款");
});

test("进行中售后阻止重复申请并允许终态后重新申请", () => {
  assert.equal(canApplyAfterSale("PAID"), true);
  assert.equal(canApplyAfterSale("SHIPPED", afterSale("REQUESTED")), false);
  assert.equal(canApplyAfterSale("COMPLETED", afterSale("REFUND_FAILED")), false);
  assert.equal(canApplyAfterSale("COMPLETED", afterSale("REJECTED")), true);
  assert.equal(canApplyAfterSale("REFUNDED", afterSale("REFUNDED")), false);
  assert.equal(isActiveAfterSale("WAITING_RETURN"), true);
  assert.equal(isActiveAfterSale("WAITING_INSPECTION"), true);
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
  assert.equal(blockedOrder.showAfterSaleAction, true);
  assert.equal(blockedOrder.afterSaleActionMode, "DETAIL");
  assert.equal(blockedOrder.afterSaleActionText, "售后详细");

  const retryOrder = buildOrderDetailView(order("COMPLETED", afterSale("REJECTED")));
  assert.equal(retryOrder.canApplyAfterSale, true);
  assert.equal(retryOrder.afterSaleActionMode, "DETAIL");
  assert.equal(retryOrder.afterSaleActionText, "售后详细");

  const failedRefundOrder = buildOrderDetailView(order("REFUNDING", afterSale("REFUND_FAILED")));
  assert.equal(failedRefundOrder.statusText, "退款待处理");
  assert.equal(failedRefundOrder.statusHeadline, "退款待处理");
  assert.equal(failedRefundOrder.statusDescription, "退款暂未完成，商家正在核查处理");
  assert.equal(failedRefundOrder.latestAfterSaleView?.statusText, "退款处理异常");

  const cancelledOrder = buildOrderDetailView(order("COMPLETED", afterSale("CANCELLED")));
  assert.equal(cancelledOrder.canApplyAfterSale, true);
  assert.equal(cancelledOrder.afterSaleActionMode, "APPLY");
  assert.equal(cancelledOrder.afterSaleActionText, "申请售后");

  const refundedOrder = buildOrderDetailView(order("REFUNDED", afterSale("REFUNDED")));
  assert.equal(refundedOrder.showAfterSaleAction, true);
  assert.equal(refundedOrder.afterSaleActionText, "退款成功");
});

test("同一申请意图在响应丢失后复用稳定幂等键", () => {
  const key = createAfterSaleRequestKey(101, 1_800_000_000_000, "retry01");
  assert.equal(key, "as-101-1800000000000-retry01");
  assert.equal(createAfterSaleRequestKey(101, 1_800_000_000_000, "retry01"), key);
  assert.throws(() => createAfterSaleRequestKey(0), /订单参数/);

  const sourceRoot = resolve(process.cwd(), "miniprogram");
  const applyLogic = readFileSync(
    resolve(sourceRoot, "pages/after-sale/apply/apply.ts"),
    "utf8"
  );
  assert.match(applyLogic, /requestKey:\s*createAfterSaleRequestKey\(orderId\)/);
  assert.match(applyLogic, /requestKey:\s*this\.data\.requestKey/);
  assert.doesNotMatch(applyLogic, /requestKey:\s*createAfterSaleRequestKey\(this\.data\.orderId\)/);
  assert.ok(
    applyLogic.indexOf("this.setData({ submitting: true })")
      < applyLogic.indexOf("if (!await confirmSubmit")
  );
});

test("售后申请携带按件自报金额并与服务端报价保持一致", () => {
  assert.deepEqual(buildAfterSaleApplyPayload({
    requestKey: "apply-101",
    quote: {
      orderId: 101,
      afterSaleType: "RETURN_REFUND",
      requestedAmountCent: 3490,
      quoteDigest: "digest-101",
      items: [{ orderItemId: 201, quantity: 1, requestedAmountCent: 3490 }]
    },
    items: [{ orderItemId: 201, quantity: 1, requestedAmountCent: 3490 }],
    reason: "  商品存在问题  ",
    description: "  包装破损  ",
    evidenceFileIds: [801, 801, "802", -1]
  }), {
    requestKey: "apply-101",
    quoteDigest: "digest-101",
    afterSaleType: "RETURN_REFUND",
    reason: "商品存在问题",
    requestedAmountCent: 3490,
    description: "包装破损",
    evidenceFileIds: [801, 802],
    items: [{ orderItemId: 201, quantity: 1, requestedAmountCent: 3490 }]
  });
  assert.throws(() => buildAfterSaleApplyPayload({
    requestKey: "apply-101",
    quote: null,
    items: [{ orderItemId: 201, quantity: 1, requestedAmountCent: 3490 }],
    reason: "",
    description: ""
  }), /原因/);
  assert.throws(() => buildAfterSaleApplyPayload({
    requestKey: "apply-101",
    quote: {
      orderId: 101,
      afterSaleType: "REFUND_ONLY",
      requestedAmountCent: 3490,
      quoteDigest: "digest-101",
      items: [{ orderItemId: 201, quantity: 1, requestedAmountCent: 3490 }]
    },
    items: [{ orderItemId: 201, quantity: 2, requestedAmountCent: 3490 }],
    reason: "其他原因",
    description: ""
  }), /重新获取报价/);
  assert.throws(() => buildAfterSaleApplyPayload({
    requestKey: "apply-101",
    quote: {
      orderId: 101,
      afterSaleType: "REFUND_ONLY",
      requestedAmountCent: 3490,
      quoteDigest: "digest-101",
      items: [{ orderItemId: 201, quantity: 1, requestedAmountCent: 3490 }]
    },
    items: [{ orderItemId: 201, quantity: 1 }],
    reason: "其他原因",
    description: ""
  }), /重新获取报价/);
});

test("按件可退上限与服务端分摊算法一致", () => {
  // 100 分摊到 3 件：第 1-2 件各 33，第 3 件收尾差值 34
  assert.equal(afterSaleItemRefundCeilingCent(100, 3, 0, 1), 33);
  assert.equal(afterSaleItemRefundCeilingCent(100, 3, 1, 1), 33);
  assert.equal(afterSaleItemRefundCeilingCent(100, 3, 2, 1), 34);
  assert.equal(afterSaleItemRefundCeilingCent(6980, 1, 0, 1), 6980);
  assert.equal(afterSaleItemRefundCeilingCent(0, 2, 0, 1), 0);
  assert.equal(afterSaleItemRefundCeilingCent(100, 0, 0, 1), 0);
  assert.equal(afterSaleItemRefundCeilingCent(100, 3, 2, 2), 0);
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
    subPackages?: Array<{ root: string; pages: string[] }>;
  };
  const pagePaths = [
    ...appConfig.pages,
    ...(appConfig.subPackages ?? []).flatMap(({ root, pages }) =>
      pages.map((pagePath) => `${root}/${pagePath}`)
    )
  ];
  [
    "pages/after-sale/apply/apply",
    "pages/after-sale/list/list",
    "pages/after-sale/detail/detail"
  ].forEach((pagePath) => {
    assert.ok(pagePaths.includes(pagePath));
    ["json", "ts", "wxml", "less"].forEach((extension) => {
      assert.equal(existsSync(resolve(sourceRoot, `${pagePath}.${extension}`)), true);
    });
  });

  const profileLogic = readFileSync(resolve(sourceRoot, "pages/profile/profile.ts"), "utf8");
  const orderDetailTemplate = readFileSync(resolve(sourceRoot, "pages/order/detail/detail.wxml"), "utf8");
  const afterSaleListTemplate = readFileSync(
    resolve(sourceRoot, "components/after-sale-list/after-sale-list.wxml"),
    "utf8"
  );
  const afterSaleDetailTemplate = readFileSync(
    resolve(sourceRoot, "pages/after-sale/detail/detail.wxml"),
    "utf8"
  );
  const serviceSource = readFileSync(resolve(sourceRoot, "services/after-sale.ts"), "utf8");
  const detailSource = readFileSync(resolve(sourceRoot, "pages/after-sale/detail/detail.ts"), "utf8");
  assert.match(profileLogic, /退款售后/);
  assert.match(orderDetailTemplate, /onAfterSaleActionTap/);
  assert.doesNotMatch(orderDetailTemplate, /退款售后|latestAfterSaleView\.statusDescription/);
  assert.match(afterSaleListTemplate, /订单 \{\{item\.orderNo\}\}/);
  assert.match(afterSaleListTemplate, /\{\{item\.listTypeText\}\}/);
  assert.match(afterSaleListTemplate, /申请数量|退款：|cardStatusText|cardStatusDescription|删除售后单|联系客服|重新申请/);
  assert.match(afterSaleDetailTemplate, /detail\.returnDeadlineAtText/);
  assert.match(afterSaleDetailTemplate, /detail\.merchantReceivedAtText/);
  assert.doesNotMatch(afterSaleDetailTemplate, /detail\.returnInfo\.(returnDeadlineAt|merchantReceivedAt)/);
  assert.match(serviceSource, /getAfterSaleEligibility/);
  assert.match(serviceSource, /quoteAfterSale/);
  assert.match(serviceSource, /cancelAfterSale/);
  assert.match(serviceSource, /submitReturnShipment/);
  assert.match(serviceSource, /deleteAfterSale/);
  assert.match(detailSource, /canSubmitReturnShipment/);
  assert.match(detailSource, /canUpdateReturnShipment/);
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
  assert.match(applyLogic, /sizeType:\s*\[["']compressed["']\]/);
  assert.doesNotMatch(applyLogic, /sizeType:\s*\[["']original["']\]/);
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
