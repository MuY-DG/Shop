import type {
  CustomerServiceConsultationContext,
  CustomerServiceConversation,
  CustomerServiceLinkedOrder,
  CustomerServiceLinkedProduct,
  CustomerServiceMessage,
  RealtimeEvent
} from "../../../types/api";
import { ensureAppLogin } from "../../../services/auth";
import {
  type CustomerServiceContextType,
  downloadCustomerServiceImage,
  getCustomerServiceConversation,
  getCustomerServiceOrderCandidates,
  getCustomerServiceProductCandidates,
  linkCustomerServiceOrder,
  linkCustomerServiceProduct,
  openCustomerServiceConversation,
  sendCustomerServiceMessage,
  uploadCustomerServiceImage
} from "../../../services/customer-service";
import {
  createAppRealtimeConnection,
  type AppRealtimeConnection
} from "../../../services/realtime";

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface MessageView extends CustomerServiceMessage {
  isMine: boolean;
  isSystem: boolean;
  timeText: string;
  imagePath: string;
  orderPriceText: string;
  productPriceText: string;
}

interface LinkedOrderView extends CustomerServiceLinkedOrder {
  payableAmountText: string;
}

interface LinkedProductView extends CustomerServiceLinkedProduct {
  priceText: string;
}

interface ContextView {
  type: "ORDER" | "PRODUCT";
  resourceId: number;
  label: string;
  title: string;
  subtitle: string;
  image: string;
  priceText: string;
}

interface ResourceCandidateView {
  type: "ORDER" | "PRODUCT";
  resourceId: number;
  title: string;
  subtitle: string;
  image: string;
  priceText: string;
}

interface CustomerServicePageData {
  loading: boolean;
  sending: boolean;
  connected: boolean;
  errorText: string;
  entryContextType: CustomerServiceContextType;
  entryContextId: number;
  conversation: CustomerServiceConversation | null;
  currentContext: ContextView | null;
  messages: MessageView[];
  linkedOrders: LinkedOrderView[];
  linkedProducts: LinkedProductView[];
  messageDraft: string;
  messageAnchor: string;
  statusText: string;
  statusHint: string;
  pickerType: "" | "ORDER" | "PRODUCT";
  pickerTitle: string;
  pickerLoading: boolean;
  resourceSending: boolean;
  resourceCandidates: ResourceCandidateView[];
}

let realtimeConnection: AppRealtimeConnection | null = null;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
const imagePathCache = new Map<number, string>();
const imageLoadFailures = new Set<number>();

function parsePositiveNumber(value: string | undefined): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function parseContextType(value: string | undefined): CustomerServiceContextType {
  const normalized = (value || "").toUpperCase();
  return normalized === "ORDER" || normalized === "PRODUCT" ? normalized : "GENERAL";
}

function formatTime(value: string): string {
  return value.replace("T", " ").slice(5, 16);
}

function formatCent(value: number | null): string {
  return value === null ? "" : `¥${(value / 100).toFixed(2)}`;
}

function formatProductPrice(product: CustomerServiceLinkedProduct | null): string {
  if (!product || product.minPriceCent === null) {
    return "";
  }
  if (product.maxPriceCent !== null && product.maxPriceCent !== product.minPriceCent) {
    return `${formatCent(product.minPriceCent)} 起`;
  }
  return formatCent(product.minPriceCent);
}

function messageViews(messages: CustomerServiceMessage[]): MessageView[] {
  return messages.map((message) => ({
    ...message,
    isMine: message.senderType === "APP_USER",
    isSystem: message.senderType === "SYSTEM",
    timeText: formatTime(message.createdAt),
    imagePath: imagePathCache.get(message.messageId) || "",
    orderPriceText: message.order ? formatCent(message.order.payableAmountCent) : "",
    productPriceText: formatProductPrice(message.product)
  }));
}

function linkedOrderViews(orders: CustomerServiceLinkedOrder[]): LinkedOrderView[] {
  return orders.map((order) => ({
    ...order,
    payableAmountText: formatCent(order.payableAmountCent)
  }));
}

