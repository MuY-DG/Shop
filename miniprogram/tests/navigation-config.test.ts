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
