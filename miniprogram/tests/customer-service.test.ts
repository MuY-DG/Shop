import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  buildCustomerServiceUrl,
  customerServiceEntryContext,
  isPersistedCustomerServiceMessageId,
  customerServiceOrderStatusText,
  customerServicePriceRange,
  customerServiceStatusHint,
  shouldShowCustomerServiceCommonQuestions
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

test("客服图片下载只允许服务端持久化后的消息 ID", () => {
  assert.equal(isPersistedCustomerServiceMessageId(1), true);
  assert.equal(isPersistedCustomerServiceMessageId(Number.MAX_SAFE_INTEGER), true);
  assert.equal(isPersistedCustomerServiceMessageId(-1), false);
  assert.equal(isPersistedCustomerServiceMessageId(0), false);
  assert.equal(isPersistedCustomerServiceMessageId(1.5), false);
  assert.equal(isPersistedCustomerServiceMessageId("1"), false);
});

test("客服会话状态、订单状态和商品价格生成稳定文案", () => {
  assert.equal(customerServiceStatusHint("DRAFT"), "发送消息后，客服会尽快接待");
  assert.equal(customerServiceStatusHint("ACTIVE", "小灶"), "小灶 正在为你服务");
  assert.equal(customerServiceOrderStatusText("SHIPPED"), "待收货");
  assert.equal(customerServiceOrderStatusText("UNKNOWN"), "订单");
  assert.equal(customerServicePriceRange(1290, 2590), "¥12.90–¥25.90");
  assert.equal(customerServicePriceRange(undefined, undefined), "价格以商品详情为准");
});

test("常见问题只在尚未发起咨询的草稿会话展示", () => {
  assert.equal(shouldShowCustomerServiceCommonQuestions("DRAFT", 3, false), true);
  assert.equal(shouldShowCustomerServiceCommonQuestions("DRAFT", 0, false), false);
  assert.equal(shouldShowCustomerServiceCommonQuestions("DRAFT", 3, true), false);
  assert.equal(shouldShowCustomerServiceCommonQuestions("WAITING", 3, false), false);
  assert.equal(shouldShowCustomerServiceCommonQuestions("ACTIVE", 3, false), false);
});

test("小程序客服使用自建接口、即时图片预览和两级商品来源面板", () => {
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
  const realtimeSource = readFileSync(
    resolve(sourceRoot, "services/customer-service-realtime.ts"),
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
  const sendTextSource = pageSource.slice(
    pageSource.indexOf("sendText()"),
    pageSource.indexOf("onPlusTap()")
  );
  const sendImageSource = pageSource.slice(
    pageSource.indexOf("async selectAndUploadImages"),
    pageSource.indexOf("onOrderActionTap()")
  );

  assert.match(endpointSource, /customerService/);
  assert.match(endpointSource, /\/app\/customer-service\/conversation\/common-questions/);
  assert.match(endpointSource, /\/app\/customer-service\/conversation\/images/);
  assert.match(endpointSource, /\/app\/customer-service\/images\/upload-sessions/);
  assert.match(endpointSource, /messages\/\$\{messageId\}\/image-access/);
  assert.match(endpointSource, /messages\/\$\{messageId\}\/thumbnail/);
  assert.match(serviceSource, /uploadCustomerServiceImage/);
  assert.match(serviceSource, /getCustomerServiceCommonQuestions/);
  assert.match(serviceSource, /uploadFileDirect<CustomerServiceMessage>/);
  assert.match(serviceSource, /thumbnailAccessMode === "SIGNED_URL"/);
  assert.match(serviceSource, /loadCachedImageFile/);
  assert.match(serviceSource, /accessMode === "SIGNED_URL"/);
  assert.match(serviceSource, /refreshCustomerServiceImageAccess/);
  assert.match(serviceSource, /downloadExternalFile/);
  assert.match(serviceSource, /downloadAuthenticatedFile/);
  assert.match(serviceSource, /thumbnailStatus === "READY"/);
  assert.match(serviceSource, /requirePersistedMessageId/);
  assert.match(pageSource, /createIntersectionObserver/);
  assert.match(pageSource, /downloadCustomerServiceOriginalImage/);
  assert.doesNotMatch(pageSource, /scheduleThumbnailStatusRefresh/);
  assert.doesNotMatch(pageSource, /缩略图处理中/);
  assert.match(pageSource, /FALLBACK_POLL_INTERVAL_MS = 15_000/);
  assert.match(pageSource, /subscribeCustomerServiceRealtimeState/);
  assert.match(realtimeSource, /"CONNECTING"[\s\S]*"CONNECTED"[\s\S]*"DISCONNECTED"/);
  assert.match(realtimeSource, /heartbeatTimeoutTimer/);
  assert.match(pageSource, /sizeType: \["compressed"\]/);
  assert.doesNotMatch(pageSource, /sizeType: \["original"\]/);
  assert.match(pageSource, /imageUploading: true/);
  assert.match(template, /message-image--uploading/);
  assert.doesNotMatch(template, /uploadProgress/);
  assert.match(sendTextSource, /appendLocallySentMessage/);
  assert.match(sendTextSource, /pendingTextMessages/);
  assert.match(sendTextSource, /deliverPendingText/);
  assert.doesNotMatch(sendTextSource, /refreshConversation/);
  assert.doesNotMatch(template, /loading="\{\{sending\}\}"/);
  assert.match(template, /message-send-error/);
  assert.match(
    template,
    /message-bubble message-bubble--other common-question-bubble[\s\S]*common-question-opening[\s\S]*common-question-list/
  );
  assert.doesNotMatch(template, /common-question-title/);
  assert.doesNotMatch(template, /common-question-message/);
  assert.doesNotMatch(template, /showCommonQuestions && !commonQuestionAnchorMessageId/);
  assert.match(template, /onCommonQuestionTap/);
  assert.match(template, /commonQuestionAnchorMessageId === item\.messageId/);
  assert.match(pageSource, /commonQuestionMessageIds\.delete\(messageId\)/);
  assert.match(pageSource, /commonQuestionSending: false/);
  assert.doesNotMatch(template, /你好，我是在线客服/);
  assert.match(pageSource, /message\.messageType === "SYSTEM"/);
  assert.match(pageSource, /showCommonQuestions: false/);
  assert.match(pageSource, /conversationMutationEpoch/);
  assert.match(pageSource, /pendingRealtimeChangeWithoutMessage = true/);
  assert.match(pageSource, /void this\.loadCommonQuestions\(generation\)/);
  assert.doesNotMatch(pageSource, /Promise\.all\(\[\s*openCustomerServiceConversation/);
  assert.match(template, /confirm-hold="\{\{true\}\}"/);
  assert.match(sendImageSource, /appendLocallySentMessage/);
  assert.doesNotMatch(sendImageSource, /refreshConversation/);
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
