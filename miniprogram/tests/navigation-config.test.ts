import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  setCustomTabBarHidden,
  syncCustomTabBar
} from "../miniprogram/utils/tab-bar";

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
  let hidden = true;
  syncCustomTabBar({
    getTabBar: () => ({
      setData(data) {
        selected = data.selected;
      },
      setHidden(value) {
        hidden = value;
      }
    })
  }, 3);
  assert.equal(selected, 3);
  assert.equal(hidden, false);

  setCustomTabBarHidden({
    getTabBar: () => ({
      setData() {},
      setHidden(value) {
        hidden = value;
      }
    })
  }, true);
  assert.equal(hidden, true);

  const cartLogic = readFileSync(resolve(sourceRoot, "pages/cart/cart.ts"), "utf8");
  const cartPageLogic = readFileSync(
    resolve(sourceRoot, "pages/cart/cart-page.ts"),
    "utf8"
  );
  const profileLogic = readFileSync(resolve(sourceRoot, "pages/profile/profile.ts"), "utf8");
  assert.match(cartLogic, /syncTabBar: true/);
  assert.match(cartPageLogic, /syncCustomTabBar\(this, 2\)/);
  assert.match(profileLogic, /syncCustomTabBar\(this, 3\)/);

  assert.doesNotThrow(() => syncCustomTabBar({}, 0));
});

test("分类筛选打开时隐藏自定义底部导航", () => {
  const categoryTemplate = readFileSync(
    resolve(sourceRoot, "pages/category/category.wxml"),
    "utf8"
  );
  const categoryLogic = readFileSync(
    resolve(sourceRoot, "pages/category/category.ts"),
    "utf8"
  );
  const catalogLogic = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.ts"),
    "utf8"
  );
  const tabTemplate = readFileSync(
    resolve(sourceRoot, "custom-tab-bar/index.wxml"),
    "utf8"
  );
  assert.match(categoryTemplate, /bindfiltervisibilitychange="onFilterVisibilityChange"/);
  assert.match(categoryLogic, /setCustomTabBarHidden\(this, Boolean\(event\.detail\.visible\)\)/);
  assert.match(catalogLogic, /filtervisibilitychange", \{ visible: true \}/);
  assert.match(catalogLogic, /filtervisibilitychange", \{ visible: false \}/);
  assert.match(tabTemplate, /hidden="\{\{hidden\}\}"/);
});

test("商品加购按钮调用真实购物车接口并同步底部角标", () => {
  const productCardTemplate = readFileSync(
    resolve(sourceRoot, "components/product-card/product-card.wxml"),
    "utf8"
  );
  const productCardLogic = readFileSync(
    resolve(sourceRoot, "components/product-card/product-card.ts"),
    "utf8"
  );
  const productCardStyle = readFileSync(
    resolve(sourceRoot, "components/product-card/product-card.less"),
    "utf8"
  );
  const homeLogic = readFileSync(resolve(sourceRoot, "pages/index/index.ts"), "utf8");
  const catalogLogic = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.ts"),
    "utf8"
  );
  const tabLogic = readFileSync(resolve(sourceRoot, "custom-tab-bar/index.ts"), "utf8");
  const tabStyle = readFileSync(resolve(sourceRoot, "custom-tab-bar/index.less"), "utf8");

  assert.match(productCardTemplate, /catchtap="handleCartTap"/);
  assert.match(productCardTemplate, /product-card__cart-plus-horizontal/);
  assert.match(productCardTemplate, /catchtap="handleTitleToggle"/);
  assert.match(productCardLogic, /measureTitleOverflow/);
  assert.match(productCardStyle, /\.product-card__title[\s\S]*text-overflow: ellipsis[\s\S]*white-space: nowrap/);
  assert.match(productCardStyle, /\.product-card--featured\s*\{[\s\S]*box-shadow: none/);
  assert.match(homeLogic, /await addCartItem\(\{ skuId: sku\.id, quantity: 1 \}\)/);
  assert.match(catalogLogic, /await addCartItem\(\{ skuId: sku\.id, quantity: 1 \}\)/);
  assert.match(tabLogic, /getCartItems\(\)/);
  assert.match(tabLogic, /cart\.totalQuantity/);
  assert.match(tabStyle, /background: rgba\(255, 251, 244, 0\.74\)/);
});

test("账户中心注册真实页面、移除消息中心并接入自建在线客服", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as AppConfig;
  const accountPages = [
    "pages/account/profile/profile",
    "pages/account/address/list/list",
    "pages/account/address/edit/edit",
    "pages/account/coupon/coupon",
    "pages/account/favorites/favorites",
    "pages/account/history/history"
  ];
  accountPages.forEach((pagePath) => {
    assert.ok(appConfig.pages.includes(pagePath));
    ["json", "ts", "wxml", "less"].forEach((extension) => {
      assert.equal(existsSync(resolve(sourceRoot, `${pagePath}.${extension}`)), true);
    });
  });
  assert.equal(appConfig.pages.includes("pages/message/message"), false);
  assert.equal(existsSync(resolve(sourceRoot, "pages/message/message.wxml")), false);

  const profileTemplate = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.wxml"),
    "utf8"
  );
  const profileLogic = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );
  assert.match(profileLogic, /收货地址/);
  assert.match(profileLogic, /优惠券/);
  assert.match(profileLogic, /我的收藏/);
  assert.match(profileLogic, /浏览记录/);
  assert.doesNotMatch(profileTemplate, /open-type="contact"/);
  assert.match(profileLogic, /customerService/);
  assert.match(profileLogic, /accountNavigationPath/);
  assert.match(profileLogic, /profile-default-avatar\.png/);
  assert.match(profileTemplate, /profile-watercolor-background\.png/);
  assert.doesNotMatch(`${profileLogic}\n${profileTemplate}`, /\.webp/);
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/profile-default-avatar.png")),
    true
  );
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/profile-watercolor-background.png")),
    true
  );
});

