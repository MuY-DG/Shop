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
  const loginStyles = readFileSync(
    resolve(sourceRoot, "pages/auth/login/login.less"),
    "utf8"
  );
  const loginConfig = JSON.parse(readFileSync(
    resolve(sourceRoot, "pages/auth/login/login.json"),
    "utf8"
  )) as { disableScroll?: boolean };
  assert.equal(loginConfig.disableScroll, true);
  assert.match(loginTemplate, /<scroll-view[\s\S]*class="login-content"[\s\S]*scroll-y="\{\{true\}\}"[\s\S]*>暂不登录<\/button>[\s\S]*<\/scroll-view>/);
  assert.match(loginStyles, /\.login-page\s*\{[\s\S]*height: 100vh;[\s\S]*overflow: hidden;[\s\S]*flex-direction: column/);
  assert.match(loginStyles, /\.login-main\s*\{[\s\S]*min-height: 0;[\s\S]*flex: 1;[\s\S]*overflow: hidden/);
  assert.match(loginStyles, /\.login-content\s*\{[\s\S]*width: 100%;[\s\S]*height: 100%/);
  assert.match(loginStyles, /\.login-content__inner\s*\{[\s\S]*min-height: 100%/);
  assert.doesNotMatch(loginTemplate, /class="login-footer"/);
  assert.doesNotMatch(loginTemplate, /绑定手机号，开启会员服务/);
  assert.doesNotMatch(loginTemplate, /授权手机号并登录/);
  assert.doesNotMatch(loginTemplate, /agreePrivacyAuthorization/);
  assert.match(loginTemplate, /open-type="getPhoneNumber"/);
  assert.doesNotMatch(loginTemplate, /隐私授权由微信官方弹窗提供/);
  assert.doesNotMatch(loginTemplate, /首次使用，请再次点击/);
  assert.doesNotMatch(loginTemplate, /老用户直接登录，新用户首次授权手机号/);
  assert.doesNotMatch(loginTemplate, /class="login-initializing"/);
  assert.doesNotMatch(loginTemplate, /login-loading-layer|login-loading-spinner/);
  const onLoadSource = loginLogic.slice(
    loginLogic.indexOf("onLoad("),
    loginLogic.indexOf("onUnload()")
  );
  assert.doesNotMatch(onLoadSource, /prepareLogin\(\)|prepareWechatLogin\(\)/);
  assert.match(
    loginLogic,
    /prepareWechatLogin\(\{[\s\S]{0,520}privacyPolicyAccepted: true[\s\S]{0,180}miniProgramEnv: APP_ENV_VERSION/
  );
  assert.match(loginLogic, /commitPreparedWechatLogin\(pending\)/);
  assert.doesNotMatch(loginLogic, /loginWithWechat\(\)/);
  assert.doesNotMatch(
    loginLogic,
    /onAgreementChange[\s\S]{0,180}this\.prepareLogin\(\)/
  );
  assert.doesNotMatch(loginLogic, /initializing/);
  assert.match(loginLogic, /loginPrepared/);
  assert.doesNotMatch(loginLogic, /wx\.requirePrivacyAuthorize/);
  assert.match(
    loginLogic,
    /async prepareLogin\(\) \{\s*if \(!this\.data\.agreed\) \{\s*showAgreementRequired\(\);\s*return;\s*\}/
  );
  assert.match(
    loginLogic,
    /onPrepareLoginTap\(\)[\s\S]{0,220}if \(!this\.data\.agreed\)[\s\S]{0,180}this\.prepareLogin\(\)/
  );
  assert.match(
    loginLogic,
    /onLoginTap[\s\S]{0,800}commitPreparedWechatLogin\(pending\)[\s\S]{0,160}this\.completeLogin\("登录成功"\)/
  );
});

test("我的页面保留金牌会员头像框、皇冠、文字和登录态条件", () => {
  const profileTemplate = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.wxml"),
    "utf8"
  );

  assert.match(
    profileTemplate,
    /<image\s+wx:if="\{\{loggedIn\}\}"\s+class="member-card__avatar-frame"[\s\S]*?src="\/assets\/images\/member-avatar-frame-v\.png"/
  );
  assert.match(
    profileTemplate,
    /<view wx:if="\{\{loggedIn\}\}" class="member-card__member-badge" aria-label="金牌会员">[\s\S]*?src="\/assets\/icons\/member-crown\.svg"[\s\S]*?<text>金牌会员<\/text>/
  );
});
