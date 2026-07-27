import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import { API_ENDPOINTS } from "../miniprogram/constants/api-endpoints";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("收货地址使用微信原生地图选址并保留停用的高德实现", () => {
  const appConfig = JSON.parse(
    readFileSync(resolve(sourceRoot, "app.json"), "utf8")
  ) as {
    pages?: string[];
    requiredPrivateInfos?: string[];
    permission?: Record<string, { desc?: string }>;
  };
  const editLogic = readFileSync(
    resolve(sourceRoot, "pages/account/address/edit/edit.ts"),
    "utf8"
  );
  const editTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/address/edit/edit.wxml"),
    "utf8"
  );
  const pickerLogic = readFileSync(
    resolve(sourceRoot, "pages/account/address/location-picker/location-picker.ts"),
    "utf8"
  );
  const pickerTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/address/location-picker/location-picker.wxml"),
    "utf8"
  );
  const pickerStyle = readFileSync(
    resolve(sourceRoot, "pages/account/address/location-picker/location-picker.less"),
    "utf8"
  );
  const editStyle = readFileSync(
    resolve(sourceRoot, "pages/account/address/edit/edit.less"),
    "utf8"
  );
  const addressListStyle = readFileSync(
    resolve(sourceRoot, "pages/account/address/list/list.less"),
    "utf8"
  );
  const addressListTemplate = readFileSync(
    resolve(sourceRoot, "pages/account/address/list/list.wxml"),
    "utf8"
  );
  const addressListLogic = readFileSync(
    resolve(sourceRoot, "pages/account/address/list/list.ts"),
    "utf8"
  );
  const designTokens = readFileSync(resolve(sourceRoot, "styles/tokens.less"), "utf8");
  const amapSdk = readFileSync(resolve(sourceRoot, "libs/amap-wx.ts"), "utf8");

  assert.ok(appConfig.requiredPrivateInfos?.includes("chooseLocation"));
  assert.ok(appConfig.requiredPrivateInfos?.includes("chooseAddress"));
  assert.ok(!appConfig.requiredPrivateInfos?.includes("getLocation"));
  assert.match(appConfig.permission?.["scope.userLocation"]?.desc || "", /收货地址/);
  assert.ok(
    !appConfig.pages?.includes("pages/account/address/location-picker/location-picker")
  );
  assert.equal(API_ENDPOINTS.location.config, "/app/location/config");
  assert.match(editLogic, /wx\.chooseLocation/);
  assert.match(editLogic, /parseRegionFromLocation/);
  assert.match(editLogic, /选择地理位置/);
  assert.doesNotMatch(editLogic, /location-picker\/location-picker/);
  assert.doesNotMatch(editLogic, /addressSelected/);
  assert.doesNotMatch(editLogic, /AMapWX|getAmapClientConfig/);
  assert.doesNotMatch(editLogic, /composeAddressDetail/);
  assert.match(editTemplate, /请选择收货地址/);
  assert.match(editTemplate, /正在打开微信地图/);
  assert.match(editTemplate, /mode="region"/);
  assert.match(editTemplate, /locationNameDisplay/);
  assert.match(editTemplate, /locationAddressDisplay/);
  assert.match(editTemplate, />地址</);
  assert.match(editTemplate, />补充地区</);
  assert.match(editTemplate, /chevron-right-light\.svg/);
  assert.doesNotMatch(editTemplate, />所在地区</);
  assert.doesNotMatch(editTemplate, />地图位置</);
  assert.match(editLogic, /const locationName = text\(location\.name\)/);
  assert.match(editLogic, /const locationAddress =\s+text\(location\.address\)/);
  assert.match(editLogic, /locationAddressDisplay: locationAddress/);
  assert.match(editLogic, /locationName/);
  assert.match(editLogic, /doorplate: this\.data\.doorplate/);
  assert.match(editTemplate, />手机号</);
  assert.match(editTemplate, /placeholder="手机号码"/);
  assert.match(editTemplate, /微信导入/);
  assert.match(editLogic, /wx\.chooseAddress/);
  assert.match(editLogic, /detailInfoNew \|\| address\.detailInfo/);
  assert.doesNotMatch(editLogic, /微信地址导入失败/);
  assert.match(editLogic, /微信地址暂不可用，请手动填写/);
  assert.match(editLogic, /deleteAddress/);
  assert.match(editTemplate, /删除收货地址/);
  assert.doesNotMatch(editTemplate, /地图选择地址，再补充楼栋、单元或房间号/);
  assert.doesNotMatch(editTemplate, /结算时优先选择该地址|已通过地图选择|>选填</);
  assert.match(editTemplate, /门牌号/);
  assert.match(editTemplate, /onDoorplateInput/);
  assert.match(editTemplate, /onRegionChange/);
  assert.match(pickerLogic, /type: "gcj02"/);
  assert.match(pickerLogic, /new AMapWXConstructor/);
  assert.match(pickerLogic, /getRegeo/);
  assert.match(pickerLogic, /getPoiAround/);
  assert.match(pickerLogic, /getPoiKeywords/);
  assert.match(pickerLogic, /preferredCity \? \{ city: preferredCity \}/);
  assert.match(pickerLogic, /this\.data\.currentCity/);
  assert.match(pickerLogic, /citylimit: false/);
  assert.match(pickerLogic, /NEARBY_PAGE_SIZE = 8/);
  assert.match(pickerLogic, /loadMoreNearbyPlaces/);
  assert.match(pickerLogic, /SEARCH_PAGE_SIZE = 8/);
  assert.match(pickerLogic, /loadMoreSearchPlaces/);
  assert.match(pickerLogic, /getCenterLocation/);
  assert.match(pickerLogic, /event\.causedBy === "update"/);
  assert.match(pickerTemplate, /<map/);
  assert.match(pickerTemplate, /class="map-back"/);
  assert.match(pickerTemplate, /navigation-back\.png/);
  assert.match(pickerTemplate, /style="height: \{\{mapVisibleHeight\}\}px;"/);
  assert.match(pickerTemplate, /style="height: \{\{mapHeight\}\}px;"/);
  assert.match(pickerStyle, /\.picker-content[\s\S]*height: 100vh/);
  assert.match(pickerLogic, /navigationOverlayMetrics/);
  assert.match(pickerTemplate, /location-target-active\.png/);
  assert.match(pickerTemplate, /location-target-idle\.png/);
  assert.match(pickerTemplate, /bindscrolltolower="onNearbyLoadMore"/);
  assert.match(pickerTemplate, /bindscrolltolower="onSearchLoadMore"/);
  assert.match(pickerTemplate, /bindtouchmove="onListTouchMove"/);
  assert.match(pickerLogic, /panelExpanded/);
  assert.match(pickerLogic, /expandResultPanel/);
  assert.match(pickerLogic, /collapseResultPanel/);
  assert.match(pickerLogic, /scheduleLoadMoreAfterExpand/);
  assert.match(pickerLogic, /const mapVisibleHeight = clamp/);
  assert.match(pickerLogic, /mapHeight - mapVisibleHeight/);
  assert.match(pickerTemplate, /mapLocateBottom/);
  assert.match(pickerStyle, /\.map-shell--dragging[\s\S]*transition: none/);
  assert.match(pickerStyle, /transition: height 380ms/);
  assert.match(pickerStyle, /transition: bottom 380ms/);
  assert.match(pickerTemplate, /address-check\.svg/);
  assert.match(pickerTemplate, /address-search\.png/);
  assert.doesNotMatch(pickerTemplate, /title="地图选择收货地址"/);
  assert.doesNotMatch(pickerTemplate, /loading="\{\{loading \|\| resolving\}\}"/);
  assert.doesNotMatch(pickerTemplate, /正在加载高德地图/);
  assert.match(pickerTemplate, /showSearchResults/);
  assert.doesNotMatch(pickerTemplate, /place-row__pin|\{\{index \+ 1\}\}/);
  assert.ok(
    pickerTemplate.indexOf('class="map-shell"') <
      pickerTemplate.indexOf('class="search-zone"')
  );
  assert.match(pickerLogic, /return text\(preferred\?\.name\) \|\| baseDetail/);
  assert.match(editStyle, /button\.form-field--address[\s\S]*align-items: center/);
  assert.match(editStyle, /\.address-summary__name/);
  assert.match(editStyle, /\.address-summary__detail/);
  assert.match(editStyle, /\.address-summary__single/);
  assert.match(editStyle, /\.address-summary[\s\S]*text-align: right/);
  assert.match(
    editStyle,
    /\.address-summary__detail\s*\{[\s\S]*?text-overflow: ellipsis[\s\S]*?white-space: nowrap/
  );
  assert.match(
    editStyle,
    /\.address-summary__single\s*\{[\s\S]*?text-overflow: ellipsis[\s\S]*?white-space: nowrap/
  );
  assert.match(editStyle, /\.form-field__input\s*\{[\s\S]*?text-align: right/);
  assert.match(editStyle, /\.form-field__value\s*\{[\s\S]*?text-align: right/);
  assert.match(editStyle, /\.wechat-import[\s\S]*background: transparent/);
  assert.match(editStyle, /\.wechat-import[\s\S]*position: absolute/);
  assert.match(editStyle, /\.wechat-import[\s\S]*right: 0/);
  assert.match(addressListTemplate, /background="#F6EDDF"/);
  assert.match(addressListStyle, /\.address-page[\s\S]*background: @color-page/);
  assert.match(addressListStyle, /\.address-state[\s\S]*background: transparent/);
  assert.match(addressListLogic, /composeAddressListTitle/);
  assert.match(addressListLogic, /address\.locationName/);
  assert.match(addressListLogic, /address\.doorplate/);
  assert.doesNotMatch(addressListTemplate, /先生|女士|性别/);
  assert.match(
    addressListStyle,
    /\.address-row__detail\s*\{[\s\S]*?text-overflow: ellipsis[\s\S]*?white-space: nowrap/
  );
  assert.doesNotMatch(pickerTemplate, />回到当前位置</);
  assert.doesNotMatch(pickerTemplate, /当前选点/);
  assert.match(pickerTemplate, /center-pin__head/);
  assert.match(pickerTemplate, /拖动地图，让指针落在目标位置/);
  assert.match(pickerTemplate, /搜索小区、大厦、街道或门店/);
  assert.match(pickerTemplate, /使用该地址/);
  assert.match(amapSdk, /restapi\.amap\.com\/v3\/geocode\/regeo/);
  assert.match(amapSdk, /restapi\.amap\.com\/v3\/place\/around/);
  assert.match(amapSdk, /restapi\.amap\.com\/v3\/place\/text/);
  assert.match(amapSdk, /e\.offset=a\.pageSize/);
  assert.match(amapSdk, /e\.page=a\.pageNumber/);
  assert.match(amapSdk, /restapi\.amap\.com\/v3\/assistant\/inputtips/);
  assert.match(amapSdk, /export \{ AMapWX \}/);
  assert.doesNotMatch(amapSdk, /module\.exports/);

  const definedTokens = new Set(
    [...designTokens.matchAll(/^(@[\w-]+):/gm)].map((match) => match[1])
  );
  const usedTokens = new Set(
    [...pickerStyle.matchAll(/@[\w-]+/g)]
      .map((match) => match[0])
      .filter((token) => token !== "@import")
  );
  assert.deepEqual(
    [...usedTokens].filter((token) => !definedTokens.has(token)),
    []
  );
});
