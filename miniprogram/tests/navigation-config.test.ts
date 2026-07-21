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
  disableScroll?: boolean;
}

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("自定义底部导航注册四个可用的 Tab 根页面", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as AppConfig;
  const expectedTabs = [
    ["pages/index/index", "首页"],
    ["pages/category/category", "分类"],
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

  const cartLogic = readFileSync(resolve(sourceRoot, "pages/cart/cart.ts"), "utf8");
  const profileLogic = readFileSync(resolve(sourceRoot, "pages/profile/profile.ts"), "utf8");
  assert.match(cartLogic, /syncCustomTabBar\(this, 2\)/);
  assert.match(profileLogic, /syncCustomTabBar\(this, 3\)/);

  assert.doesNotThrow(() => syncCustomTabBar({}, 0));
});

test("商品详情关闭下拉刷新并将规格选择收进购买弹层", () => {
  const detailPageRoot = resolve(sourceRoot, "pages/product/detail/detail");
  const detailConfig = JSON.parse(
    readFileSync(`${detailPageRoot}.json`, "utf8")
  ) as DetailPageConfig;
  const detailTemplate = readFileSync(`${detailPageRoot}.wxml`, "utf8");
  const detailLogic = readFileSync(`${detailPageRoot}.ts`, "utf8");

  assert.equal(detailConfig.enablePullDownRefresh, false);
  assert.doesNotMatch(detailTemplate, /<sku-selector|stock-text=|categoryName/);
  assert.match(detailTemplate, /data-mode="CART"/);
  assert.match(detailTemplate, /data-mode="BUY"/);
  assert.match(detailTemplate, /activeSheet === 'purchase'/);
  assert.match(detailTemplate, />商品评价</);
  assert.match(detailTemplate, /activeSheet === 'reviews'/);
  assert.match(detailTemplate, /activeSheet === 'reviewManage'/);
  assert.match(detailTemplate, /bindscrolltolower="onReviewLoadMore"/);
  assert.match(detailTemplate, /bindtap="onReviewSubmit"/);
  assert.doesNotMatch(detailTemplate, /bounces="{{false}}"/);
  assert.match(detailTemplate, /class="detail-scroll-content"/);
  assert.match(detailTemplate, /class="purchase-sheet-scroll-content"/);
  assert.match(detailLogic, /buildDirectBuyUrl/);
});

test("购物车与结算页注册真实交易路径", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as AppConfig;
  const cartTemplate = readFileSync(
    resolve(sourceRoot, "pages/cart/cart.wxml"),
    "utf8"
  );
  const previewTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/preview/preview.wxml"),
    "utf8"
  );
  const previewLogic = readFileSync(
    resolve(sourceRoot, "pages/order/preview/preview.ts"),
    "utf8"
  );

  assert.ok(appConfig.pages.includes("pages/order/preview/preview"));
  assert.ok(appConfig.pages.includes("pages/order/created/created"));
  assert.doesNotMatch(cartTemplate, /tab-placeholder|正在接入/);
  assert.match(cartTemplate, /bindtap="onCheckoutTap"/);
  assert.match(previewTemplate, /bindtap="onImportAddress"/);
  assert.match(previewTemplate, /bindtap="onPayTap"/);
  assert.match(previewTemplate, /立即支付/);
  assert.doesNotMatch(previewTemplate, /应付金额|提交订单/);
  assert.match(previewLogic, /executeOrderPayment/);
  assert.doesNotMatch(previewLogic, /wx\.showModal/);
  assert.doesNotMatch(previewTemplate, /商品原价|批发\/活动优惠/);
  assert.match(previewTemplate, /优惠券/);
});

test("微信支付与订单中心注册真实页面和关键操作", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as AppConfig;
  const createdTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/created/created.wxml"),
    "utf8"
  );
  const listTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/list/list.wxml"),
    "utf8"
  );
  const detailTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/detail/detail.wxml"),
    "utf8"
  );
  const detailLogic = readFileSync(
    resolve(sourceRoot, "pages/order/detail/detail.ts"),
    "utf8"
  );
  const paymentAdapter = readFileSync(
    resolve(sourceRoot, "utils/wechat-payment.ts"),
    "utf8"
  );

  assert.ok(appConfig.pages.includes("pages/order/list/list"));
  assert.ok(appConfig.pages.includes("pages/order/detail/detail"));
  assert.match(createdTemplate, /支付成功/);
  assert.match(createdTemplate, /支付金额/);
  assert.doesNotMatch(createdTemplate, /待支付金额|应付金额|订单提交成功|同步支付结果/);
  assert.match(listTemplate, /bindtap="onOrderTap"/);
  assert.match(listTemplate, /catchtap="onCancelTap"/);
  assert.match(detailTemplate, /bindtap="onConfirmTap"/);
  assert.match(detailTemplate, /countdownText/);
  assert.match(detailTemplate, /bindtap="onDeleteTap"/);
  assert.match(detailTemplate, /bindtap="onRebuyTap"/);
  assert.doesNotMatch(detailTemplate, /应付金额|同步结果/);
  assert.match(detailTemplate, /title="{{navigationTitle}}"/);
  assert.match(detailLogic, /return "待付款"/);
  assert.match(detailLogic, /return "已取消"/);
  assert.match(paymentAdapter, /wx\.requestPayment/);
});

test("首页使用微信原生下拉刷新图标", () => {
  const homePageRoot = resolve(sourceRoot, "pages/index/index");
  const homeConfig = JSON.parse(
    readFileSync(`${homePageRoot}.json`, "utf8")
  ) as DetailPageConfig;
  const homeTemplate = readFileSync(`${homePageRoot}.wxml`, "utf8");
  const homeLogic = readFileSync(`${homePageRoot}.ts`, "utf8");

  assert.equal(homeConfig.enablePullDownRefresh, true);
  assert.equal(homeConfig.disableScroll, undefined);
  assert.doesNotMatch(homeTemplate, /refresher-/);
  assert.doesNotMatch(homeTemplate, /refreshText/);
  assert.match(homeLogic, /onPullDownRefresh\(\)[\s\S]*loadHome\(true\)/);
  assert.match(homeLogic, /wx\.stopPullDownRefresh\(\)/);
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
