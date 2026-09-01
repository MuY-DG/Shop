import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  setCustomTabBarHidden,
  syncCustomTabBar
} from "../miniprogram/utils/tab-bar";

interface AppConfig {
  pages: string[];
  subPackages?: Array<{
    name?: string;
    root: string;
    pages: string[];
    plugins?: Record<string, {
      version?: string;
      provider?: string;
    }>;
  }>;
  preloadRule?: Record<string, {
    network?: string;
    packages?: string[];
  }>;
  lazyCodeLoading?: string;
  plugins?: Record<string, {
    version?: string;
    provider?: string;
  }>;
  tabBar?: {
    custom?: boolean;
    color?: string;
    selectedColor?: string;
    list?: Array<{
      pagePath?: string;
      text?: string;
    }>;
  };
}

function configuredPagePaths(appConfig: AppConfig): string[] {
  return [
    ...appConfig.pages,
    ...(appConfig.subPackages ?? []).flatMap(({ root, pages }) =>
      pages.map((pagePath) => `${root}/${pagePath}`)
    )
  ];
}

interface DetailPageConfig {
  enablePullDownRefresh?: boolean;
  disableScroll?: boolean;
}

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("小程序本地图标引用均有效，清理旧资源不会留下失效路径", () => {
  const sourcePaths = readdirSync(sourceRoot, { recursive: true, encoding: "utf8" })
    .filter((path) => /\.(?:ts|wxml|less|json)$/.test(path));
  sourcePaths.forEach((path) => {
    const source = readFileSync(resolve(sourceRoot, path), "utf8");
    for (const [iconPath] of source.matchAll(/\/assets\/icons\/[A-Za-z0-9_-]+\.svg/g)) {
      assert.ok(
        existsSync(resolve(sourceRoot, iconPath.slice(1))),
        `${path} references missing icon ${iconPath}`
      );
    }
  });
});

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
  assert.equal(appConfig.tabBar?.color, "#000000");
  assert.equal(appConfig.tabBar?.selectedColor, "#FF172B");
  assert.equal(appConfig.lazyCodeLoading, "requiredComponents");
  assert.deepEqual(appConfig.subPackages, [
    {
      name: "logistics",
      root: "packages/logistics",
      pages: ["loader/loader"],
      plugins: {
        logisticsPlugin: {
          version: "2.3.0",
          provider: "wx9ad912bf20548d92"
        }
      }
    }
  ]);
  assert.deepEqual(appConfig.preloadRule, {
    "pages/order/list/list": {
      network: "all",
      packages: ["logistics"]
    },
    "pages/order/detail/detail": {
      network: "all",
      packages: ["logistics"]
    }
  });
  assert.equal(appConfig.pages.length, 30);
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

  const configuredPages = configuredPagePaths(appConfig);
  assert.equal(new Set(configuredPages).size, configuredPages.length);

  ["json", "ts", "wxml", "less"].forEach((extension) => {
    assert.equal(
      existsSync(resolve(sourceRoot, `custom-tab-bar/index.${extension}`)),
      true
    );
  });
});

