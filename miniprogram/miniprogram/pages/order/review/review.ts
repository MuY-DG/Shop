import {
  normalizeReviewContent
} from "../../../features/product-review";
import { createPageOperationGuard } from "../../../features/order-center";
import {
  createProductReview,
  uploadProductReviewImage
} from "../../../services/product";
import { getOrderDetail } from "../../../services/order";
import type { AppOrderDetailResponse } from "../../../types/order";
import { isApiError } from "../../../utils/api-error";
import {
  buildPendingOrderReviewItems,
  isReviewableOrderStatus,
  parseReviewOrderId,
  reviewProgressText,
  updateOrderReviewDraft,
  type OrderReviewItemView,
  type OrderReviewSourceItem
} from "./model";

type ReviewOrderDetail = Omit<AppOrderDetailResponse, "items"> & {
  items: OrderReviewSourceItem[];
};

interface DatasetEvent {
  currentTarget: {
    dataset: {
      orderItemId?: number | string;
      rating?: number | string;
      index?: number | string;
    };
  };
}

interface LocalReviewImage {
  tempFilePath: string;
  size: number;
}

interface TextareaInputEvent {
  detail: {
    value: string;
  };
}

interface SwitchChangeEvent {
  detail: {
    value: boolean;
  };
}

let latestRequest = 0;
const reviewOperationGuard = createPageOperationGuard();
const MAX_REVIEW_IMAGE_COUNT = 6;
const MAX_REVIEW_IMAGE_SIZE = 5 * 1024 * 1024;

function chooseReviewImages(count: number): Promise<LocalReviewImage[]> {
  return new Promise((resolve, reject) => {
    wx.chooseMedia({
      count,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      sizeType: ["compressed"],
      success: (result) => resolve(result.tempFiles
        .map((file) => ({
          tempFilePath: file.tempFilePath,
          size: Number(file.size) || 0
        }))
        .filter((file) => Boolean(file.tempFilePath))),
      fail: (error) => {
        if (error.errMsg.includes("cancel")) {
          resolve([]);
          return;
        }
        reject(new Error(error.errMsg || "选择图片失败"));
      }
    });
  });
}

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function selectedItem(
  items: readonly OrderReviewItemView[],
  orderItemId: number
): OrderReviewItemView | null {
  return items.find((item) => item.orderItemId === orderItemId) ?? null;
}

