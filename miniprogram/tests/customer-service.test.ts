import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { test } from "node:test";

import {
  buildCustomerServiceUrl,
  CustomerServiceHistoryLoadGate,
  customerServiceBottomScrollTop,
  customerServiceEntryContext,
  isCustomerServiceBottomScrollSettled,
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

test("客服最新位置使用真实最大滚动距离且拒绝越界空白", () => {
  assert.equal(customerServiceBottomScrollTop(2_000, 600), 1_400);
  assert.equal(customerServiceBottomScrollTop(400, 600), 0);
  assert.equal(customerServiceBottomScrollTop(2_000, 0), null);
  assert.equal(isCustomerServiceBottomScrollSettled(1_400, 1_400, 4), true);
  assert.equal(
    isCustomerServiceBottomScrollSettled(1_000_000_000, 1_400, 4),
    false
  );
  assert.equal(isCustomerServiceBottomScrollSettled(1_400, 1_700, 4), false);
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

test("客服历史恢复期间合并最新位置请求且支持取消", () => {
  const gate = new CustomerServiceHistoryLoadGate();

  assert.equal(gate.beginManualLoad(true), true);
  gate.deferLatestPosition();
  gate.deferLatestPosition();
  assert.equal(gate.takeDeferredLatestPosition(), false);
  gate.markRestoring();
  assert.equal(gate.takeDeferredLatestPosition(), false);
  gate.finish();
  assert.equal(gate.takeDeferredLatestPosition(), true);
  assert.equal(gate.takeDeferredLatestPosition(), false);

  assert.equal(gate.beginManualLoad(true), true);
  gate.deferLatestPosition();
  gate.cancelDeferredLatestPosition();
  gate.finish();
  assert.equal(gate.takeDeferredLatestPosition(), false);

  assert.equal(gate.beginManualLoad(true), true);
  gate.deferLatestPosition();
  gate.reset();
  assert.equal(gate.takeDeferredLatestPosition(), false);
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
  const messagePreloadStart = pageSource.indexOf("  async tryPreloadEarlierMessages()");
  const latestPositionStart = pageSource.indexOf(
    "  async positionMessageListAtLatest("
  );
  const latestPositionEnd = pageSource.indexOf(
    "  onAlbumTap()",
    latestPositionStart
  );
  const imageTemplateStart = template.indexOf("item.messageType === 'IMAGE'");
  const imageTemplateEnd = template.indexOf("item.messageType === 'ORDER_CARD'");
  const composerInputStyleStart = style.indexOf(".composer__input-shell {");
  const composerInputStyleEnd = style.indexOf(
    ".composer__placeholder",
    composerInputStyleStart
  );
  const composerInputTemplateStart = template.indexOf('class="composer__input-shell"');
  const composerPlusTemplateStart = template.indexOf('class="composer__plus ');
  const messageScrollTag = template.match(
    /<scroll-view[\s\S]*?class="message-scroll[^"]*"[\s\S]*?>/
  )?.[0] ?? "";
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
    latestPositionStart,
    latestPositionEnd,
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
  const latestPositionSource = pageSource.slice(
    latestPositionStart,
    latestPositionEnd
  );
  const historyAnchorEnableIndex = historyLoadSource.indexOf(
    "historyAnchoring: true"
  );
  const historyMetricsBeforeIndex = historyLoadSource.indexOf(
    "const metricsBefore"
  );
  const historyPrependIndex = historyLoadSource.indexOf("messages: views");
  const historyRestoreIndex = historyLoadSource.indexOf(
    "const historyPositionRestored"
  );
  const historyPresentationEndIndex = historyLoadSource.lastIndexOf(
    "historyLoading: false"
  );
  const historyAnchorDisableIndex = historyLoadSource.lastIndexOf(
    "historyAnchoring: false"
  );
  const historyFinishIndex = historyLoadSource.lastIndexOf(
    "this.finishHistoryLoad()"
  );
  for (const boundary of [
    historyAnchorEnableIndex,
    historyMetricsBeforeIndex,
    historyPrependIndex,
    historyRestoreIndex,
    historyPresentationEndIndex,
    historyAnchorDisableIndex,
    historyFinishIndex
  ]) {
    assert.notEqual(boundary, -1);
  }
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
  assert.match(template, /bindkeyboardheightchange="onKeyboardHeightChange"/);
  assert.match(template, /bindblur="onInputBlur"/);
  assert.doesNotMatch(template, /keyboard-spacer|keyboardHeight/);
  assert.match(pageSource, /WechatMiniprogram\.TextareaKeyboardHeightChange/);
  assert.match(
    pageSource,
    /height === lastKeyboardHeight[\s\S]*return;[\s\S]*wx\.nextTick[\s\S]*keyboardSettleTimer = setTimeout[\s\S]*positionLatestReliably/
  );
  assert.match(
    pageSource,
    /onKeyboardHeightChange\([\s\S]*!pageActive \|\| !inputFocused \|\| !keyboardAutoFollow[\s\S]*return;/
  );
  assert.match(
    pageSource,
    /onInputBlur\(\)[\s\S]*stopKeyboardAutoFollow\(\)[\s\S]*cancelMessageListLatestPositioning\(\)/
  );
  assert.match(
    pageSource,
    /onHide\(\)[\s\S]*historyLoading: false,[\s\S]*historyAnchoring: false/
  );
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
  assert.match(messageScrollTag, /\bscroll-y\b/);
  assert.match(messageScrollTag, /\benhanced\b/);
  assert.doesNotMatch(
    messageScrollTag,
    /disable-scroll|catch(?::)?touchmove|catchtouchstart/
  );
  assert.doesNotMatch(template, /enable-flex/);
  assert.match(template, /enable-passive="\{\{true\}\}"/);
  assert.doesNotMatch(template, /\sreverse(?:\s|=|>)/);
  assert.match(messageScrollTag, /scroll-anchoring="\{\{historyAnchoring\}\}"/);
  assert.match(
    messageScrollTag,
    /message-scroll--history-anchoring/
  );
  assert.doesNotMatch(template, /scroll-with-animation|scroll-top=|scroll-into-view/);
  assert.match(template, /upper-threshold="80"/);
  assert.doesNotMatch(
    style,
    /\.message-scroll \{[^}]*display:\s*flex;/
  );
  assert.match(style, /\.message-scroll \{[^}]*overflow-anchor:\s*none;/);
  assert.match(
    style,
    /\.message-scroll--history-anchoring \{[^}]*overflow-anchor:\s*auto;/
  );
  assert.match(style, /\.history-loader \{[^}]*overflow-anchor:\s*none;/);
  assert.match(style, /\.message-list__bottom \{[^}]*overflow-anchor:\s*none;/);
  assert.match(style, /\.message-row \{[^}]*overflow-anchor:\s*none;/);
  assert.match(
    style,
    /\.message-time,[\s\S]*\.system-message \{[^}]*overflow-anchor:\s*none;/
  );
  assert.match(
    style,
    /\.message-scroll--history-anchoring \.message-row,[\s\S]*\.message-scroll--history-anchoring \.system-message \{[^}]*overflow-anchor:\s*auto;/
  );
  assert.doesNotMatch(style, /flex-direction:\s*column-reverse/);
  assert.match(
    style,
    /\.message-list \{[\s\S]*display: flex;[\s\S]*min-height: 100%;[\s\S]*flex-direction: column;[\s\S]*justify-content: flex-end;/
  );
  assert.match(template, /binddragstart="onMessageListDragStart"/);
  assert.match(template, /binddragend="onMessageListDragEnd"/);
  assert.doesNotMatch(template, /bindtouch/);
  assert.match(
    template,
    /historyLoading \|\| hasMoreHistory \|\| historyExhausted[\s\S]*class="history-loader"/
  );
  assert.match(style, /\.history-loader \{[\s\S]*height: 64rpx/);
  assert.match(pageSource, /let messageListScrollTop = 0/);
  assert.doesNotMatch(pageSource, /scrollWithAnimation/);
  assert.match(pageSource, /WechatMiniprogram\.ScrollViewContext/);
  assert.match(pageSource, /select\("\.message-scroll"\)\.node\(\)/);
  assert.match(pageSource, /context\.scrollTo\(\{ top: target, animated \}\)/);
  assert.match(
    pageSource,
    /positionLatestWithoutAnimation\([\s\S]*positionMessageListAtLatest\(false, 2, isCurrent\)/
  );
  assert.match(pageSource, /const isInitialPositioning = !this\.data\.loaded/);
  assert.match(
    pageSource,
    /loaded: true[\s\S]*wx\.nextTick\(\(\) => \{[\s\S]*positionLatestReliably\(\)/
  );
  assert.match(
    historyLoadSource,
    /loadEarlierMessages\(\)[\s\S]*beforeId: firstMessage\.messageId[\s\S]*limit: HISTORY_PAGE_SIZE/
  );
  assert.match(
    historyLoadSource,
    /this\.setData\(\{ historyLoading: true \}\)[\s\S]*getCustomerServiceMessages\([\s\S]*waitForHistoryMotionToSettle\(requestIsStale\)[\s\S]*measureHistoryEdgeVisible\(\)[\s\S]*historyAnchoring: true[\s\S]*wx\.nextTick\(resolve\)[\s\S]*measureHistoryEdgeVisible\(\)[\s\S]*const metricsBefore/
  );
  assert.match(
    historyLoadSource,
    /const historyEdgeVisibleBeforeAnchoring =[\s\S]*await this\.measureHistoryEdgeVisible\(\);[\s\S]*requestIsStale\(\)[\s\S]*!historyEdgeVisibleBeforeAnchoring[\s\S]*await new Promise<void>\(\(resolve\) => \{[\s\S]*historyAnchoring: true[\s\S]*\(\) => wx\.nextTick\(resolve\)[\s\S]*\}\);[\s\S]*const historyEdgeVisibleBeforePrepend =[\s\S]*await this\.measureHistoryEdgeVisible\(\);[\s\S]*requestIsStale\(\)[\s\S]*!historyEdgeVisibleBeforePrepend/
  );
  assert.match(
    historyLoadSource,
    /waitForHistoryMotionToSettle\(requestIsStale\)[\s\S]*measureHistoryEdgeVisible\(\)[\s\S]*const metricsBefore/
  );
  assert.match(historyLoadSource, /historyLoadGate\.phase !== "loading"/);
  assert.match(pageSource, /measureHistoryScrollMetrics\(messageId: number\)/);
  assert.match(pageSource, /restoreHistoryScrollPosition\(/);
  assert.match(pageSource, /preserveCustomerServiceHistoryScrollTop\(/);
  assert.match(
    historyLoadSource.slice(historyPrependIndex, historyRestoreIndex),
    /messages: views,[\s\S]*historyExhausted: olderMessages\.length < HISTORY_PAGE_SIZE[\s\S]*},[\s\S]*resolve[\s\S]*\);/
  );
  assert.match(
    historyLoadSource,
    /historyLoadGate\.markRestoring\(\);[\s\S]*await new Promise<void>\(\(resolve\) => \{[\s\S]*messages: views,[\s\S]*},[\s\S]*resolve[\s\S]*\);[\s\S]*\}\);[\s\S]*const historyPositionRestored = await this\.restoreHistoryScrollPosition\(/
  );
  assert.ok(historyAnchorEnableIndex < historyMetricsBeforeIndex);
  assert.ok(historyMetricsBeforeIndex < historyPrependIndex);
  assert.ok(historyPrependIndex < historyRestoreIndex);
  assert.ok(historyRestoreIndex < historyPresentationEndIndex);
  assert.ok(historyPresentationEndIndex < historyAnchorDisableIndex);
  assert.ok(historyRestoreIndex < historyAnchorDisableIndex);
  assert.ok(historyAnchorDisableIndex < historyFinishIndex);
  assert.doesNotMatch(
    historyLoadSource.slice(historyPrependIndex, historyRestoreIndex),
    /wx\.nextTick/
  );
  assert.match(
    historyLoadSource.slice(historyAnchorEnableIndex, historyMetricsBeforeIndex),
    /historyAnchoring: true[\s\S]*\(\) => wx\.nextTick\(resolve\)/
  );
  assert.doesNotMatch(
    historyLoadSource.slice(historyAnchorEnableIndex, historyRestoreIndex),
    /historyAnchoring: false|historyLoading: false|finishHistoryLoad\(\)/
  );
  assert.doesNotMatch(
    historyLoadSource.slice(historyRestoreIndex, historyAnchorDisableIndex),
    /finishHistoryLoad\(\)/
  );
  assert.match(
    historyLoadSource.slice(historyPresentationEndIndex, historyFinishIndex),
    /historyLoading: false,[\s\S]*historyAnchoring: false,[\s\S]*historyButtonSuppressed: false[\s\S]*wx\.nextTick\(resolve\)/
  );
  assert.match(
    historyLoadSource,
    /finally \{[\s\S]*await new Promise<void>\(\(resolve\) => \{[\s\S]*historyLoading: false,[\s\S]*historyAnchoring: false,[\s\S]*\(\) => wx\.nextTick\(resolve\)[\s\S]*\}\);[\s\S]*historyRequestGeneration === historyLoadGeneration[\s\S]*this\.finishHistoryLoad\(\)/
  );
  assert.equal(
    historyLoadSource.match(/historyAnchoring: false/g)?.length,
    1
  );
  assert.equal(
    historyLoadSource.match(/historyAnchoring: true/g)?.length,
    1
  );
  assert.match(
    historyLoadSource,
    /cancelMessageListLatestPositioning\(\)[\s\S]*getCustomerServiceMessages\(/
  );
  assert.match(
    pageSource,
    /function cancelMessageListLatestPositioning\(\)[\s\S]*pendingMessageListLatestPosition = null;[\s\S]*historyLoadGate\.cancelDeferredLatestPosition\(\)/
  );
  assert.match(
    pageSource,
    /finishHistoryLoad\(\) \{[\s\S]*historyLoadGate\.finish\(\);[\s\S]*flushPendingMessageListLatestPosition\(\)/
  );
  assert.match(
    pageSource,
    /flushPendingMessageListLatestPosition\(\)[\s\S]*takeDeferredLatestPosition\(\)[\s\S]*pendingMessageListLatestPosition = null;[\s\S]*positionLatestReliably\(pendingRequest\.isCurrent\)/
  );
  assert.match(
    historyLoadSource,
    /const historyPositionRestored = await this\.restoreHistoryScrollPosition\([\s\S]*if \(!historyPositionRestored\)[\s\S]*throw new Error/
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
  assert.match(messageScrollSource, /const previousScrollTop = messageListScrollTop/);
  assert.match(
    messageScrollSource,
    /nextScrollTop < previousScrollTop - HISTORY_SCROLL_DIRECTION_TOLERANCE_PX[\s\S]*messageListFollowingLatest = false/
  );
  assert.match(
    messageScrollSource,
    /nextScrollTop < previousScrollTop - HISTORY_SCROLL_DIRECTION_TOLERANCE_PX[\s\S]*historyGestureTowardUpper = true[\s\S]*nextScrollTop > previousScrollTop \+ HISTORY_SCROLL_DIRECTION_TOLERANCE_PX[\s\S]*historyGestureTowardUpper = false/
  );
  assert.match(
    messageScrollSource,
    /nextScrollTop <= HISTORY_PRELOAD_SCROLL_TOP[\s\S]*this\.tryPreloadEarlierMessages\(\)/
  );
  assert.match(pageSource, /onScrollToLower\(\)[\s\S]*messageListFollowingLatest = true/);
  assert.doesNotMatch(messageScrollSource, /void this\.loadEarlierMessages/);
  assert.match(
    pageSource,
    /onMessageListDragStart\(\)[\s\S]*cancelMessageListLatestPositioning\(\)[\s\S]*historyLoadGate\.armGesture\(this\.canLoadEarlierMessages\(\)\)/
  );
  assert.match(
    pageSource,
    /onMessageListDragEnd\(\)[\s\S]*historyGestureCancelTimer = setTimeout[\s\S]*!historyGestureActive && historyLoadGate\.phase === "idle"[\s\S]*historyLoadGate\.cancelGesture\(\)/
  );
  assert.match(
    messagePreloadSource,
    /tryPreloadEarlierMessages\(\)[\s\S]*!historyGestureTowardUpper[\s\S]*measureHistoryEdgeVisible\(\)[\s\S]*!historyGestureTowardUpper[\s\S]*historyLoadGate\.consumeGesture\(this\.canLoadEarlierMessages\(\)\)[\s\S]*this\.loadEarlierMessages\(\)/
  );
  assert.match(
    onScrollToUpperSource,
    /onScrollToUpper\(\)[\s\S]*messageListPositioningLatest[\s\S]*return;[\s\S]*historyGestureActive[\s\S]*historyGestureTowardUpper = true[\s\S]*this\.tryPreloadEarlierMessages\(\)/
  );
  assert.match(
    pageSource,
    /wx\.nextTick\(\(\) => \{\s*void this\.positionLatestReliably\(\);\s*\}\)/
  );
  assert.match(pageSource, /positionLatestReliably\([\s\S]*Promise<boolean>/);
  assert.match(
    pageSource,
    /positionLatestReliably\([\s\S]*MESSAGE_LATEST_RETRY_ATTEMPTS/
  );
  assert.match(
    pageSource,
    /measureMessageListScrollMetrics\(\)[\s\S]*customerServiceBottomScrollTop\([\s\S]*measuredScrollHeight,[\s\S]*measuredViewportHeight/
  );
  assert.match(
    latestPositionSource,
    /historyLoadGate\.phase !== "idle" \|\| this\.data\.historyLoading[\s\S]*deferMessageListLatestPosition\(externalIsCurrent\)[\s\S]*return false;[\s\S]*getMessageScrollContext\(\)[\s\S]*metricsBefore = await this\.measureMessageListScrollMetrics\(\)[\s\S]*scrollMessageListTo\([\s\S]*metricsBefore\.targetScrollTop,[\s\S]*metricsAfter = await this\.measureMessageListScrollMetrics\(\)/
  );
  assert.match(
    latestPositionSource,
    /positionIsCurrent = \(\) => \([\s\S]*historyLoadGate\.phase === "idle"[\s\S]*!this\.data\.historyLoading/
  );
  assert.doesNotMatch(
    pageSource,
    /MESSAGE_LIST_BOTTOM_SCROLL_TOP|1_000_000_000/
  );
  assert.match(
    pageSource,
    /scrollMessageListTo\([\s\S]*isCurrent[\s\S]*!isCurrent\(\)[\s\S]*context\.scrollTo\(\{ top: target, animated \}\)/
  );
  assert.match(
    pageSource,
    /requiredStableReads = 2[\s\S]*positionSettled = isCustomerServiceBottomScrollSettled\([\s\S]*layoutSettled = isCustomerServiceBottomScrollSettled\([\s\S]*stableReadCount = atLatest \? stableReadCount \+ 1 : 0/
  );
  assert.match(pageSource, /canLoadEarlierMessages\(\): boolean \{[\s\S]*historyLoadGate\.phase === "idle"/);
  assert.match(pageSource, /historyButtonSuppressed: false/);
  assert.match(template, /hasMoreHistory && !historyButtonSuppressed/);
  assert.match(template, /wx:elif="\{\{historyExhausted\}\}" class="history-loader__complete"/);
  assert.doesNotMatch(template, />正在加载更早记录</);
  assert.match(pageSource, /onHistoryTap\(\)[\s\S]*historyButtonSuppressed: true/);
  assert.doesNotMatch(pageSource, /MessageTouchEvent|messageListTouch/);
  assert.match(
    messageScrollSource,
    /!historyGestureActive && historyLoadGate\.phase === "idle"/
  );
  assert.match(
    pageSource,
    /onMessageListDragStart\(\)[\s\S]*historyGestureActive = true[\s\S]*onMessageListDragEnd\(\)[\s\S]*historyGestureActive = false/
  );
  assert.match(
    pageSource,
    /waitForHistoryMotionToSettle\([\s\S]*HISTORY_MOTION_STABLE_READS[\s\S]*return true;[\s\S]*return false;/
  );
  assert.match(pageSource, /HISTORY_RESTORE_STABLE_READS = 2/);
  assert.doesNotMatch(pageSource, /maxScrollTop - measuredScrollTop <= tolerance/);
  assert.match(pageSource, /scrollToLatest\(\)[\s\S]*positionMessageListAtLatest\(true, 3\)/);
  assert.doesNotMatch(historyLoadSource, /tryPreloadEarlierMessages\(\)/);
  assert.doesNotMatch(historyLoadSource, /scrollToLatest\(\)/);
  assert.match(pageSource, /cancelMessageListLatestPositioning/);
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
