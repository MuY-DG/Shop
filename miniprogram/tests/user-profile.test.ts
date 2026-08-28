import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  copyUserId,
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

function clipboardRuntime(fail = false) {
  const written: string[] = [];
  const toasts: Array<{ title: string; icon: "success" | "none" }> = [];
  return {
    written,
    toasts,
    runtime: {
      setClipboardData(options: {
        data: string;
        success?: () => void;
        fail?: () => void;
      }) {
        written.push(options.data);
        if (fail) options.fail?.();
        else options.success?.();
      },
      showToast(options: { title: string; icon: "success" | "none" }) {
        toasts.push(options);
      }
    }
  };
}

test("复制用户 ID 保留完整长编号且不附带展示前缀", () => {
  const fixture = clipboardRuntime();
  copyUserId("  9223372036854775807  ", fixture.runtime);
  assert.deepEqual(fixture.written, ["9223372036854775807"]);
  assert.deepEqual(fixture.toasts, [{ title: "用户 ID 已复制", icon: "success" }]);
});

test("用户 ID 缺失或不是字符串时不写入剪贴板", () => {
  for (const value of [undefined, null, "", "  ", 123]) {
    const fixture = clipboardRuntime();
    copyUserId(value, fixture.runtime);
    assert.deepEqual(fixture.written, []);
    assert.deepEqual(fixture.toasts, [{ title: "用户 ID 暂不可用", icon: "none" }]);
  }
});

test("复制用户 ID 失败时不误报成功", () => {
  const fixture = clipboardRuntime(true);
  copyUserId("123", fixture.runtime);
  assert.deepEqual(fixture.written, ["123"]);
  assert.deepEqual(fixture.toasts, [{ title: "复制失败，请稍后重试", icon: "none" }]);
});

test("个人资料按昵称、手机号、ID 排列并紧凑展示同色描边复制按钮", () => {
  const template = readFileSync(`${pageRoot}.wxml`, "utf8");
  const logic = readFileSync(`${pageRoot}.ts`, "utf8");
  const styles = readFileSync(`${pageRoot}.less`, "utf8");
  const idField = template.match(
    /<view class="profile-field profile-field--user-id profile-field--last">[\s\S]*?<\/button>\s*<\/view>/
  )?.[0] ?? "";

  assert.match(idField, /class="profile-field__label">ID<\/text>/);
  assert.match(idField, /class="profile-field__value profile-field__value--id">\{\{userId \|\| '--'\}\}<\/view>\s*<button/);
  assert.match(idField, /disabled="\{\{!userId \|\| loggingOut\}\}"/);
  assert.match(idField, /bindtap="onCopyUserIdTap"[\s\S]*?aria-label="复制用户 ID"[\s\S]*?>复制<\/button>/);
  assert.doesNotMatch(idField, /<input/);
  assert.match(template, /class="profile-field__label">昵称[\s\S]*?class="profile-field__label">手机号[\s\S]*?profile-field--user-id profile-field--last/);
  assert.match(idField, /class="profile-field__id-content"/);
  assert.match(logic, /userId: ""/);
  assert.match(logic, /userId: profile\.userId/);
  assert.match(logic, /onCopyUserIdTap\(\)[\s\S]*?if \(this\.data\.loggingOut\)\s*\{\s*return;\s*\}[\s\S]*?copyUserId\(this\.data\.userId\)/);
  assert.match(styles, /\.profile-field__copy\s*\{[^}]*?width: auto !important;[^}]*?height: 52rpx;[^}]*?margin: 0;[^}]*?border: 1rpx solid currentColor;[^}]*?flex: none;[^}]*?color: @profile-save;/);
  assert.match(idField, /!userId \|\| loggingOut \? 'profile-field__copy--disabled' : ''/);
  assert.match(styles, /\.profile-field__copy--disabled\s*\{[^}]*?color: @color-text-muted;/);
  assert.doesNotMatch(styles, /button\.profile-field__copy|\.profile-field__copy\[disabled\]|\.avatar-button\[disabled\]/);
  assert.match(styles, /\.profile-field__id-content\s*\{[^}]*?justify-content: flex-end;[^}]*?gap: @space-3;/);
  assert.match(styles, /\.profile-field__value--id\s*\{[^}]*?flex: 0 1 auto;[^}]*?word-break: break-all;/);
  assert.match(styles, /\.profile-field__label\s*\{[^}]*?color: @color-text-muted;/);
  assert.match(styles, /\.profile-field__input\s*\{[^}]*?color: @color-text-secondary;/);
  assert.match(styles, /\.profile-field__value\s*\{[^}]*?color: @color-text-secondary;/);
});

