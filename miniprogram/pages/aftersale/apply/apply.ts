import { ensureAppLogin } from "../../../services/auth";
import { applyAfterSale } from "../../../services/aftersale";
import { formatPrice } from "../../../services/product";
import { uploadEvidenceFile } from "../../../services/storage";

interface DatasetEvent {
  currentTarget: {
    dataset: Record<string, string | number | undefined>;
  };
}

interface InputEvent {
  detail: {
    value: string;
  };
}

interface PickerChangeEvent {
  detail: {
    value: string | number;
  };
}

interface EvidenceFileView {
  fileId: number;
  tempFilePath: string;
  originalFilename: string;
}

interface AfterSaleApplyPageData {
  orderId: number;
  maxRefundCent: number;
  maxRefundText: string;
  reasons: string[];
  reasonIndex: number;
  description: string;
  evidenceFiles: EvidenceFileView[];
  uploading: boolean;
  submitting: boolean;
}

const MAX_EVIDENCE_COUNT = 3;

function parsePositiveNumber(value: string | undefined): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

function toErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function chooseEvidenceImages(count: number): Promise<string[]> {
  return new Promise((resolve, reject) => {
    wx.chooseMedia({
      count,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      sizeType: ["compressed"],
      success: (result) => {
        resolve(
          result.tempFiles
            .map((file) => file.tempFilePath)
            .filter((filePath) => filePath.length > 0)
        );
      },
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

Page<AfterSaleApplyPageData, WechatMiniprogram.Page.CustomOption>({
  data: {
    orderId: 0,
    maxRefundCent: 0,
    maxRefundText: "",
    reasons: ["不想要了", "商品问题", "发货问题", "其他原因"],
    reasonIndex: 0,
    description: "",
    evidenceFiles: [] as EvidenceFileView[],
    uploading: false,
    submitting: false
  },
  onLoad(query: Record<string, string | undefined>) {
    const orderId = parsePositiveNumber(query.order_id);
    const maxRefundCent = parsePositiveNumber(query.max_amount_cent);

    this.setData({
      orderId,
      maxRefundCent,
      maxRefundText: maxRefundCent > 0 ? formatPrice(maxRefundCent) : ""
    });

    if (!orderId) {
      wx.showToast({
        title: "订单不存在",
        icon: "none"
      });
    }
  },
  onReasonChange(event: PickerChangeEvent) {
    const reasonIndex = Number(event.detail.value);
    if (!Number.isInteger(reasonIndex) || reasonIndex < 0 || reasonIndex >= this.data.reasons.length) {
      return;
    }

    this.setData({
      reasonIndex
    });
  },
  onDescriptionInput(event: InputEvent) {
    this.setData({
      description: event.detail.value
    });
  },
  async onChooseEvidenceTap() {
    if (this.data.uploading || this.data.submitting) {
      return;
    }
    if (!this.data.orderId) {
      wx.showToast({
        title: "订单不存在",
        icon: "none"
      });
      return;
    }

    const remainingCount = MAX_EVIDENCE_COUNT - this.data.evidenceFiles.length;
    if (remainingCount <= 0) {
      wx.showToast({
        title: "最多上传3张凭证",
        icon: "none"
      });
      return;
    }

    try {
      const filePaths = await chooseEvidenceImages(remainingCount);
      if (filePaths.length === 0) {
        return;
      }

      this.setData({
        uploading: true
      });

      const evidenceFiles = this.data.evidenceFiles.slice();
      for (const filePath of filePaths) {
        const uploaded = await uploadEvidenceFile(filePath, this.data.orderId);
        evidenceFiles.push({
          fileId: uploaded.id,
          tempFilePath: filePath,
          originalFilename: uploaded.originalFilename
        });
        this.setData({
          evidenceFiles
        });
      }
    } catch (error) {
      wx.showToast({
        title: toErrorMessage(error, "凭证上传失败"),
        icon: "none"
      });
    } finally {
      this.setData({
        uploading: false
      });
    }
  },
  onRemoveEvidenceTap(event: DatasetEvent) {
    if (this.data.uploading || this.data.submitting) {
      return;
    }

    const index = Number(event.currentTarget.dataset.index);
    if (!Number.isInteger(index) || index < 0 || index >= this.data.evidenceFiles.length) {
      return;
    }

    const evidenceFiles = this.data.evidenceFiles.slice();
    evidenceFiles.splice(index, 1);
    this.setData({
      evidenceFiles
    });
  },
  async onSubmitTap() {
    if (this.data.submitting || this.data.uploading) {
      return;
    }

    const requestedAmountCent = this.data.maxRefundCent;
    const reason = this.data.reasons[this.data.reasonIndex] || "";
    const description = this.data.description.trim();

    if (!this.data.orderId) {
      wx.showToast({
        title: "订单不存在",
        icon: "none"
      });
      return;
    }
    if (requestedAmountCent <= 0) {
      wx.showToast({
        title: "退款金额无效，请返回订单重试",
        icon: "none"
      });
      return;
    }
    if (!reason) {
      wx.showToast({
        title: "请选择售后原因",
        icon: "none"
      });
      return;
    }

    this.setData({
      submitting: true
    });

    try {
      await ensureAppLogin();
      await applyAfterSale(this.data.orderId, {
        afterSaleType: "REFUND_ONLY",
        reason,
        requestedAmountCent,
        description,
        evidenceFileIds: this.data.evidenceFiles.map((file) => file.fileId)
      });

      wx.redirectTo({
        url: `/pages/order/detail/detail?order_id=${this.data.orderId}`
      });
    } catch (error) {
      wx.showToast({
        title: toErrorMessage(error, "提交失败"),
        icon: "none"
      });
    } finally {
      this.setData({
        submitting: false
      });
    }
  }
});
