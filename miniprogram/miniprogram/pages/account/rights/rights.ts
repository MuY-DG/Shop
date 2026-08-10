import {
  buildAccountRightsAuditView,
  buildAccountRightsRequestView,
  normalizeAccountRightsNote,
  validateAccountRightsNote,
  type AccountRightsAuditView,
  type AccountRightsRequestView
} from "../../../features/account-rights";
import {
  getAccountRightsRequestDetail,
  getAccountRightsRequests,
  submitAccountRightsRequest,
  withdrawAccountRightsRequest
} from "../../../services/account-rights";
import { getSessionState } from "../../../services/session";
import type { AccountRightsRequestType } from "../../../types/account-rights";
import { isApiError } from "../../../utils/api-error";
import { openLoginPage } from "../../../utils/login-navigation";

interface DatasetEvent {
  currentTarget: {
    dataset: {
      id?: number | string;
      type?: AccountRightsRequestType;
    };
  };
}

const REQUEST_TYPES: ReadonlyArray<{
  type: AccountRightsRequestType;
  label: string;
  description: string;
}> = [
  {
    type: "ACCOUNT_CANCELLATION",
    label: "注销账户",
    description: "需重新完成微信身份核验；未完结交易会阻止完成注销"
  },
  {
    type: "PERSONAL_INFORMATION_DELETION",
    label: "删除个人信息",
    description: "申请删除可删除的信息；依法或履约需要保留的记录会说明原因"
  },
  {
    type: "ACCESS_COPY",
    label: "查阅/复制个人信息",
    description: "申请查阅或获取账户相关个人信息副本"
  },
  {
    type: "CORRECTION",
    label: "更正个人信息",
    description: "说明需要更正的信息及准确内容"
  }
];

let latestListRequest = 0;
let latestDetailRequest = 0;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function positiveId(value: unknown): string | undefined {
  const normalized = typeof value === "string" || typeof value === "number"
    ? String(value).trim()
    : "";
  return /^[1-9]\d*$/.test(normalized) ? normalized : undefined;
}

function requestFreshWechatCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (result) => {
        const code = result.code?.trim() || "";
        if (code) {
          resolve(code);
        } else {
          reject(new Error("微信身份核验未返回有效凭证"));
        }
      },
      fail: reject
    });
  });
}

