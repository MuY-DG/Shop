import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("收货地址使用微信原生地图选址", () => {
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
  assert.ok(appConfig.requiredPrivateInfos?.includes("chooseLocation"));
  assert.ok(appConfig.requiredPrivateInfos?.includes("chooseAddress"));
  assert.ok(!appConfig.requiredPrivateInfos?.includes("getLocation"));
  assert.match(appConfig.permission?.["scope.userLocation"]?.desc || "", /收货地址/);
  assert.ok(
    !appConfig.pages?.includes("pages/account/address/location-picker/location-picker")
  );
  assert.match(editLogic, /wx\.chooseLocation/);
  assert.match(editLogic, /parseRegionFromLocation/);
  assert.match(editLogic, /选择地理位置/);
  assert.doesNotMatch(editLogic, /location-picker\/location-picker/);
  assert.doesNotMatch(editLogic, /addressSelected/);
  assert.doesNotMatch(editLogic, /composeAddressDetail/);
  assert.match(editTemplate, /请选择收货地址/);
  assert.match(editTemplate, /show-divider="\{\{false\}\}"/);
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
  assert.doesNotMatch(editLogic, /地图位置已回填/);
  assert.match(editTemplate, />手机号</);
  assert.match(editTemplate, /placeholder="手机号码"/);
  assert.match(editTemplate, /data-field="receiverName"[\s\S]*?maxlength="10"/);
  assert.doesNotMatch(editTemplate, /1\s*[-–—至]\s*10\s*个字符/);
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
  assert.match(editStyle, /\.address-form\s*\{[\s\S]*background: #ffffff/);
  assert.match(editStyle, /button\.save-button\s*\{[\s\S]*background: #ff172b/);
  assert.match(addressListTemplate, /background="#F3F3F7"/);
  assert.match(addressListTemplate, /show-divider="\{\{false\}\}"/);
  assert.match(addressListTemplate, /edit-square-outline-mdi-iconify\.svg/);
  assert.doesNotMatch(addressListTemplate, /address-edit\.svg/);
  assert.match(addressListStyle, /\.address-page[\s\S]*background: @color-page/);
  assert.match(addressListStyle, /\.address-row__name,[\s\S]*\.address-row__phone\s*\{[\s\S]*color: #000000/);
  assert.match(addressListStyle, /\.address-state[\s\S]*background: transparent/);
  assert.match(addressListLogic, /composeAddressListTitle/);
  assert.match(addressListLogic, /address\.locationName/);
  assert.match(addressListLogic, /address\.doorplate/);
  assert.doesNotMatch(addressListTemplate, /先生|女士|性别/);
  assert.match(
    addressListStyle,
    /\.address-row__detail\s*\{[\s\S]*?text-overflow: ellipsis[\s\S]*?white-space: nowrap/
  );
});
