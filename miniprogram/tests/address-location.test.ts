import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import { API_ENDPOINTS } from "../miniprogram/constants/api-endpoints";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("收货地址使用高德微信小程序 SDK 完成地图选址", () => {
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
  const designTokens = readFileSync(resolve(sourceRoot, "styles/tokens.less"), "utf8");
  const amapSdk = readFileSync(resolve(sourceRoot, "libs/amap-wx.ts"), "utf8");

  assert.ok(appConfig.requiredPrivateInfos?.includes("getLocation"));
  assert.match(appConfig.permission?.["scope.userLocation"]?.desc || "", /收货地址/);
  assert.ok(
    appConfig.pages?.includes("pages/account/address/location-picker/location-picker")
  );
  assert.equal(API_ENDPOINTS.location.config, "/app/location/config");
  assert.match(editLogic, /location-picker\/location-picker/);
  assert.match(editLogic, /addressSelected/);
  assert.match(editLogic, /composeAddressDetail/);
  assert.match(editTemplate, /点击地图选择收货地址/);
  assert.match(editTemplate, /门牌号/);
  assert.match(editTemplate, /onDoorplateInput/);
  assert.doesNotMatch(editTemplate, /mode="region"/);
  assert.match(pickerLogic, /type: "gcj02"/);
  assert.match(pickerLogic, /new AMapWXConstructor/);
  assert.match(pickerLogic, /getRegeo/);
  assert.match(pickerLogic, /getPoiAround/);
  assert.match(pickerLogic, /getInputtips/);
  assert.match(pickerLogic, /getCenterLocation/);
  assert.match(pickerLogic, /event\.causedBy === "update"/);
  assert.match(pickerLogic, /this\.data\.selected\.longitude/);
  assert.match(pickerTemplate, /<map/);
  assert.match(pickerTemplate, /回到当前位置/);
  assert.match(pickerTemplate, /center-pin__head/);
  assert.match(pickerTemplate, /拖动地图，让指针落在目标位置/);
  assert.match(pickerTemplate, /搜索小区、大厦、街道或门店/);
  assert.match(pickerTemplate, /使用该地址/);
  assert.match(amapSdk, /restapi\.amap\.com\/v3\/geocode\/regeo/);
  assert.match(amapSdk, /restapi\.amap\.com\/v3\/place\/around/);
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
