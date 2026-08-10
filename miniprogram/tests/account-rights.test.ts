import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  ACCOUNT_RIGHTS_ROUTE,
  accountRightsStatusText,
  buildAccountRightsRequestView,
  normalizeAccountRightsNote,
  validateAccountRightsNote
} from "../miniprogram/features/account-rights";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("账户权利状态保持独立且只有待处理申请可以由用户撤回", () => {
  const base = {
    id: "190000000000000001",
    userId: "190000000000000002",
    userNickname: "用户",
    userStatus: "ENABLED",
    requestType: "ACCOUNT_CANCELLATION" as const,
    status: "PENDING" as const,
    retainedDataCategories: ["订单履约记录"],
    version: 3,
    createdAt: "2026-08-09T00:00:00Z",
    updatedAt: "2026-08-09T00:00:00Z"
  };
  const pending = buildAccountRightsRequestView(base);
  assert.equal(pending.canWithdraw, true);
  assert.equal(pending.retainedDataCategoriesText, "订单履约记录");
  assert.equal(buildAccountRightsRequestView({ ...base, status: "IN_REVIEW" }).canWithdraw, false);
  assert.equal(accountRightsStatusText("COMPLETED"), "已完成");
});

test("账户权利补充说明只做收尾规范化且有明确长度限制", () => {
  assert.equal(normalizeAccountRightsNote("  需要   更正  "), "需要 更正");
  assert.equal(validateAccountRightsNote("正常说明"), undefined);
  assert.match(validateAccountRightsNote("字".repeat(1001))!, /1000/);
});

test("注销 fresh wx.login 只在明确确认后的提交路径调用", () => {
  const pageSource = readFileSync(
    resolve(sourceRoot, "pages/account/rights/rights.ts"),
    "utf8"
  );
  const settingsSource = readFileSync(
    resolve(sourceRoot, "pages/account/settings/settings.ts"),
    "utf8"
  );
  assert.equal(ACCOUNT_RIGHTS_ROUTE, "/pages/account/rights/rights");
  assert.match(pageSource, /wx\.showModal\([\s\S]*result\.confirm[\s\S]*this\.submitNow\(\)/);
  assert.match(pageSource, /submitNow\([\s\S]*requestFreshWechatCode\(\)/);
  assert.doesNotMatch(pageSource, /onLoad\([\s\S]{0,300}wx\.login/);
  assert.match(pageSource, /withdrawAccountRightsRequest\(request\.id, \{ version: request\.version \}\)/);
  assert.match(settingsSource, /label: "账户注销与个人信息权利"/);
  ["json", "ts", "wxml", "less"].forEach((extension) => {
    assert.equal(
      existsSync(resolve(sourceRoot, `pages/account/rights/rights.${extension}`)),
      true
    );
  });
});
