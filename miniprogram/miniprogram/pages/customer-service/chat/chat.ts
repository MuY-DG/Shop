import {
  buildCustomerServiceUrl,
  CustomerServiceHistoryLoadGate,
  customerServiceBottomScrollTop,
  customerServiceEntryContext,
  customerServiceMessageId,
  customerServiceOrderStatusText,
  customerServicePriceRange,
  formatCustomerServiceMoney,
  isCustomerServiceBottomScrollSettled,
  parseCustomerServiceDate,
  preserveCustomerServiceHistoryScrollTop,
  shouldShowCustomerServiceMessageTime,
  shouldShowCustomerServiceCommonQuestions,
  type CustomerServiceEntryContext
} from "../../../features/customer-service";
import { buildOrderDetailUrl } from "../../../features/order-center";
import { getCartItems } from "../../../services/cart";
import {
  downloadCustomerServiceImage,
  downloadCustomerServiceOriginalImage,
  getCustomerServiceCommonQuestions,
  getCustomerServiceConversation,
  getCustomerServiceMessages,
  getCustomerServiceOrderCandidates,
  openCustomerServiceConversation,
  sendCustomerServiceMessage,
  sendCustomerServiceOrder,
  sendCustomerServiceProduct,
  uploadCustomerServiceImage
} from "../../../services/customer-service";
import {
  subscribeCustomerServiceRealtime,
  subscribeCustomerServiceRealtimeState
} from "../../../services/customer-service-realtime";
import {
  getBrowseHistory,
  getFavorites
} from "../../../services/product-preference";
import { getSessionState } from "../../../services/session";
import type {
  CustomerServiceConversation,
  CustomerServiceCommonQuestion,
  CustomerServiceMessage,
  CustomerServiceOrder
} from "../../../types/customer-service";
import { isApiError } from "../../../utils/api-error";
import { openLoginPage } from "../../../utils/login-navigation";

interface ChatPageOptions {
  contextType?: string;
  contextId?: string;
}

interface InputEvent {
  detail: {
    value: string;
  };
}

interface ScrollEvent {
  detail: {
    scrollTop: number;
    scrollHeight?: number;
  };
}

interface HistoryScrollMetrics {
  scrollTop: number | null;
  scrollHeight: number | null;
  viewportHeight: number | null;
  anchorOffset: number | null;
}

interface MessageListScrollMetrics {
  scrollTop: number;
  scrollHeight: number;
  viewportHeight: number;
  targetScrollTop: number;
  latestAnchorVisible: boolean;
}

interface PendingMessageListLatestPosition {
  isCurrent: () => boolean;
}

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
      question?: string;
      source?: "browse" | "favorite" | "cart";
    };
  };
}

interface MessageView {
  messageId: number;
  renderKey: string;
  consultationNo: number;
  createdAt: string;
  senderType: string;
  isMine: boolean;
  isSystem: boolean;
  senderName: string;
  senderAvatar: string;
  messageType: string;
  content: string;
  showTime: boolean;
  timeText: string;
  imageUrl: string;
  imageStyle: string;
  imageFailed: boolean;
  sending: boolean;
  sendFailed: boolean;
  orderId: number;
  orderNo: string;
  orderTitle: string;
  orderImage: string;
  orderStatusText: string;
  orderAmountText: string;
  orderItemText: string;
  productId: number;
  productTitle: string;
  productImage: string;
  productPriceText: string;
  viewKey: string;
}

interface TimedMessage {
  consultationNo: number;
  createdAt: string;
}

interface CandidateView {
  id: number;
  title: string;
  subtitle: string;
  imageUrl: string;
  hasImage: boolean;
  priceText: string;
  metaText: string;
  disabled: boolean;
}

type PanelMode = "" | "main" | "product";
type PickerKind = "" | "order" | "product";
type ProductSource = "browse" | "favorite" | "cart";

const FALLBACK_POLL_INTERVAL_MS = 15_000;
const DEFAULT_USER_AVATAR = "/assets/images/profile-default-avatar.png";
const DEFAULT_CUSTOMER_SERVICE_AVATAR =
  "/assets/images/customer-service-default-avatar.jpg";
const HISTORY_PAGE_SIZE = 30;
const MESSAGE_SCROLL_CONTEXT_ATTEMPTS = 6;
const MESSAGE_LATEST_RETRY_ATTEMPTS = 10;
const MESSAGE_LATEST_RETRY_INTERVAL_MS = 80;
const MESSAGE_ANIMATED_SCROLL_SETTLE_MS = 280;
const MESSAGE_LATEST_TOLERANCE_PX = 4;
const HISTORY_GESTURE_CANCEL_DELAY_MS = 500;
const HISTORY_PRELOAD_SCROLL_TOP = 80;
const HISTORY_SCROLL_DIRECTION_TOLERANCE_PX = 2;
const HISTORY_MOTION_SETTLE_ATTEMPTS = 64;
const HISTORY_MOTION_SETTLE_INTERVAL_MS = 32;
const HISTORY_MOTION_STABLE_READS = 3;
const HISTORY_RESTORE_ATTEMPTS = 24;
const HISTORY_RESTORE_INTERVAL_MS = 32;
const HISTORY_RESTORE_STABLE_READS = 2;
const HISTORY_RESTORE_TOLERANCE_PX = 3;
const KEYBOARD_MINIMUM_SETTLE_DELAY_MS = 320;
const KEYBOARD_MAXIMUM_SETTLE_DELAY_MS = 600;
const imageTempPaths = new Map<number, string>();
const originalImageTempPaths = new Map<number, string>();
const imageMessages = new Map<number, CustomerServiceMessage>();
const imageDownloads = new Set<number>();
const pendingImageViews = new Map<number, MessageView>();
const pendingImageRequests = new Set<number>();
const pendingTextMessages = new Map<number, CustomerServiceMessage>();
const pendingTextViews = new Map<number, MessageView>();
const pendingTextRequests = new Set<number>();
const commonQuestionMessageIds = new Set<number>();
const locallyHandledMessageIds = new Map<number, number>();
const pendingRealtimeMessageIds = new Set<number>();
const messageTimeVisibilityById = new Map<number, boolean>();
const messageTimeVisibilityByClientId = new Map<string, boolean>();
const messageRenderKeyById = new Map<number, string>();

let pageActive = false;
let initialized = false;
let initializeGeneration = 0;
let historyLoadGeneration = 0;
let refreshRunning = false;
let refreshQueued = false;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let unsubscribeRealtime: (() => void) | null = null;
let unsubscribeRealtimeState: (() => void) | null = null;
let imageObserver: WechatMiniprogram.IntersectionObserver | null = null;
let entryContext: CustomerServiceEntryContext = { contextType: "GENERAL" };
let nextPendingMessageId = -1;
let localMutationCount = 0;
let conversationMutationEpoch = 0;
let currentConsultationNo = 0;
let commonQuestionQueued = false;
let commonQuestionEngaged = false;
let pendingRealtimeChangeWithoutMessage = false;
let choosingMedia = false;
let panelInteractionGeneration = 0;
let pickerRequestGeneration = 0;
let latestPersistedMessageId = 0;
let messageListScrollTop = 0;
let messageListScrollEventSequence = 0;
let messageListFollowingLatest = true;
let messageListPositioningLatest = false;
let messageListPositionGeneration = 0;
let pendingMessageListLatestPosition: PendingMessageListLatestPosition | null = null;
let messageScrollContext: WechatMiniprogram.ScrollViewContext | null = null;
let historyGestureActive = false;
let historyGestureTowardUpper = false;
let historyGestureCancelTimer: ReturnType<typeof setTimeout> | null = null;
let keyboardSettleTimer: ReturnType<typeof setTimeout> | null = null;
let keyboardPositionGeneration = 0;
let lastKeyboardHeight = 0;
let lastKeyboardSettleDelay = 0;
let inputFocused = false;
let keyboardAutoFollow = false;
let conversationSignatureValue = "";
let imageObservationSignature = "";
const historyLoadGate = new CustomerServiceHistoryLoadGate();

function cleanText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function positiveId(value: unknown): number {
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : 0;
}

