import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  buildCustomerServiceUrl,
  CustomerServiceHistoryLoadGate,
  CustomerServiceHistoryScrollIntent,
  customerServiceEntryContext,
  isPersistedCustomerServiceMessageId,
  customerServiceOrderStatusText,
  customerServicePriceRange,
  customerServiceStatusHint,
  parseCustomerServiceDate,
  preserveCustomerServiceHistoryScrollTop,
  shouldShowCustomerServiceMessageTime,
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

test("客服时间按带偏移的 API 时间契约解析", () => {
  const localDate = parseCustomerServiceDate("2026-08-01T08:27:30.123456Z");
  assert.ok(localDate);
  assert.equal(localDate.toISOString(), "2026-08-01T08:27:30.123Z");
  assert.equal(
    parseCustomerServiceDate("2026-08-01T16:27:30Z")?.getTime(),
    Date.UTC(2026, 7, 1, 16, 27, 30)
  );
  assert.equal(parseCustomerServiceDate("2026-08-01T16:27:30"), null);
  assert.equal(parseCustomerServiceDate("not-a-date"), null);
});

test("客服临时消息沿用五分钟时间分组，避免发送后时间闪动", () => {
  const previous = { consultationNo: 1, createdAt: "2026-08-01T08:26:00Z" };
  assert.equal(
    shouldShowCustomerServiceMessageTime(
      { consultationNo: 1, createdAt: "2026-08-01T08:27:00Z" },
      previous
    ),
    false
  );
  assert.equal(
    shouldShowCustomerServiceMessageTime(
      { consultationNo: 1, createdAt: "2026-08-01T08:31:00Z" },
      previous
    ),
    true
  );
  assert.equal(
    shouldShowCustomerServiceMessageTime(
      { consultationNo: 2, createdAt: "2026-08-01T08:27:00Z" },
      previous
    ),
    true
  );
});

test("客服历史 prepend 后按锚点坐标差保持当前阅读位置", () => {
  assert.equal(preserveCustomerServiceHistoryScrollTop(12, -44, 1196), 1252);
  assert.equal(preserveCustomerServiceHistoryScrollTop(0, 20, 10), 0);
});

test("客服历史记录的一次滚动意图最多触发一次加载", () => {
  const gate = new CustomerServiceHistoryLoadGate();

  gate.armGesture(true);
  assert.equal(gate.consumeGesture(true), true);
  assert.equal(gate.phase, "loading");
  assert.equal(gate.consumeGesture(true), false);

  gate.markRestoring();
  assert.equal(gate.phase, "restoring");
  gate.finish();
  assert.equal(gate.phase, "idle");
  assert.equal(gate.consumeGesture(true), false);

  gate.armGesture(true);
  gate.cancelGesture();
  assert.equal(gate.consumeGesture(true), false);
  gate.armGesture(true);
  assert.equal(gate.consumeGesture(true), true);
  gate.finish();
  assert.equal(gate.beginManualLoad(true), true);
  assert.equal(gate.beginManualLoad(true), false);
});

