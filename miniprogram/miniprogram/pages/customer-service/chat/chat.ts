import {
  buildCustomerServiceUrl,
  customerServiceEntryContext,
  customerServiceMessageId,
  customerServiceOrderStatusText,
  customerServicePriceRange,
  formatCustomerServiceMoney,
  parseCustomerServiceDate,
  preserveCustomerServiceHistoryScrollTop,
  shouldShowCustomerServiceMessageTime,
  shouldShowCustomerServiceCommonQuestions,
  type CustomerServiceEntryContext
} from "../../../features/customer-service";
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
  };
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
  avatarText: string;
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
const HISTORY_PAGE_SIZE = 50;
const HISTORY_REARM_SCROLL_TOP = 160;
const HISTORY_SCROLL_TARGET_IDLE = "message-list-history-idle";
const MESSAGE_LIST_BOTTOM_SCROLL_TOP = 1_000_000_000;
const MESSAGE_LIST_BOTTOM_IDS = [
  "message-list-bottom-a",
  "message-list-bottom-b"
] as const;
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
let nextBottomAnchorIndex = 0;
let historyUpperArmed = true;
let messageListScrollTop = 0;
let messageListFollowingLatest = true;

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

function nextMessageListBottomId(): string {
  const target = MESSAGE_LIST_BOTTOM_IDS[nextBottomAnchorIndex];
  nextBottomAnchorIndex = (nextBottomAnchorIndex + 1) % MESSAGE_LIST_BOTTOM_IDS.length;
  return target;
}