function errorMessage(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function waitForMilliseconds(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function waitForNextRender(): Promise<void> {
  return new Promise((resolve) => wx.nextTick(resolve));
}

function clearHistoryGestureCancelTimer(): void {
  if (historyGestureCancelTimer) {
    clearTimeout(historyGestureCancelTimer);
  }
  historyGestureCancelTimer = null;
}

function clearKeyboardSettleTimer(): void {
  if (keyboardSettleTimer) {
    clearTimeout(keyboardSettleTimer);
  }
  keyboardSettleTimer = null;
}

function cancelMessageListLatestPositioning(): void {
  messageListPositionGeneration += 1;
  messageListPositioningLatest = false;
  pendingMessageListLatestPosition = null;
  historyLoadGate.cancelDeferredLatestPosition();
}

function resetKeyboardPositioning(): void {
  keyboardPositionGeneration += 1;
  lastKeyboardHeight = 0;
  lastKeyboardSettleDelay = 0;
  clearKeyboardSettleTimer();
}

function stopKeyboardAutoFollow(): void {
  keyboardAutoFollow = false;
  resetKeyboardPositioning();
}

function messageTimeText(value: string): string {
  const date = parseCustomerServiceDate(value);
  if (!date) {
    return "";
  }
  const now = new Date();
  const time = `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
  if (
    now.getFullYear() === date.getFullYear() &&
    now.getMonth() === date.getMonth() &&
    now.getDate() === date.getDate()
  ) {
    return time;
  }
  return `${date.getMonth() + 1}月${date.getDate()}日 ${time}`;
}

function imageStyle(message: CustomerServiceMessage): string {
  const sourceWidth = message.image?.width ?? 4;
  const sourceHeight = message.image?.height ?? 3;
  const ratio = sourceWidth > 0 && sourceHeight > 0 ? sourceHeight / sourceWidth : 0.75;
  const width = 420;
  const height = Math.round(Math.min(520, Math.max(180, width * ratio)));
  return `width:${width}rpx;height:${height}rpx;`;
}

function rememberMessageTimeVisibility(
  message: CustomerServiceMessage,
  showTime: boolean
): void {
  messageTimeVisibilityById.set(message.messageId, showTime);
  const clientMessageId = cleanText(message.clientMessageId);
  if (clientMessageId) {
    messageTimeVisibilityByClientId.set(clientMessageId, showTime);
  }
}

function stableMessageTimeVisibility(
  message: CustomerServiceMessage,
  previous?: TimedMessage
): boolean {
  const clientMessageId = cleanText(message.clientMessageId);
  const persistedVisibility = messageTimeVisibilityById.get(message.messageId);
  const clientVisibility = clientMessageId
    ? messageTimeVisibilityByClientId.get(clientMessageId)
    : undefined;
  const showTime = persistedVisibility ?? clientVisibility ??
    shouldShowCustomerServiceMessageTime(message, previous);
  rememberMessageTimeVisibility(message, showTime);
  return showTime;
}

function messageViewKey(
  message: CustomerServiceMessage,
  senderName: string,
  senderAvatar: string,
  imageUrl: string
): string {
  const order = message.order;
  const product = message.product;
  const image = message.image;
  return JSON.stringify([
    message.messageId,
    message.consultationNo,
    message.senderType,
    senderName,
    senderAvatar,
    message.messageType,
    cleanText(message.content),
    message.createdAt,
    imageUrl,
    image?.width ?? 0,
    image?.height ?? 0,
    image?.thumbnailStatus ?? "",
    order?.orderId ?? 0,
    order?.status ?? "",
    order?.payableAmountCent ?? 0,
    order?.itemCount ?? 0,
    cleanText(order?.orderNo),
    cleanText(order?.primaryProductTitle),
    cleanText(order?.primaryProductImage),
    product?.productId ?? 0,
    product?.status ?? "",
    cleanText(product?.title),
    cleanText(product?.image),
    product?.minPriceCent ?? 0,
    product?.maxPriceCent ?? 0,
    cleanText(message.clientMessageId)
  ]);
}

function conversationSignature(
  conversation: CustomerServiceConversation
): string {
  const rawMessages = Array.isArray(conversation.messages)
    ? conversation.messages
    : [];
  const lastMessage = rawMessages[rawMessages.length - 1];
  const context = conversation.currentContext;
  const currentUser = getSessionState().user;
  const cachedImages = rawMessages
    .map((message) => imageTempPaths.get(message.messageId) ?? "")
    .join("|");
  return [
    conversation.conversationId,
    conversation.status,
    conversation.consultationNo,
    conversation.updatedAt,
    rawMessages.length,
    lastMessage?.messageId ?? 0,
    lastMessage?.createdAt ?? "",
    context?.type ?? "",
    context?.resourceId ?? 0,
    cleanText(currentUser?.nickname),
    cleanText(currentUser?.avatarUrl),
    JSON.stringify(context),
    JSON.stringify(rawMessages),
    cachedImages
  ].join("::");
}

function messageViews(
  messages: CustomerServiceMessage[],
  previousMessage?: TimedMessage,
  existingViews?: Map<number, MessageView>
): MessageView[] {
  const currentUser = getSessionState().user;
  const currentUserName = cleanText(currentUser?.nickname);
  const currentUserAvatar = cleanText(currentUser?.avatarUrl);
  return messages.map((message, index) => {
    const avatar = cleanText(message.senderAvatar);
    const clientMessageId = cleanText(message.clientMessageId);
    const isMine = message.senderType === "APP_USER";
    const senderName = isMine
      ? currentUserName || cleanText(message.senderName) || "我"
      : cleanText(message.senderName) || "在线客服";
    const senderAvatar = isMine
      ? currentUserAvatar || DEFAULT_USER_AVATAR
      : avatar || DEFAULT_CUSTOMER_SERVICE_AVATAR;
    const renderKey = messageRenderKeyById.get(message.messageId) ??
      (clientMessageId
        ? `client-${clientMessageId}`
        : `message-${message.messageId}`);
    messageRenderKeyById.set(message.messageId, renderKey);
    const order = message.order;
    const product = message.product;
    const cachedImage = imageTempPaths.get(message.messageId) ?? "";
    const showTime = stableMessageTimeVisibility(
      message,
      messages[index - 1] ?? previousMessage
    );
    const view: MessageView = {
      messageId: message.messageId,
      renderKey,
      consultationNo: message.consultationNo,
      createdAt: message.createdAt,
      senderType: message.senderType,
      isMine,
      isSystem: message.messageType === "SYSTEM",
      senderName,
      senderAvatar,
      messageType: message.messageType,
      content: cleanText(message.content),
      showTime,
      timeText: messageTimeText(message.createdAt),
      imageUrl: cachedImage,
      imageStyle: imageStyle(message),
      imageFailed: false,
      sending: false,
      sendFailed: false,
      orderId: order?.orderId ?? 0,
      orderNo: cleanText(order?.orderNo),
      orderTitle: cleanText(order?.primaryProductTitle) || "商品订单",
      orderImage: cleanText(order?.primaryProductImage),
      orderStatusText: customerServiceOrderStatusText(order?.status),
      orderAmountText: formatCustomerServiceMoney(order?.payableAmountCent),
      orderItemText: order ? `共 ${order.itemCount || 1} 件商品` : "",
      productId: product?.productId ?? 0,
      productTitle: cleanText(product?.title) || "商品",
      productImage: cleanText(product?.image),
      productPriceText: customerServicePriceRange(
        product?.minPriceCent,
        product?.maxPriceCent
      ),
      viewKey: messageViewKey(
        message,
        senderName,
        senderAvatar,
        cachedImage
      )
    };
    const existingView = existingViews?.get(message.messageId);
    if (
      existingView &&
      existingView.viewKey === view.viewKey &&
      existingView.imageUrl === view.imageUrl &&
      existingView.showTime === showTime
    ) {
      return existingView;
    }
    return view;
  });
}

function commonQuestionAnchorMessageId(
  messages: MessageView[],
  consultationNo: number
): number {
  const currentMessages = messages.filter(
    (message) => message.consultationNo === consultationNo
  );
  const firstUserMessageIndex = currentMessages.findIndex((message) => message.isMine);
  const openingMessage = currentMessages.find((message, index) => (
    message.senderType === "BOT" &&
    message.messageType === "AUTO_REPLY" &&
    (firstUserMessageIndex < 0 || index < firstUserMessageIndex)
  ));
  return openingMessage?.messageId ?? 0;
}

function hasAskedCommonQuestion(
  questions: CustomerServiceCommonQuestion[],
  messages: MessageView[],
  consultationNo: number
): boolean {
  const questionTexts = new Set(questions.map((question) => cleanText(question.question)));
  return messages.some((message) => (
    message.isMine &&
    message.consultationNo === consultationNo &&
    message.messageType === "TEXT" &&
    questionTexts.has(message.content)
  ));
}

function stopLiveUpdates(): void {
  unsubscribeRealtimeState?.();
  unsubscribeRealtimeState = null;
  unsubscribeRealtime?.();
  unsubscribeRealtime = null;
  if (pollTimer) {
    clearInterval(pollTimer);
  }
  pollTimer = null;
}

function chooseChatImages(
  sourceType: "album" | "camera",
  count: number
): Promise<string[]> {
  return new Promise((resolve, reject) => {
    wx.chooseMedia({
      count,
      mediaType: ["image"],
      sourceType: [sourceType],
      // 只关闭微信选择器的“原图”选项，不额外调用本地图片压缩 API。
      sizeType: ["compressed"],
      success: (result) => resolve(
        result.tempFiles
          .map((file) => cleanText(file.tempFilePath))
          .filter(Boolean)
      ),
      fail: (cause) => {
        if (cause.errMsg.toLowerCase().includes("cancel")) {
          resolve([]);
          return;
        }
        reject(new Error(sourceType === "camera" ? "暂时无法使用相机" : "暂时无法读取相册"));
      }
    });
  });
}

function orderCandidateViews(orders: CustomerServiceOrder[]): CandidateView[] {
  return orders.map((order) => ({
    id: order.orderId,
    title: cleanText(order.primaryProductTitle) || "商品订单",
    subtitle: `订单号 ${cleanText(order.orderNo)}`,
    imageUrl: cleanText(order.primaryProductImage),
    hasImage: Boolean(cleanText(order.primaryProductImage)),
    priceText: formatCustomerServiceMoney(order.payableAmountCent),
    metaText: `${customerServiceOrderStatusText(order.status)} · 共 ${order.itemCount || 1} 件`,
    disabled: false
  }));
}

Page({
  data: {
    loading: true,
    loaded: false,
    errorText: "",
    conversationId: 0,
    conversationStatus: "DRAFT",
    contextPreview: "",
    messages: [] as MessageView[],
    commonQuestions: [] as CustomerServiceCommonQuestion[],
    showCommonQuestions: false,
    commonQuestionAnchorMessageId: 0,
    commonQuestionSending: false,
    historyLoading: false,
    historyAnchoring: false,
    hasMoreHistory: false,
    historyExhausted: false,
    historyButtonSuppressed: false,
    inputValue: "",
    uploading: false,
    panelMode: "" as PanelMode,
    pickerOpen: false,
    pickerKind: "" as PickerKind,
    pickerTitle: "",
    pickerLoading: false,
    pickerErrorText: "",
    pickerProductSource: "" as "" | ProductSource,
    candidates: [] as CandidateView[],
    candidateSendingId: 0
  },

  onLoad(options: ChatPageOptions) {
    pageActive = true;
    initialized = false;
    refreshRunning = false;
    refreshQueued = false;
    historyLoadGeneration += 1;
    historyLoadGate.reset();
    clearHistoryGestureCancelTimer();
    cancelMessageListLatestPositioning();
    inputFocused = false;
    stopKeyboardAutoFollow();
    conversationSignatureValue = "";
    imageObservationSignature = "";
    messageScrollContext = null;
    entryContext = customerServiceEntryContext(options.contextType, options.contextId);
    initializeGeneration += 1;
    nextPendingMessageId = -1;
    localMutationCount = 0;
    conversationMutationEpoch = 0;
    currentConsultationNo = 0;
    commonQuestionQueued = false;
    commonQuestionEngaged = false;
    pendingRealtimeChangeWithoutMessage = false;
    imageTempPaths.clear();
    originalImageTempPaths.clear();
    imageMessages.clear();
    imageDownloads.clear();
    pendingImageViews.clear();
    pendingImageRequests.clear();
    pendingTextMessages.clear();
    pendingTextViews.clear();
    pendingTextRequests.clear();
    commonQuestionMessageIds.clear();
    locallyHandledMessageIds.clear();
    pendingRealtimeMessageIds.clear();
    messageTimeVisibilityById.clear();
    messageTimeVisibilityByClientId.clear();
    messageRenderKeyById.clear();
    choosingMedia = false;
    latestPersistedMessageId = 0;
    messageListScrollTop = 0;
    messageListScrollEventSequence = 0;
    messageListFollowingLatest = true;
    historyGestureActive = false;
    historyGestureTowardUpper = false;
    panelInteractionGeneration += 1;
    pickerRequestGeneration += 1;
  },

  onShow() {
    pageActive = true;
    const session = getSessionState();
    if (!session.user || (!session.accessToken && !session.refreshToken)) {
      this.setData({ loading: false, loaded: false });
      openLoginPage(buildCustomerServiceUrl(entryContext.contextType, entryContext.contextId));
      return;
    }
    if (!initialized) {
      void this.initialize();
      return;
    }
    this.startLiveUpdates();
    void this.refreshConversation(true).then(() => {
      // 返回页面时布局可能已经变化，仍在跟随最新消息就重新确认一次位置。
      if (pageActive && messageListFollowingLatest && this.data.messages.length) {
        void this.positionLatestReliably();
      }
    });
  },

  onHide() {
    pageActive = false;
    historyLoadGeneration += 1;
    historyLoadGate.reset();
    clearHistoryGestureCancelTimer();
    historyGestureActive = false;
    historyGestureTowardUpper = false;
    cancelMessageListLatestPositioning();
    inputFocused = false;
    stopKeyboardAutoFollow();
    panelInteractionGeneration += 1;
    stopLiveUpdates();
    this.setData({
      historyLoading: false,
      historyAnchoring: false,
      historyButtonSuppressed: false
    });
  },

  onUnload() {
    pageActive = false;
    initialized = false;
    initializeGeneration += 1;
    historyLoadGeneration += 1;
    historyLoadGate.reset();
    clearHistoryGestureCancelTimer();
    historyGestureActive = false;
    historyGestureTowardUpper = false;
    cancelMessageListLatestPositioning();
    inputFocused = false;
    stopKeyboardAutoFollow();
    conversationSignatureValue = "";
    imageObservationSignature = "";
    messageScrollContext = null;
    stopLiveUpdates();
    imageObserver?.disconnect();
    imageObserver = null;
    imageTempPaths.clear();
    originalImageTempPaths.clear();
    imageMessages.clear();
    imageDownloads.clear();
    pendingImageViews.clear();
    pendingImageRequests.clear();
    pendingTextMessages.clear();
    pendingTextViews.clear();
    pendingTextRequests.clear();
    commonQuestionMessageIds.clear();
    locallyHandledMessageIds.clear();
    pendingRealtimeMessageIds.clear();
    messageTimeVisibilityById.clear();
    messageTimeVisibilityByClientId.clear();
    messageRenderKeyById.clear();
    localMutationCount = 0;
    currentConsultationNo = 0;
    commonQuestionQueued = false;
    commonQuestionEngaged = false;
    pendingRealtimeChangeWithoutMessage = false;
    choosingMedia = false;
    latestPersistedMessageId = 0;
    messageListScrollTop = 0;
    messageListScrollEventSequence = 0;
    messageListFollowingLatest = true;
    panelInteractionGeneration += 1;
    pickerRequestGeneration += 1;
  },

  async initialize() {
    const generation = ++initializeGeneration;
    this.setData({ loading: true, errorText: "" });
    try {
      const conversation = await openCustomerServiceConversation({
        contextType: entryContext.contextType,
        contextId: entryContext.contextId
      });
      if (!pageActive || generation !== initializeGeneration) {
        return;
      }
      initialized = true;
      this.applyConversation(conversation);
      this.setData(
        { loading: false, loaded: true, errorText: "" },
        () => {
          if (this.data.messages.length) {
            wx.nextTick(() => {
              void this.positionLatestReliably();
            });
          }
        }
      );
      this.startLiveUpdates();
      void this.loadCommonQuestions(generation);
    } catch (error) {
      if (pageActive && generation === initializeGeneration) {
        this.setData({
          loading: false,
          loaded: false,
          errorText: errorMessage(error, "客服会话加载失败，请稍后重试")
        });
      }
    }
  },

  async loadCommonQuestions(generation: number) {
    try {
      const commonQuestions = await getCustomerServiceCommonQuestions();
      if (generation !== initializeGeneration) {
        return;
      }
      commonQuestionEngaged = commonQuestionEngaged ||
        hasAskedCommonQuestion(
          commonQuestions,
          this.data.messages,
          currentConsultationNo
        );
      this.setData(
        {
          commonQuestions,
          commonQuestionAnchorMessageId: commonQuestionAnchorMessageId(
            this.data.messages,
            currentConsultationNo
          ),
          showCommonQuestions: commonQuestionEngaged ||
            shouldShowCustomerServiceCommonQuestions(
              this.data.conversationStatus as CustomerServiceConversation["status"],
              commonQuestions.length,
              pendingTextViews.size > 0
            )
        },
        () => {
          if (messageListFollowingLatest && historyLoadGate.phase === "idle") {
            this.positionLatestWithoutAnimation();
          }
        }
      );
    } catch {
      // 常见问题是增强能力，加载失败不阻塞会话本身。
    }
  },

  onRetry() {
    if (initialized) {
      void this.refreshConversation(false);
    } else {
      void this.initialize();
    }
  },

  startLiveUpdates() {
    stopLiveUpdates();
    unsubscribeRealtime = subscribeCustomerServiceRealtime((event) => {
      if (event.type === "CUSTOMER_SERVICE_CONVERSATION_UPDATED") {
        const messageId = positiveId(event.data?.messageId);
        this.pruneLocallyHandledMessages();
        if (messageId && locallyHandledMessageIds.has(messageId)) {
          return;
        }
        if (localMutationCount > 0) {
          if (messageId) {
            pendingRealtimeMessageIds.add(messageId);
          } else {
            pendingRealtimeChangeWithoutMessage = true;
          }
          return;
        }
        void this.refreshConversation(true);
      }
    });
    unsubscribeRealtimeState = subscribeCustomerServiceRealtimeState((state) => {
      if (!pageActive) {
        return;
      }
      if (state === "CONNECTED") {
        if (pollTimer) {
          clearInterval(pollTimer);
          pollTimer = null;
        }
        void this.refreshConversation(true);
        return;
      }
      if (!pollTimer) {
        pollTimer = setInterval(() => {
          void this.refreshConversation(true);
        }, FALLBACK_POLL_INTERVAL_MS);
      }
    });
  },

  async refreshConversation(silent: boolean) {
    if (!pageActive) {
      return;
    }
    if (localMutationCount > 0) {
      refreshQueued = true;
      return;
    }
    if (refreshRunning) {
      refreshQueued = true;
      return;
    }
    refreshRunning = true;
    refreshQueued = false;
    const requestGeneration = initializeGeneration;
    const mutationEpoch = conversationMutationEpoch;
    if (!silent) {
      this.setData({ loading: !this.data.loaded, errorText: "" });
    }
    try {
      const conversation = await getCustomerServiceConversation();
      if (
        requestGeneration !== initializeGeneration ||
        !pageActive ||
        !conversation ||
        mutationEpoch !== conversationMutationEpoch ||
        localMutationCount > 0
      ) {
        if (pageActive && conversation && (
          mutationEpoch !== conversationMutationEpoch ||
          localMutationCount > 0
        )) {
          refreshQueued = true;
        }
        return;
      }
      this.applyConversation(conversation);
      this.setData({ loading: false, loaded: true, errorText: "" });
    } catch (error) {
      if (requestGeneration === initializeGeneration && pageActive && !silent) {
        this.setData({
          loading: false,
          errorText: errorMessage(error, "消息刷新失败，请稍后重试")
        });
      }
    } finally {
      if (requestGeneration !== initializeGeneration) {
        return;
      }
      refreshRunning = false;
      if (refreshQueued && pageActive) {
        refreshQueued = false;
        void this.refreshConversation(true);
      }
    }
  },

  applyConversation(conversation: CustomerServiceConversation) {
    const nextSignature = conversationSignature(conversation);
    if (this.data.loaded && nextSignature === conversationSignatureValue) {
      return;
    }
    conversationSignatureValue = nextSignature;
    if (currentConsultationNo && currentConsultationNo !== conversation.consultationNo) {
      commonQuestionQueued = false;
      commonQuestionEngaged = false;
      commonQuestionMessageIds.clear();
    }
    currentConsultationNo = conversation.consultationNo;
    const rawMessages = Array.isArray(conversation.messages) ? conversation.messages : [];
    const persistedClientMessageIds = new Set(
      rawMessages
        .map((message) => cleanText(message.clientMessageId))
        .filter(Boolean)
    );
    pendingTextMessages.forEach((message, messageId) => {
      if (message.clientMessageId && persistedClientMessageIds.has(message.clientMessageId)) {
        pendingTextMessages.delete(messageId);
        pendingTextViews.delete(messageId);
      }
    });
    rawMessages.forEach((message) => imageMessages.set(message.messageId, message));
    const persistedMessages = Array.from(imageMessages.values())
      .sort((left, right) => left.messageId - right.messageId);
    const pendingViews = [
      ...Array.from(pendingTextViews.values()),
      ...Array.from(pendingImageViews.values())
    ].sort((left, right) => right.messageId - left.messageId);
    const existingViews = new Map(
      this.data.messages.map((view) => [view.messageId, view])
    );
    const views = [
      ...messageViews(persistedMessages, undefined, existingViews),
      ...pendingViews
    ];
    const nextPersistedMessageId = persistedMessages.length
      ? persistedMessages[persistedMessages.length - 1].messageId
      : 0;
    const isInitialPositioning = !this.data.loaded && latestPersistedMessageId === 0;
    const shouldScrollToLatest = Boolean(
      views.length &&
      nextPersistedMessageId !== latestPersistedMessageId &&
      messageListFollowingLatest &&
      !this.data.historyLoading
    );
    latestPersistedMessageId = nextPersistedMessageId;
    commonQuestionEngaged = commonQuestionEngaged ||
      hasAskedCommonQuestion(
        this.data.commonQuestions,
        views,
        conversation.consultationNo
      );
    const context = conversation.currentContext;
    const contextPreview = conversation.status === "DRAFT"
      ? context?.product?.title
        ? `将咨询商品：${context.product.title}`
        : context?.order?.orderNo
          ? `将咨询订单：${context.order.orderNo}`
          : ""
      : "";
    this.setData(
      {
        conversationId: conversation.conversationId,
        conversationStatus: conversation.status,
        contextPreview,
        messages: views,
        hasMoreHistory: isInitialPositioning
          ? rawMessages.length >= HISTORY_PAGE_SIZE
          : this.data.hasMoreHistory,
        historyExhausted: isInitialPositioning
          ? false
          : this.data.historyExhausted,
        commonQuestionAnchorMessageId: commonQuestionAnchorMessageId(
          views,
          conversation.consultationNo
        ),
        showCommonQuestions: commonQuestionEngaged ||
          shouldShowCustomerServiceCommonQuestions(
            conversation.status,
            this.data.commonQuestions.length,
            pendingTextViews.size > 0
          )
      },
      () => {
        this.observePrivateImages();
        if (!isInitialPositioning && shouldScrollToLatest) {
          this.scrollToLatest();
        }
      }
    );
  },

  onMessageListScroll(event: ScrollEvent) {
    const previousScrollTop = messageListScrollTop;
    const nextScrollTop = Math.max(0, Number(event.detail.scrollTop) || 0);
    messageListScrollTop = nextScrollTop;
    messageListScrollEventSequence += 1;
    if (messageListPositioningLatest) {
      return;
    }
    if (
      nextScrollTop < previousScrollTop - HISTORY_SCROLL_DIRECTION_TOLERANCE_PX
    ) {
      messageListFollowingLatest = false;
      historyGestureTowardUpper = true;
    } else if (
      nextScrollTop > previousScrollTop + HISTORY_SCROLL_DIRECTION_TOLERANCE_PX
    ) {
      historyGestureTowardUpper = false;
    }
    if (
      nextScrollTop <= HISTORY_PRELOAD_SCROLL_TOP &&
      historyLoadGate.phase === "idle"
    ) {
      void this.tryPreloadEarlierMessages();
    }
    if (!historyGestureActive && historyLoadGate.phase === "idle") {
      this.scheduleHistoryGestureCancel();
    }
  },

  onMessageListDragStart() {
    historyGestureActive = true;
    historyGestureTowardUpper = false;
    stopKeyboardAutoFollow();
    cancelMessageListLatestPositioning();
    clearHistoryGestureCancelTimer();
    historyLoadGate.armGesture(this.canLoadEarlierMessages());
  },

  onMessageListDragEnd() {
    historyGestureActive = false;
    this.scheduleHistoryGestureCancel();
  },

  scheduleHistoryGestureCancel() {
    clearHistoryGestureCancelTimer();
    historyGestureCancelTimer = setTimeout(() => {
      historyGestureCancelTimer = null;
      if (!historyGestureActive && historyLoadGate.phase === "idle") {
        historyLoadGate.cancelGesture();
        historyGestureTowardUpper = false;
      }
    }, HISTORY_GESTURE_CANCEL_DELAY_MS);
  },

  onScrollToLower() {
    messageListFollowingLatest = true;
    historyGestureTowardUpper = false;
  },

  onScrollToUpper() {
    if (messageListPositioningLatest) {
      return;
    }
    messageListScrollTop = 0;
    messageListFollowingLatest = false;
    if (historyGestureActive) {
      historyGestureTowardUpper = true;
    }
    void this.tryPreloadEarlierMessages();
  },

  canLoadEarlierMessages(): boolean {
    return Boolean(
      pageActive &&
      this.data.loaded &&
      historyLoadGate.phase === "idle" &&
      !this.data.historyLoading &&
      this.data.hasMoreHistory
    );
  },

  async tryPreloadEarlierMessages() {
    if (
      !historyGestureTowardUpper ||
      !this.canLoadEarlierMessages() ||
      !await this.measureHistoryEdgeVisible()
    ) {
      return;
    }
    if (
      !historyGestureTowardUpper ||
      !historyLoadGate.consumeGesture(this.canLoadEarlierMessages())
    ) {
      return;
    }
    clearHistoryGestureCancelTimer();
    historyGestureTowardUpper = false;
    messageListFollowingLatest = false;
    void this.loadEarlierMessages();
  },

  onHistoryTap() {
    clearHistoryGestureCancelTimer();
    historyGestureTowardUpper = false;
    if (!historyLoadGate.beginManualLoad(this.canLoadEarlierMessages())) {
      return;
    }
    messageListFollowingLatest = false;
    this.setData({ historyButtonSuppressed: true });
    void this.loadEarlierMessages();
  },

  getMessageScrollContext(): Promise<WechatMiniprogram.ScrollViewContext | null> {
    if (messageScrollContext) {
      return Promise.resolve(messageScrollContext);
    }
    const requestGeneration = initializeGeneration;
    return new Promise((resolve) => {
      const query = wx.createSelectorQuery().in(this);
      query.select(".message-scroll").node();
      query.exec((results) => {
        const nodeResult = results?.[0] as
          | Partial<WechatMiniprogram.NodeCallbackResult>
          | null
          | undefined;
        const context = nodeResult?.node as
          | WechatMiniprogram.ScrollViewContext
          | undefined;
        if (
          requestGeneration === initializeGeneration &&
          pageActive &&
          context &&
          typeof context.scrollTo === "function"
        ) {
          messageScrollContext = context;
          resolve(context);
          return;
        }
        resolve(null);
      });
    });
  },

  async scrollMessageListTo(
    top: number,
    animated: boolean,
    isCurrent: () => boolean = () => true
  ): Promise<boolean> {
    const requestGeneration = initializeGeneration;
    const target = Number.isFinite(top) ? Math.max(0, top) : 0;
    for (let attempt = 0; attempt < MESSAGE_SCROLL_CONTEXT_ATTEMPTS; attempt += 1) {
      if (!isCurrent()) {
        return false;
      }
      const context = await this.getMessageScrollContext();
      if (
        requestGeneration !== initializeGeneration ||
        !pageActive ||
        !isCurrent()
      ) {
        return false;
      }
      if (context) {
        try {
          if (!isCurrent()) {
            return false;
          }
          context.scrollTo({ top: target, animated });
          return true;
        } catch {
          messageScrollContext = null;
        }
      }
      await waitForMilliseconds(16);
    }
    return false;
  },

  measureMessageListScrollMetrics(): Promise<MessageListScrollMetrics | null> {
    return new Promise((resolve) => {
      const query = wx.createSelectorQuery().in(this);
      query.select(".message-scroll").scrollOffset();
      query.select(".message-scroll").boundingClientRect();
      query.select("#message-list-bottom").boundingClientRect();
      query.exec((results) => {
        const scrollOffset = results?.[0] as
          | Partial<WechatMiniprogram.ScrollOffsetCallbackResult>
          | null
          | undefined;
        const viewport = results?.[1] as
          | Partial<WechatMiniprogram.BoundingClientRectCallbackResult>
          | null
          | undefined;
        const latestAnchor = results?.[2] as
          | Partial<WechatMiniprogram.BoundingClientRectCallbackResult>
          | null
          | undefined;
        const measuredScrollTop = scrollOffset?.scrollTop;
        const measuredScrollHeight = scrollOffset?.scrollHeight;
        const measuredViewportHeight = viewport?.height;
        if (
          typeof measuredScrollTop !== "number" ||
          !Number.isFinite(measuredScrollTop) ||
          typeof measuredScrollHeight !== "number" ||
          !Number.isFinite(measuredScrollHeight) ||
          typeof viewport?.top !== "number" ||
          !Number.isFinite(viewport.top) ||
          typeof measuredViewportHeight !== "number" ||
          !Number.isFinite(measuredViewportHeight) ||
          typeof latestAnchor?.top !== "number" ||
          !Number.isFinite(latestAnchor.top) ||
          typeof latestAnchor.height !== "number" ||
          !Number.isFinite(latestAnchor.height)
        ) {
          resolve(null);
          return;
        }
        const targetScrollTop = customerServiceBottomScrollTop(
          measuredScrollHeight,
          measuredViewportHeight
        );
        if (targetScrollTop === null) {
          resolve(null);
          return;
        }
        const viewportBottom = viewport.top + measuredViewportHeight;
        const anchorBottom = latestAnchor.top + latestAnchor.height;
        resolve({
          scrollTop: measuredScrollTop,
          scrollHeight: Math.max(0, measuredScrollHeight),
          viewportHeight: measuredViewportHeight,
          targetScrollTop,
          latestAnchorVisible:
            latestAnchor.top <= viewportBottom + MESSAGE_LATEST_TOLERANCE_PX &&
            anchorBottom >= viewport.top - MESSAGE_LATEST_TOLERANCE_PX &&
            anchorBottom <= viewportBottom + MESSAGE_LATEST_TOLERANCE_PX
        });
      });
    });
  },

  measureHistoryScrollMetrics(messageId: number): Promise<HistoryScrollMetrics> {
    return new Promise((resolve) => {
      const query = wx.createSelectorQuery().in(this);
      query.select(".message-scroll").scrollOffset();
      query.select(".message-scroll").boundingClientRect();
      query.select(`#message-${messageId}`).boundingClientRect();
      query.exec((results) => {
        const scrollOffset = results?.[0] as
          | Partial<WechatMiniprogram.ScrollOffsetCallbackResult>
          | null
          | undefined;
        const viewport = results?.[1] as
          | Partial<WechatMiniprogram.BoundingClientRectCallbackResult>
          | null
          | undefined;
        const anchor = results?.[2] as
          | Partial<WechatMiniprogram.BoundingClientRectCallbackResult>
          | null
          | undefined;
        const scrollTop = scrollOffset?.scrollTop;
        const scrollHeight = scrollOffset?.scrollHeight;
        const viewportHeight = viewport?.height;
        const viewportTop = viewport?.top;
        const anchorTop = anchor?.top;
        resolve({
          scrollTop: typeof scrollTop === "number" && Number.isFinite(scrollTop)
            ? Math.max(0, scrollTop)
            : null,
          scrollHeight: typeof scrollHeight === "number" &&
              Number.isFinite(scrollHeight)
            ? Math.max(0, scrollHeight)
            : null,
          viewportHeight: typeof viewportHeight === "number" &&
              Number.isFinite(viewportHeight)
            ? Math.max(0, viewportHeight)
            : null,
          anchorOffset: typeof viewportTop === "number" &&
              Number.isFinite(viewportTop) &&
              typeof anchorTop === "number" &&
              Number.isFinite(anchorTop)
            ? anchorTop - viewportTop
            : null
        });
      });
    });
  },

  clampHistoryScrollTop(target: number, metrics: HistoryScrollMetrics): number {
    const maxScrollTop =
      metrics.scrollHeight !== null && metrics.viewportHeight !== null
        ? Math.max(0, metrics.scrollHeight - metrics.viewportHeight)
        : null;
    return Math.max(
      0,
      maxScrollTop === null ? target : Math.min(target, maxScrollTop)
    );
  },

  async waitForHistoryMotionToSettle(
    requestIsStale: () => boolean
  ): Promise<boolean> {
    let observedSequence = messageListScrollEventSequence;
    let stableReadCount = 0;
    for (
      let attempt = 0;
      attempt < HISTORY_MOTION_SETTLE_ATTEMPTS;
      attempt += 1
    ) {
      await waitForMilliseconds(HISTORY_MOTION_SETTLE_INTERVAL_MS);
      if (requestIsStale()) {
        return false;
      }
      if (
        historyGestureActive ||
        observedSequence !== messageListScrollEventSequence
      ) {
        observedSequence = messageListScrollEventSequence;
        stableReadCount = 0;
        continue;
      }
      stableReadCount += 1;
      if (stableReadCount >= HISTORY_MOTION_STABLE_READS) {
        return true;
      }
    }
    return false;
  },

  async restoreHistoryScrollPosition(
    messageId: number,
    metricsBefore: HistoryScrollMetrics,
    requestIsStale: () => boolean
  ): Promise<boolean> {
    let stableReadCount = 0;
    for (let attempt = 0; attempt < HISTORY_RESTORE_ATTEMPTS; attempt += 1) {
      if (requestIsStale()) {
        return false;
      }
      const metricsAfter = await this.measureHistoryScrollMetrics(messageId);
      if (requestIsStale()) {
        return false;
      }
      if (metricsAfter.scrollTop === null) {
        stableReadCount = 0;
        await waitForMilliseconds(HISTORY_RESTORE_INTERVAL_MS);
        continue;
      }
      messageListScrollTop = metricsAfter.scrollTop;

      let targetScrollTop: number | null = null;
      let positionError: number | null = null;
      if (
        metricsBefore.anchorOffset !== null &&
        metricsAfter.anchorOffset !== null
      ) {
        positionError = metricsAfter.anchorOffset - metricsBefore.anchorOffset;
        targetScrollTop = preserveCustomerServiceHistoryScrollTop(
          metricsAfter.scrollTop,
          metricsBefore.anchorOffset,
          metricsAfter.anchorOffset
        );
      } else if (
        metricsBefore.scrollTop !== null &&
        metricsBefore.scrollHeight !== null &&
        metricsAfter.scrollHeight !== null
      ) {
        targetScrollTop = metricsBefore.scrollTop + Math.max(
          0,
          metricsAfter.scrollHeight - metricsBefore.scrollHeight
        );
        positionError = metricsAfter.scrollTop - targetScrollTop;
      }

      if (positionError === null || targetScrollTop === null) {
        stableReadCount = 0;
        await waitForMilliseconds(HISTORY_RESTORE_INTERVAL_MS);
        continue;
      }
      const positionSettled = Math.abs(positionError) <=
        HISTORY_RESTORE_TOLERANCE_PX;
      stableReadCount = positionSettled ? stableReadCount + 1 : 0;
      if (stableReadCount >= HISTORY_RESTORE_STABLE_READS) {
        return true;
      }
      if (!positionSettled) {
        const scrollStarted = await this.scrollMessageListTo(
          this.clampHistoryScrollTop(targetScrollTop, metricsAfter),
          false,
          () => !requestIsStale()
        );
        if (!scrollStarted) {
          return false;
        }
      }
      await waitForMilliseconds(HISTORY_RESTORE_INTERVAL_MS);
    }
    return false;
  },

  measureHistoryEdgeVisible(): Promise<boolean> {
    return new Promise((resolve) => {
      const query = wx.createSelectorQuery().in(this);
      query.select(".message-scroll").boundingClientRect();
      query.select(".history-loader").boundingClientRect();
      query.exec((results) => {
        const viewport = results?.[0] as
          | Partial<WechatMiniprogram.BoundingClientRectCallbackResult>
          | null
          | undefined;
        const historyLoader = results?.[1] as
          | Partial<WechatMiniprogram.BoundingClientRectCallbackResult>
          | null
          | undefined;
        if (
          typeof viewport?.top !== "number" ||
          typeof viewport.height !== "number" ||
          typeof historyLoader?.top !== "number" ||
          typeof historyLoader.height !== "number"
        ) {
          resolve(false);
          return;
        }
        const viewportBottom = viewport.top + viewport.height;
        const loaderBottom = historyLoader.top + historyLoader.height;
        resolve(
          historyLoader.top <= viewportBottom &&
          loaderBottom >= viewport.top - 4
        );
      });
    });
  },

  deferMessageListLatestPosition(isCurrent: () => boolean) {
    const previousRequest = pendingMessageListLatestPosition;
    pendingMessageListLatestPosition = {
      isCurrent: previousRequest
        ? () => previousRequest.isCurrent() || isCurrent()
        : isCurrent
    };
    historyLoadGate.deferLatestPosition();
  },

  flushPendingMessageListLatestPosition() {
    if (!historyLoadGate.takeDeferredLatestPosition()) {
      return;
    }
    const pendingRequest = pendingMessageListLatestPosition;
    pendingMessageListLatestPosition = null;
    if (!pendingRequest?.isCurrent()) {
      return;
    }
    void this.positionLatestReliably(pendingRequest.isCurrent);
  },

  finishHistoryLoad() {
    historyLoadGate.finish();
    this.flushPendingMessageListLatestPosition();
  },

  async loadEarlierMessages() {
    if (historyLoadGate.phase !== "loading" || !this.data.hasMoreHistory) {
      this.finishHistoryLoad();
      return;
    }
    const persistedMessages = Array.from(imageMessages.values())
      .sort((left, right) => left.messageId - right.messageId);
    const firstMessage = persistedMessages[0];
    if (!firstMessage) {
      this.finishHistoryLoad();
      this.setData({
        hasMoreHistory: false,
        historyExhausted: true,
        historyButtonSuppressed: false
      });
      return;
    }
    cancelMessageListLatestPositioning();
    const requestGeneration = initializeGeneration;
    const historyRequestGeneration = ++historyLoadGeneration;
    const requestIsStale = () => (
      requestGeneration !== initializeGeneration ||
      historyRequestGeneration !== historyLoadGeneration ||
      !pageActive
    );
    messageListFollowingLatest = false;
    this.setData({ historyLoading: true });
    try {
      const olderMessages = await getCustomerServiceMessages({
        beforeId: firstMessage.messageId,
        limit: HISTORY_PAGE_SIZE
      });
      if (requestIsStale()) {
        return;
      }
      if (!await this.waitForHistoryMotionToSettle(requestIsStale)) {
        return;
      }
      const historyEdgeVisibleBeforeAnchoring =
        await this.measureHistoryEdgeVisible();
      if (
        requestIsStale() ||
        historyGestureActive ||
        !historyEdgeVisibleBeforeAnchoring
      ) {
        return;
      }
      await new Promise<void>((resolve) => {
        this.setData(
          { historyAnchoring: true },
          () => wx.nextTick(resolve)
        );
      });
      const historyEdgeVisibleBeforePrepend =
        await this.measureHistoryEdgeVisible();
      if (
        requestIsStale() ||
        historyGestureActive ||
        !historyEdgeVisibleBeforePrepend
      ) {
        return;
      }
      const metricsBefore = await this.measureHistoryScrollMetrics(
        firstMessage.messageId
      );
      if (requestIsStale()) {
        return;
      }
      if (
        metricsBefore.scrollTop === null ||
        (
          metricsBefore.anchorOffset === null &&
          metricsBefore.scrollHeight === null
        )
      ) {
        throw new Error("无法稳定读取历史消息位置，请重试");
      }
      let addedCount = 0;
      olderMessages.forEach((message) => {
        if (!imageMessages.has(message.messageId)) {
          addedCount += 1;
        }
        imageMessages.set(message.messageId, message);
      });
      if (!addedCount) {
        this.setData({
          hasMoreHistory: false,
          historyExhausted: true
        });
        return;
      }
      const allPersistedMessages = Array.from(imageMessages.values())
        .sort((left, right) => left.messageId - right.messageId);
      const pendingViews = [
        ...Array.from(pendingTextViews.values()),
        ...Array.from(pendingImageViews.values())
      ].sort((left, right) => right.messageId - left.messageId);
      const existingViews = new Map(
        this.data.messages.map((view) => [view.messageId, view])
      );
      const views = [
        ...messageViews(allPersistedMessages, undefined, existingViews),
        ...pendingViews
      ];
      historyLoadGate.markRestoring();
      await new Promise<void>((resolve) => {
        this.setData(
          {
            messages: views,
            hasMoreHistory: olderMessages.length >= HISTORY_PAGE_SIZE,
            historyExhausted: olderMessages.length < HISTORY_PAGE_SIZE
          },
          resolve
        );
      });
      if (requestIsStale()) {
        return;
      }
      const historyPositionRestored = await this.restoreHistoryScrollPosition(
        firstMessage.messageId,
        metricsBefore,
        requestIsStale
      );
      if (requestIsStale()) {
        return;
      }
      if (!historyPositionRestored) {
        throw new Error("历史消息位置恢复失败，请重试");
      }
      this.observePrivateImages();
    } catch (error) {
      if (!requestIsStale()) {
        wx.showToast({
          title: errorMessage(error, "历史消息加载失败，请重试"),
          icon: "none"
        });
      }
    } finally {
      if (historyRequestGeneration === historyLoadGeneration) {
        historyGestureTowardUpper = false;
        await new Promise<void>((resolve) => {
          this.setData(
            {
              historyLoading: false,
              historyAnchoring: false,
              historyButtonSuppressed: false
            },
            () => wx.nextTick(resolve)
          );
        });
        if (historyRequestGeneration === historyLoadGeneration) {
          this.finishHistoryLoad();
        }
      }
    }
  },

  observePrivateImages() {
    const imageMessagesNow = this.data.messages
      .filter((message) => message.messageType === "IMAGE")
      .map((message) => message.messageId)
      .join(":");
    if (imageMessagesNow === imageObservationSignature) {
      return;
    }
    imageObservationSignature = imageMessagesNow;
    imageObserver?.disconnect();
    imageObserver = null;
    if (!imageMessagesNow) {
      return;
    }
    imageObserver = this.createIntersectionObserver({
      observeAll: true,
      thresholds: [0, 0.01]
    });
    imageObserver
      .relativeToViewport({ top: 600, bottom: 600 })
      .observe(".message-image-shell", (result) => {
        if (result.intersectionRatio <= 0) {
          return;
        }
        const messageId = positiveId(result.dataset.id);
        if (messageId) {
          void this.loadPrivateImage(messageId);
        }
      });
  },

  async loadPrivateImage(messageId: number) {
    const message = imageMessages.get(messageId);
    if (
      !message ||
      message.messageType !== "IMAGE" ||
      imageTempPaths.has(messageId) ||
      imageDownloads.has(messageId)
    ) {
      return;
    }
    imageDownloads.add(messageId);
    try {
      const tempFilePath = await downloadCustomerServiceImage(message);
      imageTempPaths.set(messageId, tempFilePath);
      if (
        message.image?.thumbnailStatus !== "READY" &&
        !originalImageTempPaths.has(messageId)
      ) {
        originalImageTempPaths.set(messageId, tempFilePath);
      }
      if (pageActive) {
        const messageIndex = this.data.messages.findIndex(
          (view) => view.messageId === messageId
        );
        if (messageIndex >= 0) {
          this.setData({
            [`messages[${messageIndex}].imageUrl`]: tempFilePath,
            [`messages[${messageIndex}].imageFailed`]: false
          });
        }
      }
    } catch {
      if (pageActive) {
        const messageIndex = this.data.messages.findIndex(
          (view) => view.messageId === messageId
        );
        if (messageIndex >= 0) {
          this.setData({
            [`messages[${messageIndex}].imageFailed`]: true
          });
        }
      }
    } finally {
      imageDownloads.delete(messageId);
    }
  },

  onInput(event: InputEvent) {
    this.setData({ inputValue: event.detail.value });
  },

  onInputFocus(event: WechatMiniprogram.TextareaFocus) {
    panelInteractionGeneration += 1;
    resetKeyboardPositioning();
    const focusPositionGeneration = keyboardPositionGeneration;
    inputFocused = true;
    keyboardAutoFollow = true;
    messageListFollowingLatest = true;
    const focusHeight = Math.max(0, Math.round(Number(event.detail.height) || 0));
    const focusRequestIsCurrent = () => (
      pageActive &&
      inputFocused &&
      keyboardAutoFollow &&
      focusPositionGeneration === keyboardPositionGeneration
    );
    const positionForFocus = () => {
      if (!focusRequestIsCurrent()) {
        return;
      }
      this.positionLatestWithoutAnimation(focusRequestIsCurrent);
      if (focusHeight > 0) {
        this.scheduleKeyboardLatestPosition(
          focusHeight,
          KEYBOARD_MINIMUM_SETTLE_DELAY_MS
        );
      }
    };
    if (this.data.panelMode || this.data.pickerOpen) {
      this.setData(
        { panelMode: "", pickerOpen: false },
        positionForFocus
      );
      return;
    }
    positionForFocus();
  },

  onInputBlur() {
    inputFocused = false;
    stopKeyboardAutoFollow();
    cancelMessageListLatestPositioning();
  },

  onKeyboardHeightChange(
    event: WechatMiniprogram.TextareaKeyboardHeightChange
  ) {
    if (!pageActive || !inputFocused || !keyboardAutoFollow) {
      return;
    }
    this.scheduleKeyboardLatestPosition(
      event.detail.height,
      event.detail.duration
    );
  },

  scheduleKeyboardLatestPosition(heightValue: number, durationValue: number) {
    const height = Math.max(0, Math.round(Number(heightValue) || 0));
    const reportedDuration = Math.max(0, Math.round(Number(durationValue) || 0));
    const settleDelay = Math.min(
      KEYBOARD_MAXIMUM_SETTLE_DELAY_MS,
      Math.max(KEYBOARD_MINIMUM_SETTLE_DELAY_MS, reportedDuration)
    );
    if (
      height === lastKeyboardHeight &&
      (!height || settleDelay <= lastKeyboardSettleDelay)
    ) {
      return;
    }
    lastKeyboardHeight = height;
    lastKeyboardSettleDelay = height ? settleDelay : 0;
    clearKeyboardSettleTimer();
    const positionGeneration = ++keyboardPositionGeneration;
    if (!height) {
      return;
    }

    messageListFollowingLatest = true;
    const requestIsCurrent = () => (
      pageActive &&
      inputFocused &&
      keyboardAutoFollow &&
      positionGeneration === keyboardPositionGeneration
    );
    wx.nextTick(() => {
      if (requestIsCurrent()) {
        this.positionLatestWithoutAnimation(requestIsCurrent);
      }
    });

    keyboardSettleTimer = setTimeout(() => {
      keyboardSettleTimer = null;
      if (requestIsCurrent() && lastKeyboardHeight > 0) {
        void this.positionLatestReliably(requestIsCurrent);
      }
    }, settleDelay);
  },

  onInputConfirm(event: InputEvent) {
    void this.sendText(event.detail.value);
  },

  onCommonQuestionTap(event: DatasetEvent) {
    if (commonQuestionQueued) {
      return;
    }
    const question = cleanText(event.currentTarget.dataset.question);
    if (question) {
      commonQuestionQueued = true;
      this.setData({ commonQuestionSending: true });
      this.queueText(question, false, true);
    }
  },

  sendText(value?: string) {
    this.queueText(typeof value === "string" ? value : this.data.inputValue);
  },

  queueText(value: string, clearInput = true, fromCommonQuestion = false) {
    const content = value.trim();
    if (!content) {
      return;
    }
    const messageId = nextPendingMessageId;
    nextPendingMessageId -= 1;
    const pendingMessage: CustomerServiceMessage = {
      messageId,
      conversationId: this.data.conversationId,
      consultationNo: currentConsultationNo || 1,
      senderType: "APP_USER",
      senderName: "我",
      messageType: "TEXT",
      content,
      clientMessageId: customerServiceMessageId(),
      createdAt: new Date().toISOString()
    };
    const previousMessage = this.data.messages[this.data.messages.length - 1];
    const pendingView = {
      ...messageViews([pendingMessage], previousMessage)[0],
      sending: true
    };
    pendingTextMessages.set(messageId, pendingMessage);
    pendingTextViews.set(messageId, pendingView);
    if (fromCommonQuestion) {
      commonQuestionEngaged = true;
      commonQuestionMessageIds.add(messageId);
    }
    const messages = [...this.data.messages, pendingView];
    panelInteractionGeneration += 1;
    this.setData(
      {
        inputValue: clearInput ? "" : this.data.inputValue,
        panelMode: "",
        showCommonQuestions: fromCommonQuestion || commonQuestionEngaged,
        messages
      },
      () => this.scrollToLatest()
    );
    void this.deliverPendingText(messageId);
  },

  async deliverPendingText(messageId: number) {
    const pendingMessage = pendingTextMessages.get(messageId);
    if (!pendingMessage || pendingTextRequests.has(messageId)) {
      return;
    }
    const requestGeneration = initializeGeneration;
    pendingTextRequests.add(messageId);
    const pendingView = pendingTextViews.get(messageId);
    if (pendingView) {
      pendingView.sending = true;
      pendingView.sendFailed = false;
      this.syncPendingTextView(pendingView);
    }
    this.beginLocalMutation();
    try {
      const message = await sendCustomerServiceMessage({
        content: pendingMessage.content,
        clientMessageId: pendingMessage.clientMessageId || customerServiceMessageId()
      });
      if (requestGeneration !== initializeGeneration) {
        return;
      }
      pendingTextMessages.delete(messageId);
      pendingTextViews.delete(messageId);
      this.appendLocallySentMessage(message, messageId);
      // 自动回复与用户消息分别持久化；发送完成后拉取一次权威会话，
      // 即使实时事件短暂丢失，也能立即拿到 FAQ / 智能 / 离线回复。
      pendingRealtimeChangeWithoutMessage = true;
    } catch {
      if (requestGeneration !== initializeGeneration) {
        return;
      }
      const failedView = pendingTextViews.get(messageId);
      if (failedView) {
        failedView.sending = false;
        failedView.sendFailed = true;
        this.syncPendingTextView(failedView);
      }
    } finally {
      if (requestGeneration === initializeGeneration) {
        pendingTextRequests.delete(messageId);
        if (commonQuestionMessageIds.delete(messageId)) {
          commonQuestionQueued = false;
          this.setData({ commonQuestionSending: false });
        }
        this.endLocalMutation();
      }
    }
  },

  syncPendingTextView(pendingView: MessageView) {
    this.setData({
      messages: this.data.messages.map((view) => (
        view.messageId === pendingView.messageId ? { ...pendingView } : view
      ))
    });
  },

  onRetryTextTap(event: DatasetEvent) {
    const messageId = Number(event.currentTarget.dataset.id);
    if (!Number.isSafeInteger(messageId) || messageId >= 0) {
      return;
    }
    const pendingView = pendingTextViews.get(messageId);
    if (!pendingView?.sendFailed) {
      return;
    }
    void this.deliverPendingText(messageId);
  },

  onPlusTap() {
    if (this.data.uploading || choosingMedia) {
      return;
    }
    if (this.data.panelMode) {
      this.closePanels();
      return;
    }
    const requestGeneration = initializeGeneration;
    const panelGeneration = ++panelInteractionGeneration;
    wx.hideKeyboard({
      complete: () => {
        if (
          requestGeneration !== initializeGeneration ||
          panelGeneration !== panelInteractionGeneration
        ) {
          return;
        }
        this.setData(
          { panelMode: "main", pickerOpen: false },
          () => this.scrollToLatest()
        );
      }
    });
  },

  onProductActionTap() {
    panelInteractionGeneration += 1;
    this.setData({ panelMode: "product" }, () => this.scrollToLatest());
  },

  onPanelBackTap() {
    panelInteractionGeneration += 1;
    this.setData({ panelMode: "main" }, () => this.scrollToLatest());
  },

  closePanels() {
    panelInteractionGeneration += 1;
    if (!this.data.panelMode && !this.data.pickerOpen) {
      return;
    }
    this.setData(
      { panelMode: "", pickerOpen: false },
      () => this.scrollToLatest()
    );
  },

  scrollToLatest() {
    void this.positionMessageListAtLatest(true, 3);
  },

  positionLatestWithoutAnimation(isCurrent: () => boolean = () => true) {
    void this.positionMessageListAtLatest(false, 2, isCurrent);
  },

  positionLatestReliably(
    isCurrent: () => boolean = () => true
  ): Promise<boolean> {
    return this.positionMessageListAtLatest(
      false,
      MESSAGE_LATEST_RETRY_ATTEMPTS,
      isCurrent
    );
  },

  async positionMessageListAtLatest(
    animated: boolean,
    attempts: number,
    externalIsCurrent: () => boolean = () => true
  ): Promise<boolean> {
    if (!pageActive || !externalIsCurrent()) {
      return false;
    }
    if (historyLoadGate.phase !== "idle" || this.data.historyLoading) {
      this.deferMessageListLatestPosition(externalIsCurrent);
      return false;
    }
    const requestGeneration = initializeGeneration;
    const positionGeneration = ++messageListPositionGeneration;
    messageListPositioningLatest = true;
    messageListFollowingLatest = true;
    const positionIsCurrent = () => (
      pageActive &&
      requestGeneration === initializeGeneration &&
      positionGeneration === messageListPositionGeneration &&
      messageListFollowingLatest &&
      historyLoadGate.phase === "idle" &&
      !this.data.historyLoading &&
      externalIsCurrent()
    );
    const requiredStableReads = 2;
    let stableReadCount = 0;
    try {
      await waitForNextRender();
      for (let attempt = 0; attempt < attempts; attempt += 1) {
        if (!positionIsCurrent()) {
          return false;
        }
        const context = await this.getMessageScrollContext();
        if (!positionIsCurrent()) {
          return false;
        }
        if (!context) {
          await waitForMilliseconds(MESSAGE_LATEST_RETRY_INTERVAL_MS);
          continue;
        }
        const metricsBefore = await this.measureMessageListScrollMetrics();
        if (!positionIsCurrent()) {
          return false;
        }
        if (!metricsBefore) {
          stableReadCount = 0;
          await waitForMilliseconds(MESSAGE_LATEST_RETRY_INTERVAL_MS);
          continue;
        }
        const shouldAnimate = animated && attempt === 0;
        const scrollStarted = await this.scrollMessageListTo(
          metricsBefore.targetScrollTop,
          shouldAnimate,
          positionIsCurrent
        );
        if (!scrollStarted) {
          return false;
        }
        await waitForMilliseconds(
          shouldAnimate
            ? MESSAGE_ANIMATED_SCROLL_SETTLE_MS
            : MESSAGE_LATEST_RETRY_INTERVAL_MS
        );
        if (!positionIsCurrent()) {
          return false;
        }
        const metricsAfter = await this.measureMessageListScrollMetrics();
        if (!positionIsCurrent()) {
          return false;
        }
        if (!metricsAfter) {
          stableReadCount = 0;
          continue;
        }
        messageListScrollTop = Math.max(0, metricsAfter.scrollTop);
        const positionSettled = isCustomerServiceBottomScrollSettled(
          metricsAfter.scrollTop,
          metricsAfter.targetScrollTop,
          MESSAGE_LATEST_TOLERANCE_PX
        );
        const layoutSettled = isCustomerServiceBottomScrollSettled(
          metricsAfter.targetScrollTop,
          metricsBefore.targetScrollTop,
          MESSAGE_LATEST_TOLERANCE_PX
        );
        const atLatest = positionSettled &&
          layoutSettled &&
          metricsAfter.latestAnchorVisible;
        stableReadCount = atLatest ? stableReadCount + 1 : 0;
        if (stableReadCount >= requiredStableReads) {
          return true;
        }
      }
      return false;
    } finally {
      if (positionGeneration === messageListPositionGeneration) {
        messageListPositioningLatest = false;
      }
    }
  },

  onAlbumTap() {
    void this.selectAndUploadImages("album", 9);
  },

  onCameraTap() {
    void this.selectAndUploadImages("camera", 1);
  },

  async selectAndUploadImages(sourceType: "album" | "camera", count: number) {
    if (this.data.uploading || choosingMedia) {
      return;
    }
    const requestGeneration = initializeGeneration;
    choosingMedia = true;
    let mutationStarted = false;
    try {
      const filePaths = await chooseChatImages(sourceType, count);
      if (requestGeneration !== initializeGeneration) {
        return;
      }
      if (!filePaths.length) {
        return;
      }
      let previousMessage: MessageView | CustomerServiceMessage | undefined =
        this.data.messages[this.data.messages.length - 1];
      const pendingUploads = filePaths.map((filePath) => {
        const messageId = nextPendingMessageId;
        nextPendingMessageId -= 1;
        imageTempPaths.set(messageId, filePath);
        const pendingMessage: CustomerServiceMessage = {
          messageId,
          conversationId: this.data.conversationId,
          consultationNo: currentConsultationNo || 1,
          senderType: "APP_USER",
          senderName: "我",
          messageType: "IMAGE",
          content: "[图片]",
          image: {
            originalFilename: "",
            contentType: "image/jpeg"
          },
          createdAt: new Date().toISOString()
        };
        const pendingView = {
          ...messageViews([pendingMessage], previousMessage)[0],
          sending: true
        };
        previousMessage = pendingMessage;
        pendingImageViews.set(messageId, pendingView);
        return { filePath, messageId, pendingView };
      });
      const nextMessages = [
        ...this.data.messages,
        ...pendingUploads.map((pending) => pending.pendingView)
      ];
      this.beginLocalMutation();
      mutationStarted = true;
      this.setData(
        {
          uploading: true,
          panelMode: "",
          showCommonQuestions: false,
          messages: nextMessages
        },
        () => this.scrollToLatest()
      );
      let successCount = 0;
      let lastFailure = "";
      for (const pending of pendingUploads) {
        try {
          const message = await uploadCustomerServiceImage(pending.filePath);
          if (requestGeneration !== initializeGeneration) {
            return;
          }
          imageTempPaths.set(message.messageId, pending.filePath);
          originalImageTempPaths.set(message.messageId, pending.filePath);
          imageMessages.set(message.messageId, message);
          successCount += 1;
          this.appendLocallySentMessage(message, pending.messageId);
        } catch (error) {
          if (requestGeneration !== initializeGeneration) {
            return;
          }
          lastFailure = isApiError(error) && error.code === 800002
            ? "图片需为常见格式，且不超过 5MB"
            : errorMessage(error, "图片发送失败，请重试");
          const failedView = pendingImageViews.get(pending.messageId);
          if (failedView) {
            failedView.sending = false;
            failedView.sendFailed = true;
            this.syncPendingImageView(failedView);
          }
        }
      }
      if (successCount !== filePaths.length) {
        wx.showToast({
          title: successCount
            ? `${successCount} 张已发送，部分失败`
            : lastFailure || "图片发送失败，请重试",
          icon: "none"
        });
      }
    } catch (error) {
      if (requestGeneration === initializeGeneration) {
        wx.showToast({
          title: errorMessage(error, "图片选择失败"),
          icon: "none"
        });
      }
    } finally {
      if (requestGeneration === initializeGeneration) {
        choosingMedia = false;
        this.setData({ uploading: false });
        if (mutationStarted) {
          this.endLocalMutation();
        }
      }
    }
  },

  syncPendingImageView(pendingView: MessageView) {
    this.setData({
      messages: this.data.messages.map((view) => (
        view.messageId === pendingView.messageId ? { ...pendingView } : view
      ))
    });
  },

  onRetryImageTap(event: DatasetEvent) {
    const messageId = Number(event.currentTarget.dataset.id);
    if (
      !Number.isSafeInteger(messageId) ||
      messageId >= 0 ||
      this.data.uploading ||
      pendingImageRequests.has(messageId)
    ) {
      return;
    }
    const pendingView = pendingImageViews.get(messageId);
    if (!pendingView?.sendFailed || !imageTempPaths.has(messageId)) {
      return;
    }
    void this.retryPendingImage(messageId);
  },

  async retryPendingImage(messageId: number) {
    const filePath = imageTempPaths.get(messageId);
    const pendingView = pendingImageViews.get(messageId);
    if (!filePath || !pendingView || pendingImageRequests.has(messageId)) {
      return;
    }
    const requestGeneration = initializeGeneration;
    pendingImageRequests.add(messageId);
    pendingView.sending = true;
    pendingView.sendFailed = false;
    this.syncPendingImageView(pendingView);
    this.beginLocalMutation();
    try {
      const message = await uploadCustomerServiceImage(filePath);
      if (requestGeneration !== initializeGeneration) {
        return;
      }
      imageTempPaths.set(message.messageId, filePath);
      originalImageTempPaths.set(message.messageId, filePath);
      imageMessages.set(message.messageId, message);
      this.appendLocallySentMessage(message, messageId);
    } catch (error) {
      if (requestGeneration !== initializeGeneration) {
        return;
      }
      const failedView = pendingImageViews.get(messageId);
      if (failedView) {
        failedView.sending = false;
        failedView.sendFailed = true;
        this.syncPendingImageView(failedView);
      }
      wx.showToast({
        title: isApiError(error) && error.code === 800002
          ? "图片需为常见格式，且不超过 5MB"
          : errorMessage(error, "图片发送失败，请重试"),
        icon: "none"
      });
    } finally {
      if (requestGeneration === initializeGeneration) {
        pendingImageRequests.delete(messageId);
        this.endLocalMutation();
      }
    }
  },

  onOrderActionTap() {
    panelInteractionGeneration += 1;
    const requestGeneration = ++pickerRequestGeneration;
    this.setData({
      panelMode: "",
      pickerOpen: true,
      pickerKind: "order",
      pickerTitle: "选择订单",
      pickerLoading: true,
      pickerErrorText: "",
      pickerProductSource: "",
      candidates: []
    });
    void this.loadOrderCandidates(requestGeneration);
  },

  async loadOrderCandidates(requestGeneration: number) {
    try {
      const orders = await getCustomerServiceOrderCandidates();
      if (
        requestGeneration === pickerRequestGeneration &&
        this.data.pickerKind === "order"
      ) {
        this.setData({
          pickerLoading: false,
          candidates: orderCandidateViews(orders),
          pickerErrorText: ""
        });
      }
    } catch (error) {
      if (
        requestGeneration === pickerRequestGeneration &&
        this.data.pickerKind === "order"
      ) {
        this.setData({
          pickerLoading: false,
          pickerErrorText: errorMessage(error, "订单加载失败，请重试")
        });
      }
    }
  },

  onProductSourceTap(event: DatasetEvent) {
    const source = event.currentTarget.dataset.source;
    if (!source) {
      return;
    }
    const title = source === "browse"
      ? "最近浏览"
      : source === "favorite"
        ? "我的收藏"
        : "购物车商品";
    panelInteractionGeneration += 1;
    const requestGeneration = ++pickerRequestGeneration;
    this.setData({
      panelMode: "",
      pickerOpen: true,
      pickerKind: "product",
      pickerTitle: title,
      pickerLoading: true,
      pickerErrorText: "",
      pickerProductSource: source,
      candidates: []
    });
    void this.loadProductCandidates(source, requestGeneration);
  },

  async loadProductCandidates(
    source: ProductSource,
    requestGeneration: number
  ) {
    try {
      let candidates: CandidateView[];
      if (source === "browse") {
        const response = await getBrowseHistory(1, 50);
        candidates = response.records.map((item) => ({
          id: item.spuId,
          title: cleanText(item.title) || "商品",
          subtitle: cleanText(item.subtitle),
          imageUrl: cleanText(item.mainImage),
          hasImage: Boolean(cleanText(item.mainImage)),
          priceText: customerServicePriceRange(item.minPriceCent, item.maxPriceCent),
          metaText: "最近浏览",
          disabled: !item.available
        }));
      } else if (source === "favorite") {
        const response = await getFavorites(1, 50);
        candidates = response.records.map((item) => ({
          id: item.spuId,
          title: cleanText(item.title) || "商品",
          subtitle: cleanText(item.subtitle),
          imageUrl: cleanText(item.mainImage),
          hasImage: Boolean(cleanText(item.mainImage)),
          priceText: customerServicePriceRange(item.minPriceCent, item.maxPriceCent),
          metaText: "已收藏",
          disabled: !item.available
        }));
      } else {
        const response = await getCartItems();
        const seen = new Set<number>();
        candidates = response.items
          .filter((item) => {
            if (seen.has(item.spuId)) {
              return false;
            }
            seen.add(item.spuId);
            return true;
          })
          .map((item) => ({
            id: item.spuId,
            title: cleanText(item.productTitle) || "商品",
            subtitle: cleanText(item.productSubtitle),
            imageUrl: cleanText(item.displayImage || item.skuImage || item.mainImage),
            hasImage: Boolean(cleanText(item.displayImage || item.skuImage || item.mainImage)),
            priceText: formatCustomerServiceMoney(item.priceCent),
            metaText: "购物车",
            disabled: !item.available
          }));
      }
      if (
        requestGeneration === pickerRequestGeneration &&
        this.data.pickerKind === "product" &&
        this.data.pickerProductSource === source
      ) {
        this.setData({
          pickerLoading: false,
          candidates,
          pickerErrorText: ""
        });
      }
    } catch (error) {
      if (
        requestGeneration === pickerRequestGeneration &&
        this.data.pickerKind === "product" &&
        this.data.pickerProductSource === source
      ) {
        this.setData({
          pickerLoading: false,
          pickerErrorText: errorMessage(error, "商品加载失败，请重试")
        });
      }
    }
  },

  onCandidateTap(event: DatasetEvent) {
    const id = positiveId(event.currentTarget.dataset.id);
    const candidate = this.data.candidates.find((item) => item.id === id);
    if (
      !candidate ||
      candidate.disabled ||
      this.data.candidateSendingId ||
      !this.data.pickerKind
    ) {
      return;
    }
    this.setData({ showCommonQuestions: false });
    void this.sendCandidate(id, this.data.pickerKind);
  },

  async sendCandidate(id: number, kind: Exclude<PickerKind, "">) {
    const pageGeneration = initializeGeneration;
    this.setData({ candidateSendingId: id });
    try {
      if (kind === "order") {
        await sendCustomerServiceOrder(id);
      } else {
        await sendCustomerServiceProduct(id);
      }
      if (pageGeneration !== initializeGeneration) {
        return;
      }
      pickerRequestGeneration += 1;
      this.setData({
        candidateSendingId: 0,
        pickerOpen: false,
        pickerKind: "",
        pickerProductSource: "",
        candidates: []
      });
      await this.refreshConversation(true);
    } catch (error) {
      if (pageGeneration !== initializeGeneration) {
        return;
      }
      this.setData({ candidateSendingId: 0 });
      wx.showToast({
        title: errorMessage(error, kind === "order" ? "订单发送失败" : "商品发送失败"),
        icon: "none"
      });
    }
  },

  onPickerCloseTap() {
    if (!this.data.candidateSendingId) {
      pickerRequestGeneration += 1;
      this.setData({
        pickerOpen: false,
        pickerKind: "",
        pickerProductSource: "",
        candidates: [],
        pickerErrorText: ""
      });
    }
  },

  onPickerRetry() {
    if (this.data.pickerKind === "order") {
      const requestGeneration = ++pickerRequestGeneration;
      this.setData({ pickerLoading: true, pickerErrorText: "" });
      void this.loadOrderCandidates(requestGeneration);
      return;
    }
    if (this.data.pickerKind === "product" && this.data.pickerProductSource) {
      const requestGeneration = ++pickerRequestGeneration;
      this.setData({ pickerLoading: true, pickerErrorText: "" });
      void this.loadProductCandidates(
        this.data.pickerProductSource,
        requestGeneration
      );
    }
  },

  pruneLocallyHandledMessages() {
    const now = Date.now();
    locallyHandledMessageIds.forEach((expiresAt, messageId) => {
      if (expiresAt <= now) {
        locallyHandledMessageIds.delete(messageId);
      }
    });
  },

  beginLocalMutation() {
    localMutationCount += 1;
    conversationMutationEpoch += 1;
  },

  endLocalMutation() {
    localMutationCount = Math.max(0, localMutationCount - 1);
    if (localMutationCount > 0) {
      return;
    }
    this.pruneLocallyHandledMessages();
    const needsRefresh =
      pendingRealtimeChangeWithoutMessage ||
      Array.from(pendingRealtimeMessageIds).some(
        (messageId) => !locallyHandledMessageIds.has(messageId)
      );
    pendingRealtimeMessageIds.clear();
    pendingRealtimeChangeWithoutMessage = false;
    if ((needsRefresh || refreshQueued) && pageActive) {
      refreshQueued = false;
      void this.refreshConversation(true);
    }
  },

  appendLocallySentMessage(
    message: CustomerServiceMessage,
    pendingMessageId?: number
  ) {
    locallyHandledMessageIds.set(message.messageId, Date.now() + 15_000);
    imageMessages.set(message.messageId, message);
    latestPersistedMessageId = message.messageId;
    const pendingView = pendingMessageId === undefined
      ? undefined
      : this.data.messages.find((view) => view.messageId === pendingMessageId);
    if (pendingView) {
      rememberMessageTimeVisibility(message, pendingView.showTime);
      messageRenderKeyById.set(message.messageId, pendingView.renderKey);
    }
    const serverView = messageViews([message], pendingView)[0];
    let replaced = false;
    const messages = this.data.messages
      .filter((view) => view.messageId !== message.messageId)
      .map((view) => {
        if (view.messageId !== pendingMessageId) {
          return view;
        }
        replaced = true;
        return {
          ...serverView,
          showTime: view.showTime,
          sending: false
        };
      });
    if (!replaced) {
      messages.push(serverView);
    }
    if (pendingMessageId !== undefined) {
      pendingTextMessages.delete(pendingMessageId);
      pendingTextViews.delete(pendingMessageId);
      pendingImageViews.delete(pendingMessageId);
      pendingImageRequests.delete(pendingMessageId);
      imageTempPaths.delete(pendingMessageId);
      messageRenderKeyById.delete(pendingMessageId);
    }
    const wasDraft = this.data.conversationStatus === "DRAFT";
    const conversationStatus = (
      wasDraft ? "WAITING" : this.data.conversationStatus
    ) as CustomerServiceConversation["status"];
    this.setData({
      messages,
      conversationStatus
    });
  },

  async onImageTap(event: DatasetEvent) {
    const messageId = positiveId(event.currentTarget.dataset.id);
    const message = imageMessages.get(messageId);
    if (!message) {
      return;
    }
    let originalPath = originalImageTempPaths.get(messageId);
    if (!originalPath) {
      wx.showLoading({ title: "正在加载原图", mask: true });
      try {
        originalPath = await downloadCustomerServiceOriginalImage(message);
        originalImageTempPaths.set(messageId, originalPath);
      } catch (error) {
        wx.showToast({
          title: errorMessage(error, "原图加载失败，请重试"),
          icon: "none"
        });
        return;
      } finally {
        wx.hideLoading();
      }
    }
    wx.previewImage({ current: originalPath, urls: [originalPath] });
  },

  onOrderCardTap(event: DatasetEvent) {
    const orderId = positiveId(event.currentTarget.dataset.id);
    if (orderId) {
      wx.navigateTo({ url: buildOrderDetailUrl(orderId) });
    }
  },

  onProductCardTap(event: DatasetEvent) {
    const productId = positiveId(event.currentTarget.dataset.id);
    if (productId) {
      wx.navigateTo({ url: `/pages/product/detail/detail?id=${productId}` });
    }
  }
});
