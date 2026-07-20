import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import { syncCustomTabBar } from "../miniprogram/utils/tab-bar";

interface AppConfig {
  pages: string[];
  tabBar?: {
    custom?: boolean;
    list?: Array<{
      pagePath?: string;
      text?: string;
    }>;
  };
}

interface DetailPageConfig {
  enablePullDownRefresh?: boolean;
}

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("自定义底部导航注册五个可用的 Tab 根页面", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as AppConfig;
  const expectedTabs = [
    ["pages/index/index", "首页"],
    ["pages/category/category", "分类"],
    ["pages/message/message", "消息"],
    ["pages/cart/cart", "购物车"],
    ["pages/profile/profile", "我的"]
  ];

  assert.equal(appConfig.tabBar?.custom, true);
  assert.deepEqual(
    appConfig.tabBar?.list?.map((item) => [item.pagePath, item.text]),
    expectedTabs
  );

  expectedTabs.forEach(([pagePath]) => {
    assert.ok(pagePath);
    assert.ok(appConfig.pages.includes(pagePath));
    ["json", "ts", "wxml", "less"].forEach((extension) => {
      assert.equal(
        existsSync(resolve(sourceRoot, `${pagePath}.${extension}`)),
        true,
        `${pagePath}.${extension} should exist`
      );
    });
  });

  ["json", "ts", "wxml", "less"].forEach((extension) => {
    assert.equal(
      existsSync(resolve(sourceRoot, `custom-tab-bar/index.${extension}`)),
      true
    );
  });
});

test("Tab 页面显示时同步自定义导航选中项", () => {
  let selected = -1;
  syncCustomTabBar({
    getTabBar: () => ({
      setData(data) {
        selected = data.selected;
      }
    })
  }, 3);
  assert.equal(selected, 3);

  assert.doesNotThrow(() => syncCustomTabBar({}, 0));
});

test("商品详情关闭下拉刷新并将规格选择收进购买弹层", () => {
  const detailPageRoot = resolve(sourceRoot, "pages/product/detail/detail");
  const detailConfig = JSON.parse(
    readFileSync(`${detailPageRoot}.json`, "utf8")
  ) as DetailPageConfig;
  const detailTemplate = readFileSync(`${detailPageRoot}.wxml`, "utf8");

  assert.equal(detailConfig.enablePullDownRefresh, false);
  assert.doesNotMatch(detailTemplate, /<sku-selector|stock-text=|categoryName/);
  assert.match(detailTemplate, /data-mode="CART"/);
  assert.match(detailTemplate, /data-mode="BUY"/);
  assert.match(detailTemplate, /activeSheet === 'purchase'/);
});

test("商品轮播延后同步当前位置并在手势中断时恢复吸附", () => {
  const galleryTemplate = readFileSync(
    resolve(sourceRoot, "components/product-gallery/product-gallery.wxml"),
    "utf8"
  );
  const galleryLogic = readFileSync(
    resolve(sourceRoot, "components/product-gallery/product-gallery.ts"),
    "utf8"
  );

  assert.match(galleryTemplate, /bindanimationfinish="onAnimationFinish"/);
  assert.match(galleryTemplate, /bindtransition="onTransition"/);
  assert.match(galleryTemplate, /bindtouchcancel="onTouchCancel"/);
  assert.match(galleryTemplate, /duration="300"/);
  assert.match(galleryLogic, /galleryRuntime\(this\)\.pendingCurrent = current/);
  assert.match(galleryLogic, /swiperVisible: false, current/);
});