test("客服历史滚动必须离开顶部并产生新的连续向上位移才触发", () => {
  const intent = new CustomerServiceHistoryScrollIntent({
    rearmScrollTop: 160,
    loadScrollTop: 80,
    directionTolerance: 2,
    minimumTowardUpperDistance: 24,
    minimumTowardUpperSamples: 2
  });

  intent.reset(1200, true);
  assert.equal(intent.consumeUpper(true), false);
  assert.equal(intent.recordScroll(1200, 600, true), false);
  assert.equal(intent.consumeUpper(true), false);
  assert.equal(intent.recordScroll(600, 70, true), true);
  assert.equal(intent.consumeUpper(true), true);
  assert.equal(intent.consumeUpper(true), false);
  assert.equal(intent.recordScroll(70, 0, true), false);

  intent.reset(0, false);
  assert.equal(intent.consumeUpper(true), false);
  assert.equal(intent.recordScroll(0, 200, true), false);
  assert.equal(intent.recordScroll(200, 140, true), false);
  assert.equal(intent.recordScroll(140, 70, true), true);
  assert.equal(intent.consumeScrollEnd(70, true), true);
  assert.equal(intent.consumeUpper(true), false);
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
  const style = readFileSync(
    resolve(sourceRoot, "pages/customer-service/chat/chat.less"),
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
  const sendTextStart = pageSource.indexOf("  sendText(value?: string)");
  const sendTextEnd = pageSource.indexOf("  onPlusTap()", sendTextStart);
  const sendImageStart = pageSource.indexOf("  async selectAndUploadImages");
  const sendImageEnd = pageSource.indexOf("  onOrderActionTap()", sendImageStart);
  const historyLoadStart = pageSource.indexOf("  async loadEarlierMessages()");
  const historyLoadEnd = pageSource.indexOf("  observePrivateImages()", historyLoadStart);
  const messageScrollStart = pageSource.indexOf("  onMessageListScroll(event: ScrollEvent)");
  const messageScrollEnd = pageSource.indexOf(
    "  onScrollToLower()",
    messageScrollStart
  );
  const onScrollToUpperStart = pageSource.indexOf("  onScrollToUpper()");
  const onHistoryTapStart = pageSource.indexOf("  onHistoryTap()");
  const messagePreloadStart = pageSource.indexOf("  tryPreloadEarlierMessages()");
  const imageTemplateStart = template.indexOf("item.messageType === 'IMAGE'");
  const imageTemplateEnd = template.indexOf("item.messageType === 'ORDER_CARD'");
  const composerInputStyleStart = style.indexOf(".composer__input-shell {");
  const composerInputStyleEnd = style.indexOf(
    ".composer__placeholder",
    composerInputStyleStart
  );
  const composerInputTemplateStart = template.indexOf('class="composer__input-shell"');
  const composerPlusTemplateStart = template.indexOf('class="composer__plus ');
  for (const boundary of [
    sendTextStart,
    sendTextEnd,
    sendImageStart,
    sendImageEnd,
    historyLoadStart,
    historyLoadEnd,
    messageScrollStart,
    messageScrollEnd,
    onScrollToUpperStart,
    onHistoryTapStart,
    messagePreloadStart,
    imageTemplateStart,
    imageTemplateEnd,
    composerInputStyleStart,
    composerInputStyleEnd,
    composerInputTemplateStart,
    composerPlusTemplateStart
  ]) {
    assert.notEqual(boundary, -1);
  }
  const sendTextSource = pageSource.slice(sendTextStart, sendTextEnd);
  const sendImageSource = pageSource.slice(sendImageStart, sendImageEnd);
  const historyLoadSource = pageSource.slice(historyLoadStart, historyLoadEnd);
  const messageScrollSource = pageSource.slice(messageScrollStart, messageScrollEnd);
  const onScrollToUpperSource = pageSource.slice(
    onScrollToUpperStart,
    onHistoryTapStart
  );
  const messagePreloadSource = pageSource.slice(
    messagePreloadStart,
    onHistoryTapStart
  );
  const imageTemplate = template.slice(imageTemplateStart, imageTemplateEnd);
  const composerInputStyle = style.slice(
    composerInputStyleStart,
    composerInputStyleEnd
  );

  assert.match(endpointSource, /customerService/);
  assert.match(endpointSource, /\/app\/customer-service\/conversation\/common-questions/);
  assert.match(endpointSource, /\/app\/customer-service\/conversation\/images/);
  assert.match(endpointSource, /\/app\/customer-service\/images\/upload-sessions/);
  assert.match(endpointSource, /messages\/\$\{messageId\}\/image-access/);
  assert.match(endpointSource, /messages\/\$\{messageId\}\/thumbnail/);
  assert.match(serviceSource, /uploadCustomerServiceImage/);
  assert.match(serviceSource, /getCustomerServiceCommonQuestions/);
  assert.match(serviceSource, /beforeId\?: number/);
  assert.match(serviceSource, /limit\?: number/);
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
  assert.match(pageSource, /sending: true/);
  assert.match(imageTemplate, /item\.sending/);
  assert.match(imageTemplate, /message-send-loading/);
  assert.match(imageTemplate, /onRetryImageTap/);
  assert.doesNotMatch(template, /message-image--uploading/);
  assert.doesNotMatch(style, /grayscale/);
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
  assert.match(template, />MuYbaby</);
  assert.match(template, />客服会话</);
  assert.doesNotMatch(template, /title="在线客服"/);
  assert.doesNotMatch(template, /service-status|statusHint/);
  assert.match(pageSource, /message\.messageType === "SYSTEM"/);
  assert.match(pageSource, /showCommonQuestions: false/);
  assert.match(pageSource, /conversationMutationEpoch/);
  assert.match(pageSource, /localMutationCount > 0[\s\S]*refreshQueued = true/);
  assert.match(
    pageSource,
    /const requestGeneration = initializeGeneration;[\s\S]*requestGeneration !== initializeGeneration/
  );
  assert.match(pageSource, /pendingRealtimeChangeWithoutMessage = true/);
  assert.match(pageSource, /void this\.loadCommonQuestions\(generation\)/);
  assert.doesNotMatch(pageSource, /Promise\.all\(\[\s*openCustomerServiceConversation/);
  assert.match(template, /confirm-type="send"/);
  assert.match(template, /confirm-hold="\{\{true\}\}"/);
  assert.match(template, /adjust-position="\{\{true\}\}"/);
  assert.match(template, /bindconfirm="onInputConfirm"/);
  assert.doesNotMatch(template, /bindkeyboardheightchange|keyboard-spacer|keyboardHeight/);
  assert.doesNotMatch(pageSource, /KeyboardHeightEvent|keyboardSettleTimer|keyboardTransitionDuration/);
  assert.match(pageSource, /stableMessageTimeVisibility/);
  assert.match(pageSource, /rememberMessageTimeVisibility\(message, pendingView\.showTime\)/);
  assert.match(pageSource, /const currentUser = getSessionState\(\)\.user/);
  assert.match(pageSource, /const senderAvatar = isMine \? currentUserAvatar \|\| avatar : avatar/);
  assert.match(pageSource, /messageRenderKeyById\.set\(message\.messageId, pendingView\.renderKey\)/);
  assert.match(template, /wx:key="renderKey"/);
  assert.match(template, /item\.senderAvatar \? 'message-avatar--image' : 'message-avatar--mine'/);
  assert.match(pageSource, /consultationNo: currentConsultationNo \|\| 1/);
  assert.doesNotMatch(style, /composer--keyboard-open|keyboard-spacer/);
  assert.match(pageSource, /nextPersistedMessageId !== latestPersistedMessageId/);
  assert.match(pageSource, /this\.observePrivateImages\(\);[\s\S]*this\.scrollToLatest\(\);/);
  assert.doesNotMatch(template, /composer__send|onSendTap|chat-send\.svg/);
  assert.ok(composerInputTemplateStart < composerPlusTemplateStart);
  assert.doesNotMatch(template, /wx:if="\{\{inputValue\}\}"/);
  assert.doesNotMatch(pageSource, /\bcanSend\b|onSendTap/);
  assert.match(composerInputStyle, /width: 0;[\s\S]*flex: 1 1 0;/);
  assert.match(style, /composer--panel-open/);
  assert.doesNotMatch(style, /attachment-panel--open/);
  assert.match(template, /id="message-list-bottom" class="message-list__bottom"/);
  assert.match(template, /enable-passive="\{\{true\}\}"/);
  assert.doesNotMatch(template, /scroll-anchoring/);
  assert.doesNotMatch(template, /scroll-with-animation|scroll-top=/);
  assert.match(template, /scroll-into-view="\{\{scrollAnchor\}\}"/);
  assert.match(template, /upper-threshold="600"/);
  assert.match(
    style,
    /\.message-list \{[\s\S]*display: flex;[\s\S]*min-height: 100%;[\s\S]*flex-direction: column;[\s\S]*justify-content: flex-end;/
  );
  assert.match(template, /binddragend="onMessageListDragEnd"/);
  assert.doesNotMatch(template, /bindtouch|binddragstart/);
  assert.match(
    template,
    /historyLoading \|\| hasMoreHistory \|\| historyExhausted[\s\S]*class="history-loader"/
  );
  assert.match(style, /\.history-loader \{[\s\S]*height: 64rpx/);
  assert.doesNotMatch(pageSource, /messageScrollTop|scrollWithAnimation/);
  assert.match(pageSource, /WechatMiniprogram\.ScrollViewContext/);
  assert.match(pageSource, /select\("\.message-scroll"\)\.node\(\)/);
  assert.match(pageSource, /context\.scrollTo\(\{ top: target, animated \}\)/);
  assert.match(
    pageSource,
    /positionLatestWithoutAnimation\(\)[\s\S]*settleMessageListAtBottom\(false, commandGeneration\)/
  );
  assert.match(pageSource, /const isInitialPositioning = !this\.data\.loaded/);
  assert.match(
    pageSource,
    /loaded: true[\s\S]*scrollAnchor: "message-list-bottom"[\s\S]*positionLatestReliably\(\)/
  );
  assert.match(
    historyLoadSource,
    /loadEarlierMessages\(\)[\s\S]*beforeId: firstMessage\.messageId[\s\S]*limit: HISTORY_PAGE_SIZE/
  );
  assert.match(historyLoadSource, /this\.setData\(\{ historyLoading: true \}\)/);
  assert.match(historyLoadSource, /historyLoadGate\.phase !== "loading"/);
  assert.match(historyLoadSource, /historyLoadGate\.markRestoring\(\)/);
  assert.match(pageSource, /measureHistoryScrollMetrics\(messageId: number\)/);
  assert.match(pageSource, /select\("\.message-scroll"\)\.scrollOffset\(\)/);
  assert.match(pageSource, /select\("\.message-scroll"\)\.boundingClientRect\(\)/);
  assert.match(pageSource, /anchorOffset: hasAnchorOffset/);
  assert.match(pageSource, /scrollHeight:[\s\S]*: null/);
  assert.match(
    historyLoadSource,
    /metricsBefore[\s\S]*messages: views[\s\S]*metricsAfter[\s\S]*historyScrollTop/
  );
  assert.match(
    pageSource,
    /metricsAfter\.scrollTop[\s\S]*metricsBefore\.anchorOffset[\s\S]*metricsAfter\.anchorOffset[\s\S]*preserveCustomerServiceHistoryScrollTop/
  );
  assert.match(
    pageSource,
    /metricsBefore\.scrollTop \+ Math\.max\([\s\S]*metricsAfter\.scrollHeight - metricsBefore\.scrollHeight/
  );
  assert.match(
    historyLoadSource,
    /waitForHistoryScrollRestore[\s\S]*historyLoading: false[\s\S]*historyLoadGate\.finish\(\)/
  );
  assert.match(template, /bindscrolltoupper="onScrollToUpper"/);
  assert.doesNotMatch(template, /bindscrollend|bind:scrollend/);
  assert.match(template, /bindscrolltolower="onScrollToLower"/);
  assert.match(template, /bindtap="onHistoryTap"/);
  assert.match(template, />查看更早记录</);
  assert.match(template, /historyExhausted/);
  assert.match(template, />已显示全部记录</);
  assert.match(
    pageSource,
    /historyExhausted: olderMessages\.length < HISTORY_PAGE_SIZE/
  );
  assert.match(
    pageSource,
    /nextPersistedMessageId !== latestPersistedMessageId[\s\S]*messageListFollowingLatest[\s\S]*!this\.data\.historyLoading/
  );
  assert.match(pageSource, /const nextSignature = conversationSignature\(conversation\)/);
  assert.match(
    pageSource,
    /this\.data\.loaded && nextSignature === conversationSignatureValue[\s\S]*return;/
  );
  assert.match(pageSource, /conversationSignatureValue = nextSignature/);
  assert.match(pageSource, /function conversationSignature\(/);
  assert.match(pageSource, /conversation\.conversationId,[\s\S]*conversation\.status,[\s\S]*conversation\.consultationNo[\s\S]*rawMessages\.length[\s\S]*lastMessage\?\.messageId/);
  assert.match(
    pageSource,
    /JSON\.stringify\(context\),[\s\S]*JSON\.stringify\(rawMessages\),[\s\S]*cachedImages/
  );
  assert.match(pageSource, /viewKey: string;/);
  assert.match(
    pageSource,
    /function messageViewKey\([\s\S]*message: CustomerServiceMessage,[\s\S]*senderName: string,[\s\S]*senderAvatar: string,[\s\S]*imageUrl: string[\s\S]*\): string/
  );
  assert.match(
    pageSource,
    /message\.consultationNo,[\s\S]*senderName,[\s\S]*senderAvatar,[\s\S]*imageUrl,[\s\S]*image\?\.width[\s\S]*order\?\.orderId[\s\S]*primaryProductImage[\s\S]*product\?\.productId[\s\S]*product\?\.image/
  );
  assert.match(pageSource, /existingViews\?\.get\(message\.messageId\)/);
  assert.match(
    pageSource,
    /existingView\.viewKey === view\.viewKey[\s\S]*existingView\.imageUrl === view\.imageUrl[\s\S]*existingView\.showTime === showTime[\s\S]*return existingView/
  );
  assert.match(
    pageSource,
    /const existingViews = new Map\(\s*this\.data\.messages\.map\(\(view\) => \[view\.messageId, view\]\)\s*\)/,
  );
  assert.match(pageSource, /const imageMessagesNow = this\.data\.messages[\s\S]*\.filter\(\(message\) => message\.messageType === "IMAGE"\)/);
  assert.match(
    pageSource,
    /imageMessagesNow === imageObservationSignature[\s\S]*return;/
  );
  assert.match(
    pageSource,
    /const messageIndex = this\.data\.messages\.findIndex\([\s\S]*messages\[\$\{messageIndex\}\]\.imageUrl/,
  );
  assert.match(
    pageSource,
    /scrollDelta < -HISTORY_SCROLL_DIRECTION_TOLERANCE_PX[\s\S]*messageListFollowingLatest = false/
  );
  assert.match(pageSource, /onScrollToLower\(\)[\s\S]*messageListFollowingLatest = true/);
  assert.doesNotMatch(messageScrollSource, /void this\.loadEarlierMessages/);
  assert.match(
    messageScrollSource,
    /historyLoadGate\.phase === "idle"[\s\S]*historyScrollIntent\.recordScroll\(/
  );
  assert.match(
    messageScrollSource,
    /!messageListPositioningLatest[\s\S]*!messageListFollowingLatest[\s\S]*nextScrollTop < HISTORY_PRELOAD_THRESHOLD[\s\S]*this\.tryPreloadEarlierMessages\(\);/
  );
  assert.match(
    messagePreloadSource,
    /tryPreloadEarlierMessages\(\)[\s\S]*messageListPositioningLatest[\s\S]*messageListFollowingLatest[\s\S]*messageListScrollTop >= HISTORY_PRELOAD_THRESHOLD[\s\S]*historyLoadGate\.beginManualLoad\(this\.canLoadEarlierMessages\(\)\)[\s\S]*this\.loadEarlierMessages\(\)/
  );
  assert.match(
    onScrollToUpperSource,
    /onScrollToUpper\(\)[\s\S]*messageListPositioningLatest[\s\S]*return;[\s\S]*this\.tryPreloadEarlierMessages\(\)[\s\S]*scheduleHistoryScrollEnd\(\)/
  );
  assert.doesNotMatch(onScrollToUpperSource, /void this\.loadEarlierMessages/);
  assert.doesNotMatch(pageSource, /historyPreloadArmed/);
  assert.match(
    pageSource,
    /wx\.nextTick\(\(\) => \{\s*void this\.positionLatestReliably\(\);\s*\}\)/
  );
  assert.match(pageSource, /async positionLatestReliably\(\): Promise<boolean>/);
  assert.match(
    pageSource,
    /positionLatestReliably\(\): Promise<boolean>[\s\S]*waitForMilliseconds\(MESSAGE_INITIAL_POSITION_DELAY_MS\)/
  );
  assert.match(pageSource, /HISTORY_POSITION_OUTER_ATTEMPTS/);
  assert.match(
    pageSource,
    /scrollToLatest\(\)[\s\S]*scrollAnchor: ""[\s\S]*scrollAnchor: "message-list-bottom"/
  );
  assert.match(
    pageSource,
    /commandGeneration !== messageScrollCommandGeneration[\s\S]*!pageActive[\s\S]*!messageListFollowingLatest/
  );
  assert.match(
    pageSource,
    /attempt % 4 === 3[\s\S]*MESSAGE_LIST_BOTTOM_SCROLL_TOP/
  );
  assert.match(
    pageSource,
    /onMessageListDragEnd\(\)[\s\S]*historyScrollReleasePending = true;[\s\S]*scheduleHistoryScrollEnd\(\)/
  );
  assert.match(
    pageSource,
    /scheduleHistoryScrollEnd\(\)[\s\S]*!historyScrollReleasePending[\s\S]*measureMessageListScrollTop\(\)[\s\S]*scrollEndGeneration !== historyScrollEndGeneration[\s\S]*historyScrollReleasePending = false;[\s\S]*historyScrollIntent\.consumeScrollEnd\([\s\S]*historyLoadGate\.armGesture\(true\)[\s\S]*tryLoadEarlierForGesture\(\)[\s\S]*HISTORY_SCROLL_END_DEBOUNCE_MS/
  );
  assert.doesNotMatch(
    pageSource.slice(
      pageSource.indexOf("  onScrollToUpper()"),
      pageSource.indexOf("  onHistoryTap()")
    ),
    /tryLoadEarlierForGesture|void this\.loadEarlierMessages/
  );
  assert.doesNotMatch(pageSource, /waitForMessageListMotionToSettle|HISTORY_MOTION/);
  assert.match(
    historyLoadSource,
    /metricsBefore[\s\S]*messages: views[\s\S]*metricsAfter[\s\S]*historyScrollTop/
  );
  assert.match(pageSource, /canLoadEarlierMessages\(\): boolean \{[\s\S]*historyLoadGate\.phase === "idle"/);
  assert.match(pageSource, /historyButtonSuppressed: false/);
  assert.match(template, /hasMoreHistory && !historyButtonSuppressed/);
  assert.match(template, /wx:elif="\{\{historyExhausted\}\}" class="history-loader__complete"/);
  assert.doesNotMatch(template, />正在加载更早记录</);
  assert.match(pageSource, /onHistoryTap\(\)[\s\S]*historyButtonSuppressed: true/);
  assert.doesNotMatch(pageSource, /MessageTouchEvent|messageListDragging|messageListTouch/);
  assert.match(pageSource, /HISTORY_SCROLL_RESTORE_STABLE_READS = 2/);
  assert.match(pageSource, /HISTORY_SCROLL_DRAIN_STABLE_READS = 4/);
  assert.match(
    historyLoadSource,
    /messages: views,[\s\S]*historyLoading: false[\s\S]*historyLoadGate\.finish\(\)[\s\S]*messageListScrollTop < HISTORY_PRELOAD_THRESHOLD[\s\S]*tryPreloadEarlierMessages\(\)/
  );
  assert.match(
    historyLoadSource,
    /userScrollDrained[\s\S]*waitForMessageListScrollEventsToDrain[\s\S]*historyStartScrollCommandGeneration !== messageScrollCommandGeneration[\s\S]*metricsBefore/
  );
  assert.match(
    pageSource,
    /beginMessageListLatestPositioning[\s\S]*finishMessageListLatestPositioning[\s\S]*resetMessageListLatestPositioning/
  );
  assert.match(pageSource, /consumeGesture[\s\S]*loadEarlierMessages/);
  assert.match(pageSource, /panelGeneration !== panelInteractionGeneration/);
  assert.match(sendImageSource, /appendLocallySentMessage/);
  assert.match(sendImageSource, /failedView\.sending = false/);
  assert.match(sendImageSource, /failedView\.sendFailed = true/);
  assert.match(sendImageSource, /retryPendingImage/);
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
  for (const icon of [
    "chat-add.svg",
    "chat-photo.svg",
    "chat-camera.svg",
    "chat-order.svg",
    "chat-product.svg",
    "chat-history.svg",
    "chat-favorite.svg",
    "chat-cart.svg",
    "chat-back.svg",
    "chat-error.svg"
  ]) {
    assert.match(template, new RegExp(`/assets/icons/${icon.replace(".", "\\.")}`));
    assert.match(
      readFileSync(resolve(sourceRoot, "assets/icons", icon), "utf8"),
      /<svg[\s\S]*fill="#[0-9a-fA-F]{6}"/
    );
  }
  const staticIconPaths = new Set(
    Array.from(
      template.matchAll(/src="(\/assets\/icons\/[^"{]+\.svg)"/g),
      (match) => match[1]
    )
  );
  staticIconPaths.forEach((iconPath) => {
    assert.match(
      readFileSync(resolve(sourceRoot, iconPath.slice(1)), "utf8"),
      /<svg[\s\S]*<\/svg>/
    );
  });
  assert.doesNotMatch(`${profileTemplate}\n${detailTemplate}`, /open-type="contact"/);
});