test("个人资料头像独立于信息列表并衔接白色导航，保存按钮紧跟列表", () => {
  const template = readFileSync(`${pageRoot}.wxml`, "utf8");
  const styles = readFileSync(`${pageRoot}.less`, "utf8");
  const cameraIcon = readFileSync(
    resolve(process.cwd(), "miniprogram/assets/icons/chat-camera.svg"),
    "utf8"
  );
  const avatarSection = template.slice(
    template.indexOf('<view class="profile-avatar-section">'),
    template.indexOf('<view class="user-profile-content">')
  );

  assert.match(template, /<navigation-bar[^>]*?background="#ffffff"/);
  assert.match(avatarSection, /class="avatar-slot"/);
  assert.match(avatarSection, /class="avatar-camera" aria-hidden="true"/);
  assert.match(avatarSection, /class="avatar-camera__icon"[\s\S]*?src="\/assets\/icons\/chat-camera\.svg"/);
  assert.match(avatarSection, /open-type="chooseAvatar"[\s\S]*?bindchooseavatar="onAvatarChoose"/);
  assert.doesNotMatch(avatarSection, /class="profile-card"/);
  assert.match(cameraIcon, /<svg[^>]*?viewBox="0 0 24 24"/);
  assert.match(styles, /\.profile-avatar-section\s*\{[^}]*?background: @color-surface-white;/);
  assert.match(styles, /\.avatar-slot\s*\{[^}]*?position: relative;[^}]*?background: transparent;/);
  assert.doesNotMatch(styles, /\.avatar-slot\s*\{[^}]*?(?:top: -|overflow: hidden|box-shadow:|transform:)/);
  assert.match(styles, /\.avatar-button__image\s*\{[^}]*?background: transparent;/);
  assert.match(styles, /@profile-camera-background: #e5e5e5;/);
  assert.match(styles, /\.avatar-camera\s*\{[^}]*?right: 0;[^}]*?bottom: 0;[^}]*?background: @profile-camera-background;[^}]*?pointer-events: none;/);
  assert.match(styles, /\.user-profile-content\s*\{[^}]*?padding: @space-6 @page-gutter/);
  assert.match(styles, /\.profile-card\s*\{[^}]*?margin-right: -@page-gutter;[^}]*?margin-left: -@page-gutter;[^}]*?padding: 0 @space-6;[^}]*?border-radius: 0;[^}]*?box-shadow: none;/);
  assert.match(styles, /\.profile-actions\s*\{[^}]*?margin-top: @space-6;/);
  assert.doesNotMatch(styles, /\.profile-actions\s*\{[^}]*?(?:margin-top: auto|padding-top:)/);
});

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
  const styles = readFileSync(`${pageRoot}.less`, "utf8");
  const service = readFileSync(servicePath, "utf8");
  const avatarButton = template.match(
    /<button\s+class="avatar-button[^\"]*"[\s\S]*?<\/button>/
  )?.[0] ?? "";

  assert.match(template, /open-type="chooseAvatar"/);
  assert.match(template, /bindchooseavatar="onAvatarChoose"/);
  assert.match(template, /aria-label="更换头像"/);
  assert.doesNotMatch(template, /user-profile-background\.png/);
  assert.match(template, /round-back="\{\{true\}\}"/);
  assert.match(template, /class="avatar-button__image"[\s\S]*mode="aspectFill"/);
  assert.match(template, /type="nickname"/);
  assert.match(template, />退出登录<\/button>/);
  assert.doesNotMatch(template, /点击使用微信头像|头像仅通过微信头像选择器|>微信<\/view>/);
  assert.doesNotMatch(template, /微信账号|已绑定/);
  assert.doesNotMatch(template, /chooseImage|chooseMedia|type="file"/);
  assert.doesNotMatch(logic, /只允许使用微信头像|不能上传相册或拍照图片/);
  assert.doesNotMatch(logic, /wx\.getUserProfile/);
  assert.match(logic, /profile-default-avatar\.png/);
  assert.match(logic, /savingAvatar: true,\s+validationErrorText: ""/);
  assert.doesNotMatch(logic, /savingAvatar: true,\s+avatarUrl/);
  assert.doesNotMatch(logic, /avatarUrl: previousAvatarUrl/);
  assert.match(logic, /saveAvatar\(avatarUrl\)/);
  assert.match(logic, /头像已更新，还剩 \$\{result\.remainingChanges\} 次/);
  assert.match(logic, /error\.kind === "RATE_LIMIT"/);
  assert.doesNotMatch(avatarButton, /loading=/);
  assert.match(styles, /\.avatar-button\s*\{[\s\S]*?opacity:\s*0;/);
  assert.match(avatarButton, /saving \|\| savingAvatar \|\| loggingOut \? 'avatar-button--disabled' : ''/);
  assert.match(styles, /\.avatar-button--disabled/);
  assert.match(service, /uploadFileDirect<AppUserAvatarUpdateResponse>/);
  assert.match(service, /avatarUploads/);
  assert.match(service, /remainingChanges/);
  assert.doesNotMatch(service, /compressImage|chooseImage|chooseMedia/);
  assert.match(logic, /logoutSession/);
});
