import {
  AFTER_SALE_REASONS,
  buildAfterSaleApplyPayload,
  buildAfterSaleDetailUrl,
  positiveAfterSaleId
} from "../../../features/after-sale";
import {
  buildOrderDetailView,
  positiveOrderId,
  type OrderDetailView
} from "../../../features/order-center";
import {
  applyAfterSale,
  uploadAfterSaleEvidence
} from "../../../services/after-sale";
import { getOrderDetail } from "../../../services/order";
import { isApiError } from "../../../utils/api-error";

interface InputEvent {
  detail: {
    value: string;
  };
}

interface DatasetEvent {
  currentTarget: {
    dataset: {
      index?: number | string;
      reason?: string;
      path?: string;
    };
  };
}

interface SelectedEvidence {
  fileId: number;
  tempFilePath: string;
  originalFilename: string;
  sizeBytes: number;
}

interface LocalImage {
  tempFilePath: string;
  size: number;
}

const MAX_EVIDENCE_COUNT = 3;
const MAX_EVIDENCE_SIZE = 1024 * 1024;
let latestOrderRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function chooseEvidenceImages(count: number): Promise<LocalImage[]> {
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

function confirmSubmit(amountText: string): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title: "确认提交退款申请",
      content: `商家审核通过后，将按订单实付金额 ${amountText} 原路退款。`,
      confirmText: "确认提交",
      confirmColor: "#B72B22",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false)
    });
  });
}

Page({
  data: {
    orderId: 0,
    detail: null as OrderDetailView | null,
    reasons: AFTER_SALE_REASONS,
    selectedReason: AFTER_SALE_REASONS[0],
    description: "",
    evidenceFiles: [] as SelectedEvidence[],
    blockedAfterSaleId: 0,
    loading: true,
    loaded: false,
    errorText: "",
    uploading: false,
    submitting: false
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderId = positiveOrderId(query.order_id);
    if (!orderId) {
      this.setData({ loading: false, errorText: "订单参数无效" });
      return;
    }
    this.setData({ orderId });
    void this.loadOrder();
  },

  onUnload() {
    latestOrderRequest += 1;
  },

  onRetry() {
    void this.loadOrder();
  },

  async loadOrder() {
    if (!this.data.orderId) {
      return;
    }
    const requestId = ++latestOrderRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const detail = buildOrderDetailView(await getOrderDetail(this.data.orderId));
      if (requestId !== latestOrderRequest) {
        return;
      }
      const blockedAfterSaleId = !detail.canApplyAfterSale
        ? positiveAfterSaleId(detail.latestAfterSaleView?.id)
        : 0;
      this.setData({
        detail,
        blockedAfterSaleId,
        loading: false,
        loaded: true,
        errorText: detail.canApplyAfterSale || blockedAfterSaleId
          ? ""
          : "当前订单状态暂不支持申请退款"
      });
    } catch (error) {
      if (requestId === latestOrderRequest) {
        this.setData({
          loading: false,
          loaded: this.data.detail !== null,
          errorText: actionError(error, "订单加载失败，请稍后重试")
        });
      }
    }
  },

  onReasonTap(event: DatasetEvent) {
    const reason = String(event.currentTarget.dataset.reason || "");
    if (AFTER_SALE_REASONS.includes(reason) && !this.data.submitting) {
      this.setData({ selectedReason: reason });
    }
  },

  onDescriptionInput(event: InputEvent) {
    this.setData({ description: event.detail.value });
  },

  async onChooseEvidenceTap() {
    if (this.data.uploading || this.data.submitting || !this.data.detail?.canApplyAfterSale) {
      return;
    }
    const remaining = MAX_EVIDENCE_COUNT - this.data.evidenceFiles.length;
    if (remaining <= 0) {
      wx.showToast({ title: "最多上传 3 张凭证", icon: "none" });
      return;
    }
    try {
      const selected = await chooseEvidenceImages(remaining);
      const accepted = selected.filter((file) => file.size > 0 && file.size <= MAX_EVIDENCE_SIZE);
      if (accepted.length < selected.length) {
        wx.showToast({ title: "单张图片不能超过 1MB", icon: "none" });
      }
      if (!accepted.length) {
        return;
      }
      this.setData({ uploading: true });
      const evidenceFiles = this.data.evidenceFiles.slice();
      for (const file of accepted) {
        const uploaded = await uploadAfterSaleEvidence(this.data.orderId, file.tempFilePath);
        evidenceFiles.push({
          fileId: uploaded.id,
          tempFilePath: file.tempFilePath,
          originalFilename: uploaded.originalFilename,
          sizeBytes: uploaded.sizeBytes
        });
        this.setData({ evidenceFiles });
      }
    } catch (error) {
      wx.showToast({
        title: actionError(error, "凭证上传失败，请稍后重试"),
        icon: "none"
      });
    } finally {
      this.setData({ uploading: false });
    }
  },

  onPreviewEvidenceTap(event: DatasetEvent) {
    const index = Number(event.currentTarget.dataset.index);
    const current = this.data.evidenceFiles[index]?.tempFilePath;
    if (!current) {
      return;
    }
    wx.previewImage({
      current,
      urls: this.data.evidenceFiles.map((file) => file.tempFilePath)
    });
  },

  onRemoveEvidenceTap(event: DatasetEvent) {
    if (this.data.uploading || this.data.submitting) {
      return;
    }
    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isSafeInteger(index) || index < 0 || index >= this.data.evidenceFiles.length) {
      return;
    }
    const evidenceFiles = this.data.evidenceFiles.slice();
    evidenceFiles.splice(index, 1);
    this.setData({ evidenceFiles });
  },

  onBlockedAfterSaleTap() {
    if (this.data.blockedAfterSaleId) {
      wx.redirectTo({ url: buildAfterSaleDetailUrl(this.data.blockedAfterSaleId) });
    }
  },

  async onSubmitTap() {
    const detail = this.data.detail;
    if (
      !detail?.canApplyAfterSale ||
      this.data.uploading ||
      this.data.submitting ||
      !await confirmSubmit(detail.paidAmountText)
    ) {
      return;
    }
    let payload;
    try {
      payload = buildAfterSaleApplyPayload({
        reason: this.data.selectedReason,
        requestedAmountCent: detail.paidAmountCent,
        description: this.data.description,
        evidenceFileIds: this.data.evidenceFiles.map((file) => file.fileId)
      });
    } catch (error) {
      wx.showToast({ title: actionError(error, "申请内容不完整"), icon: "none" });
      return;
    }
    this.setData({ submitting: true });
    try {
      const result = await applyAfterSale(this.data.orderId, payload);
      wx.showToast({ title: "申请已提交", icon: "success" });
      wx.redirectTo({
        url: buildAfterSaleDetailUrl(result.id),
        fail: () => this.setData({ submitting: false })
      });
    } catch (error) {
      this.setData({ submitting: false });
      wx.showToast({
        title: actionError(error, "申请提交失败，请稍后重试"),
        icon: "none"
      });
      await this.loadOrder();
    }
  }
});
