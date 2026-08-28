import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  countBadge,
  guestProfileOverviewDisplay,
  overviewCount,
  profileOverviewDisplay,
  profileOverviewFingerprint
} from "../miniprogram/features/profile-overview";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("我的页面登录后展示用户 ID，未登录保留登录提示", () => {
  const pageSource = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );
  const template = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.wxml"),
    "utf8"
  );

  assert.match(
    pageSource,
    /memberCopy: loggedIn && session\.user\s*\? `ID：\$\{session\.user\.userId\}`\s*: "登录后查看订单与会员服务"/
  );
  assert.doesNotMatch(pageSource, /已绑定手机|phoneNumberMasked|欢迎回来，会员服务已为你开启/);
  assert.match(template, /class="member-card__copy">\{\{memberCopy\}\}<\/view>/);
});

test("未登录概览隐藏个人数量且优惠券保留单位", () => {
  const display = guestProfileOverviewDisplay();

  assert.equal(display.couponValue, "***张");
  assert.equal(display.favoriteValue, "***");
  assert.equal(display.browseHistoryValue, "***");
  assert.deepEqual(display.orderBadges, ["", "", "", "", ""]);
  assert.equal(display.customerServiceBadge, "");
  assert.equal(display.customerServiceOnline, false);
});

test("真实概览数量生成订单、客服角标并限制为 99+", () => {
  const display = profileOverviewDisplay({
    availableCouponCount: 3,
    favoriteCount: 12,
    browseHistoryCount: 108,
    unpaidOrderCount: 0,
    toShipOrderCount: 2,
    toReceiveOrderCount: 100,
    toReviewOrderCount: -1,
    activeAfterSaleCount: 1,
    customerServiceUnreadCount: 5,
    customerServiceOnline: true
  });

  assert.equal(display.couponValue, "3张");
  assert.equal(display.favoriteValue, "12");
  assert.equal(display.browseHistoryValue, "108");
  assert.deepEqual(display.orderBadges, ["", "2", "99+", "", "1"]);
  assert.equal(display.customerServiceBadge, "5");
  assert.equal(display.customerServiceOnline, true);
  assert.equal(countBadge(0), "");
  assert.equal(countBadge(100), "99+");
  assert.equal(overviewCount(Number.NaN), 0);
  assert.equal(
    profileOverviewFingerprint(display),
    profileOverviewFingerprint({ ...display })
  );
  assert.notEqual(
    profileOverviewFingerprint(display),
    profileOverviewFingerprint({ ...display, favoriteValue: "13" })
  );
});