function linkedProductViews(products: CustomerServiceLinkedProduct[]): LinkedProductView[] {
  return products.map((product) => ({
    ...product,
    priceText: formatProductPrice(product)
  }));
}

function contextView(context: CustomerServiceConsultationContext): ContextView | null {
  if (context.type === "ORDER" && context.order) {
    return {
      type: "ORDER",
      resourceId: context.order.orderId,
      label: "本次咨询订单",
      title: context.order.orderNo,
      subtitle: `${context.order.primaryProductTitle || "订单商品"} · 共 ${context.order.itemCount} 件`,
      image: context.order.primaryProductImage || "",
      priceText: formatCent(context.order.payableAmountCent)
    };
  }
  if (context.type === "PRODUCT" && context.product) {
    return {
      type: "PRODUCT",
      resourceId: context.product.productId,
      label: "本次咨询商品",
      title: context.product.title,
      subtitle: "客服将围绕此商品为你解答",
      image: context.product.image,
      priceText: formatProductPrice(context.product)
    };
  }
  return null;
}

function statusCopy(conversation: CustomerServiceConversation): {
  statusText: string;
  statusHint: string;
} {
  if (conversation.status === "DRAFT") {
    return { statusText: "准备咨询", statusHint: "发送消息后，客服会收到并接入本次咨询" };
  }
  if (conversation.status === "WAITING") {
    return { statusText: "等待客服接入", statusHint: "已进入接待队列，客服会尽快回复" };
  }
  if (conversation.status === "ACTIVE") {
    return {
      statusText: "客服接待中",
      statusHint: conversation.assignedAdminDisplayName
        ? `${conversation.assignedAdminDisplayName} 正在为你服务`
        : "客服正在为你服务"
    };
  }
  return { statusText: "本次咨询已结束", statusHint: "再次进入或发送消息会开始新的咨询" };
}

function toErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function chooseChatImages(): Promise<string[]> {
  return new Promise((resolve, reject) => {
    wx.chooseMedia({
      count: 3,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      sizeType: ["compressed"],
      success: (result) => resolve(
        result.tempFiles
          .map((file) => file.tempFilePath)
          .filter((filePath) => filePath.length > 0)
      ),
      fail: (error) => {
        if (error.errMsg && error.errMsg.includes("cancel")) {
          resolve([]);
          return;
        }
        reject(new Error(error.errMsg || "选择图片失败"));
      }
    });
  });
}

function orderCandidates(orders: CustomerServiceLinkedOrder[]): ResourceCandidateView[] {
  return orders.map((order) => ({
    type: "ORDER",
    resourceId: order.orderId,
    title: `订单 ${order.orderNo}`,
    subtitle: `${order.primaryProductTitle || "订单商品"} · 共 ${order.itemCount} 件`,
    image: order.primaryProductImage || "",
    priceText: formatCent(order.payableAmountCent)
  }));
}

function productCandidates(products: CustomerServiceLinkedProduct[]): ResourceCandidateView[] {
  return products.map((product) => ({
    type: "PRODUCT",
    resourceId: product.productId,
    title: product.title,
    subtitle: "在售商品",
    image: product.image,
    priceText: formatProductPrice(product)
  }));
}