function nextMessageListBottomScrollTop(current: number): number {
  return current === MESSAGE_LIST_BOTTOM_SCROLL_TOP
    ? MESSAGE_LIST_BOTTOM_SCROLL_TOP - 1
    : MESSAGE_LIST_BOTTOM_SCROLL_TOP;
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

function messageViews(
  messages: CustomerServiceMessage[],
  previousMessage?: TimedMessage
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
    const senderAvatar = isMine ? currentUserAvatar || avatar : avatar;
    const renderKey = messageRenderKeyById.get(message.messageId) ??
      (clientMessageId
        ? `client-${clientMessageId}`
        : `message-${message.messageId}`);
    messageRenderKeyById.set(message.messageId, renderKey);
    const order = message.order;
    const product = message.product;
    const cachedImage = imageTempPaths.get(message.messageId) ?? "";
    return {
      messageId: message.messageId,
      renderKey,
      consultationNo: message.consultationNo,
      createdAt: message.createdAt,
      senderType: message.senderType,
      isMine,
      isSystem: message.messageType === "SYSTEM",
      senderName,
      senderAvatar,
      avatarText: (senderName || "客服").slice(0, 1),
      messageType: message.messageType,
      content: cleanText(message.content),
      showTime: stableMessageTimeVisibility(
        message,
        messages[index - 1] ?? previousMessage
      ),
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
      )
    };
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
    hasMoreHistory: false,
    historyExhausted: false,
    scrollTarget: "",
    messageScrollTop: 0,
    scrollWithAnimation: false,
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
    nextBottomAnchorIndex = 0;
    historyUpperArmed = true;
    messageListScrollTop = 0;
    messageListFollowingLatest = true;
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
    void this.refreshConversation(true);
  },

  onHide() {
    pageActive = false;
    panelInteractionGeneration += 1;
    stopLiveUpdates();
    this.setData({ historyLoading: false });
  },

  onUnload() {
    pageActive = false;
    initialized = false;
    initializeGeneration += 1;
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
    historyUpperArmed = true;
    messageListScrollTop = 0;
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
      this.setData({ loading: false, loaded: true, errorText: "" });
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
      this.setData({
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
      });
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
    const views = [
      ...messageViews(persistedMessages),
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
        scrollTarget: isInitialPositioning && views.length
          ? nextMessageListBottomId()
          : this.data.scrollTarget,
        messageScrollTop: isInitialPositioning && views.length
          ? nextMessageListBottomScrollTop(this.data.messageScrollTop)
          : this.data.messageScrollTop,
        scrollWithAnimation: isInitialPositioning
          ? false
          : this.data.scrollWithAnimation,
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
        if (isInitialPositioning) {
          const requestGeneration = initializeGeneration;
          wx.nextTick(() => {
            if (requestGeneration === initializeGeneration && pageActive) {
              this.setData({
                scrollTarget: HISTORY_SCROLL_TARGET_IDLE,
                scrollWithAnimation: true
              });
            }
          });
        } else if (shouldScrollToLatest) {
          this.scrollToLatest();
        }
      }
    );
  },

  onMessageListScroll(event: ScrollEvent) {
    const nextScrollTop = Math.max(0, Number(event.detail.scrollTop) || 0);
    if (nextScrollTop < messageListScrollTop - 2) {
      messageListFollowingLatest = false;
    }
    messageListScrollTop = nextScrollTop;
    if (messageListScrollTop > HISTORY_REARM_SCROLL_TOP) {
      historyUpperArmed = true;
    }
  },

  onScrollToLower() {
    messageListFollowingLatest = true;
  },

  measureMessageTop(messageId: number): Promise<number | null> {
    return new Promise((resolve) => {
      wx.createSelectorQuery()
        .in(this)
        .select(`#message-${messageId}`)
        .boundingClientRect((rect) => {
          resolve(rect && typeof rect.top === "number" ? rect.top : null);
        })
        .exec();
    });
  },

  onScrollToUpper() {
    if (
      !historyUpperArmed ||
      this.data.historyLoading ||
      !this.data.hasMoreHistory
    ) {
      return;
    }
    historyUpperArmed = false;
    messageListFollowingLatest = false;
    void this.loadEarlierMessages();
  },

  onHistoryTap() {
    void this.loadEarlierMessages();
  },

  async loadEarlierMessages() {
    if (this.data.historyLoading || !this.data.hasMoreHistory) {
      return;
    }
    const persistedMessages = Array.from(imageMessages.values())
      .sort((left, right) => left.messageId - right.messageId);
    const firstMessage = persistedMessages[0];
    if (!firstMessage) {
      this.setData({ hasMoreHistory: false });
      return;
    }
    const requestGeneration = initializeGeneration;
    messageListFollowingLatest = false;
    this.setData({
      historyLoading: true,
      messageScrollTop: messageListScrollTop
    });
    try {
      const olderMessages = await getCustomerServiceMessages({
        beforeId: firstMessage.messageId,
        limit: HISTORY_PAGE_SIZE
      });
      if (requestGeneration !== initializeGeneration || !pageActive) {
        return;
      }
      const anchorTopBefore = await this.measureMessageTop(firstMessage.messageId);
      if (requestGeneration !== initializeGeneration || !pageActive) {
        return;
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
          historyLoading: false,
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
      const views = [
        ...messageViews(allPersistedMessages),
        ...pendingViews
      ];
      await new Promise<void>((resolve) => {
        this.setData(
          {
            messages: views,
            scrollWithAnimation: false,
            scrollTarget: HISTORY_SCROLL_TARGET_IDLE
          },
          () => wx.nextTick(resolve)
        );
      });
      if (requestGeneration !== initializeGeneration || !pageActive) {
        return;
      }
      const anchorTopAfter = await this.measureMessageTop(firstMessage.messageId);
      if (requestGeneration !== initializeGeneration || !pageActive) {
        return;
      }
      const canRestorePrecisely = anchorTopBefore !== null && anchorTopAfter !== null;
      const nextScrollTop = canRestorePrecisely
        ? preserveCustomerServiceHistoryScrollTop(
            messageListScrollTop,
            anchorTopBefore,
            anchorTopAfter
          )
        : messageListScrollTop;
      messageListScrollTop = nextScrollTop;
      this.setData(
        {
          historyLoading: false,
          hasMoreHistory: olderMessages.length >= HISTORY_PAGE_SIZE,
          historyExhausted: olderMessages.length < HISTORY_PAGE_SIZE,
          messageScrollTop: nextScrollTop,
          scrollTarget: canRestorePrecisely
            ? HISTORY_SCROLL_TARGET_IDLE
            : `message-${firstMessage.messageId}`
        },
        () => {
          this.observePrivateImages();
          wx.nextTick(() => {
            if (requestGeneration === initializeGeneration && pageActive) {
              this.setData({
                scrollTarget: HISTORY_SCROLL_TARGET_IDLE,
                scrollWithAnimation: true
              });
            }
          });
        }
      );
    } catch (error) {
      if (requestGeneration === initializeGeneration && pageActive) {
        this.setData({ historyLoading: false });
        wx.showToast({
          title: errorMessage(error, "历史消息加载失败，请重试"),
          icon: "none"
        });
      }
    }
  },

  observePrivateImages() {
    imageObserver?.disconnect();
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
        this.setData({
          messages: this.data.messages.map((view) => (
            view.messageId === messageId
              ? {
                  ...view,
                  imageUrl: tempFilePath,
                  imageFailed: false
                }
              : view
          ))
        });
      }
    } catch {
      if (pageActive) {
        this.setData({
          messages: this.data.messages.map((view) => (
            view.messageId === messageId
              ? { ...view, imageFailed: true }
              : view
          ))
        });
      }
    } finally {
      imageDownloads.delete(messageId);
    }
  },

  onInput(event: InputEvent) {
    this.setData({ inputValue: event.detail.value });
  },

  onInputFocus() {
    panelInteractionGeneration += 1;
    if (this.data.panelMode || this.data.pickerOpen) {
      this.setData(
        { panelMode: "", pickerOpen: false },
        () => this.positionLatestWithoutAnimation()
      );
      return;
    }
    this.positionLatestWithoutAnimation();
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
    this.setData({
      inputValue: clearInput ? "" : this.data.inputValue,
      panelMode: "",
      showCommonQuestions: fromCommonQuestion || commonQuestionEngaged,
      messages,
      scrollWithAnimation: true,
      scrollTarget: nextMessageListBottomId(),
      messageScrollTop: nextMessageListBottomScrollTop(this.data.messageScrollTop)
    });
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
    messageListFollowingLatest = true;
    this.setData({
      scrollWithAnimation: true,
      scrollTarget: nextMessageListBottomId(),
      messageScrollTop: nextMessageListBottomScrollTop(this.data.messageScrollTop)
    });
  },

  positionLatestWithoutAnimation() {
    messageListFollowingLatest = true;
    const requestGeneration = initializeGeneration;
    this.setData({
      scrollWithAnimation: false,
      scrollTarget: nextMessageListBottomId(),
      messageScrollTop: nextMessageListBottomScrollTop(this.data.messageScrollTop)
    }, () => {
      wx.nextTick(() => {
        if (requestGeneration === initializeGeneration && pageActive) {
          this.setData({ scrollWithAnimation: true });
        }
      });
    });
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
      this.setData({
        uploading: true,
        panelMode: "",
        showCommonQuestions: false,
        messages: nextMessages,
        scrollWithAnimation: true,
        scrollTarget: nextMessageListBottomId(),
        messageScrollTop: nextMessageListBottomScrollTop(this.data.messageScrollTop)
      });
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
      wx.navigateTo({ url: `/pages/order/detail/detail?id=${orderId}` });
    }
  },

  onProductCardTap(event: DatasetEvent) {
    const productId = positiveId(event.currentTarget.dataset.id);
    if (productId) {
      wx.navigateTo({ url: `/pages/product/detail/detail?id=${productId}` });
    }
  }
});