test("收藏与足迹整卡进入商品详情且移除操作不会冒泡", () => {
  const favoriteTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/favorites/favorites.wxml"),
    "utf8"
  );
  const favoriteLogic = readFileSync(
    resolve(sourceRoot, "pages/account/favorites/favorites.ts"),
    "utf8"
  );
  const historyTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/history/history.wxml"),
    "utf8"
  );
  const historyLogic = readFileSync(
    resolve(sourceRoot, "pages/account/history/history.ts"),
    "utf8"
  );
  const orderTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/list/list.wxml"),
    "utf8"
  );
  const orderLogic = readFileSync(
    resolve(sourceRoot, "pages/order/list/list.ts"),
    "utf8"
  );
  const profileLogic = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );

  [favoriteTemplate, historyTemplate].forEach((template) => {
    assert.match(template, /data-id="\{\{item\.spuId\}\}"[\s\S]*bindtap="onProductTap"/);
    assert.match(template, /catchtap="on(?:Remove|Delete)Tap"/);
    assert.match(template, /aria-role="group"/);
    assert.match(template, /aria-role="button"/);
    assert.match(template, /aria-disabled="\{\{!item\.available\}\}"/);
    assert.match(template, /aria-label="\{\{item\.available \?/);
  });
  [favoriteLogic, historyLogic].forEach((logic) => {
    assert.match(logic, /wx\.navigateTo\(\{ url: product\.navigationPath \}\)/);
    assert.match(logic, /async refresh\(\)[\s\S]{0,180}loadingMore: false/);
  });
  assert.match(favoriteLogic, /await removeFavorite\(spuId\);[\s\S]*await this\.refresh\(\);/);
  assert.match(historyLogic, /await deleteBrowseHistoryItem\(spuId\);[\s\S]*await this\.refresh\(\);/);
  assert.match(favoriteLogic, /current: 1,[\s\S]*hasMore: false/);
  assert.match(historyLogic, /current: 1,[\s\S]*hasMore: false/);
  assert.match(orderTemplate, /class="order-card"[\s\S]*aria-role="group"[\s\S]*class="order-card__detail"[\s\S]*aria-role="button"/);
  assert.match(orderTemplate, /wx:for="\{\{item\.items\}\}"[\s\S]*class="order-product/);
  assert.match(orderTemplate, /binderror="onItemImageError"/);
  ["onCancelTap", "onModifyTap", "onDeleteTap", "onRebuyTap", "onReviewTap", "onPayTap"]
    .forEach((handler) => assert.match(orderTemplate, new RegExp(`catchtap="${handler}"`)));
  assert.doesNotMatch(orderTemplate, /灶香集|order-card__merchant|onSyncTap|onConfirmTap|评价晒单/);
  assert.match(orderLogic, /await deleteOrder\(orderId\)/);
  assert.match(orderLogic, /const detail = await getOrderDetail\(orderId\)[\s\S]*await addCartItem/);
  assert.match(orderLogic, /buildOrderReviewUrl\(orderId\)/);
  assert.match(orderLogic, /buildOrderModifyUrl\(orderId\)/);
  assert.match(orderLogic, /this\.data\.loadingMore/);
  assert.match(orderLogic, /loading: true, loadingMore: false/);
  assert.match(profileLogic, /group: "TO_REVIEW",[\s\S]{0,80}label: "待评价"/);
});

