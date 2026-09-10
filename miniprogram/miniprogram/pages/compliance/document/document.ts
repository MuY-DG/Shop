import {
  buildLegalDocumentView,
  buildLegalDocumentUrl,
  legalDocumentTitle,
  parseLegalDocumentType,
  type LegalDocumentView
} from "../../../features/compliance";
import { getCurrentLegalDocument } from "../../../services/compliance";
import type { LegalDocumentType } from "../../../types/compliance";
import { isApiError } from "../../../utils/api-error";
import { enableNativeShareMenu } from "../../../utils/share";
import { getDisplayName, refreshDisplayConfig } from "../../../services/display-config";

interface DocumentPageOptions {
  type?: string;
}

let currentType: LegalDocumentType | undefined;
let latestRequest = 0;

function errorMessage(error: unknown): string {
  return isApiError(error)
    ? error.message
    : "法律文档加载失败，请稍后重试";
}

Page({
  data: {
    pageTitle: "协议与政策",
    loading: true,
    loaded: false,
    unconfigured: false,
    errorText: "",
    document: null as LegalDocumentView | null
  },

  onLoad(options: DocumentPageOptions) {
    enableNativeShareMenu();
    currentType = parseLegalDocumentType(options.type);
    if (!currentType) {
      this.setData({
        loading: false,
        loaded: false,
        unconfigured: false,
        errorText: "无效的法律文档类型"
      });
      return;
    }
    this.setData({ pageTitle: legalDocumentTitle(currentType) });
    void this.loadDocument();
  },

  onShow() {
    void refreshDisplayConfig();
  },

  onUnload() {
    latestRequest += 1;
    currentType = undefined;
  },

  onRetry() {
    if (currentType) {
      void this.loadDocument();
    }
  },

  async loadDocument() {
    const type = currentType;
    if (!type) {
      return;
    }
    const requestId = ++latestRequest;
    this.setData({
      loading: true,
      loaded: false,
      unconfigured: false,
      errorText: "",
      document: null
    });
    try {
      const response = await getCurrentLegalDocument(type);
      if (response === null) {
        if (requestId === latestRequest && currentType === type) {
          this.setData({
            loading: false,
            loaded: true,
            unconfigured: true,
            document: null
          });
        }
        return;
      }
      const document = buildLegalDocumentView(response, type);
      if (!document) {
        throw new Error("法律文档内容不完整");
      }
      if (requestId === latestRequest && currentType === type) {
        this.setData({
          loading: false,
          loaded: true,
          unconfigured: false,
          document
        });
      }
    } catch (error) {
      if (requestId === latestRequest && currentType === type) {
        this.setData({
          loading: false,
          loaded: false,
          unconfigured: false,
          errorText: errorMessage(error),
          document: null
        });
      }
    }
  },

  onShareAppMessage() {
    const type = currentType;
    return type
      ? {
          title: `${getDisplayName()}${legalDocumentTitle(type)}`,
          path: buildLegalDocumentUrl(type)
        }
      : { title: `${getDisplayName()}协议与政策` };
  },

  onShareTimeline() {
    return {
      title: currentType
        ? `${getDisplayName()}${legalDocumentTitle(currentType)}`
        : `${getDisplayName()}协议与政策`,
      query: currentType ? `type=${currentType}` : ""
    };
  }
});
