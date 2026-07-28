import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import { enableNativeShareMenu } from "../miniprogram/utils/share";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("公开内容页启用微信原生好友与朋友圈分享入口", () => {
  let menus: string[] = [];
  Object.defineProperty(globalThis, "wx", {
    configurable: true,
    value: {
      showShareMenu(options: WechatMiniprogram.ShowShareMenuOption) {
        menus = options.menus || [];
      }
    } as unknown as WechatMiniprogram.Wx,
    writable: true
  });

  enableNativeShareMenu();
  assert.deepEqual(menus, ["shareAppMessage", "shareTimeline"]);

  [
    "pages/index/index.ts",
    "pages/category/category.ts",
    "pages/product/list/list.ts",
    "pages/product/detail/detail.ts"
  ].forEach((pagePath) => {
    const source = readFileSync(resolve(sourceRoot, pagePath), "utf8");
    assert.match(source, /enableNativeShareMenu\(\)/);
    assert.match(source, /onShareAppMessage\(\)/);
    assert.match(source, /onShareTimeline\(\)/);
  });
});

test("登录页保持单一界面且仅新用户由手机号能力触发官方隐私授权", () => {
  const loginLogic = readFileSync(
    resolve(sourceRoot, "pages/auth/login/login.ts"),
    "utf8"
  );
  const loginTemplate = readFileSync(
    resolve(sourceRoot, "pages/auth/login/login.wxml"),
    "utf8"
  );
  assert.doesNotMatch(loginTemplate, /绑定手机号，开启会员服务/);
  assert.doesNotMatch(loginTemplate, /授权手机号并登录/);
  assert.doesNotMatch(loginTemplate, /agreePrivacyAuthorization/);
  assert.match(loginTemplate, /open-type="getPhoneNumber"/);
  assert.doesNotMatch(loginTemplate, /隐私授权由微信官方弹窗提供/);
  assert.doesNotMatch(loginTemplate, /首次使用，请再次点击/);
  assert.doesNotMatch(loginTemplate, /老用户直接登录，新用户首次授权手机号/);
  assert.doesNotMatch(loginTemplate, /class="login-initializing"/);
  assert.match(
    loginTemplate,
    /wx:if="\{\{initializing\}\}" class="login-loading-layer"[\s\S]*class="login-loading-spinner"/
  );
  assert.match(loginLogic, /onLoad[\s\S]*this\.prepareLogin\(\)/);
  assert.doesNotMatch(
    loginLogic,
    /onAgreementChange[\s\S]{0,180}this\.prepareLogin\(\)/
  );
  assert.match(loginLogic, /initializing: true/);
  assert.match(loginLogic, /initializing: false/);
  assert.match(loginLogic, /loginPrepared/);
  assert.doesNotMatch(loginLogic, /wx\.requirePrivacyAuthorize/);
  assert.match(loginLogic, /onLoginTap[\s\S]{0,320}this\.completeLogin\("登录成功"\)/);
});
