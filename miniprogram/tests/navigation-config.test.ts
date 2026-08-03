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

test("分类页固定工具区并统一搜索、排序和分类视觉", () => {
  const categoryRoot = resolve(sourceRoot, "pages/category/category");
  const categoryConfig = JSON.parse(
    readFileSync(`${categoryRoot}.json`, "utf8")
  ) as DetailPageConfig;
  const categoryTemplate = readFileSync(`${categoryRoot}.wxml`, "utf8");
  const categoryStyle = readFileSync(`${categoryRoot}.less`, "utf8");
  const catalogTemplate = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.wxml"),
    "utf8"
  );
  const catalogStyle = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.less"),
    "utf8"
  );
  const catalogLogic = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.ts"),
    "utf8"
  );

  assert.equal(categoryConfig.disableScroll, true);
  assert.equal(categoryConfig.enablePullDownRefresh, false);
  assert.match(categoryTemplate, /search-material-symbols-iconify\.svg/);
  assert.match(categoryTemplate, /class="category-catalog"/);
  assert.match(categoryStyle, /\.category-page\s*\{[\s\S]*height: 100vh;[\s\S]*overflow: hidden/);
  assert.match(categoryStyle, /\.category-search\s*\{[\s\S]*width: calc\(100% - 40rpx\);[\s\S]*border-radius: 18rpx;[\s\S]*background: #ffffff/);
  assert.match(catalogTemplate, /class="catalog-content"[\s\S]*scroll-y="\{\{tabPage \|\| scrollPage\}\}"[\s\S]*enhanced="\{\{tabPage \|\| scrollPage\}\}"[\s\S]*refresher-enabled="\{\{tabPage \|\| scrollPage\}\}"/);
  assert.match(catalogTemplate, /wx:if="\{\{tabPage\}\}"[\s\S]*class="catalog-tab-spacer"[\s\S]*<\/scroll-view>/);
  assert.doesNotMatch(catalogTemplate, /catalog-tab-wash/);
  assert.match(catalogStyle, /\.catalog-browser--fixed\s*\{[\s\S]*display: flex;[\s\S]*overflow: hidden/);
  assert.match(catalogStyle, /\.catalog-browser--tab \.catalog-content\s*\{[\s\S]*padding-bottom: 0/);
  assert.match(catalogStyle, /\.catalog-tab-spacer\s*\{[\s\S]*height: calc\(@tab-bar-height \+ env\(safe-area-inset-bottom\)\)/);
  assert.doesNotMatch(catalogStyle, /\.catalog-tab-wash/);
  assert.match(catalogStyle, /\.sort-bar\s*\{[\s\S]*background: transparent/);
  assert.match(catalogStyle, /\.sort-item\s*\{[\s\S]*color: #000000/);
  assert.match(catalogStyle, /\.sort-item--active\s*\{[\s\S]*color: #ff172b/);
  assert.match(catalogStyle, /\.sort-item--active::after\s*\{[\s\S]*bottom: 14rpx;[\s\S]*width: 24rpx/);
  assert.match(catalogStyle, /\.category-tab\s*\{[\s\S]*border: 0;[\s\S]*color: #000000;[\s\S]*background: #ffffff/);
  assert.match(catalogStyle, /\.category-tab--active\s*\{[\s\S]*border: 1rpx solid #fe0000;[\s\S]*color: #fe0000;[\s\S]*background: #ffebef/);
  assert.match(catalogStyle, /\.filter-panel\s*\{[\s\S]*background: @color-page/);
  assert.match(catalogStyle, /\.filter-panel__header\s*\{[\s\S]*border-bottom: 0/);
  assert.match(catalogStyle, /\.filter-option\s*\{[\s\S]*border: 0;[\s\S]*color: #000000;[\s\S]*background: #ffffff/);
  assert.match(catalogStyle, /\.filter-option--active\s*\{[\s\S]*border: 1rpx solid #fe0000;[\s\S]*color: #fe0000;[\s\S]*background: #ffebef/);
  assert.match(catalogStyle, /\.filter-panel__actions\s*\{[\s\S]*border-top: 0;[\s\S]*background: @color-page/);
  assert.match(catalogStyle, /\.filter-action--secondary\s*\{[\s\S]*color: #000000/);
  assert.match(catalogStyle, /\.filter-action--primary\s*\{[\s\S]*background: #ff172b/);
  assert.match(catalogTemplate, />确定<\/view>/);
  assert.doesNotMatch(catalogTemplate, />查看商品<\/view>/);
  assert.match(catalogLogic, /async onContentRefresh\(\)[\s\S]*await this\.refresh\(\)/);
  assert.match(catalogLogic, /onContentLower\(\)[\s\S]*this\.loadMore\(\)/);
});

test("商品搜索使用内嵌按钮和最近搜索样式", () => {
  const searchRoot = resolve(sourceRoot, "pages/product/search/search");
  const searchTemplate = readFileSync(`${searchRoot}.wxml`, "utf8");
  const searchStyle = readFileSync(`${searchRoot}.less`, "utf8");
  const searchConfig = JSON.parse(
    readFileSync(`${searchRoot}.json`, "utf8")
  ) as DetailPageConfig;
  const resultRoot = resolve(sourceRoot, "pages/product/list/list");
  const resultTemplate = readFileSync(`${resultRoot}.wxml`, "utf8");
  const resultStyle = readFileSync(`${resultRoot}.less`, "utf8");
  const resultConfig = JSON.parse(
    readFileSync(`${resultRoot}.json`, "utf8")
  ) as DetailPageConfig;
  const catalogStyle = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.less"),
    "utf8"
  );

  assert.equal(searchConfig.disableScroll, true);
  assert.match(searchTemplate, /placeholder="输入商品名称"/);
  assert.doesNotMatch(searchTemplate, /search-field__icon|address-search\.svg/);
  assert.match(searchTemplate, />最近搜索<\/text>/);
  assert.match(searchTemplate, /trash-can-outline-muted-iconify\.svg/);
  assert.match(searchTemplate, /class="search-history__scroll"[\s\S]*scroll-y="\{\{true\}\}"/);
  assert.match(searchStyle, /\.search-field\s*\{[\s\S]*width: calc\(100% - 40rpx\);[\s\S]*height: 64rpx;[\s\S]*border-radius: 18rpx;[\s\S]*background: #ffffff/);
  assert.match(searchStyle, /\.search-field__input\s*\{[\s\S]*font-size: 26rpx;[\s\S]*font-weight: 400;[\s\S]*text-align: left/);
  assert.match(searchStyle, /\.search-field__submit\s*\{[\s\S]*height: 54rpx;[\s\S]*border-radius: 14rpx;[\s\S]*background: #e93a3d/);
  assert.match(searchStyle, /\.search-history__item\s*\{[\s\S]*color: #b5b5b5;[\s\S]*background: #ffffff/);
  assert.match(searchStyle, /\.search-history__clear-icon\s*\{[\s\S]*display: block/);
  assert.equal(resultConfig.disableScroll, true);
  assert.equal(resultConfig.enablePullDownRefresh, false);
  assert.match(resultTemplate, /search-material-symbols-iconify\.svg/);
  assert.doesNotMatch(resultTemplate, /address-search\.svg/);
  assert.match(resultTemplate, /scroll-page="\{\{true\}\}"/);
  assert.match(resultTemplate, /<input[\s\S]*value="\{\{initialKeyword\}\}"[\s\S]*placeholder="输入商品名称"/);
  assert.match(resultStyle, /\.catalog-search\s*\{[\s\S]*width: calc\(100% - 40rpx\);[\s\S]*height: 64rpx;[\s\S]*border-radius: 18rpx;[\s\S]*background: #ffffff/);
  assert.match(resultStyle, /\.catalog-search__text\s*\{[\s\S]*height: 64rpx;[\s\S]*font-size: 26rpx;[\s\S]*font-weight: 400;[\s\S]*line-height: 64rpx/);
  assert.match(catalogStyle, /\.catalog-browser--fixed:not\(\.catalog-browser--tab\) \.catalog-content\s*\{[\s\S]*padding-bottom: 0/);
});

test("购物车提供管理批量删除并使用后端权威计价", () => {
  const template = readFileSync(
    resolve(sourceRoot, "pages/cart/cart.wxml"),
    "utf8"
  );
  const styles = readFileSync(
    resolve(sourceRoot, "pages/cart/cart.less"),
    "utf8"
  );
  const loginStyles = readFileSync(
    resolve(sourceRoot, "pages/auth/login/login.less"),
    "utf8"
  );
  const logic = readFileSync(
    resolve(sourceRoot, "pages/cart/cart-page.ts"),
    "utf8"
  );
  const service = readFileSync(
    resolve(sourceRoot, "services/cart.ts"),
    "utf8"
  );
  const cartConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "pages/cart/cart.json"), "utf8")
  ) as DetailPageConfig;
  const standaloneConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "pages/cart/standalone/standalone.json"), "utf8")
  ) as DetailPageConfig;

  assert.equal(cartConfig.disableScroll, true);
  assert.equal(cartConfig.enablePullDownRefresh, false);
  assert.equal(standaloneConfig.disableScroll, true);
  assert.equal(standaloneConfig.enablePullDownRefresh, false);
  assert.match(template, /购物车（\{\{cartTotalQuantity\}\}）/);
  assert.match(template, /\{\{managing \? '完成' : '管理'\}\}/);
  assert.match(template, /trash-can-outline-iconify\.svg/);
  assert.match(template, /check-rounded-material-symbols-iconify\.svg/);
  assert.doesNotMatch(template, />✓<\/text>/);
  assert.match(template, /class="cart-content"[\s\S]*scroll-y="\{\{true\}\}"[\s\S]*bindrefresherrefresh="onContentRefresh"/);
  assert.match(template, /class="batch-delete-action"/);
  assert.doesNotMatch(template, /商品清单与结算信息/);
  assert.doesNotMatch(template, /这一锅，慢慢挑|>清空<|>移除<|小计|优惠将在结算页计算|已选 \{\{/);
  assert.match(logic, /确认要删除这\$\{normalizedIds\.length\}种商品吗/);
  assert.match(logic, /您还没有选择商品/);
  assert.match(logic, /previewOrder\(\{[\s\S]*source: "CART"/);
  assert.match(logic, /async onContentRefresh\(\)[\s\S]*await this\.loadCart\(\)/);
  assert.match(service, /API_ENDPOINTS\.cart\.batchDelete/);
  assert.match(styles, /\.cart-page\s*\{[\s\S]*height: 100vh;[\s\S]*display: flex;[\s\S]*overflow: hidden;[\s\S]*background: #f3f3f7/);
  assert.match(styles, /\.cart-content\s*\{[\s\S]*height: 0;[\s\S]*flex: 1/);
  assert.match(styles, /\.cart-login-state\s*\{[\s\S]*border: 0;[\s\S]*background: transparent;[\s\S]*box-shadow: none/);
  assert.match(styles, /\.cart-login-state\s*\{[\s\S]*flex: 1;[\s\S]*justify-content: center/);
  assert.match(styles, /button\.cart-login-state__action\s*\{[\s\S]*background: @color-login-action/);
  assert.match(loginStyles, /button\.primary-action\s*\{[\s\S]*background: @color-login-action/);
  assert.doesNotMatch(styles, /\.cart-login-state\s*\{[^}]*\.card-surface\(\)/);
  assert.match(styles, /\.cart-card\s*\{[\s\S]*background: #ffffff/);
  assert.match(styles, /\.cart-card__image-shell\s*\{[\s\S]*width: 166rpx;[\s\S]*height: 166rpx/);
  assert.match(styles, /\.cart-card__footer\s*\{[\s\S]*width: 162rpx;[\s\S]*justify-content: center/);
  assert.match(styles, /\.settlement-bar\s*\{[\s\S]*border-top: 0;[\s\S]*background: #ffffff;[\s\S]*box-shadow: none/);
  assert.match(styles, /\.quantity-stepper__value\s*\{[\s\S]*background: #f6f6f6/);
  assert.match(styles, /\.selection--checked\s*\{[\s\S]*border-color: #ff172b;[\s\S]*background: #ff172b/);
  assert.match(styles, /\.selection__check\s*\{[\s\S]*width: 26rpx;[\s\S]*height: 26rpx/);
  assert.match(styles, /button\.checkout-action\s*\{[\s\S]*background: #ff172b/);
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
  const homeCategoryStyle = readFileSync(
    resolve(sourceRoot, "components/home-category-grid/home-category-grid.less"),
    "utf8"
  );
  const homeProductSectionStyle = readFileSync(
    resolve(sourceRoot, "components/home-product-section/home-product-section.less"),
    "utf8"
  );
  const homeProductSectionTemplate = readFileSync(
    resolve(sourceRoot, "components/home-product-section/home-product-section.wxml"),
    "utf8"
  );
  const homeProductSectionLogic = readFileSync(
    resolve(sourceRoot, "components/home-product-section/home-product-section.ts"),
    "utf8"
  );
  const homeTemplate = readFileSync(resolve(sourceRoot, "pages/index/index.wxml"), "utf8");
  const homeLogic = readFileSync(resolve(sourceRoot, "pages/index/index.ts"), "utf8");
  const catalogTemplate = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.wxml"),
    "utf8"
  );
  const catalogLogic = readFileSync(
    resolve(sourceRoot, "components/catalog-browser/catalog-browser.ts"),
    "utf8"
  );
  const tabLogic = readFileSync(resolve(sourceRoot, "custom-tab-bar/index.ts"), "utf8");
  const tabTemplate = readFileSync(resolve(sourceRoot, "custom-tab-bar/index.wxml"), "utf8");
  const tabStyle = readFileSync(resolve(sourceRoot, "custom-tab-bar/index.less"), "utf8");

  assert.match(productCardTemplate, /catchtap="handleCartTap"/);
  assert.match(productCardTemplate, /product-card__cart-plus-horizontal/);
  assert.match(productCardTemplate, /catchtap="handleTitleToggle"/);
  assert.match(productCardLogic, /measureTitleOverflow/);
  assert.match(productCardStyle, /\.product-card__title[\s\S]*text-overflow: ellipsis[\s\S]*white-space: nowrap/);
  assert.match(productCardStyle, /\.product-card\s*\{[\s\S]*background: #ffffff/);
  assert.match(productCardStyle, /\.product-card--featured\s*\{[\s\S]*box-shadow: none/);
  assert.match(productCardStyle, /\.product-card--flat\s*\{[\s\S]*box-shadow: none/);
  assert.match(homeCategoryStyle, /\.category-card\s*\{[\s\S]*background: #ffffff/);
  assert.match(homeProductSectionStyle, /\.product-showcase\s*\{[\s\S]*border: 0;[\s\S]*background: transparent;[\s\S]*box-shadow: none/);
  assert.match(homeProductSectionStyle, /\.product-showcase\s*\{[\s\S]*margin: 8rpx 12rpx 0;[\s\S]*padding: 0 0 28rpx/);
  assert.match(homeProductSectionStyle, /\.product-showcase--separated\s*\{\s*margin-top: 4rpx/);
  assert.match(homeProductSectionStyle, /\.product-showcase--featured\s*\{\s*padding-bottom: 0/);
  assert.match(homeProductSectionStyle, /\.section-heading__more\s*\{[\s\S]*color: #e10203/);
  assert.match(homeProductSectionTemplate, /catchtap="onMoreTap"[\s\S]*>查看更多<\/text>/);
  assert.equal((homeProductSectionTemplate.match(/flat="\{\{true\}\}"/g) ?? []).length, 2);
  assert.match(homeProductSectionLogic, /this\.triggerEvent\("more"\)/);
  assert.equal((homeTemplate.match(/bindmore="onMoreProductsTap"/g) ?? []).length, 2);
  assert.match(homeLogic, /wx\.switchTab\(\{[\s\S]*url: "\/pages\/category\/category"/);
  assert.match(homeLogic, /await addCartItem\(\{ skuId: sku\.id, quantity: 1 \}\)/);
  assert.match(catalogLogic, /await addCartItem\(\{ skuId: sku\.id, quantity: 1 \}\)/);
  assert.match(catalogTemplate, /<product-card[\s\S]*flat="\{\{true\}\}"/);
  assert.match(tabLogic, /getCartItems\(\)/);
  assert.match(tabLogic, /cart\.totalQuantity/);
  assert.match(tabTemplate, /tab-bar__cart-handle/);
  assert.match(tabTemplate, /tab-bar__cart-basket/);
  assert.match(tabTemplate, /tab-bar__cart-wheel/);
  assert.doesNotMatch(tabTemplate, /shopping-cart-outline-iconify\.svg/);
  assert.match(tabStyle, /\.tab-bar\s*\{[\s\S]*background: transparent;[\s\S]*isolation: isolate/);
  assert.match(tabStyle, /\.tab-bar::before\s*\{[\s\S]*rgba\(255, 255, 255, 0\.72\)[\s\S]*backdrop-filter: blur\(20rpx\) saturate\(135%\)/);
  assert.match(tabStyle, /\.tab-bar__items\s*\{[\s\S]*position: relative;[\s\S]*z-index: 1/);
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
  assert.match(profileTemplate, /class="member-card__avatar-frame"/);
  assert.match(profileTemplate, /src="\/assets\/images\/member-avatar-frame-v\.png"/);
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/profile-default-avatar.png")),
    true
  );
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/profile-watercolor-background.png")),
    true
  );
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/member-avatar-frame-v.webp")),
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
