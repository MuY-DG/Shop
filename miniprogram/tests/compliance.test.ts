import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  buildLegalDocumentUrl,
  buildMerchantPublicationView,
  legalDocumentTitle,
  normalizeLegalDocument,
  parseLegalDocumentType
} from "../miniprogram/features/compliance";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("合规公开页只接受三种法律文档类型并生成内部路由", () => {
  assert.equal(parseLegalDocumentType("PRIVACY_POLICY"), "PRIVACY_POLICY");
  assert.equal(parseLegalDocumentType("USER_AGREEMENT"), "USER_AGREEMENT");
  assert.equal(parseLegalDocumentType("AFTER_SALE_POLICY"), "AFTER_SALE_POLICY");
  assert.equal(parseLegalDocumentType("../../admin"), undefined);
  assert.equal(
    buildLegalDocumentUrl("PRIVACY_POLICY"),
    "/pages/compliance/document/document?type=PRIVACY_POLICY"
  );
  assert.equal(legalDocumentTitle("AFTER_SALE_POLICY"), "售后服务政策");
});

test("隐私政策必须是当前已发布且内容完整的修订", () => {
  const document = normalizeLegalDocument({
    id: "101",
    documentType: "PRIVACY_POLICY",
    version: "2026.08.09",
    title: "MuYbaby个人信息保护政策",
    content: "第一条\n我们依法保护个人信息。",
    contentSha256: "a".repeat(64),
    status: "PUBLISHED",
    effectiveAt: "2026-08-09T00:00:00Z",
    publishedAt: "2026-08-09T00:00:00Z"
  }, "PRIVACY_POLICY");

  assert.equal(document?.version, "2026.08.09");
  assert.equal(document?.content.includes("依法保护"), true);
  assert.equal(normalizeLegalDocument({ ...document, status: "DRAFT" }, "PRIVACY_POLICY"), undefined);
  assert.equal(normalizeLegalDocument({ ...document, version: "" }, "PRIVACY_POLICY"), undefined);
  assert.equal(normalizeLegalDocument({ ...document, documentType: "USER_AGREEMENT" }, "PRIVACY_POLICY"), undefined);
});

test("商家资质视图不伪造缺失字段并保留证照有效期", () => {
  const view = buildMerchantPublicationView({
    id: "201",
    revisionNo: 3,
    status: "PUBLISHED",
    legalName: "成都示例食品有限公司",
    entityType: "有限责任公司",
    unifiedSocialCreditCode: "91510100TEST000001",
    businessAddress: "四川省成都市示例路 1 号",
    customerServicePhone: "400-000-0000",
    complaintPhone: "028-00000000",
    businessLicenseUrl: "https://assets.example.test/license.png",
    foodQualificationType: "食品经营许可证",
    foodQualificationNumber: "JY10000000000001",
    foodQualificationUrl: "https://assets.example.test/food.png",
    foodQualificationValidFrom: "2026-01-01",
    foodQualificationValidUntil: "2031-01-01",
    publishedAt: "2026-08-09T00:00:00Z"
  });

  assert.equal(view?.legalName, "成都示例食品有限公司");
  assert.equal(view?.foodQualificationValidity, "2026.01.01 至 2031.01.01");
  assert.equal(buildMerchantPublicationView({ status: "DRAFT" }), undefined);
  assert.equal(buildMerchantPublicationView({ status: "PUBLISHED" }), undefined);
});

test("小程序注册公开合规页并从我的页面提供免登录设置入口", () => {
  const appConfig = JSON.parse(readFileSync(resolve(sourceRoot, "app.json"), "utf8")) as {
    pages: string[];
  };
  assert.ok(appConfig.pages.includes("pages/compliance/merchant/merchant"));
  assert.ok(appConfig.pages.includes("pages/compliance/document/document"));

  [
    "pages/account/settings/settings",
    "pages/compliance/merchant/merchant",
    "pages/compliance/document/document"
  ].forEach((pagePath) => {
    ["json", "ts", "wxml", "less"].forEach((extension) => {
      assert.equal(existsSync(resolve(sourceRoot, `${pagePath}.${extension}`)), true);
    });
  });

  const profileLogic = readFileSync(resolve(sourceRoot, "pages/profile/profile.ts"), "utf8");
  assert.match(profileLogic, /label: "关于与协议"[\s\S]{0,220}kind: "public-route"/);
  assert.match(profileLogic, /kind === "public-route"[\s\S]{0,180}wx\.navigateTo/);
});

test("小程序隐私入口使用微信原生只读指引且登录不依赖后台政策", () => {
  const loginLogic = readFileSync(resolve(sourceRoot, "pages/auth/login/login.ts"), "utf8");
  const loginTemplate = readFileSync(resolve(sourceRoot, "pages/auth/login/login.wxml"), "utf8");
  const sessionLogic = readFileSync(resolve(sourceRoot, "services/session.ts"), "utf8");
  const settingsLogic = readFileSync(resolve(sourceRoot, "pages/account/settings/settings.ts"), "utf8");
  const settingsTemplate = readFileSync(resolve(sourceRoot, "pages/account/settings/settings.wxml"), "utf8");

  assert.match(loginLogic, /wx\.getPrivacySetting\(/);
  assert.match(loginLogic, /wx\.openPrivacyContract\(/);
  assert.match(loginTemplate, /catchtap="onPrivacyTap">\{\{privacyContractName\}\}/);
  assert.doesNotMatch(loginLogic, /getCurrentLegalDocument|policyVersion|policyReady/);
  assert.doesNotMatch(loginTemplate, /onPolicyRetry|policyErrorText|policyLoading/);
  assert.match(sessionLogic, /data: \{ code \}/);
  assert.doesNotMatch(sessionLogic, /privacyPolicyVersion|privacyPolicyAccepted|miniProgramEnv/);
  assert.match(settingsLogic, /key === "privacy"[\s\S]{0,120}wx\.openPrivacyContract\(/);
  assert.match(settingsTemplate, /隐私保护指引由微信小程序平台只读展示/);
});