test("我的页面从聚合接口取数并统一角标颜色", () => {
  const pageSource = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );
  const template = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.wxml"),
    "utf8"
  );
  const style = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.less"),
    "utf8"
  );
  const pageConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "pages/profile/profile.json"), "utf8")
  ) as { usingComponents?: Record<string, string>; onReachBottomDistance?: number };
  const catalogSource = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.ts"),
    "utf8"
  );
  const catalogTemplate = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.wxml"),
    "utf8"
  );
  const catalogStyle = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.less"),
    "utf8"
  );
  const tabStyle = readFileSync(
    resolve(sourceRoot, "custom-tab-bar/index.less"),
    "utf8"
  );

  assert.match(pageSource, /getMyOverview\(\)/);
  assert.match(pageSource, /getCustomerServicePresence\(\)/);
  assert.match(pageSource, /profileOverviewDisplay\(overview\)/);
  assert.match(pageSource, /label: "收货地址",[\s\S]{0,100}iconPath: "\/assets\/icons\/profile-location\.svg"/);
  assert.doesNotMatch(pageSource, /label: "收货地址",[\s\S]{0,100}location-on-outline-rounded\.svg/);
  assert.doesNotMatch(template, /我的订单|查看全部订单|order-center-card__heading/);
  assert.match(
    pageSource,
    /group: "ALL",[\s\S]{0,100}label: "全部订单",[\s\S]{0,100}order-all\.svg/
  );
  assert.match(
    pageSource,
    /label: "退款售后",[\s\S]{0,160}order-after-sale\.svg[\s\S]{0,160}ACCOUNT_ROUTES\.afterSales/
  );
  assert.doesNotMatch(pageSource, /label: "售后服务"|account-after-sale\.svg/);
  assert.equal(existsSync(resolve(sourceRoot, "assets/icons/order-all.svg")), true);
  assert.equal(existsSync(resolve(sourceRoot, "assets/icons/account-after-sale.svg")), false);
  assert.equal(existsSync(resolve(sourceRoot, "assets/icons/chevron-right-service.svg")), false);
  assert.match(template, /service-presence--online/);
  assert.match(template, /service-item__badge/);
  assert.doesNotMatch(template, /service-item__chevron|chevron-right-service\.svg/);
  assert.match(style, /\.account-metrics\s*\{[\s\S]*background: @color-surface-white;/);
  assert.match(style, /\.order-center-card,\s*\.service-card\s*\{[\s\S]*background: @color-surface-white;/);
  assert.match(
    style,
    /\.service-list\s*\{[\s\S]*display: grid;[\s\S]*grid-template-columns: repeat\(5, minmax\(0, 1fr\)\);/
  );
  assert.match(
    style,
    /\.service-item\s*\{[\s\S]*flex-direction: column;[\s\S]*align-items: center;/
  );
  assert.doesNotMatch(style, /service-item \+ \.service-item::before|service-item__chevron/);
  assert.doesNotMatch(style, /backdrop-filter/);
  assert.match(style, /\.order-shortcut__badge[\s\S]*background: #ff172b/);
  assert.match(style, /\.service-item__badge[\s\S]*background: #ff172b/);
  assert.match(tabStyle, /\.tab-bar__badge[\s\S]*background: #ff172b/);
  assert.equal(
    pageConfig.usingComponents?.["catalog-browser"],
    "/components/catalog-browser/catalog-browser"
  );
  assert.equal(pageConfig.onReachBottomDistance, 160);
  assert.match(
    template,
    /<catalog-browser[\s\S]*id="profile-catalog"[\s\S]*embedded="\{\{true\}\}"[\s\S]*bindproductselect="onProductSelect"[\s\S]*bindcartchange="onCartChange"/
  );
  assert.match(pageSource, /onReachBottom\(\)[\s\S]{0,100}catalog\(\)\?\.loadMore\(\)/);
  assert.match(pageSource, /onProductSelect[\s\S]{0,220}pages\/product\/detail\/detail\?id=/);
  assert.match(pageSource, /onCartChange\(\)[\s\S]{0,100}refreshCustomTabBarCartCount\(this\)/);
  assert.match(catalogSource, /embedded:\s*\{[\s\S]{0,100}type: Boolean/);
  assert.match(catalogSource, /sortMode: "COMPREHENSIVE"/);
  assert.match(catalogSource, /viewMode: "grid"/);
  assert.match(
    catalogSource,
    /attached\(\)[\s\S]{0,500}this\.data\.embedded[\s\S]{0,120}this\.loadFirstPage\(\)/
  );
  assert.match(catalogTemplate, /<view wx:if="\{\{!embedded\}\}" class="catalog-tools">/);
  assert.match(catalogTemplate, /catalog-grid catalog-grid--\{\{viewMode\}\}/);
  assert.match(
    catalogStyle,
    /\.catalog-browser--embedded \.catalog-content\s*\{[\s\S]*min-height: 0;[\s\S]*padding: 24rpx 0 0;/
  );
});

test("我的页面返回时保留同一用户的已有概览并静默刷新", () => {
  const pageSource = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );
  const loadOverviewSource = pageSource.slice(
    pageSource.indexOf("async loadOverview"),
    pageSource.indexOf("onMemberTap")
  );

  assert.match(
    pageSource,
    /const sameOverviewOwner = this\.data\.overviewOwnerKey === overviewOwnerKey/
  );
  assert.match(
    pageSource,
    /const overviewState = sameOverviewOwner\s*\? \{\}\s*: \{/
  );
  assert.match(pageSource, /profileOverviewState\(initialOverviewDisplay\)/);
  assert.doesNotMatch(
    loadOverviewSource,
    /loadingProfileOverviewDisplay\(\)/
  );
  assert.match(
    loadOverviewSource,
    /this\.data\.overviewFingerprint !== overviewFingerprint/
  );
});
