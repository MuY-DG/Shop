import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import { normalizeContactPhone } from "../miniprogram/features/contact";

test("公开联系电话只接受后端允许的安全格式", () => {
  assert.equal(normalizeContactPhone(" 400-800-1234 "), "400-800-1234");
  assert.equal(normalizeContactPhone("+86 (28) 1234 5678"), "+86 (28) 1234 5678");
  assert.equal(normalizeContactPhone("1234"), "");
  assert.equal(normalizeContactPhone("400-800<script>"), "");
  assert.equal(normalizeContactPhone(undefined), "");
});

test("个人中心通过公开接口读取联系电话并使用微信拨号", () => {
  const sourceRoot = resolve(process.cwd(), "miniprogram");
  const endpointSource = readFileSync(
    resolve(sourceRoot, "constants/api-endpoints.ts"),
    "utf8"
  );
  const serviceSource = readFileSync(
    resolve(sourceRoot, "services/contact.ts"),
    "utf8"
  );
  const profileSource = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.ts"),
    "utf8"
  );

  assert.match(endpointSource, /contact: "\/app\/contact"/);
  assert.match(serviceSource, /auth: false/);
  assert.match(profileSource, /getPublicContact/);
  assert.match(profileSource, /wx\.makePhoneCall/);
});
