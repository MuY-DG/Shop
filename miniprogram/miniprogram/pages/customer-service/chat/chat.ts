import {
  buildCustomerServiceUrl,
  customerServiceEntryContext,
  customerServiceMessageId,
  customerServiceOrderStatusText,
  customerServicePriceRange,
  customerServiceStatusHint,
  formatCustomerServiceMoney,
  type CustomerServiceEntryContext
} from "../../../features/customer-service";
import { getCartItems } from "../../../services/cart";
import {
  downloadCustomerServiceImage,
  downloadCustomerServiceOriginalImage,
  getCustomerServiceConversation,
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

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
      source?: "browse" | "favorite" | "cart";
    };
  };
}

interface MessageView {
  messageId: number;
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
  imageUploading: boolean;
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
const imageTempPaths = new Map<number, string>();
const originalImageTempPaths = new Map<number, string>();
const imageMessages = new Map<number, CustomerServiceMessage>();
const imageDownloads = new Set<number>();
const pendingImageViews = new Map<number, MessageView>();
const pendingTextMessages = new Map<number, CustomerServiceMessage>();
const pendingTextViews = new Map<number, MessageView>();
const pendingTextRequests = new Set<number>();
const locallyHandledMessageIds = new Map<number, number>();
const pendingRealtimeMessageIds = new Set<number>();

let pageActive = false;
let initialized = false;
let initializeGeneration = 0;
let refreshRunning = false;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let unsubscribeRealtime: (() => void) | null = null;
let unsubscribeRealtimeState: (() => void) | null = null;
let imageObserver: WechatMiniprogram.IntersectionObserver | null = null;
let entryContext: CustomerServiceEntryContext = { contextType: "GENERAL" };
let nextPendingMessageId = -1;
let localMutationCount = 0;
let pendingRealtimeChangeWithoutMessage = false;

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

function parsedDate(value: string): Date | null {
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date : null;
}

function messageTimeText(value: string): string {
  const date = parsedDate(value);
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

function shouldShowMessageTime(
  message: CustomerServiceMessage,
  previous?: CustomerServiceMessage
): boolean {
  if (!previous || message.consultationNo !== previous.consultationNo) {
    return true;
  }
  const currentDate = parsedDate(message.createdAt);
  const previousDate = parsedDate(previous.createdAt);
  return Boolean(
    currentDate &&
    previousDate &&
    currentDate.getTime() - previousDate.getTime() >= 5 * 60 * 1000
  );
}

function imageStyle(message: CustomerServiceMessage): string {
  const sourceWidth = message.image?.width ?? 4;
  const sourceHeight = message.image?.height ?? 3;
  const ratio = sourceWidth > 0 && sourceHeight > 0 ? sourceHeight / sourceWidth : 0.75;
  const width = 420;
  const height = Math.round(Math.min(520, Math.max(180, width * ratio)));
  return `width:${width}rpx;height:${height}rpx;`;
}

function messageViews(messages: CustomerServiceMessage[]): MessageView[] {
  return messages.map((message, index) => {
    const avatar = cleanText(message.senderAvatar);
    const order = message.order;
    const product = message.product;
    const cachedImage = imageTempPaths.get(message.messageId) ?? "";
    return {
      messageId: message.messageId,
      senderType: message.senderType,
      isMine: message.senderType === "APP_USER",
      isSystem: message.senderType === "SYSTEM" || message.messageType === "SYSTEM",
      senderName: cleanText(message.senderName) || "在线客服",
      senderAvatar: avatar,
      avatarText: (cleanText(message.senderName) || "客服").slice(0, 1),
      messageType: message.messageType,
      content: cleanText(message.content),
      showTime: shouldShowMessageTime(message, messages[index - 1]),
      timeText: messageTimeText(message.createdAt),
      imageUrl: cachedImage,
      imageStyle: imageStyle(message),
      imageFailed: false,
      imageUploading: false,
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
    statusHint: "发送消息后，客服会尽快接待",
    contextPreview: "",
    messages: [] as MessageView[],
    scrollTarget: "",
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
    entryContext = customerServiceEntryContext(options.contextType, options.contextId);
    initializeGeneration += 1;
    nextPendingMessageId = -1;
    localMutationCount = 0;
    pendingRealtimeChangeWithoutMessage = false;
    imageTempPaths.clear();
    originalImageTempPaths.clear();
    imageMessages.clear();
    imageDownloads.clear();
    pendingImageViews.clear();
    pendingTextMessages.clear();
    pendingTextViews.clear();
    pendingTextRequests.clear();
    locallyHandledMessageIds.clear();
    pendingRealtimeMessageIds.clear();
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
    stopLiveUpdates();
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
    pendingTextMessages.clear();
    pendingTextViews.clear();
    pendingTextRequests.clear();
    locallyHandledMessageIds.clear();
    pendingRealtimeMessageIds.clear();
    localMutationCount = 0;
    pendingRealtimeChangeWithoutMessage = false;
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
    if (!pageActive || refreshRunning) {
      return;
    }
    refreshRunning = true;
    if (!silent) {
      this.setData({ loading: !this.data.loaded, errorText: "" });
    }
    try {
      const conversation = await getCustomerServiceConversation();
      if (!pageActive || !conversation) {
        return;
      }
      this.applyConversation(conversation);
      this.setData({ loading: false, loaded: true, errorText: "" });
    } catch (error) {
      if (pageActive && !silent) {
        this.setData({
          loading: false,
          errorText: errorMessage(error, "消息刷新失败，请稍后重试")
        });
      }
    } finally {
      refreshRunning = false;
    }
  },

  applyConversation(conversation: CustomerServiceConversation) {
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
    imageMessages.clear();
    rawMessages.forEach((message) => imageMessages.set(message.messageId, message));
    const pendingViews = [
      ...Array.from(pendingTextViews.values()),
      ...Array.from(pendingImageViews.values())
    ].sort((left, right) => right.messageId - left.messageId);
    const views = [
      ...messageViews(rawMessages),
      ...pendingViews
    ];
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
        statusHint: customerServiceStatusHint(
          conversation.status,
          conversation.assignedAdminDisplayName
        ),
        contextPreview,
        messages: views,
        scrollTarget: views.length ? `message-${views[views.length - 1].messageId}` : ""
      },
      () => {
        this.observePrivateImages();
      }
    );
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
    this.setData({ inputValue: event.detail.value.slice(0, 2000) });
  },

  onInputFocus() {
    this.closePanels();
  },

  onInputConfirm() {
    void this.sendText();
  },

  onSendTap() {
    void this.sendText();
  },

  sendText() {
    const content = this.data.inputValue.trim();
    if (!content) {
      return;
    }
    const messageId = nextPendingMessageId;
    nextPendingMessageId -= 1;
    const pendingMessage: CustomerServiceMessage = {
      messageId,
      conversationId: this.data.conversationId,
      consultationNo: 1,
      senderType: "APP_USER",
      senderName: "我",
      messageType: "TEXT",
      content,
      clientMessageId: customerServiceMessageId(),
      createdAt: new Date().toISOString()
    };
    const pendingView = messageViews([pendingMessage])[0];
    pendingTextMessages.set(messageId, pendingMessage);
    pendingTextViews.set(messageId, pendingView);
    const messages = [...this.data.messages, pendingView];
    this.setData({
      inputValue: "",
      panelMode: "",
      messages,
      scrollTarget: `message-${messageId}`
    });
    void this.deliverPendingText(messageId);
  },

  async deliverPendingText(messageId: number) {
    const pendingMessage = pendingTextMessages.get(messageId);
    if (!pendingMessage || pendingTextRequests.has(messageId)) {
      return;
    }
    pendingTextRequests.add(messageId);
    const pendingView = pendingTextViews.get(messageId);
    if (pendingView?.sendFailed) {
      pendingView.sendFailed = false;
      this.syncPendingTextView(pendingView);
    }
    this.beginLocalMutation();
    try {
      const message = await sendCustomerServiceMessage({
        content: pendingMessage.content,
        clientMessageId: pendingMessage.clientMessageId || customerServiceMessageId()
      });
      pendingTextMessages.delete(messageId);
      pendingTextViews.delete(messageId);
      this.appendLocallySentMessage(message, messageId);
    } catch {
      const failedView = pendingTextViews.get(messageId);
      if (failedView) {
        failedView.sendFailed = true;
        this.syncPendingTextView(failedView);
      }
    } finally {
      pendingTextRequests.delete(messageId);
      this.endLocalMutation();
    }
  },

  syncPendingTextView(pendingView: MessageView) {
    if (!pageActive) {
      return;
    }
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
    if (this.data.uploading) {
      return;
    }
    wx.hideKeyboard();
    this.setData({
      panelMode: this.data.panelMode ? "" : "main",
      pickerOpen: false
    });
  },

  onProductActionTap() {
    this.setData({ panelMode: "product" });
  },

  onPanelBackTap() {
    this.setData({ panelMode: "main" });
  },

  closePanels() {
    this.setData({ panelMode: "", pickerOpen: false });
  },

  onAlbumTap() {
    void this.selectAndUploadImages("album", 9);
  },

  onCameraTap() {
    void this.selectAndUploadImages("camera", 1);
  },

  async selectAndUploadImages(sourceType: "album" | "camera", count: number) {
    if (this.data.uploading) {
      return;
    }
    let mutationStarted = false;
    try {
      const filePaths = await chooseChatImages(sourceType, count);
      if (!filePaths.length) {
        return;
      }
      const pendingUploads = filePaths.map((filePath, index) => {
        const messageId = nextPendingMessageId;
        nextPendingMessageId -= 1;
        imageTempPaths.set(messageId, filePath);
        const pendingMessage: CustomerServiceMessage = {
          messageId,
          conversationId: this.data.conversationId,
          consultationNo: 1,
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
          ...messageViews([pendingMessage])[0],
          showTime: index === 0,
          imageUploading: true
        };
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
        messages: nextMessages,
        scrollTarget: `message-${pendingUploads[pendingUploads.length - 1].messageId}`
      });
      let successCount = 0;
      let lastFailure = "";
      for (const pending of pendingUploads) {
        try {
          const message = await uploadCustomerServiceImage(pending.filePath);
          imageTempPaths.set(message.messageId, pending.filePath);
          originalImageTempPaths.set(message.messageId, pending.filePath);
          imageMessages.set(message.messageId, message);
          successCount += 1;
          this.appendLocallySentMessage(message, pending.messageId);
        } catch (error) {
          lastFailure = isApiError(error) && error.code === 800002
            ? "图片需为常见格式，且不超过 5MB"
            : errorMessage(error, "图片发送失败，请重试");
          this.removePendingImage(pending.messageId);
        }
      }
      this.setData({ uploading: false });
      if (successCount !== filePaths.length) {
        wx.showToast({
          title: successCount
            ? `${successCount} 张已发送，部分失败`
            : lastFailure || "图片发送失败，请重试",
          icon: "none"
        });
      }
    } catch (error) {
      this.setData({ uploading: false });
      wx.showToast({
        title: errorMessage(error, "图片选择失败"),
        icon: "none"
      });
    } finally {
      if (mutationStarted) {
        this.endLocalMutation();
      }
    }
  },

  onOrderActionTap() {
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
    void this.loadOrderCandidates();
  },

  async loadOrderCandidates() {
    try {
      const orders = await getCustomerServiceOrderCandidates();
      if (pageActive && this.data.pickerKind === "order") {
        this.setData({
          pickerLoading: false,
          candidates: orderCandidateViews(orders),
          pickerErrorText: ""
        });
      }
    } catch (error) {
      if (pageActive && this.data.pickerKind === "order") {
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
    void this.loadProductCandidates(source);
  },

  async loadProductCandidates(source: ProductSource) {
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
      if (pageActive && this.data.pickerKind === "product") {
        this.setData({
          pickerLoading: false,
          candidates,
          pickerErrorText: ""
        });
      }
    } catch (error) {
      if (pageActive && this.data.pickerKind === "product") {
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
    void this.sendCandidate(id, this.data.pickerKind);
  },

  async sendCandidate(id: number, kind: Exclude<PickerKind, "">) {
    this.setData({ candidateSendingId: id });
    try {
      if (kind === "order") {
        await sendCustomerServiceOrder(id);
      } else {
        await sendCustomerServiceProduct(id);
      }
      this.setData({
        candidateSendingId: 0,
        pickerOpen: false,
        pickerKind: "",
        pickerProductSource: "",
        candidates: []
      });
      await this.refreshConversation(true);
    } catch (error) {
      this.setData({ candidateSendingId: 0 });
      wx.showToast({
        title: errorMessage(error, kind === "order" ? "订单发送失败" : "商品发送失败"),
        icon: "none"
      });
    }
  },

  onPickerCloseTap() {
    if (!this.data.candidateSendingId) {
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
      this.setData({ pickerLoading: true, pickerErrorText: "" });
      void this.loadOrderCandidates();
      return;
    }
    if (this.data.pickerKind === "product" && this.data.pickerProductSource) {
      this.setData({ pickerLoading: true, pickerErrorText: "" });
      void this.loadProductCandidates(this.data.pickerProductSource);
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
    if (needsRefresh && pageActive) {
      void this.refreshConversation(true);
    }
  },

  removePendingImage(messageId: number) {
    pendingImageViews.delete(messageId);
    imageTempPaths.delete(messageId);
    this.setData({
      messages: this.data.messages.filter((message) => message.messageId !== messageId)
    });
  },

  appendLocallySentMessage(
    message: CustomerServiceMessage,
    pendingMessageId?: number
  ) {
    locallyHandledMessageIds.set(message.messageId, Date.now() + 15_000);
    const serverView = messageViews([message])[0];
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
          imageUploading: false
        };
      });
    if (!replaced) {
      messages.push(serverView);
    }
    if (pendingMessageId !== undefined) {
      pendingTextMessages.delete(pendingMessageId);
      pendingTextViews.delete(pendingMessageId);
      pendingImageViews.delete(pendingMessageId);
      imageTempPaths.delete(pendingMessageId);
    }
    const wasDraft = this.data.conversationStatus === "DRAFT";
    const conversationStatus = (
      wasDraft ? "WAITING" : this.data.conversationStatus
    ) as CustomerServiceConversation["status"];
    this.setData({
      messages,
      conversationStatus,
      statusHint: wasDraft
        ? customerServiceStatusHint(conversationStatus)
        : this.data.statusHint,
      scrollTarget: `message-${messages[messages.length - 1].messageId}`
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
