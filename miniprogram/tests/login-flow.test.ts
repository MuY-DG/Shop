import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildLoginUrl,
  isTabRoute,
  LOGIN_ROUTE,
  needsPhoneAuthorization,
  sanitizeLoginRedirect
} from "../miniprogram/features/login";
import { replaceWithExpiredSessionLogin } from "../miniprogram/utils/login-navigation";

test("登录跳转只接受小程序内部页面", () => {
  assert.equal(
    buildLoginUrl("/pages/product/detail/detail?id=12"),
    `${LOGIN_ROUTE}?redirect=${encodeURIComponent("/pages/product/detail/detail?id=12")}`
  );
  assert.equal(sanitizeLoginRedirect("https://example.com"), "");
  assert.equal(sanitizeLoginRedirect(LOGIN_ROUTE), "");
  assert.equal(buildLoginUrl("//pages/profile/profile"), LOGIN_ROUTE);
});

test("手机号只对尚未完成注册的用户请求授权", () => {
  const baseProfile = {
    userId: "1",
    nickname: "灶香集会员",
    openidMasked: "open****id"
  };
  assert.equal(needsPhoneAuthorization({
    ...baseProfile,
    phoneAuthorized: true,
    phoneNumberMasked: "138****5678"
  }), false);
  assert.equal(needsPhoneAuthorization({
    ...baseProfile,
    phoneAuthorized: false
  }), true);
  assert.equal(needsPhoneAuthorization(undefined), false);
});

test("登录完成后能区分 tabBar 与普通页面", () => {
  assert.equal(isTabRoute("/pages/profile/profile"), true);
  assert.equal(isTabRoute("/pages/cart/cart?from=login"), true);
  assert.equal(isTabRoute("/pages/order/list/list"), false);
});

test("会话失效时替换当前页面并保留登录后的返回地址", () => {
  const redirects: string[] = [];
  const toasts: string[] = [];
  Object.defineProperty(globalThis, "getCurrentPages", {
    configurable: true,
    value: () => [{
      route: "pages/account/profile/profile",
      options: { source: "member" }
    }],
    writable: true
  });
  Object.defineProperty(globalThis, "wx", {
    configurable: true,
    value: {
      redirectTo(options: WechatMiniprogram.RedirectToOption) {
        redirects.push(options.url);
      },
      showToast(options: WechatMiniprogram.ShowToastOption) {
        toasts.push(options.title);
      }
    } as WechatMiniprogram.Wx,
    writable: true
  });

  assert.equal(replaceWithExpiredSessionLogin(), true);
  assert.deepEqual(redirects, [
    buildLoginUrl("/pages/account/profile/profile?source=member")
  ]);
  assert.deepEqual(toasts, ["登录状态已失效，请重新登录"]);
  assert.equal(replaceWithExpiredSessionLogin(), false);
});