Page({
  data: {
    requestTypes: REQUEST_TYPES,
    selectedType: "ACCESS_COPY" as AccountRightsRequestType,
    requestNote: "",
    requests: [] as AccountRightsRequestView[],
    loading: true,
    loaded: false,
    submitting: false,
    withdrawingId: "",
    errorText: "",
    detailOpen: false,
    detailLoading: false,
    selectedRequest: null as AccountRightsRequestView | null,
    selectedAudits: [] as AccountRightsAuditView[]
  },

  onShow() {
    const session = getSessionState();
    if (!session.user || (!session.accessToken && !session.refreshToken)) {
      openLoginPage("/pages/account/rights/rights");
      return;
    }
    if (!this.data.loaded && !this.data.loading) {
      void this.loadRequests();
      return;
    }
    if (!this.data.loaded) {
      void this.loadRequests();
    }
  },

  onUnload() {
    latestListRequest += 1;
    latestDetailRequest += 1;
  },

  onTypeTap(event: DatasetEvent) {
    const type = event.currentTarget.dataset.type;
    if (type && !this.data.submitting) {
      this.setData({ selectedType: type });
    }
  },

  onNoteInput(event: WechatMiniprogram.Input) {
    this.setData({ requestNote: event.detail.value });
  },

  onRetry() {
    void this.loadRequests();
  },

  async loadRequests() {
    const requestId = ++latestListRequest;
    this.setData({ loading: true, errorText: "" });
    try {
      const records = await getAccountRightsRequests();
      if (requestId !== latestListRequest) {
        return;
      }
      this.setData({
        requests: (Array.isArray(records) ? records : []).map(buildAccountRightsRequestView),
        loading: false,
        loaded: true,
        errorText: ""
      });
    } catch (error) {
      if (requestId === latestListRequest) {
        this.setData({
          loading: false,
          loaded: false,
          errorText: actionError(error, "申请记录加载失败，请稍后重试")
        });
      }
    }
  },

  onSubmitTap() {
    if (this.data.submitting) {
      return;
    }
    const validationError = validateAccountRightsNote(this.data.requestNote);
    if (validationError) {
      wx.showToast({ title: validationError, icon: "none" });
      return;
    }
    const cancellation = this.data.selectedType === "ACCOUNT_CANCELLATION";
    wx.showModal({
      title: cancellation ? "确认提交账户注销申请" : "确认提交个人信息申请",
      content: cancellation
        ? "提交后仍需后台审核；未完结订单、支付、退款或售后会阻止完成注销。确认后才会调用微信登录进行本次身份核验。"
        : "申请会进入人工处理流程，处理结果和数据保留说明可在本页查看。",
      confirmText: "确认提交",
      success: (result) => {
        if (result.confirm) {
          void this.submitNow();
        }
      }
    });
  },

  async submitNow() {
    if (this.data.submitting) {
      return;
    }
    this.setData({ submitting: true });
    try {
      const wechatCode = this.data.selectedType === "ACCOUNT_CANCELLATION"
        ? await requestFreshWechatCode()
        : undefined;
      await submitAccountRightsRequest({
        requestType: this.data.selectedType,
        requestNote: normalizeAccountRightsNote(this.data.requestNote) || undefined,
        ...(wechatCode ? { wechatCode } : {})
      });
      this.setData({ requestNote: "" });
      wx.showToast({ title: "申请已提交", icon: "success" });
      await this.loadRequests();
    } catch (error) {
      wx.showToast({
        title: actionError(error, "申请提交失败，请稍后重试"),
        icon: "none"
      });
    } finally {
      this.setData({ submitting: false });
    }
  },

  onWithdrawTap(event: DatasetEvent) {
    const requestId = positiveId(event.currentTarget.dataset.id);
    const request = this.data.requests.find((item) => item.id === requestId);
    if (!request?.canWithdraw || this.data.withdrawingId) {
      return;
    }
    wx.showModal({
      title: "撤回申请",
      content: "仅待处理申请可以撤回；撤回后如仍有需要，可重新提交。",
      confirmText: "确认撤回",
      confirmColor: "#B42318",
      success: (result) => {
        if (result.confirm) {
          void this.withdrawNow(request);
        }
      }
    });
  },

  async withdrawNow(request: AccountRightsRequestView) {
    this.setData({ withdrawingId: request.id });
    try {
      await withdrawAccountRightsRequest(request.id, { version: request.version });
      wx.showToast({ title: "申请已撤回", icon: "success" });
      await this.loadRequests();
    } catch (error) {
      wx.showToast({ title: actionError(error, "撤回失败，请稍后重试"), icon: "none" });
    } finally {
      this.setData({ withdrawingId: "" });
    }
  },

  async onRequestTap(event: DatasetEvent) {
    const requestId = positiveId(event.currentTarget.dataset.id);
    if (!requestId) {
      return;
    }
    const detailRequestId = ++latestDetailRequest;
    this.setData({ detailOpen: true, detailLoading: true, selectedAudits: [] });
    try {
      const detail = await getAccountRightsRequestDetail(requestId);
      if (detailRequestId !== latestDetailRequest) {
        return;
      }
      this.setData({
        selectedRequest: buildAccountRightsRequestView(detail.request),
        selectedAudits: (Array.isArray(detail.audits) ? detail.audits : [])
          .map(buildAccountRightsAuditView),
        detailLoading: false
      });
    } catch (error) {
      if (detailRequestId === latestDetailRequest) {
        this.setData({ detailOpen: false, detailLoading: false });
        wx.showToast({ title: actionError(error, "详情加载失败"), icon: "none" });
      }
    }
  },

  onCloseDetail() {
    latestDetailRequest += 1;
    this.setData({
      detailOpen: false,
      detailLoading: false,
      selectedRequest: null,
      selectedAudits: []
    });
  },

  onPreventMove() {}
});
