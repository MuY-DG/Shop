import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  normalizeProfileNickname,
  profileHasChanges,
  validateProfileNickname
} from "../miniprogram/features/user-profile";

const pageRoot = resolve(
  process.cwd(),
  "miniprogram/pages/account/profile/profile"
);
const servicePath = resolve(
  process.cwd(),
  "miniprogram/services/user-profile.ts"
);

test("微信昵称规范化并限制有效长度", () => {
  assert.equal(normalizeProfileNickname("  山茶花  "), "山茶花");
  assert.equal(validateProfileNickname(""), "请选择或输入微信昵称");
  assert.equal(validateProfileNickname("单"), "昵称至少需要 2 个字符");
  assert.equal(validateProfileNickname("灶香集会员"), "");
  assert.equal(validateProfileNickname("用\u0001户"), "昵称包含不支持的字符");
  assert.match(validateProfileNickname("很".repeat(33)), /32/);
});

test("资料改动识别昵称变化", () => {
  assert.equal(profileHasChanges("灶香集", "灶香集"), false);
  assert.equal(profileHasChanges(" 新昵称 ", "灶香集"), true);
});

test("个人资料页接受原生头像选择结果并直接保存", () => {
  const template = readFileSync(`${pageRoot}.wxml`, "utf8");
  const logic = readFileSync(`${pageRoot}.ts`, "utf8");
  const service = readFileSync(servicePath, "utf8");

  assert.match(template, /open-type="chooseAvatar"/);
  assert.match(template, /bindchooseavatar="onAvatarChoose"/);
  assert.match(template, /aria-label="更换头像"/);
  assert.match(template, /user-profile-background\.png/);
  assert.match(template, /round-back="\{\{true\}\}"/);
  assert.match(template, /class="avatar-button__image"[\s\S]*mode="aspectFill"/);
  assert.match(template, /type="nickname"/);
  assert.match(template, />退出登录<\/button>/);
  assert.doesNotMatch(template, /点击使用微信头像|头像仅通过微信头像选择器|>微信<\/view>/);
  assert.doesNotMatch(template, /微信账号|已绑定/);
  assert.doesNotMatch(template, /chooseImage|chooseMedia|type="file"/);
  assert.doesNotMatch(logic, /只允许使用微信头像|不能上传相册或拍照图片/);
  assert.doesNotMatch(logic, /wx\.getUserProfile/);
  assert.match(logic, /saveAvatar\(avatarUrl\)/);
  assert.match(service, /uploadFile<AppUserProfile>/);
  assert.doesNotMatch(service, /compressImage|chooseImage|chooseMedia/);
  assert.match(logic, /logoutSession/);
});
