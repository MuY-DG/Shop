import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  buildCustomerServiceUrl,
  customerServiceEntryContext,
  customerServiceOrderStatusText,
  customerServicePriceRange,
  customerServiceStatusHint
} from "../miniprogram/features/customer-service";

const sourceRoot = resolve(process.cwd(), "miniprogram");

test("客服入口只携带合法商品或订单上下文", () => {
  assert.deepEqual(customerServiceEntryContext("PRODUCT", "42"), {
    contextType: "PRODUCT",
    contextId: 42
  });
  assert.deepEqual(customerServiceEntryContext("ORDER", "0"), {
    contextType: "GENERAL"
  });
  assert.deepEqual(customerServiceEntryContext("UNKNOWN", "42"), {
    contextType: "GENERAL"
  });
  assert.equal(
    buildCustomerServiceUrl("PRODUCT", 42),
    "/pages/customer-service/chat/chat?contextType=PRODUCT&contextId=42"
  );
});

test("客服会话状态、订单状态和商品价格生成稳定文案", () => {
  assert.equal(customerServiceStatusHint("DRAFT"), "发送消息后，客服会尽快接待");
  assert.equal(customerServiceStatusHint("ACTIVE", "小灶"), "小灶 正在为你服务");
  assert.equal(customerServiceOrderStatusText("SHIPPED"), "待收货");
  assert.equal(customerServiceOrderStatusText("UNKNOWN"), "订单");
  assert.equal(customerServicePriceRange(1290, 2590), "¥12.90–¥25.90");
  assert.equal(customerServicePriceRange(undefined, undefined), "价格以商品详情为准");
});

test("小程序客服使用自建接口、原图上传和两级商品来源面板", () => {
  const endpointSource = readFileSync(
    resolve(sourceRoot, "constants/api-endpoints.ts"),
    "utf8"
  );
  const serviceSource = readFileSync(
    resolve(sourceRoot, "services/customer-service.ts"),
    "utf8"
  );
  const pageSource = readFileSync(
    resolve(sourceRoot, "pages/customer-service/chat/chat.ts"),
    "utf8"
  );
  const template = readFileSync(
    resolve(sourceRoot, "pages/customer-service/chat/chat.wxml"),
    "utf8"
  );
  const profileTemplate = readFileSync(
    resolve(sourceRoot, "pages/profile/profile.wxml"),
    "utf8"
  );
  const detailTemplate = readFileSync(
    resolve(sourceRoot, "pages/product/detail/detail.wxml"),
    "utf8"
  );

  assert.match(endpointSource, /customerService/);
  assert.match(endpointSource, /\/app\/customer-service\/conversation\/images/);
  assert.match(serviceSource, /uploadCustomerServiceImage/);
  assert.match(pageSource, /sizeType: \["original"\]/);
  assert.match(pageSource, /getBrowseHistory/);
  assert.match(pageSource, /getFavorites/);
  assert.match(pageSource, /getCartItems/);
  assert.match(template, />相册</);
  assert.match(template, />拍摄</);
  assert.match(template, />订单</);
  assert.match(template, />商品</);
  assert.match(template, />浏览</);
  assert.match(template, />收藏</);
  assert.match(template, />购物车</);
  assert.doesNotMatch(`${profileTemplate}\n${detailTemplate}`, /open-type="contact"/);
});
