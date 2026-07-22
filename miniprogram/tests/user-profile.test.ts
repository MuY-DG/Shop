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

test("微信昵称规范化并限制有效长度", () => {
  assert.equal(normalizeProfileNickname("  山茶花  "), "山茶花");
  assert.equal(validateProfileNickname(""), "请选择或输入微信昵称");
  assert.equal(validateProfileNickname("单"), "昵称至少需要 2 个字符");
  assert.equal(validateProfileNickname("灶香集会员"), "");
  assert.equal(validateProfileNickname("用\u0001户"), "昵称包含不支持的字符");
  assert.match(validateProfileNickname("很".repeat(33)), /32/);
});

test("资料改动同时识别昵称和微信头像临时路径", () => {
  assert.equal(profileHasChanges("灶香集", "灶香集", ""), false);
  assert.equal(profileHasChanges(" 新昵称 ", "灶香集", ""), true);
  assert.equal(profileHasChanges("灶香集", "灶香集", "wxfile://avatar.png"), true);
});

test("个人资料页只开放微信头像和昵称能力并提供退出登录", () => {
  const template = readFileSync(`${pageRoot}.wxml`, "utf8");
  const logic = readFileSync(`${pageRoot}.ts`, "utf8");

  assert.match(template, /open-type="chooseAvatar"/);
  assert.match(template, /bindchooseavatar="onAvatarChoose"/);
  assert.match(template, /user-profile-background\.png/);
  assert.match(template, /round-back="\{\{true\}\}"/);
  assert.match(template, /class="avatar-button__image"[\s\S]*mode="aspectFill"/);
  assert.match(template, /type="nickname"/);
  assert.match(template, />退出登录<\/button>/);
  assert.doesNotMatch(template, /点击使用微信头像|头像仅通过微信头像选择器|>微信<\/view>/);
  assert.doesNotMatch(template, /微信账号|已绑定/);
  assert.doesNotMatch(template, /chooseImage|chooseMedia|type="file"/);
  assert.doesNotMatch(logic, /wx\.(chooseImage|chooseMedia)/);
  assert.match(logic, /logoutSession/);
});