Page<CustomerServicePageData, WechatMiniprogram.Page.CustomOption>({
  data: {
    loading: false,
    sending: false,
    connected: false,
    errorText: "",
    entryContextType: "GENERAL",
    entryContextId: 0,
    conversation: null,
    currentContext: null,
    messages: [],
    linkedOrders: [],
    linkedProducts: [],
    messageDraft: "",
    messageAnchor: "",
    statusText: "正在连接",
    statusHint: "请稍候",
    pickerType: "",
    pickerTitle: "",
    pickerLoading: false,
    resourceSending: false,
    resourceCandidates: []
  },
  onLoad(query: Record<string, string | undefined>) {
    imagePathCache.clear();
    imageLoadFailures.clear();
    const legacyOrderId = parsePositiveNumber(query.order_id);
    const entryContextType = legacyOrderId ? "ORDER" : parseContextType(query.context_type);
    const entryContextId = legacyOrderId || parsePositiveNumber(query.context_id);
    this.setData({
      entryContextType: entryContextId ? entryContextType : "GENERAL",
      entryContextId
    });
  },
  async onShow() {
    if (this.data.conversation) {
      this.startConversationUpdates();
      await this.refreshConversation();
      return;
    }
    await this.initializeConversation();
  },
  onHide() {
    this.stopRealtime();
  },
  onUnload() {
    this.stopRealtime();
    imagePathCache.clear();
    imageLoadFailures.clear();
  },
  async onPullDownRefresh() {
    await this.refreshConversation();
    wx.stopPullDownRefresh();
  },
  async initializeConversation() {
    this.setData({ loading: true, errorText: "" });
    try {
      await ensureAppLogin();
      const conversation = await openCustomerServiceConversation({
        contextType: this.data.entryContextType,
        contextId: this.data.entryContextId || undefined
      });
      this.applyConversation(conversation);
      this.startConversationUpdates();
    } catch (error) {
      this.setData({ errorText: toErrorMessage(error, "客服会话加载失败") });
    } finally {
      this.setData({ loading: false });
    }
  },
  async refreshConversation() {
    try {
      const conversation = await getCustomerServiceConversation();
      if (conversation) {
        this.applyConversation(conversation);
      }
    } catch {
      // Realtime and manual refresh may recover; keep the current conversation visible.
    }
  },
  applyConversation(conversation: CustomerServiceConversation) {
    const messages = messageViews(conversation.messages);
    this.setData({
      conversation,
      currentContext: contextView(conversation.currentContext),
      messages,
      linkedOrders: linkedOrderViews(conversation.linkedOrders),
      linkedProducts: linkedProductViews(conversation.linkedProducts),
      messageAnchor: messages.length ? `message-${messages[messages.length - 1].messageId}` : "",
      ...statusCopy(conversation)
    });
    void this.loadMissingImages(messages);
  },
  async loadMissingImages(messages: MessageView[]) {
    const pending = messages.filter(
      (message) =>
        message.messageType === "IMAGE" &&
        !message.imagePath &&
        !imageLoadFailures.has(message.messageId)
    );
    await Promise.all(pending.map(async (message) => {
      try {
        const imagePath = await downloadCustomerServiceImage(message.messageId);
        imagePathCache.set(message.messageId, imagePath);
        this.setData({
          messages: this.data.messages.map((current) =>
            current.messageId === message.messageId ? { ...current, imagePath } : current
          )
        });
      } catch {
        imageLoadFailures.add(message.messageId);
      }
    }));
  },
  startConversationUpdates() {
    this.startRealtime();
    if (pollTimer) {
      clearInterval(pollTimer);
    }
    pollTimer = setInterval(() => void this.refreshConversation(), 5000);
  },
  startRealtime() {
    realtimeConnection?.stop();
    realtimeConnection = createAppRealtimeConnection({
      onEvent: (event: RealtimeEvent) => {
        if (event.type !== "CUSTOMER_SERVICE_CONVERSATION_UPDATED") {
          return;
        }
        if (refreshTimer) {
          clearTimeout(refreshTimer);
        }
        refreshTimer = setTimeout(() => {
          refreshTimer = null;
          void this.refreshConversation();
        }, 200);
      },
      onStatusChange: (connected) => this.setData({ connected })
    });
    void realtimeConnection.start();
  },
  stopRealtime() {
    realtimeConnection?.stop();
    realtimeConnection = null;
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
    if (refreshTimer) {
      clearTimeout(refreshTimer);
      refreshTimer = null;
    }
  },
  onMessageInput(event: WechatMiniprogram.Input) {
    this.setData({ messageDraft: event.detail.value });
  },
  async onSendTap() {
    const content = this.data.messageDraft.trim();
    if (!content || this.data.sending) {
      return;
    }
    this.setData({ sending: true, messageDraft: "" });
    try {
      await sendCustomerServiceMessage({
        content,
        clientMessageId: `app-${Date.now()}-${Math.random().toString(16).slice(2)}`
      });
      await this.refreshConversation();
    } catch (error) {
      this.setData({ messageDraft: content });
      wx.showToast({ title: toErrorMessage(error, "消息发送失败"), icon: "none" });
    } finally {
      this.setData({ sending: false });
    }
  },
  onMoreTap() {
    if (this.data.sending || this.data.resourceSending) {
      return;
    }
    wx.showActionSheet({
      itemList: ["发送图片", "发送订单", "发送商品"],
      success: (result) => {
        if (result.tapIndex === 0) {
          void this.sendImages();
        } else if (result.tapIndex === 1) {
          void this.openResourcePicker("ORDER");
        } else if (result.tapIndex === 2) {
          void this.openResourcePicker("PRODUCT");
        }
      }
    });
  },
  async sendImages() {
    try {
      const filePaths = await chooseChatImages();
      if (!filePaths.length) {
        return;
      }
      this.setData({ sending: true });
      for (const filePath of filePaths) {
        await uploadCustomerServiceImage(filePath);
      }
      await this.refreshConversation();
    } catch (error) {
      wx.showToast({ title: toErrorMessage(error, "图片发送失败"), icon: "none" });
    } finally {
      this.setData({ sending: false });
    }
  },
  async openResourcePicker(type: "ORDER" | "PRODUCT") {
    this.setData({
      pickerType: type,
      pickerTitle: type === "ORDER" ? "选择订单" : "选择商品",
      pickerLoading: true,
      resourceCandidates: []
    });
    try {
      const candidates = type === "ORDER"
        ? orderCandidates(await getCustomerServiceOrderCandidates())
        : productCandidates(await getCustomerServiceProductCandidates());
      this.setData({ resourceCandidates: candidates });
    } catch (error) {
      this.closeResourcePicker();
      wx.showToast({ title: toErrorMessage(error, "列表加载失败"), icon: "none" });
    } finally {
      this.setData({ pickerLoading: false });
    }
  },
  closeResourcePicker() {
    if (this.data.resourceSending) {
      return;
    }
    this.setData({ pickerType: "", resourceCandidates: [] });
  },
  async onResourceCandidateTap(event: DatasetEvent) {
    const resourceId = Number(event.currentTarget.dataset.id);
    const type = event.currentTarget.dataset.type;
    if (
      this.data.resourceSending ||
      !Number.isFinite(resourceId) ||
      resourceId <= 0 ||
      (type !== "ORDER" && type !== "PRODUCT")
    ) {
      return;
    }
    this.setData({ resourceSending: true });
    try {
      if (type === "ORDER") {
        await linkCustomerServiceOrder(resourceId);
      } else {
        await linkCustomerServiceProduct(resourceId);
      }
      this.setData({ pickerType: "", resourceCandidates: [] });
      await this.refreshConversation();
    } catch (error) {
      wx.showToast({ title: toErrorMessage(error, "发送失败"), icon: "none" });
    } finally {
      this.setData({ resourceSending: false });
    }
  },
  onResourceCardTap(event: DatasetEvent) {
    const resourceId = Number(event.currentTarget.dataset.id);
    const type = event.currentTarget.dataset.type;
    if (!Number.isFinite(resourceId) || resourceId <= 0) {
      return;
    }
    if (type === "ORDER") {
      wx.navigateTo({ url: `/pages/order/detail/detail?order_id=${resourceId}` });
    } else if (type === "PRODUCT") {
      wx.navigateTo({ url: `/pages/product/detail/detail?id=${resourceId}` });
    }
  },
  onImageTap(event: DatasetEvent) {
    const current = String(event.currentTarget.dataset.src || "");
    if (!current) {
      return;
    }
    const urls = this.data.messages
      .map((message) => message.imagePath)
      .filter((imagePath) => imagePath.length > 0);
    wx.previewImage({ current, urls });
  }
});