test("通用页面主体统一引用 @color-page 背景变量", () => {
  const pageStyles = [
    ["pages/index/index.less", "home-page"],
    ["pages/category/category.less", "category-page"],
    ["pages/cart/cart.less", "cart-page"],
    ["pages/account/profile/profile.less", "user-profile-page"],
    ["pages/account/settings/settings.less", "settings-page"],
    ["styles/account-products.less", "account-page"],
    ["pages/account/address/list/list.less", "address-page"],
    ["pages/account/address/edit/edit.less", "address-edit-page"],
    ["pages/account/coupon/coupon.less", "coupon-page"],
    ["pages/product/search/search.less", "search-page"],
    ["pages/product/list/list.less", "catalog-page"],
    ["pages/product/detail/detail.less", "detail-page"],
    ["pages/customer-service/chat/chat.less", "chat-page"],
    ["pages/order/preview/preview.less", "preview-page"],
    ["pages/order/created/created.less", "created-page"],
    ["pages/order/list/list.less", "orders-page"],
    ["pages/order/detail/detail.less", "detail-page"],
    ["pages/order/review/review.less", "review-page"],
    ["pages/order/modify/modify.less", "modify-page"],
    ["pages/after-sale/apply/apply.less", "apply-page"],
    ["pages/after-sale/list/list.less", "after-sale-list-page"],
    ["pages/after-sale/detail/detail.less", "after-sale-detail-page"],
    ["pages/compliance/merchant/merchant.less", "merchant-page"],
    ["pages/compliance/document/document.less", "legal-document-page"]
  ] as const;

  const tokens = readFileSync(resolve(sourceRoot, "styles/tokens.less"), "utf8");
  assert.match(tokens, /@color-page: #f6f6f6;/);
  assert.match(tokens, /@color-page-top: @color-page;/);
  assert.match(tokens, /@color-page-middle: @color-page;/);
  assert.match(tokens, /@color-page-bottom: @color-page;/);

  const appStyle = readFileSync(resolve(sourceRoot, "app.less"), "utf8");
  assert.match(appStyle, /page\s*\{[\s\S]*?background: @color-page;/);

  pageStyles.forEach(([stylePath, rootClass]) => {
    const style = readFileSync(resolve(sourceRoot, stylePath), "utf8");
    assert.match(
      style,
      new RegExp(`\\.${rootClass}\\s*\\{[\\s\\S]*?background(?:-color)?: @color-page;`),
      `${stylePath} should use the shared page background token`
    );
  });

  const favoritesStyle = readFileSync(
    resolve(sourceRoot, "pages/account/favorites/favorites.less"),
    "utf8"
  );
  const historyStyle = readFileSync(
    resolve(sourceRoot, "pages/account/history/history.less"),
    "utf8"
  );
  assert.match(favoritesStyle, /\.account-page\.favorites-page\s*\{[\s\S]*?background: @color-page;/);
  assert.match(historyStyle, /\.account-page\.history-page\s*\{[\s\S]*?background: @color-page;/);

  const loginStyle = readFileSync(
    resolve(sourceRoot, "pages/auth/login/login.less"),
    "utf8"
  );
  assert.match(loginStyle, /page,[\s\S]*?\.login-page\s*\{[\s\S]*?background: #ffffff;/);

  const homeTemplate = readFileSync(resolve(sourceRoot, "pages/index/index.wxml"), "utf8");
  const homeStyle = readFileSync(resolve(sourceRoot, "pages/index/index.less"), "utf8");
  const bannerTemplate = readFileSync(
    resolve(sourceRoot, "components/home-banner/home-banner.wxml"),
    "utf8"
  );
  const bannerStyle = readFileSync(
    resolve(sourceRoot, "components/home-banner/home-banner.less"),
    "utf8"
  );
  assert.doesNotMatch(homeTemplate, /ink-halo/);
  assert.doesNotMatch(homeStyle, /ink-halo|\.home-page::after/);
  assert.doesNotMatch(bannerTemplate, /hero-fade/);
  assert.doesNotMatch(bannerStyle, /\.hero-fade/);
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

  let atomicSelected = -1;
  syncCustomTabBar({
    getTabBar: () => ({
      setData() {
        assert.fail("支持原子同步时不应分步更新");
      },
      syncSelection(value) {
        atomicSelected = value;
      }
    })
  }, 2);
  assert.equal(atomicSelected, 2);

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
  const tabLogic = readFileSync(resolve(sourceRoot, "custom-tab-bar/index.ts"), "utf8");
  const tabTemplate = readFileSync(
    resolve(sourceRoot, "custom-tab-bar/index.wxml"),
    "utf8"
  );
  const tabStyle = readFileSync(
    resolve(sourceRoot, "custom-tab-bar/index.less"),
    "utf8"
  );
  assert.match(cartLogic, /syncTabBar: true/);
  assert.match(cartPageLogic, /syncCustomTabBar\(this, 2\)/);
  assert.match(profileLogic, /syncCustomTabBar\(this, 3\)/);
  assert.match(tabLogic, /selected: -1,[\s\S]*hidden: true/);
  assert.match(tabLogic, /syncSelection\(selected: number\)[\s\S]*this\.setData\(\{ selected, hidden: false \}\)/);
  assert.doesNotMatch(tabLogic, /this\.setData\(\{ selected: index \}\)/);
  assert.match(tabLogic, /wx\.switchTab\(\{ url: item\.pagePath \}\)/);
  assert.match(tabLogic, /getCartItems\(\{ preferCache: true \}\)/);
  assert.match(tabTemplate, /src="\{\{item\.iconPath\}\}"/);
  assert.match(tabTemplate, /src="\{\{item\.selectedIconPath\}\}"/);
  assert.doesNotMatch(tabTemplate, /src="\{\{selected === index/);
  assert.doesNotMatch(tabTemplate, /wx:(?:if|elif)="\{\{(?:selected|item\.icon)/);
  assert.doesNotMatch(tabTemplate + tabStyle, /tab-bar__(?:category-square|cart-handle|cart-basket|cart-wheel|profile-head|profile-body)/);
  assert.match(tabStyle, /\.tab-bar__item\s*\{[\s\S]*color: #000000;/);
  assert.match(tabStyle, /\.tab-bar__item--selected\s*\{[\s\S]*color: @color-action-primary;/);
  ["home", "category", "cart", "profile"].forEach((name) => {
    const iconPath = `/assets/icons/tab-${name}.svg`;
    const selectedIconPath = `/assets/icons/tab-${name}-active.svg`;
    const tabItem = tabLogic.match(new RegExp(`icon: "${name}",[^}]+`))?.[0] || "";
    assert.ok(tabItem.includes(`iconPath: "${iconPath}"`));
    assert.ok(tabItem.includes(`selectedIconPath: "${selectedIconPath}"`));
    const icon = readFileSync(resolve(sourceRoot, iconPath.slice(1)), "utf8");
    const activeIcon = readFileSync(resolve(sourceRoot, selectedIconPath.slice(1)), "utf8");
    assert.match(icon, /fill="#000000"/);
    assert.match(activeIcon, /fill="#f70517"/);
    assert.match(icon, /viewBox="0 0 96 96"/);
    assert.match(activeIcon, /viewBox="0 0 96 96"/);
    assert.notDeepEqual(
      [...icon.matchAll(/\bd="([^"]+)"/g)].map((match) => match[1]),
      [...activeIcon.matchAll(/\bd="([^"]+)"/g)].map((match) => match[1])
    );
  });
  assert.match(
    tabStyle,
    /\.tab-bar__icon-image\s*\{[\s\S]*position: absolute;[\s\S]*opacity: 0;[\s\S]*\.tab-bar__icon-image--visible\s*\{[\s\S]*opacity: 1;/
  );
  assert.match(tabLogic, /systemInfo\.platform === "android"/);
  assert.match(tabLogic, /return \{ isAndroid: true, itemOffsetRpx: 34 \}/);
  assert.match(tabLogic, /Math\.round\(bottomInsetRpx \/ 2\)/);
  assert.match(tabTemplate, /class="tab-bar \{\{isAndroid \? 'tab-bar--android' : ''\}\}"/);
  assert.match(tabTemplate, /style="transform: translateY\(\{\{itemOffsetRpx\}\}rpx\);"/);
  assert.match(tabStyle, /\.tab-bar--android\s*\{\s*padding-bottom: @tab-bar-bottom-inset;/);

  assert.doesNotThrow(() => syncCustomTabBar({}, 0));
});

test("购物车选择控件提供 88rpx 热区", () => {
  const cartTemplate = readFileSync(
    resolve(sourceRoot, "pages/cart/cart.wxml"),
    "utf8"
  );
  const cartStyle = readFileSync(
    resolve(sourceRoot, "pages/cart/cart.less"),
    "utf8"
  );

  assert.match(
    cartTemplate,
    /class="selection-hit-target"[\s\S]*catchtap="onSelectionToggle"[\s\S]*class="selection /
  );
  assert.match(
    cartStyle,
    /\.selection-hit-target\s*\{[\s\S]*width: 88rpx;[\s\S]*height: 88rpx;/
  );
});

test("本地品牌 Logo 保持 PNG 并限制像素和包体积", () => {
  const logo = readFileSync(
    resolve(sourceRoot, "assets/images/zaoxiangji-login-emblem18.png")
  );

  assert.equal(logo.subarray(1, 4).toString("ascii"), "PNG");
  assert.equal(logo.readUInt32BE(16), 288);
  assert.equal(logo.readUInt32BE(20), 216);
  assert.ok(logo.byteLength < 20 * 1024);
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
  const categoryLogic = readFileSync(`${categoryRoot}.ts`, "utf8");
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
  assert.match(categoryTemplate, /<navigation-bar[\s\S]*?background="#f6f6f6"/);
  assert.match(categoryTemplate, /class="category-catalog"/);
  assert.match(categoryStyle, /\.category-page\s*\{[\s\S]*height: 100vh;[\s\S]*overflow: hidden/);
  assert.match(categoryStyle, /\.category-search\s*\{[\s\S]*width: calc\(100% - 40rpx\);[\s\S]*border-radius: 18rpx;[\s\S]*background: #ffffff/);
  assert.match(catalogTemplate, /class="catalog-content"[\s\S]*scroll-y="\{\{tabPage \|\| scrollPage\}\}"[\s\S]*enhanced="\{\{tabPage \|\| scrollPage\}\}"[\s\S]*refresher-enabled="\{\{tabPage \|\| scrollPage\}\}"/);
  assert.match(catalogTemplate, /wx:if="\{\{tabPage\}\}"[\s\S]*class="catalog-tab-spacer"[\s\S]*<\/scroll-view>/);
  assert.doesNotMatch(catalogTemplate, /catalog-tab-wash/);
  assert.match(catalogStyle, /\.catalog-browser--fixed\s*\{[\s\S]*display: flex;[\s\S]*overflow: hidden/);
  assert.match(catalogStyle, /\.catalog-browser--tab \.catalog-content\s*\{[\s\S]*padding-bottom: 0/);
  assert.match(catalogStyle, /\.catalog-tab-spacer\s*\{[\s\S]*height: calc\(@tab-bar-height \+ @tab-bar-bottom-inset\)/);
  assert.match(catalogTemplate, /catalog-footer catalog-footer--done[\s\S]*catalog-footer__line[\s\S]*已经到底了[\s\S]*catalog-footer__line/);
  assert.match(catalogStyle, /\.catalog-browser--tab \.catalog-footer--done\s*\{[\s\S]*color: @color-text-muted;[\s\S]*font-family: "Songti SC", "STSong", serif;[\s\S]*font-size: 20rpx;[\s\S]*letter-spacing: 4rpx/);
  assert.match(catalogStyle, /\.catalog-browser--tab \.catalog-footer__line\s*\{[\s\S]*width: 76rpx;[\s\S]*background: linear-gradient/);
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
  assert.match(categoryLogic, /this\.data\.shown[\s\S]*this\.catalog\(\)\?\.silentRefresh\(\)/);
  assert.match(catalogLogic, /async silentRefresh\(\)[\s\S]*this\.loadFirstPage\(true, true\)/);
  assert.match(catalogLogic, /onContentLower\(\)[\s\S]*this\.loadMore\(\)/);
  assert.doesNotMatch(catalogLogic + catalogTemplate + catalogStyle, /embedded/);
  assert.match(catalogLogic, /sortMode: "COMPREHENSIVE"/);
  assert.match(catalogLogic, /viewMode: "grid"/);
  assert.match(
    catalogLogic,
    /attached\(\)[\s\S]*?Promise\.all\(\[\s*this\.loadCategories\(\),\s*this\.loadFilterFacets\(activeCategoryId, \{\}\),\s*this\.loadFirstPage\(\)/
  );
  assert.match(catalogTemplate, /<view class="catalog-tools">/);
  assert.match(catalogTemplate, /catalog-grid catalog-grid--\{\{viewMode\}\}/);
  assert.match(catalogTemplate, /wx:if="\{\{filterVisible\}\}"\s+class="filter-mask"/);
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
  assert.match(template, /class="cart-content"[\s\S]*bounces="\{\{false\}\}"[\s\S]*refresher-triggered="\{\{contentRefreshing\}\}"/);
  assert.match(template, /class="batch-delete-action"/);
  assert.match(template, /settlement-total__amount">\{\{selectedAmountText\}\}/);
  assert.doesNotMatch(template, /计算中/);
  assert.doesNotMatch(template, /商品清单与结算信息/);
  assert.doesNotMatch(template, /这一锅，慢慢挑|>清空<|>移除<|小计|优惠将在结算页计算|已选 \{\{/);
  assert.match(logic, /确认要删除这\$\{normalizedIds\.length\}种商品吗/);
  assert.match(logic, /您还没有选择商品/);
  assert.match(logic, /previewOrder\(\{[\s\S]*source: "CART"/);
  assert.match(logic, /async onContentRefresh\(\)[\s\S]*await this\.loadCart\(\)/);
  assert.match(logic, /void this\.loadCart\(\{ suppressError: this\.data\.loaded \}\)/);
  assert.match(logic, /pricingCache\.get\(signature\) \?\? summary\.selectedAmountText/);
  assert.match(logic, /loadCart\(\{ preserveItemOrder: true \}\)/);
  assert.doesNotMatch(logic, /onPullDownRefresh|stopPullDownRefresh/);
  assert.match(service, /API_ENDPOINTS\.cart\.batchDelete/);
  assert.match(styles, /\.cart-page\s*\{[\s\S]*height: 100vh;[\s\S]*display: flex;[\s\S]*overflow: hidden;[\s\S]*background: @color-page/);
  assert.match(styles, /\.cart-content\s*\{[\s\S]*height: 0;[\s\S]*flex: 1;[\s\S]*padding: @space-4 12rpx calc\(@tab-bar-height \+ 186rpx\)/);
  assert.doesNotMatch(styles, /\.cart-page::after/);
  assert.match(styles, /\.cart-login-state\s*\{[\s\S]*border: 0;[\s\S]*background: transparent;[\s\S]*box-shadow: none/);
  assert.match(styles, /\.cart-login-state\s*\{[\s\S]*flex: 1;[\s\S]*justify-content: center/);
  assert.match(styles, /button\.cart-login-state__action\s*\{[\s\S]*background: @color-login-action/);
  assert.match(loginStyles, /button\.primary-action\s*\{[\s\S]*background: @color-login-action/);
  assert.doesNotMatch(styles, /\.cart-login-state\s*\{[^}]*\.card-surface\(\)/);
  assert.match(styles, /\.cart-card\s*\{[\s\S]*padding: 28rpx @space-5;[\s\S]*border: 0;[\s\S]*background: #ffffff;[\s\S]*box-shadow: none/);
  assert.match(styles, /\.cart-card\s*\{[\s\S]*grid-template-columns: 36rpx 176rpx minmax\(0, 1fr\);[\s\S]*column-gap: @space-5/);
  assert.match(styles, /\.cart-card__image-shell\s*\{[\s\S]*width: 176rpx;[\s\S]*height: 176rpx/);
  assert.match(styles, /\.cart-card__body\s*\{[\s\S]*margin-left: @space-1/);
  assert.match(styles, /\.cart-card__body\s*\{[\s\S]*grid-template-rows: 120rpx auto/);
  assert.match(styles, /\.cart-card__title\s*\{[\s\S]*font-weight: 500/);
  assert.match(styles, /\.cart-card__spec\s*\{[\s\S]*border-radius: @radius-xs;[\s\S]*background: #f5f5f5/);
  assert.match(styles, /\.cart-card__footer\s*\{[\s\S]*width: 162rpx;[\s\S]*justify-content: center/);
  assert.match(styles, /\.settlement-bar\s*\{[\s\S]*border: 0;[\s\S]*background: #ffffff;[\s\S]*background-image: none;[\s\S]*box-shadow: none/);
  assert.match(styles, /\.settlement-bar \.selection--checked\s*\{[\s\S]*box-shadow: none/);
  assert.match(styles, /\.quantity-stepper\s*\{[\s\S]*grid-template-columns: 46rpx 68rpx 46rpx/);
  assert.match(styles, /\.quantity-stepper__value\s*\{[\s\S]*height: 42rpx;[\s\S]*background: #f6f6f6/);
  assert.match(template, /class="quantity-stepper__value quantity-stepper__input"[\s\S]*bindblur="onQuantityInputCommit"[\s\S]*bindconfirm="onQuantityInputCommit"/);
  assert.match(logic, /async recoverStockShortage\(cartItemId: number\)/);
  assert.doesNotMatch(template, /disabled="\{\{!item\.available \|\| item\.quantity <= 1/);
  assert.match(styles, /\.selection--checked\s*\{[\s\S]*border-color: #ff172b;[\s\S]*background: #ff172b;[\s\S]*box-shadow: none/);
  assert.match(styles, /\.selection\s*\{[\s\S]*width: 36rpx;[\s\S]*height: 36rpx/);
  assert.match(styles, /\.selection__check\s*\{[\s\S]*width: 24rpx;[\s\S]*height: 24rpx/);
  assert.match(styles, /\.price-row__currency\s*\{[\s\S]*font-size: 24rpx/);
  assert.match(styles, /\.price-row__integer\s*\{[\s\S]*font-size: 38rpx/);
  assert.match(styles, /\.price-row__decimal\s*\{[\s\S]*font-size: 20rpx/);
  assert.match(styles, /\.wholesale-copy\s*\{[\s\S]*color: #ff172b;[\s\S]*font-weight: 600;/);
  assert.match(styles, /\.next-tier-copy\s*\{[\s\S]*color: @color-text-secondary;/);
  assert.doesNotMatch(styles, /\.next-tier-copy\s*\{[\s\S]*color: @color-gold-text;/);
  assert.match(styles, /button\.checkout-action\s*\{[\s\S]*background: #ff172b;[\s\S]*box-shadow: none/);
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
  const designTokens = readFileSync(resolve(sourceRoot, "styles/tokens.less"), "utf8");
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
  const homeStyle = readFileSync(resolve(sourceRoot, "pages/index/index.less"), "utf8");
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
  assert.match(productCardStyle, /\.product-card__cart\s*\{[^}]*background: #ff172b;/);
  assert.match(productCardTemplate, /catchtap="handleTitleToggle"/);
  assert.match(productCardLogic, /measureTitleOverflow/);
  assert.match(productCardStyle, /\.product-card__title[\s\S]*text-overflow: ellipsis[\s\S]*white-space: nowrap/);
  assert.match(designTokens, /@color-text-gray: #8f939c;/);
  assert.match(designTokens, /@font-family-commerce-price:/);
  assert.match(productCardStyle, /\.product-card\s*\{[^}]*border: 0;[^}]*background: #ffffff/);
  assert.match(productCardStyle, /\.product-card\s*\{[^}]*height: 100%;[^}]*display: flex;[^}]*flex-direction: column/);
  assert.match(productCardStyle, /\.product-card--list\s*\{[^}]*flex-direction: row/);
  assert.match(productCardStyle, /\.product-card__body\s*\{[^}]*flex: 1/);
  assert.match(productCardStyle, /\.product-card__subtitle\s*\{[^}]*color: @color-text-gray/);
  assert.match(productCardStyle, /\.product-card__fact\s*\{[^}]*color: @color-text-gray/);
  assert.match(productCardStyle, /\.product-card__sales\s*\{[^}]*color: @color-text-gray/);
  assert.match(productCardStyle, /\.product-card__meta\s*\{[^}]*margin-top: 4rpx/);
  assert.match(productCardStyle, /\.product-card__footer\s*\{[^}]*margin-top: auto;[^}]*padding-top: 12rpx/);
  assert.match(productCardStyle, /\.product-card__price\s*\{[^}]*font-family: @font-family-commerce-price;[^}]*font-variant-numeric: proportional-nums;[^}]*line-height: 1\.08/);
  assert.match(productCardStyle, /\.product-card__price-decimal\s*\{[^}]*font-size: 26rpx/);
  assert.match(productCardStyle, /\.product-card--featured \.product-card__price-decimal\s*\{[^}]*font-size: 23rpx/);
  assert.match(productCardStyle, /\.product-card--list \.product-card__price-decimal\s*\{[^}]*font-size: 27rpx/);
  assert.match(productCardStyle, /\.product-card--featured\s*\{[\s\S]*box-shadow: none/);
  assert.match(productCardStyle, /\.product-card--flat\s*\{[\s\S]*box-shadow: none/);
  assert.match(homeCategoryStyle, /\.category-card\s*\{[\s\S]*padding: 22rpx 12rpx 20rpx;[\s\S]*border: 0;[\s\S]*background: #ffffff;[\s\S]*box-shadow: none/);
  assert.match(homeProductSectionStyle, /\.product-showcase\s*\{[\s\S]*border: 0;[\s\S]*background: transparent;[\s\S]*box-shadow: none/);
  assert.match(homeProductSectionStyle, /\.product-showcase\s*\{[\s\S]*margin: 8rpx 12rpx 0;[\s\S]*padding: 0 0 28rpx/);
  assert.match(homeProductSectionStyle, /\.product-showcase--separated\s*\{\s*margin-top: 4rpx/);
  assert.match(homeProductSectionStyle, /\.product-showcase--featured\s*\{\s*padding-bottom: 0/);
  assert.match(homeProductSectionStyle, /@home-section-subtitle-color: #785f50;/);
  assert.match(homeProductSectionStyle, /\.section-heading__subtitle\s*\{[^}]*color: @home-section-subtitle-color/);
  assert.match(homeTemplate, /subtitle="大家都在买的川味好料"/);
  assert.match(homeTemplate, /subtitle="更多值得尝试的川味好料"/);
  assert.doesNotMatch(homeStyle, /\.home-page::after/);
  assert.match(homeProductSectionStyle, /\.section-heading__more\s*\{[^}]*color: #e10203/);
  assert.match(homeProductSectionTemplate, /catchtap="onMoreTap"[\s\S]*>查看更多<\/text>/);
  assert.equal((homeProductSectionTemplate.match(/flat="\{\{true\}\}"/g) ?? []).length, 2);
  assert.match(homeProductSectionLogic, /this\.triggerEvent\("more"\)/);
  assert.equal((homeTemplate.match(/bindmore="onMoreProductsTap"/g) ?? []).length, 2);
  assert.match(homeLogic, /wx\.switchTab\(\{[\s\S]*url: "\/pages\/category\/category"/);
  assert.match(homeLogic, /await addCartItem\(\{ skuId: sku\.id, quantity: 1 \}\)/);
  assert.match(catalogLogic, /await addCartItem\(\{ skuId: sku\.id, quantity: 1 \}\)/);
  assert.match(catalogTemplate, /<product-card[\s\S]*flat="\{\{true\}\}"/);
  assert.match(tabLogic, /cart\.totalQuantity/);
  assert.match(tabLogic, /icon: "cart",[\s\S]*?iconPath: "\/assets\/icons\/tab-cart\.svg",[\s\S]*?selectedIconPath: "\/assets\/icons\/tab-cart-active\.svg"/);
  assert.match(tabTemplate, /class="tab-bar__badge"/);
  assert.doesNotMatch(tabTemplate, /shopping-cart-outline-iconify\.svg/);
  assert.match(tabStyle, /\.tab-bar\s*\{[\s\S]*border: 0;[\s\S]*background: #ffffff;[\s\S]*box-shadow: none/);
  assert.doesNotMatch(tabStyle, /\.tab-bar::before|backdrop-filter/);
  assert.match(tabStyle, /\.tab-bar__items\s*\{[\s\S]*position: relative;[\s\S]*z-index: 1/);
});

test("首页推荐商品使用稳定的两列网格", () => {
  const styles = readFileSync(
    resolve(sourceRoot, "components/home-product-section/home-product-section.less"),
    "utf8"
  );

  assert.match(
    styles,
    /\.compact-grid\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\);[^}]*gap:\s*@space-5;/
  );
  assert.match(styles, /\.compact-item\s*\{[^}]*min-width:\s*0;/);
  assert.doesNotMatch(styles, /\.compact-item\s*\{[^}]*width:\s*calc\(/);
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
  const pagePaths = configuredPagePaths(appConfig);
  accountPages.forEach((pagePath) => {
    assert.ok(pagePaths.includes(pagePath));
    ["json", "ts", "wxml", "less"].forEach((extension) => {
      assert.equal(existsSync(resolve(sourceRoot, `${pagePath}.${extension}`)), true);
    });
  });
  assert.equal(pagePaths.includes("pages/message/message"), false);
  assert.equal(existsSync(resolve(sourceRoot, "pages/message/message.wxml")), false);

  const profileTemplate = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.wxml"),
    "utf8"
  );
  const profileLogic = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );
  const profileStyle = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.less"),
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
  assert.match(
    profileTemplate,
    /<image[\s\S]*?class="profile-page__header-background"[\s\S]*?profile-member-background\.png[\s\S]*?mode="widthFix"[\s\S]*?<navigation-bar/
  );
  assert.match(profileTemplate, /<navigation-bar[\s\S]*?background="transparent"/);
  assert.match(profileStyle, /\.profile-page\s*\{[\s\S]*?background-color: @color-page/);
  assert.match(
    profileStyle,
    /\.profile-page\s*\{[^}]*?background-image: linear-gradient\(180deg, #fcf3e7 0%, @color-page 100%\);[^}]*?background-repeat: no-repeat;[^}]*?background-size: 100% 640rpx;/
  );
  assert.match(
    profileStyle,
    /\.profile-page__header-background\s*\{[^}]*?top: 0;[^}]*?width: 100%;[^}]*?height: auto;/
  );
  assert.doesNotMatch(profileStyle, /\.profile-page__header-background\s*\{[^}]*?linear-gradient/);
  assert.match(profileStyle, /\.profile-content\s*\{[\s\S]*?margin-top: -96rpx;[\s\S]*?padding: 8rpx 34rpx/);
  assert.match(
    profileStyle,
    /\.member-card\s*\{[^}]*?position: relative;[^}]*?margin-top: 102rpx;[^}]*?padding: 30rpx 34rpx 30rpx 0;[^}]*?border: 0;[^}]*?background: transparent;[^}]*?box-shadow: none/
  );
  assert.doesNotMatch(profileStyle, /\.member-card\s*\{[^}]*?\s(?:top|transform)\s*:/);
  assert.match(
    profileStyle,
    /\.account-metrics,[\s\S]*?\.service-card\s*\{[\s\S]*?margin-right: -8rpx;[\s\S]*?margin-left: -8rpx;/
  );
  assert.match(
    profileStyle,
    /\.member-card__avatar-shell\s*\{[\s\S]*?width: 160rpx;[\s\S]*?height: 150rpx;/
  );
  assert.match(
    profileStyle,
    /\.member-card__avatar\s*\{[\s\S]*?width: 124rpx;[\s\S]*?height: 124rpx;/
  );
  assert.match(
    profileStyle,
    /\.member-card__avatar-frame\s*\{[\s\S]*?width: 206rpx;[\s\S]*?height: 206rpx;/
  );
  assert.match(profileStyle, /\.account-metrics\s*\{[^}]*?margin-top: -4rpx;/);
  assert.match(profileTemplate, /class="member-card__avatar-frame"/);
  assert.match(profileTemplate, /src="\/assets\/images\/member-avatar-frame\.png"/);
  assert.match(profileTemplate, /aria-label="头像框"/);
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/profile-default-avatar.png")),
    true
  );
  const defaultAvatar = readFileSync(resolve(sourceRoot, "assets/images/profile-default-avatar.png"));
  assert.equal(defaultAvatar.subarray(1, 4).toString("ascii"), "PNG");
  assert.equal(defaultAvatar.readUInt32BE(16), 256);
  assert.equal(defaultAvatar.readUInt32BE(20), 256);
  assert.ok(defaultAvatar.byteLength <= 32 * 1024);
  const avatarFrame = readFileSync(resolve(sourceRoot, "assets/images/member-avatar-frame.png"));
  assert.equal(avatarFrame.subarray(1, 4).toString("ascii"), "PNG");
  assert.equal(avatarFrame.readUInt32BE(16), 512);
  assert.equal(avatarFrame.readUInt32BE(20), 512);
  assert.ok(avatarFrame.byteLength <= 64 * 1024);
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/profile-member-background.png")),
    true
  );
  const profileMemberBackground = readFileSync(
    resolve(sourceRoot, "assets/images/profile-member-background.png")
  );
  assert.equal(profileMemberBackground.subarray(1, 4).toString("ascii"), "PNG");
  assert.equal(profileMemberBackground.readUInt32BE(16), 1774);
  assert.equal(profileMemberBackground.readUInt32BE(20), 887);
  assert.ok(profileMemberBackground.byteLength <= 320 * 1024);
});

test("收藏复用分类卡片并支持批量取消，足迹支持管理、批量删除和加购", () => {
  const favoriteTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/favorites/favorites.wxml"),
    "utf8"
  );
  const favoriteLogic = readFileSync(
    resolve(sourceRoot, "pages/account/favorites/favorites.ts"),
    "utf8"
  );
  const favoriteStyle = readFileSync(
    resolve(sourceRoot, "pages/account/favorites/favorites.less"),
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
  const historyStyle = readFileSync(
    resolve(sourceRoot, "pages/account/history/history.less"),
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
  const orderStyle = readFileSync(
    resolve(sourceRoot, "pages/order/list/list.less"),
    "utf8"
  );
  const orderSearchTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/search/search.wxml"),
    "utf8"
  );
  const orderSearchLogic = readFileSync(
    resolve(sourceRoot, "pages/order/search/search.ts"),
    "utf8"
  );
  const orderService = readFileSync(
    resolve(sourceRoot, "services/order.ts"),
    "utf8"
  );
  const profileLogic = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );

  assert.match(favoriteTemplate, /<navigation-bar[\s\S]*wide-center="\{\{true\}\}"[\s\S]*slot="center" class="favorites-navigation-title">我的收藏<\/view>/);
  assert.match(favoriteTemplate, /\{\{managing \? '完成' : '管理'\}\}/);
  assert.match(favoriteTemplate, /class="favorite-selection/);
  assert.match(favoriteTemplate, /<product-card[\s\S]*variant="list"[\s\S]*flat="\{\{true\}\}"[\s\S]*bindselect="onProductSelect"[\s\S]*bindadd="onProductAdd"/);
  assert.match(favoriteTemplate, /取消收藏（\{\{selectedIds\.length\}\}）/);
  assert.match(favoriteTemplate, /bindtap="onCancelFavoritesTap"/);
  assert.doesNotMatch(favoriteTemplate, /account-heading|metaText|收藏时间|onClearTap|onRemoveTap/);
  assert.match(favoriteStyle, /\.favorites-navigation-title\s*\{[\s\S]*text-align:\s*left;/);
  assert.match(favoriteStyle, /\.favorite-row\s*\{[\s\S]*overflow:\s*hidden;/);
  assert.match(favoriteStyle, /\.favorite-row--managing[\s\S]*translateX\(/);
  assert.match(favoriteLogic, /await removeFavorites\(selectedSpuIds\)/);
  assert.match(favoriteLogic, /confirmAction\(\s*"取消收藏"/);
  assert.match(favoriteLogic, /const detail = await getProductDetail\(spuId\);[\s\S]*await addCartItem/);
  assert.match(favoriteLogic, /wx\.navigateTo\(\{ url: product\.navigationPath \}\)/);
  assert.match(favoriteLogic, /async refresh\(options: RefreshOptions = \{\}\)[\s\S]{0,360}loadingMore: false/);
  assert.doesNotMatch(favoriteLogic, /removeFavorite\(/);

  assert.match(historyTemplate, /data-id="\{\{item\.spuId\}\}"[\s\S]*bindtap="onProductTap"/);
  assert.match(historyTemplate, /aria-role="group"/);
  assert.match(historyTemplate, /aria-role="button"/);
  assert.match(historyTemplate, /aria-label="\{\{item\.available \?/);
  assert.match(historyTemplate, /aria-disabled="\{\{!managing && !item\.available\}\}"/);
  assert.match(historyLogic, /wx\.navigateTo\(\{ url: product\.navigationPath \}\)/);
  assert.match(historyLogic, /async refresh\(options: RefreshOptions = \{\}\)[\s\S]{0,360}loadingMore: false/);
  assert.match(historyTemplate, /<navigation-bar[\s\S]*wide-center="\{\{true\}\}"[\s\S]*slot="center" class="history-navigation-title">足迹<\/view>/);
  assert.match(historyTemplate, /\{\{managing \? '完成' : '管理'\}\}/);
  assert.match(historyTemplate, /bindtap="onClearTap"/);
  assert.match(historyTemplate, /class="history-selection/);
  assert.match(historyTemplate, /bindtap="onSelectAllToggle"/);
  assert.match(historyTemplate, /已选 \{\{selectedIds\.length\}\} 个商品/);
  assert.match(historyTemplate, /bindtap="onBatchDeleteTap"/);
  assert.match(historyTemplate, /catchtap="onAddCartTap"/);
  assert.match(historyTemplate, /history-card__cart-plus-horizontal/);
  assert.match(historyTemplate, /history-card__cart-plus-vertical/);
  assert.match(historyStyle, /\.history-navigation-title\s*\{[\s\S]*text-align:\s*left;/);
  assert.match(historyStyle, /\.history-card\s*\{[\s\S]*background:\s*#ffffff;/);
  assert.doesNotMatch(historyTemplate, /最近看过的商品|共 \{\{total\}\} 件|history-group__count|history-card__delete/);
  assert.match(historyLogic, /await deleteBrowseHistoryItems\(selectedSpuIds\)/);
  assert.match(historyLogic, /hasMore: response\.hasMore/);
  assert.doesNotMatch(historyLogic, /response\.total/);
  assert.match(historyLogic, /const detail = await getProductDetail\(spuId\);[\s\S]*await addCartItem/);
  const deleteSelectedSource = historyLogic.slice(
    historyLogic.indexOf("  async deleteSelected()"),
    historyLogic.indexOf("  onClearTap()")
  );
  assert.doesNotMatch(deleteSelectedSource, /confirmAction|showModal/);
  assert.match(orderTemplate, /class="order-card"[\s\S]*aria-role="group"[\s\S]*class="order-card__detail"[\s\S]*aria-role="button"/);
  assert.match(orderTemplate, /wx:for="\{\{item\.items\}\}"[\s\S]*class="order-product/);
  assert.match(orderTemplate, /binderror="onItemImageError"/);
  [
    "onCancelTap",
    "onModifyTap",
    "onMoreTap",
    "onDeleteMenuTap",
    "onAfterSaleTap",
    "onRebuyTap",
    "onReviewTap",
    "onPayTap"
  ]
    .forEach((handler) => assert.match(orderTemplate, new RegExp(`catchtap="${handler}"`)));
  assert.doesNotMatch(orderTemplate, /title="我的订单"/);
  assert.match(orderTemplate, /class="order-search"[\s\S]*搜索商品名称或订单号/);
  assert.match(orderTemplate, /class="order-card__time">下单时间：\{\{item\.createdAtText\}\}<\/text>/);
  assert.match(orderTemplate, /class="order-card__actions">[\s\S]*class="order-more"[\s\S]*catchtap="onMoreTap"[\s\S]*wx:if="\{\{item\.canCancel\}\}"/);
  assert.match(orderTemplate, /class="order-menu"[\s\S]*data-order-no="\{\{item\.orderNo\}\}"[\s\S]*catchtap="onCopyOrderNoTap"[\s\S]*>复制订单号<\/button>[\s\S]*wx:if="\{\{item\.canDelete\}\}"[\s\S]*>删除订单<\/button>/);
  assert.doesNotMatch(orderTemplate, /order-menu-mask|onMenuMaskTap/);
  assert.doesNotMatch(orderTemplate, /catchtap="onDeleteTap"/);
  assert.match(orderTemplate, /class="order-action order-action--more"[\s\S]*aria-label="更多订单操作[\s\S]*>更多<\/button>/);
  assert.doesNotMatch(orderTemplate, /order-more__dot/);
  assert.match(orderTemplate, /\{\{item\.afterSaleActionText\}\}/);
  assert.match(orderTemplate, /class="order-product__content"[\s\S]*class="order-product__details"[\s\S]*class="order-product__quantity"[\s\S]*class="order-product__price"/);
  assert.match(orderTemplate, /class="order-product__price"[\s\S]*class="order-product__after-sale-status">\{\{item\.afterSaleStatusText\}\}/);
  assert.doesNotMatch(orderTemplate, /order-status--\{\{item\.statusTone\}\}/);
  assert.match(orderStyle, /\.status-tab--active\s*\{[\s\S]*border:\s*1rpx solid #fe0000;/);
  assert.match(orderTemplate, /wx:if="\{\{keyword\}\}" class="order-search__text order-search__keyword">\{\{keyword\}\}<\/text>/);
  assert.match(orderStyle, /\.order-search__text\s*\{[\s\S]*height:\s*64rpx;[\s\S]*font-size:\s*@font-size-base;[\s\S]*line-height:\s*64rpx;/);
  assert.match(orderStyle, /\.order-search__keyword\s*\{[\s\S]*color:\s*#2e1d16;/);
  assert.match(orderStyle, /\.order-card__actions\s*\{[\s\S]*border:\s*0;/);
  assert.match(orderStyle, /button\.order-action--secondary\s*\{[\s\S]*border:\s*2rpx solid #c9c9c9;/);
  assert.match(orderStyle, /button\.order-action--primary\s*\{[\s\S]*color:\s*#fe0000;[\s\S]*background:\s*transparent;/);
  assert.match(orderStyle, /button\.order-action--more\s*\{[\s\S]*border:\s*0;[\s\S]*color:\s*@color-text-muted;/);
  assert.doesNotMatch(orderStyle, /\.order-more__dot/);
  assert.match(orderStyle, /\.order-more\s*\{[\s\S]*margin-right:\s*auto;/);
  assert.match(orderStyle, /\.order-product__stack-badge\s*\{[\s\S]*border-radius:\s*@radius-pill;/);
  assert.match(orderStyle, /\.order-product__bundle-amount-label\s*\{[\s\S]*color:\s*@color-text-muted;/);
  assert.match(orderStyle, /\.order-product__bundle-amount\s*\{[\s\S]*align-items:\s*baseline;[\s\S]*justify-content:\s*flex-end;/);
  assert.match(orderStyle, /\.order-product__content\s*\{[\s\S]*min-height:\s*176rpx;[\s\S]*grid-column:\s*2 \/ 4;[\s\S]*grid-template-rows:\s*auto auto;[\s\S]*align-content:\s*center;[\s\S]*row-gap:\s*@space-3;/);
  assert.match(orderStyle, /\.order-product__quantity\s*\{[\s\S]*grid-column:\s*1;[\s\S]*grid-row:\s*2;/);
  assert.match(orderStyle, /\.order-product__price\s*\{[\s\S]*grid-column:\s*2;[\s\S]*grid-row:\s*1;[\s\S]*justify-self:\s*end;/);
  assert.match(orderStyle, /\.order-product__after-sale-status\s*\{[\s\S]*grid-column:\s*2;[\s\S]*grid-row:\s*2;[\s\S]*justify-self:\s*end;[\s\S]*color:\s*@color-action-primary;[\s\S]*font-size:\s*@font-size-xs;/);
  assert.doesNotMatch(orderTemplate, /order-product__body|order-product__commerce/);
  assert.doesNotMatch(orderStyle, /grid-template-rows:\s*1fr auto;/);
  assert.match(orderTemplate, /wx:elif="\{\{item\.items\.length > 1\}\}"[\s\S]*order-product--bundle[\s\S]*\+?\{\{item\.items\.length - 1\}\}[\s\S]*\{\{item\.amountText\}\}/);
  assert.match(orderStyle, /\.order-menu\s*\{[\s\S]*bottom:\s*76rpx;[\s\S]*background:\s*#ffffff;[\s\S]*box-shadow:/);
  assert.doesNotMatch(orderStyle, /\.order-menu-mask/);
  assert.match(orderSearchTemplate, /搜索商品名称或订单号/);
  assert.match(orderSearchLogic, /ORDER_SEARCH_HISTORY_KEY/);
  assert.match(orderService, /\.\.\.\(query\.keyword \? \{ keyword: query\.keyword \} : \{\}\)/);
  assert.doesNotMatch(orderTemplate, /灶香集|order-card__merchant|onSyncTap|onConfirmTap|评价晒单/);
  assert.match(orderLogic, /await deleteOrder\(orderId\)/);
  assert.match(orderLogic, /const detail = await getOrderDetail\(orderId\)[\s\S]*await addCartItem/);
  assert.match(orderLogic, /buildOrderReviewUrl\(orderId\)/);
  assert.match(orderLogic, /buildOrderModifyUrl\(orderId\)/);
  assert.match(orderLogic, /if \(paid\) \{\s*this\.navigatePaymentSuccess\(orderId\);/);
  assert.match(orderLogic, /navigatePaymentSuccess\(orderId: number\)[\s\S]*wx\.navigateTo\(\{[\s\S]*pages\/order\/created\/created/);
  assert.match(orderLogic, /this\.data\.loadingMore/);
  assert.match(orderLogic, /loading: true, loadingMore: false/);
  assert.match(profileLogic, /group: "TO_REVIEW",[\s\S]{0,80}label: "待评价"/);
});

test("全局导航统一返回图标且不再显示首页按钮和加载圈", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as AppConfig;
  const navigationTemplate = readFileSync(
    resolve(sourceRoot, "components/navigation-bar/navigation-bar.wxml"),
    "utf8"
  );
  const navigationLogic = readFileSync(
    resolve(sourceRoot, "components/navigation-bar/navigation-bar.ts"),
    "utf8"
  );
  const navigationStyle = readFileSync(
    resolve(sourceRoot, "components/navigation-bar/navigation-bar.less"),
    "utf8"
  );
  const addressListTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/address/list/list.wxml"),
    "utf8"
  );

  assert.match(navigationTemplate, /navigation-back\.svg/);
  assert.match(navigationLogic, /background:\s*\{[\s\S]*?value: '#ffffff'/);
  assert.match(navigationLogic, /lightBack:\s*\{[\s\S]*?value: false/);
  assert.match(navigationStyle, /\.navigation-bar\s*\{[\s\S]*?background: @color-surface-white;/);
  assert.match(navigationStyle, /\.navigation-bar__back-icon--light\s*\{[^}]*filter:/);
  assert.doesNotMatch(navigationTemplate, /navigation-bar__home|handleHome/);
  assert.doesNotMatch(navigationTemplate, /navigation-bar__loading|\{\{loading\}\}/);
  assert.doesNotMatch(navigationLogic, /\bhome:\s*\{|handleHome\(/);
  assert.doesNotMatch(navigationLogic, /\bloading:\s*\{/);
  assert.doesNotMatch(navigationStyle, /navigation-bar__loading|navigation-bar-spin/);
  assert.doesNotMatch(addressListTemplate, /\bhome=/);

  appConfig.pages.forEach((pagePath) => {
    const templatePath = resolve(sourceRoot, `${pagePath}.wxml`);
    if (!existsSync(templatePath)) {
      return;
    }

    const template = readFileSync(templatePath, "utf8");
    const navigationTags = template.match(/<navigation-bar\b[\s\S]*?(?:\/>|>)/g) ?? [];
    navigationTags.forEach((navigationTag) => {
      assert.doesNotMatch(
        navigationTag,
        /\bloading=/,
        `${pagePath} should not pass loading state to navigation-bar`
      );
    });
  });
});

test("全局空状态统一使用黑色图标和灰色说明文字", () => {
  const emptyIconNames = [
    "empty-addresses.svg",
    "empty-after-sale.svg",
    "empty-cart.svg",
    "empty-coupons.svg",
    "empty-favorites.svg",
    "empty-history.svg",
    "empty-orders.svg",
    "empty-products.svg",
    "empty-search.svg"
  ];
  const uiStateStyle = readFileSync(
    resolve(sourceRoot, "components/ui-state/ui-state.less"),
    "utf8"
  );

  emptyIconNames.forEach((iconName) => {
    const icon = readFileSync(resolve(sourceRoot, "assets/icons", iconName), "utf8");
    assert.match(icon, /fill="#1C1C1E"/i);
    assert.doesNotMatch(icon, /#A79386/i);
  });
  assert.match(
    uiStateStyle,
    /\.ui-state__empty-description\s*\{[\s\S]*?color: @color-text-gray;/
  );
});

test("全局服务繁忙状态使用中性样式和统一文案", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as AppConfig;
  const uiStateTemplate = readFileSync(
    resolve(sourceRoot, "components/ui-state/ui-state.wxml"),
    "utf8"
  );
  const uiStateStyle = readFileSync(
    resolve(sourceRoot, "components/ui-state/ui-state.less"),
    "utf8"
  );
  const homeStyle = readFileSync(
    resolve(sourceRoot, "pages/index/index.less"),
    "utf8"
  );
  const templatePaths = [
    ...configuredPagePaths(appConfig).map((pagePath) => `${pagePath}.wxml`),
    "components/catalog-browser/catalog-browser.wxml"
  ];

  assert.doesNotMatch(uiStateTemplate, /ui-state__error-mark|variant="danger"/);
  assert.match(uiStateTemplate, /variant="secondary"/);
  assert.doesNotMatch(uiStateStyle, /ui-state__error-mark|@color-danger/);
  assert.match(
    homeStyle,
    /\.home-state-shell,[\s\S]*?border: 0;[\s\S]*?background: transparent;[\s\S]*?box-shadow: none;/
  );

  templatePaths.forEach((templatePath) => {
    const template = readFileSync(resolve(sourceRoot, templatePath), "utf8");
    const stateTags = template.match(/<ui-state\b[\s\S]*?\/>/g) ?? [];
    stateTags
      .filter((tag) => (
        /type="error"/.test(tag)
        && /description="\{\{(?:errorText|loadErrorText)\}\}"/.test(tag)
      ))
      .forEach((tag) => {
        assert.match(tag, /title="当前服务繁忙"/, templatePath);
        assert.doesNotMatch(tag, /action-text=|bindaction=/, templatePath);
      });
  });
});

test("图片缺失与加载占位背景统一为白色", () => {
  const placeholderSelectors = new Map<string, string[]>([
    ["components/home-banner/home-banner.less", ["hero-section", "hero-swiper", "hero-placeholder"]],
    ["components/home-category-grid/home-category-grid.less", ["category-placeholder"]],
    ["components/product-card/product-card.less", ["product-card__image-wrap", "product-card__placeholder"]],
    ["components/product-gallery/product-gallery.less", ["gallery-shell", "gallery-image", "gallery-placeholder"]],
    ["pages/account/history/history.less", ["history-card__image-wrap", "history-card__placeholder"]],
    ["styles/account-products.less", ["account-product__image-wrap", "account-product__placeholder"]],
    ["pages/cart/cart.less", ["cart-card__image-shell"]],
    ["pages/order/preview/preview.less", ["preview-item__image-shell"]],
    ["pages/order/detail/detail.less", ["detail-item__image-shell"]],
    ["pages/order/list/list.less", ["order-product__image-shell"]],
    ["pages/after-sale/apply/apply.less", ["order-item__image-shell"]],
    ["pages/order/review/review.less", ["product-option__image", "review-product__image-shell", "review-image-item__image"]],
    ["pages/product/detail/detail.less", ["purchase-image", "review-image-gallery__image", "review-spec-product__image"]]
  ]);

  placeholderSelectors.forEach((selectors, path) => {
    const style = readFileSync(resolve(sourceRoot, path), "utf8");
    selectors.forEach((selector) => {
      assert.match(
        style,
        new RegExp(`\\.${selector}\\s*\\{[^}]*background:\\s*(?:@color-surface-white|#fff(?:fff)?);`),
        `${path} .${selector} should use a white placeholder background`
      );
    });
  });

  const customerServiceStyle = readFileSync(
    resolve(sourceRoot, "pages/customer-service/chat/chat.less"),
    "utf8"
  );
  assert.match(
    customerServiceStyle,
    /\.chat-card__image-shell,[\s\S]*?\.candidate__image-shell\s*\{[^}]*background:\s*#ffffff;/
  );
});

test("首页加载骨架使用白底灰色斜向扫光", () => {
  const homeStyle = readFileSync(
    resolve(sourceRoot, "pages/index/index.less"),
    "utf8"
  );

  assert.match(
    homeStyle,
    /\.home-skeleton\s*\{[^}]*background:\s*@color-surface-white;/
  );
  assert.match(
    homeStyle,
    /\.skeleton-block\s*\{[\s\S]*?background:\s*linear-gradient\(\s*105deg,[\s\S]*?fade\(@color-text-black,\s*7%\)[\s\S]*?fade\(@color-text-black,\s*3%\)[\s\S]*?background-size:\s*320% 100%;[\s\S]*?animation:\s*skeleton-shimmer 1\.35s ease-in-out infinite;/
  );
  assert.match(
    homeStyle,
    /@keyframes skeleton-shimmer\s*\{[\s\S]*?0%\s*\{\s*background-position:\s*100% 0;\s*\}[\s\S]*?100%\s*\{\s*background-position:\s*0 0;\s*\}/
  );
});

test("核心交易页面首次加载使用共用灰色扫光骨架", () => {
  const stateLogic = readFileSync(
    resolve(sourceRoot, "components/ui-state/ui-state.ts"),
    "utf8"
  );
  const stateTemplate = readFileSync(
    resolve(sourceRoot, "components/ui-state/ui-state.wxml"),
    "utf8"
  );
  const stateStyle = readFileSync(
    resolve(sourceRoot, "components/ui-state/ui-state.less"),
    "utf8"
  );

  assert.match(stateLogic, /skeletonType:\s*\{[\s\S]*?type:\s*String,[\s\S]*?value:\s*''/);
  ["catalog", "product-detail", "cart", "record-list", "record-detail"].forEach((type) => {
    assert.match(stateTemplate, new RegExp(`skeletonType === '${type}'`), type);
  });
  assert.match(stateTemplate, /type === 'loading' && skeletonType/);
  assert.match(stateTemplate, /wx:elif="\{\{type === 'loading'\}\}" class="ui-state__loading-toast"/);
  assert.match(
    stateStyle,
    /\.loading-skeleton__block\s*\{[\s\S]*?linear-gradient\(\s*105deg,[\s\S]*?fade\(@color-text-black,\s*7%\)[\s\S]*?fade\(@color-text-black,\s*3%\)[\s\S]*?animation:\s*ui-skeleton-shimmer 1\.35s ease-in-out infinite;/
  );
  assert.match(stateStyle, /\.loading-skeleton__card\s*\{[^}]*background:\s*@color-surface-white;/);
  assert.match(
    stateStyle,
    /@keyframes ui-skeleton-shimmer\s*\{[\s\S]*?background-position:\s*100% 0;[\s\S]*?background-position:\s*0 0;/
  );

  const skeletonBindings = new Map<string, string>([
    ["components/catalog-browser/catalog-browser.wxml", "catalog"],
    ["pages/product/detail/detail.wxml", "product-detail"],
    ["pages/cart/cart.wxml", "cart"],
    ["pages/order/list/list.wxml", "record-list"],
    ["pages/order/detail/detail.wxml", "record-detail"],
    ["pages/order/preview/preview.wxml", "workflow"],
    ["pages/order/modify/modify.wxml", "workflow"],
    ["pages/order/review/review.wxml", "workflow"],
    ["pages/after-sale/apply/apply.wxml", "workflow"],
    ["components/after-sale-list/after-sale-list.wxml", "record-list"],
    ["pages/after-sale/detail/detail.wxml", "record-detail"]
  ]);

  skeletonBindings.forEach((skeletonType, path) => {
    const template = readFileSync(resolve(sourceRoot, path), "utf8");
    assert.match(template, /wx:if="\{\{!loaded && loading\}\}"/, path);
    assert.match(
      template,
      new RegExp(`<ui-state\\s+type="loading"\\s+skeleton-type="${skeletonType}"`),
      path
    );
  });

  const categoryTemplate = readFileSync(
    resolve(sourceRoot, "pages/category/category.wxml"),
    "utf8"
  );
  const productListTemplate = readFileSync(
    resolve(sourceRoot, "pages/product/list/list.wxml"),
    "utf8"
  );
  const orderListTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/list/list.wxml"),
    "utf8"
  );
  const afterSalePageTemplate = readFileSync(
    resolve(sourceRoot, "pages/after-sale/list/list.wxml"),
    "utf8"
  );
  assert.match(categoryTemplate, /<catalog-browser/);
  assert.match(productListTemplate, /<catalog-browser/);
  assert.match(orderListTemplate, /<after-sale-list/);
  assert.match(afterSalePageTemplate, /<after-sale-list/);
});

test("账户与订单相关页面固定顶部导航并在内部滚动", () => {
  const fixedPages = [
    {
      path: "pages/account/history/history",
      rootClass: "account-page",
      scrollClass: "account-scroll",
      stylePath: "styles/account-products.less",
      refreshable: true,
      pageable: true
    },
    {
      path: "pages/account/favorites/favorites",
      rootClass: "account-page",
      scrollClass: "account-scroll",
      stylePath: "styles/account-products.less",
      refreshable: true,
      pageable: true
    },
    {
      path: "pages/account/coupon/coupon",
      rootClass: "coupon-page",
      scrollClass: "coupon-scroll",
      stylePath: "pages/account/coupon/coupon.less",
      refreshable: true,
      pageable: false
    },
    {
      path: "pages/order/list/list",
      rootClass: "orders-page",
      scrollClass: "orders-scroll",
      stylePath: "pages/order/list/list.less",
      refreshable: true,
      pageable: true
    },
    {
      path: "pages/order/detail/detail",
      rootClass: "detail-page",
      scrollClass: "detail-scroll",
      stylePath: "pages/order/detail/detail.less",
      refreshable: false,
      pageable: false
    },
    {
      path: "pages/order/review/review",
      rootClass: "review-page",
      scrollClass: "review-scroll",
      stylePath: "pages/order/review/review.less",
      refreshable: false,
      pageable: false
    },
    {
      path: "pages/order/preview/preview",
      rootClass: "preview-page",
      scrollClass: "preview-scroll",
      stylePath: "pages/order/preview/preview.less",
      refreshable: false,
      pageable: false
    },
    {
      path: "pages/order/modify/modify",
      rootClass: "modify-page",
      scrollClass: "modify-scroll",
      stylePath: "pages/order/modify/modify.less",
      refreshable: true,
      pageable: false
    },
    {
      path: "pages/after-sale/detail/detail",
      rootClass: "after-sale-detail-page",
      scrollClass: "detail-scroll",
      stylePath: "pages/after-sale/detail/detail.less",
      refreshable: false,
      pageable: false
    },
    {
      path: "pages/after-sale/apply/apply",
      rootClass: "apply-page",
      scrollClass: "apply-scroll",
      stylePath: "pages/after-sale/apply/apply.less",
      refreshable: false,
      pageable: false
    },
    {
      path: "pages/account/address/list/list",
      rootClass: "address-page",
      scrollClass: "address-scroll",
      stylePath: "pages/account/address/list/list.less",
      refreshable: true,
      pageable: false
    },
    {
      path: "pages/account/profile/profile",
      rootClass: "user-profile-page",
      scrollClass: "user-profile-scroll",
      stylePath: "pages/account/profile/profile.less",
      refreshable: false,
      pageable: false
    }
  ];

  fixedPages.forEach((page) => {
    const config = JSON.parse(
      readFileSync(resolve(sourceRoot, `${page.path}.json`), "utf8")
    ) as DetailPageConfig;
    const template = readFileSync(resolve(sourceRoot, `${page.path}.wxml`), "utf8");
    const logic = readFileSync(resolve(sourceRoot, `${page.path}.ts`), "utf8");
    const style = readFileSync(resolve(sourceRoot, page.stylePath), "utf8");

    assert.equal(config.disableScroll, true, `${page.path} should disable page scrolling`);
    assert.equal(config.enablePullDownRefresh, false, `${page.path} should disable native pull refresh`);
    assert.match(
      template,
      new RegExp(`<navigation-bar[\\s\\S]*?<scroll-view[\\s\\S]*?class="${page.scrollClass}"[\\s\\S]*?scroll-y="\\{\\{true\\}\\}"`)
    );
    assert.match(
      style,
      new RegExp(`\\.${page.rootClass}\\s*\\{[\\s\\S]*?height: 100vh;[\\s\\S]*?display: flex;[\\s\\S]*?overflow: hidden;[\\s\\S]*?flex-direction: column;`)
    );
    assert.match(
      style,
      new RegExp(`\\.${page.scrollClass}\\s*\\{[\\s\\S]*?height: 0;[\\s\\S]*?min-height: 0;[\\s\\S]*?flex: 1;`)
    );

    if (page.refreshable) {
      assert.match(template, /refresher-enabled="\{\{true\}\}"/);
      assert.match(template, /bounces="\{\{false\}\}"/);
      assert.match(template, /refresher-triggered="\{\{contentRefreshing\}\}"/);
      assert.match(template, /bindrefresherrefresh="onContentRefresh"/);
      assert.match(logic, /contentRefreshing: false/);
      assert.match(logic, /async onContentRefresh\(\)[\s\S]*contentRefreshing: true[\s\S]*contentRefreshing: false/);
      assert.match(logic, /silent: true, suppressError: true/);
      assert.doesNotMatch(logic, /onPullDownRefresh|stopPullDownRefresh/);
    } else {
      assert.doesNotMatch(template, /bindrefresherrefresh=/);
    }
    if (page.pageable) {
      assert.match(template, /bindscrolltolower="onReachBottom"/);
    }
  });

  const couponTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/coupon/coupon.wxml"),
    "utf8"
  );
  const orderTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/list/list.wxml"),
    "utf8"
  );
  assert.match(couponTemplate, /class="coupon-section-tabs"[\s\S]*?class="coupon-scroll"/);
  assert.match(orderTemplate, /class="status-tabs"[\s\S]*?class="orders-scroll"/);

  const afterSaleListPageTemplate = readFileSync(
    resolve(sourceRoot, "pages/after-sale/list/list.wxml"),
    "utf8"
  );
  const afterSaleListComponentTemplate = readFileSync(
    resolve(sourceRoot, "components/after-sale-list/after-sale-list.wxml"),
    "utf8"
  );
  const afterSaleListComponentStyle = readFileSync(
    resolve(sourceRoot, "components/after-sale-list/after-sale-list.less"),
    "utf8"
  );
  assert.match(afterSaleListPageTemplate, /<navigation-bar[\s\S]*?<after-sale-list/);
  assert.match(
    afterSaleListComponentTemplate,
    /class="after-sale-tabs"[\s\S]*?<scroll-view[\s\S]*?class="after-sale-scroll"[\s\S]*?scroll-y="\{\{true\}\}"/
  );
  assert.match(afterSaleListComponentTemplate, /refresher-enabled="\{\{true\}\}"/);
  assert.match(afterSaleListComponentTemplate, /bindscrolltolower="onReachBottom"/);
  assert.match(afterSaleListComponentTemplate, /cardStatusText[\s\S]*cardStatusDescription/);
  assert.match(afterSaleListComponentStyle, /\.after-sale-product__body\s*\{[\s\S]*min-height:\s*176rpx;[\s\S]*justify-content:\s*space-between;/);
  assert.match(afterSaleListComponentStyle, /\.after-sale-product__refund\s*\{[\s\S]*min-height:\s*176rpx;[\s\S]*align-items:\s*flex-end;[\s\S]*justify-content:\s*flex-end;[\s\S]*text-align:\s*right;/);
  assert.match(afterSaleListComponentStyle, /\.after-sale-card__status-summary\s*\{[\s\S]*padding:\s*14rpx 18rpx;[\s\S]*border-radius:\s*@radius-sm;[\s\S]*align-items:\s*center;[\s\S]*background:\s*#f5f5f5;[\s\S]*gap:\s*@space-4;/);
  assert.match(afterSaleListComponentStyle, /\.after-sale-card__status-text\s*\{[\s\S]*color:\s*@color-text-primary;/);
  assert.match(afterSaleListComponentStyle, /\.after-sale-card__status-description\s*\{[\s\S]*color:\s*@color-text-secondary;[\s\S]*text-overflow:\s*ellipsis;[\s\S]*white-space:\s*nowrap;/);
  assert.match(
    afterSaleListComponentStyle,
    /\.after-sale-scroll\s*\{[\s\S]*?height: 0;[\s\S]*?min-height: 0;[\s\S]*?flex: 1;/
  );
});

test("商品详情使用自建规格、评价和收货地址弹层", () => {
  const detailPageRoot = resolve(sourceRoot, "pages/product/detail/detail");
  const detailConfig = JSON.parse(
    readFileSync(`${detailPageRoot}.json`, "utf8")
  ) as DetailPageConfig;
  const detailTemplate = readFileSync(`${detailPageRoot}.wxml`, "utf8");
  const detailLogic = readFileSync(`${detailPageRoot}.ts`, "utf8");
  const detailStyle = readFileSync(`${detailPageRoot}.less`, "utf8");
  const detailChevronIcon = readFileSync(
    resolve(sourceRoot, "assets/icons/chevron-right-detail.svg"),
    "utf8"
  );
  const detailInformationIcons = [
    "sell-outline-rounded.svg",
    "location-on-outline-rounded.svg",
    "local-shipping-outline-rounded.svg"
  ].map((iconName) => readFileSync(resolve(sourceRoot, "assets/icons", iconName), "utf8"));
  const productSummaryStyle = readFileSync(
    resolve(sourceRoot, "components/product-summary/product-summary.less"),
    "utf8"
  );
  const productSummaryTemplate = readFileSync(
    resolve(sourceRoot, "components/product-summary/product-summary.wxml"),
    "utf8"
  );
  const designTokens = readFileSync(
    resolve(sourceRoot, "styles/tokens.less"),
    "utf8"
  );

  assert.equal(detailConfig.enablePullDownRefresh, false);
  assert.match(detailTemplate, /<navigation-bar[\s\S]*?background="transparent"/);
  assert.doesNotMatch(detailTemplate, /<sku-selector|stock-text=|categoryName/);
  assert.match(detailTemplate, /data-mode="CART"/);
  assert.match(detailTemplate, /data-mode="BUY"/);
  assert.match(detailTemplate, /wx:if="\{\{purchaseSheetOpen\}\}"/);
  assert.match(detailTemplate, />买家评价</);
  assert.match(detailTemplate, /\{\{reviewSummary\.reviewCountPlusText\}\}/);
  assert.match(detailTemplate, /reviewSummary\.hasReviews \? reviewSummary\.goodRateText : '暂无评价'/);
  assert.doesNotMatch(detailTemplate, /class="review-score"/);
  const reviewPreviewTemplate = detailTemplate.match(
    /<view wx:elif="\{\{reviewPreview\.length\}\}"[\s\S]*?<view wx:else class="review-empty-preview">/
  )?.[0] ?? "";
  assert.match(reviewPreviewTemplate, /src="\/assets\/images\/profile-default-avatar\.png"/);
  assert.match(reviewPreviewTemplate, /src="\/assets\/images\/member-avatar-frame\.png"/);
  assert.doesNotMatch(
    reviewPreviewTemplate,
    /reviewerInitial|review-stars|review-item__date|review-item__meta|verifiedPurchase|skuSpecText/
  );
  assert.match(
    reviewPreviewTemplate,
    /review-item__content--empty'\}\}">\{\{review\.hasContent \? review\.content/
  );
  assert.match(reviewPreviewTemplate, /class="review-preview-media"/);
  assert.match(reviewPreviewTemplate, /src="\{\{review\.previewImageUrl\}\}"/);
  assert.match(reviewPreviewTemplate, /class="review-preview-media__count">\{\{review\.imageCount\}\}/);
  assert.doesNotMatch(reviewPreviewTemplate, /wx:for="\{\{review\.images\}\}"/);
  assert.match(detailLogic, /const REVIEW_PREVIEW_SIZE = 2;/);
  assert.match(detailStyle, /\.review-card\s*\{[\s\S]*?background: @color-surface-white;/);
  assert.match(
    detailTemplate,
    /class="commerce-info-group"[\s\S]*?tune-outline-rounded\.svg[\s\S]*?location-on-outline-rounded\.svg[\s\S]*?data-sheet="guarantee"[\s\S]*?data-sheet="freight"/
  );
  assert.match(detailStyle, /\.commerce-info-group\s*\{[\s\S]*?background: @color-surface-white;[\s\S]*?box-shadow: none;/);
  assert.match(detailStyle, /\.commerce-info-group \.commerce-row\s*\{[\s\S]*?min-height: 76rpx;[\s\S]*?gap: 14rpx;/);
  assert.match(detailStyle, /\.commerce-row--detail-info\s*\{[\s\S]*?border: 0;[\s\S]*?background: transparent;[\s\S]*?box-shadow: none;/);
  assert.equal((detailTemplate.match(/chevron-right-detail\.svg/g) ?? []).length, 5);
  assert.match(detailChevronIcon, /#a8abb3/i);
  detailInformationIcons.forEach((icon) => assert.match(icon, /#a8abb3/i));
  assert.match(detailTemplate, /data-sheet="guarantee"[\s\S]{0,260}profile-about\.svg/);
  assert.match(detailTemplate, /class="review-toolbar__truth"[\s\S]{0,160}profile-about\.svg/);
  assert.match(detailTemplate, /class="utility-icon utility-icon--support"[\s\S]{0,100}profile-customer-service\.svg/);
  assert.match(detailTemplate, /bindtap="onGoToCart"[\s\S]{0,200}src="\/assets\/icons\/tab-cart\.svg"/);
  assert.match(detailTemplate, /data-sheet="freight"[\s\S]{0,260}local-shipping-outline-rounded\.svg/);
  assert.match(detailLogic, /\.join\("｜"\)/);
  assert.match(detailLogic, /summary: `\$\{cleanText\(template\.name\)\}｜\$\{chargeText\}`/);
  assert.match(
    detailTemplate,
    /class="commerce-row commerce-row--detail-info commerce-row--address commerce-row--interactive"[\s\S]{0,100}bindtap="onAddressTap"/
  );
  assert.match(
    detailTemplate,
    /commerce-address-copy[\s\S]{0,220}commerce-value__text--address">\{\{selectedAddress \? selectedAddress\.formattedAddress[\s\S]{0,180}commerce-default-tag">默认/
  );
  assert.match(detailStyle, /\.commerce-value__text--address\s*\{\s*flex: 0 1 auto;/);
  assert.match(detailTemplate, /activeSheet === 'guarantee' \? 'sheet-panel--guarantee'/);
  assert.match(detailTemplate, /activeSheet === 'freight' \? 'sheet-panel--freight'/);
  assert.match(detailTemplate, /activeSheet === 'wholesale' \? 'sheet-panel--wholesale'/);
  assert.doesNotMatch(detailTemplate, /sheet-mask--above-purchase/);
  assert.doesNotMatch(detailStyle, /\.sheet-mask--above-purchase/);
  assert.match(
    detailStyle,
    /\.sheet-panel--address,\s*\.sheet-panel--parameters,\s*\.sheet-panel--guarantee,\s*\.sheet-panel--freight,\s*\.sheet-panel--wholesale\s*\{[\s\S]*?height: 80vh;[\s\S]*?background: @color-surface-white;/
  );
  assert.match(
    detailTemplate,
    /activeSheet === 'guarantee'[\s\S]*?parameter-sheet-title">安心保障[\s\S]*?>我知道了<\/button>/
  );
  assert.match(
    detailTemplate,
    /activeSheet === 'freight'[\s\S]*?parameter-sheet-title">运费说明[\s\S]*?>我知道了<\/button>/
  );
  assert.match(
    detailTemplate,
    /activeSheet === 'wholesale'[\s\S]*?parameter-sheet-title">批发价格[\s\S]*?wx:for="\{\{wholesaleTiers\}\}"[\s\S]*?>我知道了<\/button>/
  );
  assert.match(
    detailTemplate,
    /class="commerce-info-group"[\s\S]*?data-sheet="wholesale"[\s\S]*?sell-outline-rounded\.svg[\s\S]*?data-sheet="parameters"/
  );
  assert.doesNotMatch(detailTemplate, /info-sheet-header|wholesale-table/);
  assert.match(detailStyle, /@keyframes sheet-rise\s*\{\s*from \{ transform: translateY\(100%\); \}\s*to \{ transform: translateY\(0\); \}/);
  assert.match(
    detailTemplate,
    /activeSheet === 'address'[\s\S]*?parameter-sheet-title">选择收货地址[\s\S]*?class="address-sheet-intro">请选择已保存的地址/
  );
  assert.match(detailStyle, /\.address-sheet-intro\s*\{[\s\S]*?color: #8f939c;/);
  assert.match(detailTemplate, /class="address-option__check"[\s\S]{0,260}check-rounded-material-symbols-iconify\.svg/);
  assert.match(detailStyle, /\.address-option--selected \.address-option__check\s*\{[\s\S]*?background: #ff172b;/);
  assert.match(detailStyle, /\.add-address-action\s*\{[\s\S]*?height: 96rpx;[\s\S]*?background: #ff172b;/);
  assert.match(detailStyle, /\.review-card\s*\{[\s\S]*?border: 0;/);
  assert.doesNotMatch(detailStyle, /\.review-item\s*\{[^}]*border-bottom:/);
  assert.match(detailStyle, /\.review-preview-list \.review-item\s*\{[\s\S]*?padding: 10rpx 0;/);
  assert.match(detailStyle, /\.review-preview-list \.review-user\s*\{\s*align-items: center;/);
  assert.match(detailStyle, /\.review-preview-list \.review-user__avatar\s*\{[\s\S]*?width: 42rpx;[\s\S]*?border: 0;/);
  assert.match(detailStyle, /\.review-card__count\s*\{[\s\S]*?color: @color-text-primary;[\s\S]*?font-size: @font-size-md;/);
  assert.match(detailStyle, /\.review-preview-list \.review-user__name\s*\{[\s\S]*?font-family: -apple-system,[\s\S]*?font-size: 26rpx;[\s\S]*?font-weight: 400;/);
  assert.match(detailStyle, /\.review-preview-list \.review-item__content\s*\{[\s\S]*?padding-left: 60rpx;[\s\S]*?line-height: 36rpx;[\s\S]*?-webkit-line-clamp: 3;/);
  assert.match(detailStyle, /\.review-preview-media\s*\{[\s\S]*?width: 158rpx;[\s\S]*?height: 158rpx;[\s\S]*?background: transparent;/);
  assert.match(detailStyle, /\.review-preview-media__image\s*\{[\s\S]*?background: transparent;/);
  assert.match(detailStyle, /padding: 0 16rpx calc\(154rpx \+ env\(safe-area-inset-bottom\)\);/);
  assert.match(detailStyle, /\.detail-page\s*\{[\s\S]*?background: @color-page;/);
  assert.match(detailStyle, /\.detail-scroll\s*\{[\s\S]*?background: @color-page;/);
  assert.match(detailStyle, /\.detail-scroll-content\s*\{[\s\S]*?background: @color-page;/);
  assert.match(detailStyle, /\.detail-card\s*\{[\s\S]*?box-shadow: none;/);
  assert.match(detailStyle, /\.commerce-row\s*\{[\s\S]*?box-shadow: none;/);
  assert.match(detailStyle, /\.commerce-info-group\s*\{[\s\S]*?box-shadow: none;/);
  assert.match(detailStyle, /\.purchase-bar\s*\{[\s\S]*?background: @color-surface-white;[\s\S]*?box-shadow: none;/);
  assert.match(detailTemplate, />暂时售空</);
  assert.doesNotMatch(detailTemplate, /当前暂无可售规格|purchase-sold-out__hint/);
  assert.match(
    detailStyle,
    /\.purchase-action--buy,\s*\.purchase-sold-out\s*\{[^}]*background: @color-action-primary;/
  );
  assert.match(detailTemplate, /activeSheet === 'reviews' \? 'sheet-panel--reviews'/);
  assert.match(detailStyle, /\.sheet-panel--reviews\s*\{[\s\S]*?height: 72vh;[\s\S]*?background: #f6f6f6;/);
  assert.match(
    detailStyle,
    /\.sheet-mask--reviews\s*\{\s*bottom: calc\(104rpx \+ env\(safe-area-inset-bottom\)\);\s*overflow: hidden;\s*\}/
  );
  assert.match(detailLogic, /purchaseSheetOpen: false/);
  assert.match(detailLogic, /purchaseSheetOpen: true,[\s\S]{0,180}purchaseMode/);
  assert.doesNotMatch(detailLogic, /activeSheet === "reviews"[\s\S]{0,120}animateSheetClose/);
  assert.match(
    detailTemplate,
    /wx:if="\{\{purchaseSheetOpen\}\}"[\s\S]{0,260}purchase-sheet-mask[\s\S]*?catchtap="onClosePurchaseSheet"/
  );
  assert.match(detailStyle, /\.purchase-sheet-mask\s*\{\s*z-index: 360;\s*\}/);
  assert.match(detailStyle, /\.sheet-panel--purchase\s*\{[\s\S]*?background: @color-page;/);
  assert.match(detailStyle, /\.purchase-sheet-header\s*\{[\s\S]*?background: @color-surface-white;/);
  assert.match(detailStyle, /\.purchase-sheet-scroll\s*\{[\s\S]*?background: @color-page;/);
  assert.match(detailStyle, /\.purchase-sheet-scroll-content\s*\{[\s\S]*?background: @color-page;/);
  assert.match(detailStyle, /\.sheet-section\s*\{[\s\S]*?background: @color-surface-white;/);
  assert.match(detailStyle, /\.sheet-footer\s*\{[\s\S]*?border-top: 8rpx solid @color-page;[\s\S]*?background: @color-page;/);
  assert.match(detailTemplate, />中\/差评 \{\{reviewSummary\.criticalReviewCount\}\}<\/button>/);
  assert.match(detailStyle, /\.review-filter-bar\s*\{[\s\S]*?display: flex;[\s\S]*?background: #f6f6f6;[\s\S]*?gap: 14rpx;/);
  assert.match(detailStyle, /\.review-filter-chip\s*\{[\s\S]*?width: auto !important;[\s\S]*?min-width: 96rpx !important;[\s\S]*?color: #000000;[\s\S]*?background: #ffffff;/);
  assert.match(detailStyle, /\.review-filter-chip--active\s*\{[\s\S]*?border: 1rpx solid #fe0000;[\s\S]*?color: #fe0000;[\s\S]*?background: #ffebef;/);
  assert.match(detailStyle, /\.review-item--sheet\s*\{[\s\S]*?background: @color-surface-white;/);
  assert.match(detailStyle, /\.review-toolbar__actions\s*\{[\s\S]*?justify-content: flex-end;[\s\S]*?margin-left: auto;/);
  assert.match(detailTemplate, /review-rating-summary__label">\{\{review\.ratingLabel\}\}/);
  assert.match(detailTemplate, /favorite-clarity-solid\.svg/);
  assert.match(
    detailTemplate,
    /class="review-purchase-spec">\{\{review\.purchaseSpecText \? '｜已购 ' \+ review\.purchaseSpecText : '｜已购'\}\}/
  );
  assert.match(
    detailTemplate,
    /wx:if="\{\{selectedSkuName\}\}" class="selected-spec">已选：\{\{selectedSkuName\}\}/
  );
  assert.match(
    detailTemplate,
    /wx:if="\{\{reviewSpecOptions\.length\}\}"[\s\S]{0,140}bindtap="onReviewSpecOpen"/
  );
  const reviewSheetTemplate = detailTemplate.match(
    /<block wx:elif="\{\{activeSheet === 'reviews'\}\}">[\s\S]*?<\/block>\s*<\/view>\s*<\/view>\s*<\/view>/
  )?.[0] ?? "";
  assert.doesNotMatch(reviewSheetTemplate, /review\.createdAtText/);
  assert.match(reviewSheetTemplate, /review\.contentCollapsible && !review\.contentExpanded/);
  assert.match(reviewSheetTemplate, />…展开<\/button>/);
  assert.match(reviewSheetTemplate, /class="review-content-collapse"[\s\S]*?> 收起<\/text>/);
  assert.match(reviewSheetTemplate, /bindtap="onReviewContentToggle"/);
  assert.match(detailStyle, /\.review-item--sheet \.review-item__content\s*\{[\s\S]*?-webkit-line-clamp: 5;/);
  assert.match(detailStyle, /\.review-toolbar-action\s*\{[\s\S]*?font-size: 24rpx;/);
  assert.match(detailStyle, /\.review-toolbar-action image\s*\{[\s\S]*?width: 31rpx;[\s\S]*?height: 31rpx;/);
  assert.match(detailStyle, /\.review-content-toggle\s*\{[\s\S]*?color: #1677ff;/);
  assert.match(detailStyle, /\.review-content-collapse\s*\{[\s\S]*?color: #1677ff;/);
  assert.match(detailStyle, /\.review-item--sheet \.review-image-gallery__image\s*\{[\s\S]*?width: 200rpx;[\s\S]*?height: 200rpx;[\s\S]*?background: transparent;/);
  assert.match(detailLogic, /measureReviewContentOverflow\(\)/);
  assert.match(detailStyle, /\.review-spec-mask\s*\{[\s\S]*?position: fixed;[\s\S]*?inset: 0;[\s\S]*?z-index: 420;/);
  assert.match(detailStyle, /\.review-spec-panel\s*\{[\s\S]*?height: 70vh;/);
  assert.match(productSummaryStyle, /background: @color-surface-white;/);
  assert.match(productSummaryStyle, /border: 0;/);
  assert.match(productSummaryStyle, /\.current-price\s*\{[\s\S]*?color: @color-detail-price;/);
  assert.match(productSummaryStyle, /\.original-price\s*\{[^}]*color: #a3a4a5;/);
  assert.match(detailStyle, /\.sheet-original-price\s*\{[^}]*color: #a3a4a5;/);
  assert.match(productSummaryStyle, /\.sales-text\s*\{[\s\S]*?color: @color-text-black;/);
  assert.match(productSummaryStyle, /\.product-subtitle\s*\{[\s\S]*?color: #8f939c;/);
  assert.match(productSummaryTemplate, /wx:for="\{\{detail\.sellingPoints\}\}"[\s\S]*?class="selling-point-tag"/);
  assert.match(
    productSummaryStyle,
    /\.selling-point-tag\s*\{[\s\S]*?min-width: 0;[\s\S]*?padding: 6rpx 14rpx;[\s\S]*?border: 0;[\s\S]*?border-radius: 10rpx;[\s\S]*?color: #bb784e;[\s\S]*?font-size: 26rpx;[\s\S]*?background: #fff5e8;/
  );
  assert.match(productSummaryStyle, /\.selling-point-scroll\s*\{[\s\S]*?margin-left: 0;[\s\S]*?padding-left: 0;/);
  assert.doesNotMatch(detailTemplate, /class="benefit-scroll"|benefitItems/);
  assert.doesNotMatch(detailLogic, /BenefitItemView|buildBenefitItems|benefitItems/);
  assert.doesNotMatch(productSummaryStyle, /\.parameter-strip/);
  assert.doesNotMatch(detailTemplate, /weight-parameter="\{\{weightParameter\}\}"/);
  assert.match(detailTemplate, /class="commerce-info-group"[\s\S]*?data-sheet="parameters"[\s\S]*?tune-outline-rounded\.svg[\s\S]*?bindtap="onAddressTap"/);
  assert.match(detailTemplate, /commerce-parameter-item[\s\S]*?净含量：\{\{weightParameter\.value\}\}/);
  assert.match(detailStyle, /\.commerce-parameter-track\s*\{[\s\S]*?color: @color-text-black;[\s\S]*?font-size: @font-size-sm;/);
  assert.match(detailStyle, /\.commerce-value__text\s*\{[\s\S]*?font-size: @font-size-sm;[\s\S]*?line-height: 36rpx;/);
  assert.match(detailStyle, /\.commerce-parameter-item \+ \.commerce-parameter-item::before\s*\{[\s\S]*?color: @color-text-black;[\s\S]*?content: "｜";/);
  assert.match(detailStyle, /\.commerce-parameter-spice--mild\s*\{ color: @color-spice-mild; \}/);
  assert.match(detailStyle, /\.commerce-parameter-spice--medium\s*\{ color: @color-spice-medium; \}/);
  assert.match(detailStyle, /\.commerce-parameter-spice--hot\s*\{ color: @color-spice-hot; \}/);
  assert.match(detailTemplate, /activeSheet === 'parameters' \? 'sheet-panel--parameters'/);
  assert.match(detailTemplate, />商品参数<\/text>[\s\S]*?wx:for="\{\{parameterViews\}\}"[\s\S]*?>我知道了<\/button>/);
  assert.match(detailStyle, /\.parameter-sheet-spice--mild\s*\{ color: @color-spice-mild; \}/);
  assert.match(detailStyle, /\.parameter-sheet-spice--hot\s*\{ color: @color-spice-hot; \}/);
  assert.match(detailStyle, /\.parameter-sheet-label\s*\{[\s\S]*?color: #8f939c;/);
  assert.match(detailStyle, /\.parameter-sheet-tip\s*\{[\s\S]*?color: #8f939c;/);
  assert.match(detailStyle, /\.freight-detail-label\s*\{ color: #8f939c;/);
  assert.match(detailStyle, /\.freight-tip\s*\{[\s\S]*?color: #8f939c;/);
  assert.match(detailStyle, /\.parameter-sheet-confirm\s*\{[\s\S]*?background: #ff172b;/);
  assert.match(detailStyle, /\.review-empty-preview__description\s*\{[\s\S]*?color: #8f939c;/);
  assert.match(designTokens, /@color-price: #fa091d;/);
  assert.match(designTokens, /@color-detail-price: @color-price;/);
  assert.match(designTokens, /@color-text-primary: @color-text-black;/);
  assert.match(designTokens, /@color-text-secondary: #5f6368;/);
  assert.match(designTokens, /@color-text-muted: @color-text-gray;/);
  assert.match(designTokens, /@color-text-placeholder: #a8abb3;/);
  assert.match(designTokens, /@color-text-inverse: #fff;/);
  assert.match(
    detailStyle,
    /\.specification-option--selected\s*\{[\s\S]*?border-color: #fe0000;[\s\S]*?color: #fe0000;[\s\S]*?font-weight: 650;[\s\S]*?background: #ffebef;/
  );
  assert.match(
    detailStyle,
    /\.wholesale-shortcut--active\s*\{[\s\S]*?border-color: #fe0000;[\s\S]*?color: #fe0000;[\s\S]*?font-weight: 650;[\s\S]*?background: #ffebef;/
  );
  assert.match(detailStyle, /\.selected-spec\s*\{[\s\S]*?color: @color-text-black;/);
  assert.match(detailStyle, /\.sheet-wholesale-hint\s*\{[\s\S]*?color: @color-text-gray;/);
  assert.match(detailStyle, /\.sheet-section-note,\s*\.quantity-note\s*\{[\s\S]*?color: @color-text-gray;/);
  assert.match(detailStyle, /\.specification-option\s*\{[\s\S]*?color: @color-text-black;/);
  assert.match(detailStyle, /\.specification-option__sold-out\s*\{[\s\S]*?color: @color-text-gray;/);
  assert.match(detailStyle, /\.wholesale-shortcut__quantity\s*\{[\s\S]*?color: @color-text-black;/);
  assert.match(detailStyle, /\.quantity-control\s*\{[\s\S]*?grid-template-columns: 46rpx 68rpx 46rpx;/);
  assert.match(detailStyle, /\.quantity-button,[\s\S]*?\.quantity-value\s*\{[\s\S]*?height: 42rpx;/);
  assert.match(detailTemplate, /class="quantity-value"[\s\S]*bindblur="onQuantityInputCommit"[\s\S]*bindconfirm="onQuantityInputCommit"/);
  assert.match(detailStyle, /\.quantity-button\s*\{[\s\S]*?color: @color-text-primary;[\s\S]*?font-size: 28rpx;[\s\S]*?background: transparent;/);
  assert.match(detailStyle, /\.quantity-value\s*\{[\s\S]*?border-radius: @radius-xs;[\s\S]*?background: #f6f6f6;[\s\S]*?font-size: @font-size-sm;[\s\S]*?font-weight: 600;/);
  assert.match(detailTemplate, /activeSheet === 'reviews'/);
  assert.match(detailTemplate, /data-review-filter="WITH_IMAGES"/);
  assert.match(detailTemplate, /data-review-filter="GOOD"/);
  assert.match(detailTemplate, /data-review-filter="CRITICAL"/);
  assert.match(detailTemplate, /bindtap="onReviewSortTap"/);
  assert.match(detailTemplate, /bindtap="onReviewSpecOpen"/);
  assert.match(detailTemplate, /class="review-spec-panel"/);
  assert.doesNotMatch(detailTemplate, /activeSheet === 'reviewManage'|写评价|onReviewSubmit|onReviewEdit|onReviewDelete/);
  assert.doesNotMatch(detailLogic, /getMyProductReviews|updateProductReview|deleteProductReview|loadReviewManagement/);
  assert.match(detailTemplate, /activeSheet === 'address'/);
  assert.match(detailTemplate, /bindtap="onAddAddress">新增地址</);
  assert.match(detailTemplate, /bindscrolltolower="onReviewLoadMore"/);
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
  const pagePaths = configuredPagePaths(appConfig);
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
  const previewStyle = readFileSync(
    resolve(sourceRoot, "pages/order/preview/preview.less"),
    "utf8"
  );
  const previewConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "pages/order/preview/preview.json"), "utf8")
  ) as Record<string, unknown>;
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
  assert.ok(pagePaths.includes("pages/order/preview/preview"));
  assert.ok(pagePaths.includes("pages/order/created/created"));
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
  assert.match(previewTemplate, /wide-center="\{\{true\}\}"/);
  assert.match(previewTemplate, /light-back="\{\{true\}\}"/);
  assert.match(previewTemplate, /background="#fd4041"/);
  assert.match(previewTemplate, /slot="center" class="preview-navigation-title">确认订单/);
  assert.match(previewTemplate, /class="preview-scroll"[\s\S]*bounces="\{\{false\}\}"/);
  assert.doesNotMatch(
    previewTemplate,
    /pullGesture|preview-pull\.wxs|bindtouchstart|bindtouchmove|bindtouchend|bindtouchcancel|refresher-enabled|refresher-triggered|bindrefresherrefresh/
  );
  assert.doesNotMatch(previewLogic, /contentRefreshing|onPullDownRefresh|stopPullDownRefresh/);
  assert.equal(previewConfig.navigationBarBackgroundColor, "#fd4041");
  assert.equal(previewConfig.navigationBarTextStyle, "white");
  assert.doesNotMatch(previewTemplate, /应付金额|提交订单/);
  assert.doesNotMatch(previewTemplate, /商品清单|金额明细|放心下单/);
  assert.doesNotMatch(previewTemplate, /address-card__marker/);
  assert.doesNotMatch(previewTemplate, /productSubtitle|preview-item__subtitle/);
  assert.match(previewTemplate, /receiverPhoneDisplay/);
  assert.match(previewTemplate, /preview-item__quantity">x\{\{item\.quantity\}\}/);
  assert.match(previewTemplate, /preview-item__line-prices/);
  assert.match(previewTemplate, /retailLineAmountText/);
  assert.match(previewTemplate, /商品金额[\s\S]*运费[\s\S]*批发优惠[\s\S]*优惠券/);
  assert.match(
    previewStyle,
    /\.amount-row\s*\{[^}]*color:\s*@color-text-primary;[^}]*font-size:\s*@font-size-base;/
  );
  assert.match(
    previewStyle,
    /\.amount-row--discount\s*>\s*text:last-child,[\s\S]*?\.amount-row__discount-value\s*\{[^}]*color:\s*@color-price;/
  );
  assert.match(previewTemplate, /class="amount-row__discount-value"/);
  assert.match(previewTemplate, /以下商品当前库存不足/);
  assert.match(previewTemplate, /class="stock-shortage-image-badge">库存不足<\/view>/);
  assert.match(previewTemplate, /bindtap="onStockShortageBackTap">返回购物车<\/button>/);
  assert.match(previewStyle, /\.stock-shortage-mask\s*\{[\s\S]*z-index:\s*3200;/);
  assert.match(previewStyle, /\.preview-content\s*\{[^}]*padding:\s*0 @page-gutter /);
  assert.doesNotMatch(previewStyle, /min-height:\s*calc\(100% \+ 1px\)|will-change:\s*transform|overflow-anchor/);
  assert.match(previewStyle, /\.preview-navigation-title\s*\{[^}]*text-align:\s*left;/);
  assert.match(
    previewStyle,
    /\.address-card,[\s\S]*?\.preview-section,[\s\S]*?\.preview-items\s*\{[^}]*border-radius:\s*@radius-lg;/
  );
  assert.match(previewTemplate, /class="address-hero"[\s\S]*class="address-card"/);
  assert.doesNotMatch(previewTemplate, /address-card--joined/);
  assert.match(
    previewStyle,
    /\.address-hero\s*\{[^}]*background:\s*linear-gradient\([\s\S]*?#fd4041[\s\S]*?@color-page 100%/
  );
  assert.match(previewStyle, /\.preview-items\s*\{[^}]*margin-top:\s*0;[^}]*gap:\s*0;/);
  assert.doesNotMatch(previewStyle, /\.preview-items\s*\{[^}]*border-top:/);
  assert.match(
    previewStyle,
    /\.preview-item\s*\{[^}]*padding:\s*@space-2 @space-6;[^}]*grid-template-columns:\s*136rpx minmax\(0, 1fr\);/
  );
  assert.match(
    previewStyle,
    /\.preview-item__body\s*\{[^}]*height:\s*136rpx;[^}]*grid-template-rows:\s*repeat\(3, minmax\(0, 1fr\)\);/
  );
  assert.doesNotMatch(previewStyle, /\.preview-item \+ \.preview-item\s*\{[^}]*border-top:/);
  assert.match(previewStyle, /\.preview-item__quantity\s*\{[^}]*color:\s*@color-text-primary;/);
  assert.match(previewTemplate, /amount-section__divider/);
  assert.match(previewTemplate, /共\{\{preview\.totalQuantity\}\}件，合计/);
  assert.match(
    previewStyle,
    /\.amount-section__divider\s*\{[^}]*background-image:\s*linear-gradient\([\s\S]*?20rpx,[\s\S]*?transparent 32rpx[\s\S]*?background-size:\s*32rpx 1rpx;/
  );
  assert.match(previewTemplate, /class="amount-row amount-row--discount"/);
  assert.match(previewTemplate, /class="amount-row amount-row--interactive amount-row--discount"/);
  assert.match(previewTemplate, /总计优惠/);
  assert.match(previewTemplate, /class="sheet-panel sheet-panel--address"/);
  assert.match(previewTemplate, /class="sheet-panel sheet-panel--coupon"/);
  assert.match(previewTemplate, /close-material-symbols\.svg/);
  assert.match(previewTemplate, /data-tab="available"[\s\S]*可用券（\{\{availableCoupons\.length\}\}）/);
  assert.match(previewTemplate, /data-tab="unavailable"[\s\S]*不可用券（\{\{unavailableCoupons\.length\}\}）/);
  assert.match(previewTemplate, /couponSheetTab === 'available'/);
  assert.match(previewLogic, /couponSheetTab:\s*"available" as CouponSheetTab/);
  assert.match(previewLogic, /onCouponTabTap\(event: DatasetEvent\)/);
  assert.match(
    previewStyle,
    /\.sheet-panel\s*\{[^}]*height:\s*80vh;[^}]*border-radius:\s*36rpx 36rpx 0 0;/
  );
  assert.match(previewStyle, /\.sheet-header\s*\{[^}]*min-height:\s*116rpx;/);
  assert.match(previewStyle, /\.coupon-sheet-tab--active::after\s*\{/);
  assert.doesNotMatch(previewTemplate, /coupon-group-heading/);
  assert.match(previewLogic, /executeOrderPayment/);
  assert.doesNotMatch(previewLogic, /wx\.showModal/);
  assert.doesNotMatch(previewTemplate, /商品原价|批发\/活动优惠/);
  assert.match(previewTemplate, /优惠券/);
});

test("微信支付与订单中心注册真实页面和关键操作", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as AppConfig;
  const pagePaths = configuredPagePaths(appConfig);
  const createdTemplate = readFileSync(
    resolve(sourceRoot, "pages/order/created/created.wxml"),
    "utf8"
  );
  const createdStyles = readFileSync(
    resolve(sourceRoot, "pages/order/created/created.less"),
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
  const detailStyles = readFileSync(
    resolve(sourceRoot, "pages/order/detail/detail.less"),
    "utf8"
  );
  const detailConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "pages/order/detail/detail.json"), "utf8")
  ) as DetailPageConfig;
  const orderService = readFileSync(
    resolve(sourceRoot, "services/order.ts"),
    "utf8"
  );
  const logisticsFeature = readFileSync(
    resolve(sourceRoot, "features/order-logistics.ts"),
    "utf8"
  );
  const paymentAdapter = readFileSync(
    resolve(sourceRoot, "utils/wechat-payment.ts"),
    "utf8"
  );

  assert.ok(pagePaths.includes("pages/order/list/list"));
  assert.ok(pagePaths.includes("pages/order/detail/detail"));
  assert.equal(appConfig.plugins, undefined);
  assert.deepEqual(appConfig.subPackages?.[0]?.plugins?.logisticsPlugin, {
    version: "2.3.0",
    provider: "wx9ad912bf20548d92"
  });
  assert.match(createdTemplate, /支付成功/);
  assert.match(createdTemplate, /支付金额/);
  assert.match(createdTemplate, /payment-success-background\.jpg/);
  assert.match(createdTemplate, /payment-success-check\.png/);
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/payment-success-background.jpg")),
    true
  );
  assert.ok(
    readFileSync(resolve(sourceRoot, "assets/images/payment-success-background.jpg")).byteLength <= 200 * 1024
  );
  assert.equal(
    existsSync(resolve(sourceRoot, "pages/order/assets/images/payment-success-check.png")),
    true
  );
  const successCheck = readFileSync(
    resolve(sourceRoot, "pages/order/assets/images/payment-success-check.png")
  );
  assert.equal(successCheck.subarray(1, 4).toString("ascii"), "PNG");
  assert.equal(successCheck.readUInt32BE(16), 512);
  assert.equal(successCheck.readUInt32BE(20), 512);
  assert.ok(successCheck.byteLength <= 64 * 1024);
  assert.equal(
    existsSync(resolve(sourceRoot, "pages/order/assets/images/payment-success-background.jpg")),
    false
  );
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/images/payment-success-check.png")),
    false
  );
  assert.match(createdTemplate, /back="\{\{true\}\}"/);
  assert.match(createdTemplate, /background="transparent"/);
  assert.match(createdTemplate, /show-divider="\{\{false\}\}"/);
  assert.match(createdTemplate, />查看订单<\/button>/);
  assert.match(createdTemplate, />返回首页<\/button>/);
  assert.match(createdStyles, /\.created-background\s*\{/);
  assert.doesNotMatch(createdTemplate, /待支付金额|应付金额|订单提交成功|同步支付结果/);
  assert.doesNotMatch(createdTemplate, /title="支付成功"|order-card|result-subtitle/);
  assert.match(listTemplate, /bindtap="onOrderTap"/);
  assert.match(listTemplate, /catchtap="onCancelTap"/);
  assert.match(detailTemplate, /bindtap="onConfirmTap"/);
  assert.match(detailTemplate, /countdownHours/);
  assert.match(detailTemplate, /bindtap="onDeleteTap"/);
  assert.match(detailTemplate, /bindtap="onRebuyTap"/);
  assert.doesNotMatch(detailTemplate, /应付金额|同步结果/);
  assert.doesNotMatch(detailTemplate, /商品清单|金额明细/);
  assert.match(detailTemplate, /class="status-navigation"[\s\S]*detail\.statusIcon[\s\S]*detail\.statusHeadline/);
  assert.match(detailTemplate, /还剩[\s\S]*countdownHours[\s\S]*countdownMinutes[\s\S]*countdownSeconds[\s\S]*订单自动取消/);
  assert.match(detailTemplate, /receiverPhoneDisplay/);
  assert.match(detailTemplate, /bindtap="onModifyTap"[\s\S]*>修改<\/button>/);
  assert.match(detailTemplate, /class="detail-card logistics-card"/);
  assert.match(detailTemplate, /detail\.shipmentViews/);
  assert.match(detailTemplate, /shipment\.carrierName/);
  assert.match(detailTemplate, /shipment\.trackingNo/);
  assert.match(detailTemplate, /catchtap="onCopyTrackingNoTap"/);
  assert.match(detailTemplate, /catchtap="onOpenLogisticsTap"/);
  assert.match(detailTemplate, /loading="\{\{logisticsOpening\}\}"/);
  assert.match(detailTemplate, /disabled="\{\{logisticsOpening\}\}"/);
  assert.match(detailTemplate, /共\{\{detail\.orderInfoItemCount\}\}项/);
  assert.match(detailTemplate, /bindtap="onOrderInfoToggle"/);
  assert.match(detailTemplate, /bindtap="onCustomerServiceTap"/);
  assert.match(detailTemplate, /class="payment-action"[\s\S]*继续支付/);
  assert.match(detailTemplate, /payment-summary__main[\s\S]*支付金额[\s\S]*detail\.payableAmountText[\s\S]*detail\.originalPayableAmountText/);
  assert.match(detailTemplate, /payment-summary__discount">总计优惠 \{\{detail\.totalDiscountText\}\}/);
  assert.match(detailTemplate, /cancel-rounded-material-symbols-iconify\.svg[\s\S]*payment-tool__label">取消<\/text>/);
  assert.equal(
    existsSync(resolve(sourceRoot, "assets/icons/cancel-rounded-material-symbols-iconify.svg")),
    true
  );
  assert.match(detailTemplate, /copy-action__divider">｜<\/text>[\s\S]*copy-action__label">复制<\/text>/);
  assert.match(detailTemplate, /class="detail-scroll"[\s\S]*scroll-y="\{\{true\}\}"/);
  assert.match(detailLogic, /buildOrderModifyUrl/);
  assert.match(detailLogic, /copyOrderNo/);
  assert.match(detailLogic, /copyTrackingNo/);
  assert.match(detailLogic, /openOrderLogistics/);
  assert.match(detailLogic, /getShipmentWaybillToken/);
  assert.match(detailLogic, /this\.data\.logisticsOpening/);
  assert.match(detailLogic, /finally\s*\{\s*this\.setData\(\{ logisticsOpening: false \}\)/);
  assert.doesNotMatch(detailLogic, /setData\(\{[^}]*waybillToken/);
  assert.match(orderService, /shipmentWaybillToken\(orderId, shipmentId\)[\s\S]*method:\s*"POST"/);
  assert.match(logisticsFeature, /requirePlugin\.async\("logisticsPlugin"\)/);
  assert.doesNotMatch(logisticsFeature, /setStorage|globalData|console\./);
  assert.match(detailLogic, /buildCustomerServiceUrl\("ORDER", detail\.orderId\)/);
  assert.equal(detailConfig.disableScroll, true);
  assert.equal(detailConfig.enablePullDownRefresh, false);
  assert.match(detailStyles, /\.detail-page\s*\{[\s\S]*height:\s*100vh;[\s\S]*overflow:\s*hidden;/);
  assert.match(detailStyles, /\.detail-scroll\s*\{[\s\S]*height:\s*0;[\s\S]*flex:\s*1;/);
  assert.match(detailStyles, /\.detail-card,[\s\S]*\.payment-notice\s*\{[\s\S]*background:\s*#ffffff;/);
  assert.match(detailStyles, /\.payment-notice\s*\{[^}]*border:\s*0;/);
  assert.match(detailStyles, /\.payment-notice\s*\{[^}]*align-items:\s*baseline;/);
  assert.match(detailStyles, /\.payment-notice__countdown\s*\{[\s\S]*color:\s*@color-action-primary;/);
  assert.match(detailTemplate, /countdownHours[\s\S]*payment-notice__separator[\s\S]*countdownMinutes[\s\S]*payment-notice__separator[\s\S]*countdownSeconds/);
  assert.match(detailStyles, /\.payment-notice__separator\s*\{[\s\S]*display:\s*inline;[\s\S]*margin:\s*0 -2rpx;/);
  assert.match(detailStyles, /button\.receiver-card__modify\s*\{[\s\S]*width:\s*92rpx !important;/);
  assert.match(detailTemplate, /<view class="info-row info-row--order-no">\s*<text>订单编号<\/text>\s*<text class="info-row__order-no">\{\{detail\.orderNo\}\}<\/text>\s*<button class="copy-action"/);
  assert.match(detailStyles, /\.info-row__order-no\s*\{[\s\S]*width:\s*100%;[\s\S]*min-width:\s*0;[\s\S]*display:\s*block;[\s\S]*overflow:\s*hidden;[\s\S]*color:\s*@color-text-black;[\s\S]*font-size:\s*@font-size-sm;[\s\S]*text-align:\s*right;[\s\S]*text-overflow:\s*ellipsis;[\s\S]*white-space:\s*nowrap;/);
  assert.match(detailTemplate, /class="order-info__toggle[\s\S]*chevron-right-detail\.svg/);
  assert.doesNotMatch(detailTemplate, /orderInfoExpanded \? '⌃' : '⌄'/);
  assert.match(detailStyles, /\.order-info__toggle\s*\{[\s\S]*width:\s*44rpx;[\s\S]*height:\s*44rpx;[\s\S]*transform:\s*rotate\(90deg\);/);
  assert.match(detailStyles, /\.order-info__heading\s*\{[\s\S]*height:\s*92rpx;/);
  assert.match(detailStyles, /\.info-row--order-no\s*\{[\s\S]*display:\s*grid;[\s\S]*justify-content:\s*stretch;[\s\S]*grid-template-columns:\s*auto minmax\(0, 1fr\) auto;[\s\S]*gap:\s*0;/);
  assert.match(detailStyles, /button\.copy-action\s*\{[\s\S]*width:\s*auto !important;[\s\S]*justify-self:\s*end;/);
  assert.match(detailStyles, /\.logistics-card\s*\{/);
  assert.match(detailStyles, /\.logistics-card__tracking-no\s*\{[\s\S]*text-overflow:\s*ellipsis;/);
  assert.match(detailTemplate, /class="payment-utilities"[\s\S]*class="payment-tool__icon-frame"/);
  assert.match(detailStyles, /button\.payment-tool\s*\{[\s\S]*width:\s*76rpx !important;[\s\S]*height:\s*80rpx !important;/);
  assert.match(detailStyles, /\.payment-tool__icon-frame\s*\{[\s\S]*width:\s*44rpx;[\s\S]*height:\s*44rpx;/);
  assert.match(detailStyles, /\.payment-tool__icon--service\s*\{[\s\S]*width:\s*44rpx;[\s\S]*height:\s*44rpx;/);
  assert.match(detailStyles, /button\.payment-action\s*\{[\s\S]*width:\s*224rpx;[\s\S]*height:\s*84rpx;[\s\S]*background:\s*#ff172b;/);
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
  assert.doesNotMatch(homeTemplate, /home-navigation|<navigation-bar/);
  assert.doesNotMatch(homeTemplate, /refresher-/);
  assert.doesNotMatch(homeTemplate, /refreshText/);
  assert.match(homeLogic, /onShow\(\)[\s\S]*loadHome\(\{ preserveContent: true, suppressError: true \}\)/);
  assert.match(homeLogic, /onPullDownRefresh\(\)[\s\S]*stopPullDownRefresh: true/);
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

test("首页轮播在动画完成后同步位置并在页面切换时保留原生实例", () => {
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
  const pageHideSource = bannerLogic.slice(
    bannerLogic.indexOf("    hide()"),
    bannerLogic.indexOf("    show()")
  );
  assert.match(pageHideSource, /autoplayEnabled: false,[\s\S]*currentBanner/);
  assert.doesNotMatch(pageHideSource, /swiperVisible: false/);
  assert.match(bannerLogic, /show\(\)[\s\S]*scheduleAutoplayResume\(this\)/);
});
