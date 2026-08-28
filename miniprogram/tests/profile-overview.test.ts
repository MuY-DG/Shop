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
    /userId: loggedIn && session\.user \? session\.user\.userId : ""/
  );
  assert.match(pageSource, /userId: ""/);
  assert.doesNotMatch(pageSource, /已绑定手机|phoneNumberMasked|欢迎回来，会员服务已为你开启/);
  assert.doesNotMatch(pageSource + template, /ID[:：]/);
  assert.match(
    template,
    /<view\s+wx:if="\{\{loggedIn\}\}"\s+class="member-card__copy member-card__copy--id"\s+aria-label="用户 ID \{\{userId\}\}"/
  );
  assert.match(template, /class="member-card__id-label" aria-hidden="true">ID<\/view>/);
  assert.match(template, /class="member-card__id-value">\{\{userId\}\}<\/text>/);
  assert.match(template, /<view wx:else class="member-card__copy">登录后查看订单与会员服务<\/view>/);
});

test("用户名和 ID 整组下移且不改变头像与下方容器的布局占位", () => {
  const style = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.less"),
    "utf8"
  );
  const bodyStyle = style.match(/\.member-card__body\s*\{([^}]+)\}/)?.[1] || "";

  assert.match(bodyStyle, /position: relative;/);
  assert.match(bodyStyle, /top: 8rpx;/);
});

test("用户 ID 使用独立圆形徽标与小号编号，保留黑金渐变和长编号溢出保护", () => {
  const style = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.less"),
    "utf8"
  );
  const baseCopyStyle = style.match(/\.member-card__copy\s*\{([^}]+)\}/)?.[1] || "";
  const idStyle = style.match(/\.member-card__copy--id\s*\{([^}]+)\}/)?.[1] || "";
  const labelStyle = style.match(/\.member-card__id-label\s*\{([^}]+)\}/)?.[1] || "";
  const valueStyle = style.match(/\.member-card__id-value\s*\{([^}]+)\}/)?.[1] || "";

  assert.match(baseCopyStyle, /color: #000000;/);
  assert.doesNotMatch(baseCopyStyle, /background:|box-shadow:|padding:/);
  assert.match(baseCopyStyle, /overflow: hidden;/);
  assert.match(baseCopyStyle, /text-overflow: ellipsis;/);
  assert.match(baseCopyStyle, /white-space: nowrap;/);
  assert.match(idStyle, /position: relative;/);
  assert.match(idStyle, /top: 4rpx;/);
  assert.match(idStyle, /display: inline-flex;/);
  assert.match(idStyle, /max-width: 100%;/);
  assert.match(idStyle, /box-sizing: border-box;/);
  assert.match(idStyle, /margin-top: 12rpx;/);
  assert.match(idStyle, /padding: 0 9rpx 0 4rpx;/);
  assert.match(idStyle, /gap: 6rpx;/);
  assert.match(idStyle, /font-size: 17rpx;/);
  assert.match(idStyle, /font-weight: 600;/);
  assert.match(idStyle, /line-height: 1\.2;/);
  assert.match(idStyle, /color: #e9cf9b;/);
  assert.match(idStyle, /border: 1rpx solid rgba\(212, 170, 91, 0\.42\);/);
  assert.match(idStyle, /background: linear-gradient\(90deg, #1c1b19 0%, #3a3224 52%, #26231d 100%\);/);
  assert.match(labelStyle, /width: 28rpx;/);
  assert.match(labelStyle, /height: 28rpx;/);
  assert.match(labelStyle, /border-radius: 50%;/);
  assert.match(labelStyle, /border: 1rpx solid #f7dfa9;/);
  assert.match(labelStyle, /flex: none;/);
  assert.match(labelStyle, /font-size: 20rpx;/);
  assert.match(labelStyle, /color: #342817;/);
  assert.match(labelStyle, /background: linear-gradient\(135deg, #f8e6bd 0%, #d3ad68 100%\);/);
  assert.match(valueStyle, /min-width: 0;/);
  assert.match(valueStyle, /overflow: hidden;/);
  assert.match(valueStyle, /text-overflow: ellipsis;/);
  assert.match(valueStyle, /white-space: nowrap;/);
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
});

test("我的页面三个快捷容器统一高度和圆角并移除外层阴影边框", () => {
  const style = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.less"),
    "utf8"
  );
  const tokens = readFileSync(resolve(sourceRoot, "styles/tokens.less"), "utf8");
  const cardStyle = style.match(
    /\.account-metrics,\s*\.order-center-card,\s*\.service-card\s*\{([^}]+)\}/
  )?.[1] || "";
  const metricsStyle = style.match(/\.account-metrics\s*\{([^}]+)\}/)?.[1] || "";

  assert.match(cardStyle, /height: 180rpx;/);
  assert.match(cardStyle, /border: 0;/);
  assert.match(cardStyle, /border-radius: @radius-md;/);
  assert.match(cardStyle, /box-shadow: none;/);
  assert.doesNotMatch(metricsStyle, /(?:min-|max-)?height:/);
  assert.match(tokens, /@radius-md: 20rpx;/);
});

test("我的页面不再注册底部商品列表或保留商品加载与交互逻辑", () => {
  const pageSource = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );
  const template = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.wxml"),
    "utf8"
  );
  const pageConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "pages/profile/profile.json"), "utf8")
  ) as { usingComponents?: Record<string, string>; onReachBottomDistance?: number };

  assert.equal(pageConfig.usingComponents?.["catalog-browser"], undefined);
  assert.equal(pageConfig.onReachBottomDistance, undefined);
  assert.doesNotMatch(template, /catalog-browser|product-card|profile-catalog/);
  assert.doesNotMatch(
    pageSource,
    /CatalogBrowserInstance|ProductSelectEvent|catalogShown|onProductSelect|onCartChange|onReachBottom|selectComponent|loadMore|silentRefresh|refreshCustomTabBarCartCount/
  );
  assert.doesNotMatch(pageSource, /features\/product-catalog|services\/product/);
  assert.match(pageSource, /syncCustomTabBar\(this, 3\)/);
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
