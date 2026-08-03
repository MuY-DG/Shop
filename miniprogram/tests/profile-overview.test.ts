import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  countBadge,
  guestProfileOverviewDisplay,
  overviewCount,
  profileOverviewDisplay
} from "../miniprogram/features/profile-overview";

const sourceRoot = resolve(process.cwd(), "miniprogram");

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
  assert.match(template, /service-presence--online/);
  assert.match(template, /service-item__badge/);
  assert.match(style, /\.order-shortcut__badge[\s\S]*background: #ff172b/);
  assert.match(style, /\.service-item__badge[\s\S]*background: #ff172b/);
  assert.match(tabStyle, /\.tab-bar__badge[\s\S]*background: #ff172b/);
});
