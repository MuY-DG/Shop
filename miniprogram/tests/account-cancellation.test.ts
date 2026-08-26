import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import { buildAccountCancellationBlockers } from "../miniprogram/features/account-cancellation";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("account cancellation blockers only expose active obligations", () => {
  assert.deepEqual(buildAccountCancellationBlockers({
    eligible: false,
    activeOrderCount: 2,
    activePaymentCount: 0,
    activeRefundCount: 1,
    activeAfterSaleCount: 0
  }), [
    { key: "order", label: "进行中订单", count: 2 },
    { key: "refund", label: "待处理退款", count: 1 }
  ]);
});

test("账号注销页允许游客查看但禁用提交按钮", () => {
  const logic = readFileSync(
    resolve(sourceRoot, "pages/account/cancellation/cancellation.ts"),
    "utf8"
  );
  const template = readFileSync(
    resolve(sourceRoot, "pages/account/cancellation/cancellation.wxml"),
    "utf8"
  );

  assert.doesNotMatch(logic, /openLoginPage|loginRequested/);
  assert.match(logic, /onLoad\(\)[\s\S]{0,180}this\.loadNotice\(\)/);
  assert.match(template, /disabled="\{\{!notice \|\| !loggedIn\}\}"/);
  assert.match(template, /登录后可注销/);
  assert.match(template, /账号注销须知暂未配置/);
  assert.doesNotMatch(template, /当前未登录，仅可查看注销说明/);
});