test("全局导航统一返回图标且不再显示首页按钮", () => {
  const navigationTemplate = readFileSync(
    resolve(sourceRoot, "components/navigation-bar/navigation-bar.wxml"),
    "utf8"
  );
  const navigationLogic = readFileSync(
    resolve(sourceRoot, "components/navigation-bar/navigation-bar.ts"),
    "utf8"
  );
  const addressListTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/address/list/list.wxml"),
    "utf8"
  );

  assert.match(navigationTemplate, /navigation-back\.svg/);
  assert.doesNotMatch(navigationTemplate, /navigation-bar__home|handleHome/);
  assert.doesNotMatch(navigationLogic, /\bhome:\s*\{|handleHome\(/);
  assert.doesNotMatch(addressListTemplate, /\bhome=/);
});

test("商品详情使用自建规格、评价和收货地址弹层", () => {
  const detailPageRoot = resolve(sourceRoot, "pages/product/detail/detail");
  const detailConfig = JSON.parse(
    readFileSync(`${detailPageRoot}.json`, "utf8")
  ) as DetailPageConfig;
  const detailTemplate = readFileSync(`${detailPageRoot}.wxml`, "utf8");
  const detailLogic = readFileSync(`${detailPageRoot}.ts`, "utf8");
  const detailStyle = readFileSync(`${detailPageRoot}.less`, "utf8");

  assert.equal(detailConfig.enablePullDownRefresh, false);
  assert.doesNotMatch(detailTemplate, /<sku-selector|stock-text=|categoryName/);
  assert.match(detailTemplate, /data-mode="CART"/);
  assert.match(detailTemplate, /data-mode="BUY"/);
  assert.match(detailTemplate, /activeSheet === 'purchase'/);
  assert.match(detailTemplate, />商品评价</);
  assert.match(detailTemplate, /activeSheet === 'reviews'/);
  assert.match(detailTemplate, /activeSheet === 'reviewManage'/);
  assert.match(detailTemplate, /activeSheet === 'address'/);
  assert.match(detailTemplate, /bindtap="onAddAddress">新增地址</);
  assert.match(detailTemplate, /bindscrolltolower="onReviewLoadMore"/);
  assert.match(detailTemplate, /bindtap="onReviewSubmit"/);
  assert.doesNotMatch(detailTemplate, /bounces="{{false}}"/);
  assert.match(detailTemplate, /class="detail-scroll-content"/);
  assert.match(detailTemplate, /class="purchase-sheet-scroll-content"/);
  assert.match(detailLogic, /buildDirectBuyUrl/);
  assert.match(detailLogic, /getAddresses/);
  assert.match(detailLogic, /resolveAddressSelection/);
  assert.doesNotMatch(detailLogic, /wx\.chooseAddress/);
  assert.match(detailLogic, /sheetClosing/);
  assert.match(detailStyle, /@keyframes sheet-sink/);
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
  const productDetailLogic = readFileSync(
    resolve(sourceRoot, "pages/product/detail/detail.ts"),
    "utf8"
  );
  const standaloneCartRoot = resolve(
    sourceRoot,
    "pages/cart/standalone/standalone"
  );
  const standaloneCartStyle = readFileSync(`${standaloneCartRoot}.less`, "utf8");

  assert.ok(appConfig.pages.includes("pages/cart/standalone/standalone"));
  assert.ok(appConfig.pages.includes("pages/order/preview/preview"));
  assert.ok(appConfig.pages.includes("pages/order/created/created"));
  ["json", "ts", "wxml", "less"].forEach((extension) => {
    assert.equal(existsSync(`${standaloneCartRoot}.${extension}`), true);
  });
  assert.doesNotMatch(cartTemplate, /tab-placeholder|正在接入/);
  assert.match(cartTemplate, /bindtap="onCheckoutTap"/);
  assert.match(cartTemplate, /back="{{navigationBack}}"/);
  assert.match(standaloneCartStyle, /\.settlement-bar\s*\{\s*bottom:\s*0/);
  assert.match(
    standaloneCartStyle,
    /padding-bottom:\s*calc\(@space-4 \+ env\(safe-area-inset-bottom\)\)/
  );
  assert.match(
    productDetailLogic,
    /wx\.navigateTo\(\{\s*url: "\/pages\/cart\/standalone\/standalone"/
  );
  assert.match(previewTemplate, /bindtap="onAddAddress">新增地址</);
  assert.doesNotMatch(previewTemplate, /微信地址|onImportAddress/);
  assert.doesNotMatch(previewLogic, /wx\.chooseAddress|createAddress/);
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

test("首页轮播在动画完成后同步位置并在前后台切换时重建原生实例", () => {
  const bannerTemplate = readFileSync(
    resolve(sourceRoot, "components/home-banner/home-banner.wxml"),
    "utf8"
  );
  const bannerLogic = readFileSync(
    resolve(sourceRoot, "components/home-banner/home-banner.ts"),
    "utf8"
  );

  assert.match(bannerTemplate, /wx:if="{{swiperVisible}}"/);
  assert.match(
    bannerTemplate,
    /autoplay="{{autoplayEnabled && banners\.length > 1}}"/
  );
  assert.match(
    bannerTemplate,
    /bindanimationfinish="onBannerAnimationFinish"/
  );
  assert.match(bannerTemplate, /bindtouchcancel="onBannerTouchCancel"/);
  assert.match(bannerLogic, /runtime\.pendingCurrent = current/);
  assert.match(bannerLogic, /pageLifetimes:\s*{[\s\S]*hide\(\)/);
  assert.match(
    bannerLogic,
    /autoplayEnabled: false,[\s\S]*swiperVisible: false/
  );
});