Page({
  data: {
    lifecycleToken: 0,
    orderId: 0,
    detail: null as ReviewOrderDetail | null,
    pendingItems: [] as OrderReviewItemView[],
    selectedOrderItemId: 0,
    selectedItem: null as OrderReviewItemView | null,
    totalReviewCount: 0,
    submittedCount: 0,
    progressText: "",
    loading: true,
    loaded: false,
    errorText: "",
    blockingText: "",
    completed: false,
    submittingOrderItemId: 0,
    uploadingOrderItemId: 0
  },

  onLoad(query: Record<string, string | undefined>) {
    const lifecycleToken = reviewOperationGuard.mount();
    const orderId = parseReviewOrderId(query.order_id);
    if (!orderId) {
      this.setData({
        lifecycleToken,
        loading: false,
        loaded: false,
        errorText: "订单参数无效"
      });
      return;
    }
    this.setData({ lifecycleToken, orderId });
    void this.loadOrder();
  },

  onUnload() {
    reviewOperationGuard.unmount(this.data.lifecycleToken);
    latestRequest += 1;
  },

  onRetry() {
    void this.loadOrder();
  },

  async loadOrder() {
    if (
      !this.data.orderId ||
      this.data.submittingOrderItemId ||
      this.data.uploadingOrderItemId
    ) {
      return;
    }
    const requestId = ++latestRequest;
    this.setData({ loading: true, errorText: "", blockingText: "" });
    try {
      const detail = await getOrderDetail(this.data.orderId) as ReviewOrderDetail;
      if (requestId !== latestRequest) {
        return;
      }
      if (!isReviewableOrderStatus(detail.status)) {
        this.setData({
          detail,
          pendingItems: [],
          selectedOrderItemId: 0,
          selectedItem: null,
          loading: false,
          loaded: true,
          completed: false,
          blockingText: "订单确认收货后才能评价"
        });
        return;
      }
      const pendingItems = buildPendingOrderReviewItems(detail.items);
      const firstItem = pendingItems[0] ?? null;
      this.setData({
        detail,
        pendingItems,
        selectedOrderItemId: firstItem?.orderItemId ?? 0,
        selectedItem: firstItem,
        totalReviewCount: pendingItems.length,
        submittedCount: 0,
        progressText: reviewProgressText(0, pendingItems.length),
        loading: false,
        loaded: true,
        errorText: "",
        blockingText: "",
        completed: pendingItems.length === 0
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({
          loading: false,
          loaded: this.data.detail !== null,
          errorText: actionError(error, "订单加载失败，请稍后重试")
        });
      }
    }
  },

  onProductSelect(event: DatasetEvent) {
    if (this.data.submittingOrderItemId || this.data.uploadingOrderItemId) {
      return;
    }
    const orderItemId = parseReviewOrderId(event.currentTarget.dataset.orderItemId);
    const nextItem = selectedItem(this.data.pendingItems, orderItemId);
    if (nextItem) {
      this.setData({
        selectedOrderItemId: orderItemId,
        selectedItem: nextItem
      });
    }
  },

  onRatingSelect(event: DatasetEvent) {
    this.updateSelectedDraft({
      rating: Number(event.currentTarget.dataset.rating)
    });
  },

  onContentInput(event: TextareaInputEvent) {
    this.updateSelectedDraft({ content: event.detail.value });
  },

  onAnonymousChange(event: SwitchChangeEvent) {
    this.updateSelectedDraft({ anonymous: event.detail.value });
  },

  onProductImageError() {
    this.updateSelectedDraft({ hasImage: false });
  },

  async onChooseReviewImage() {
    const item = this.data.selectedItem;
    if (!item || this.data.submittingOrderItemId || this.data.uploadingOrderItemId) {
      return;
    }
    const remaining = MAX_REVIEW_IMAGE_COUNT - item.reviewImages.length;
    if (remaining <= 0) {
      wx.showToast({ title: "最多上传 6 张图片", icon: "none" });
      return;
    }
    try {
      const selected = await chooseReviewImages(remaining);
      const accepted = selected.filter((file) => (
        file.size > 0 && file.size <= MAX_REVIEW_IMAGE_SIZE
      ));
      if (accepted.length < selected.length) {
        wx.showToast({ title: "单张图片不能超过 5MB", icon: "none" });
      }
      if (!accepted.length) {
        return;
      }
      this.setData({ uploadingOrderItemId: item.orderItemId });
      const reviewImages = item.reviewImages.slice();
      for (const file of accepted) {
        const uploaded = await uploadProductReviewImage(
          item.orderItemId,
          file.tempFilePath
        );
        reviewImages.push({
          fileId: uploaded.id,
          tempFilePath: file.tempFilePath
        });
        this.updateSelectedDraft({ reviewImages });
      }
    } catch (error) {
      wx.showToast({
        title: actionError(error, "图片上传失败，请稍后重试"),
        icon: "none"
      });
    } finally {
      this.setData({ uploadingOrderItemId: 0 });
    }
  },

  onPreviewReviewImage(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index);
    const images = this.data.selectedItem?.reviewImages ?? [];
    const current = images[index]?.tempFilePath;
    if (!current) {
      return;
    }
    wx.previewImage({
      current,
      urls: images.map((image) => image.tempFilePath)
    });
  },

  onRemoveReviewImage(event: DatasetEvent) {
    const item = this.data.selectedItem;
    if (!item || this.data.submittingOrderItemId || this.data.uploadingOrderItemId) {
      return;
    }
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isSafeInteger(index) || index < 0 || index >= item.reviewImages.length) {
      return;
    }
    const reviewImages = item.reviewImages.slice();
    reviewImages.splice(index, 1);
    this.updateSelectedDraft({ reviewImages });
  },

  updateSelectedDraft(
    patch: Partial<Pick<
      OrderReviewItemView,
      "rating" | "content" | "anonymous" | "hasImage" | "reviewImages"
    >>
  ) {
    if (!this.data.selectedOrderItemId || this.data.submittingOrderItemId) {
      return;
    }
    const pendingItems = updateOrderReviewDraft(
      this.data.pendingItems,
      this.data.selectedOrderItemId,
      patch
    );
    this.setData({
      pendingItems,
      selectedItem: selectedItem(pendingItems, this.data.selectedOrderItemId)
    });
  },

  onSubmitTap() {
    void this.submitSelectedReview();
  },

  async submitSelectedReview() {
    const item = this.data.selectedItem;
    if (!item || this.data.submittingOrderItemId) {
      return;
    }
    const lifecycleToken = this.data.lifecycleToken;
    const operationToken = reviewOperationGuard.begin(lifecycleToken);
    if (!operationToken) {
      return;
    }
    this.setData({ submittingOrderItemId: item.orderItemId });
    try {
      await createProductReview(item.spuId, {
        orderItemId: item.orderItemId,
        rating: item.rating,
        content: normalizeReviewContent(item.content),
        anonymous: item.anonymous,
        imageFileIds: item.reviewImages.map((image) => image.fileId)
      });
      if (!reviewOperationGuard.isCurrent(lifecycleToken, operationToken)) {
        return;
      }
      const pendingItems = this.data.pendingItems.filter(
        (candidate) => candidate.orderItemId !== item.orderItemId
      );
      const submittedCount = this.data.submittedCount + 1;
      const nextItem = pendingItems[0] ?? null;
      this.setData({
        pendingItems,
        selectedOrderItemId: nextItem?.orderItemId ?? 0,
        selectedItem: nextItem,
        submittedCount,
        progressText: reviewProgressText(submittedCount, this.data.totalReviewCount),
        submittingOrderItemId: 0,
        completed: pendingItems.length === 0
      });
      if (pendingItems.length) {
        wx.showToast({ title: "评价已发布", icon: "success" });
      } else {
        this.showCompletion(lifecycleToken, operationToken);
      }
    } catch (error) {
      if (!reviewOperationGuard.isCurrent(lifecycleToken, operationToken)) {
        return;
      }
      this.setData({ submittingOrderItemId: 0 });
      wx.showToast({
        title: actionError(error, "评价发布失败，请稍后重试"),
        icon: "none"
      });
      if (
        isApiError(error) &&
        [200001, 200201, 200202, 400001].includes(error.code ?? 0)
      ) {
        this.setData({
          detail: null,
          pendingItems: [],
          selectedOrderItemId: 0,
          selectedItem: null,
          loaded: false,
          completed: false
        });
        await this.loadOrder();
      }
    }
  },

  showCompletion(lifecycleToken: number, operationToken: number) {
    if (!reviewOperationGuard.isCurrent(lifecycleToken, operationToken)) {
      return;
    }
    wx.showModal({
      title: "评价已完成",
      content: "感谢你的真实评价，它会帮助其他顾客了解商品。",
      showCancel: false,
      confirmText: "返回订单",
      confirmColor: "#B72B22",
      success: () => {
        if (reviewOperationGuard.isCurrent(lifecycleToken, operationToken)) {
          this.leavePage();
        }
      }
    });
  },

  onBackToOrders() {
    this.leavePage();
  },

  leavePage() {
    wx.navigateBack({
      delta: 1,
      fail: () => wx.redirectTo({
        url: "/pages/order/list/list?group=COMPLETED"
      })
    });
  }
});
