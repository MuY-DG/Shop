import { APP_ENV_VERSION } from "../../../config/app-config";
import {
  buildAccountCancellationBlockers,
  type AccountCancellationBlocker
} from "../../../features/account-cancellation";
import { buildLegalDocumentUrl } from "../../../features/compliance";
import {
  cancelAccount,
  getAccountCancellationEligibility
} from "../../../services/account-cancellation";
import { getCurrentLegalDocument } from "../../../services/compliance";
import { clearSession, getSessionState } from "../../../services/session";
import type { AccountCancellationEligibilityResponse } from "../../../types/account-cancellation";
import type { LegalDocumentResponse } from "../../../types/compliance";
import { isApiError } from "../../../utils/api-error";
import { openLoginPage } from "../../../utils/login-navigation";

const NOTICE_TYPE = "ACCOUNT_CANCELLATION_NOTICE" as const;

let latestRequest = 0;
let loginRequested = false;

function errorMessage(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function requestFreshWechatCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (result) => {
        const code = result.code?.trim() || "";
        if (code) {
          resolve(code);
        } else {
          reject(new Error("未获取到微信登录凭证"));
        }
      },
      fail: () => reject(new Error("微信身份校验未完成"))
    });
  });
}

Page({
  data: {
    loading: true,
    loaded: false,
    errorText: "",
    eligibility: null as AccountCancellationEligibilityResponse | null,
    blockers: [] as AccountCancellationBlocker[],
    notice: null as LegalDocumentResponse | null,
    confirmOpen: false,
    noticeAcknowledged: false,
    submitting: false
  },

  onLoad() {
    loginRequested = false;
  },

  onShow() {
    const session = getSessionState();
    if (!session.user || (!session.accessToken && !session.refreshToken)) {
      if (loginRequested) {
        if (getCurrentPages().length > 1) {
          wx.navigateBack();
        } else {
          wx.switchTab({ url: "/pages/profile/profile" });
        }
        return;
      }
      loginRequested = openLoginPage("/pages/account/cancellation/cancellation");
      return;
    }
    loginRequested = false;
    if (!this.data.loaded && !this.data.loading) {
      void this.loadCancellationState();
    }
  },

  onReady() {
    if (getSessionState().user) {
      void this.loadCancellationState();
    } else {
      this.setData({ loading: false });
    }
  },

  onUnload() {
    latestRequest += 1;
  },

  onRetry() {
    void this.loadCancellationState();
  },

  async loadCancellationState() {
    const requestId = ++latestRequest;
    this.setData({ loading: true, loaded: false, errorText: "" });
    try {
      const [eligibility, notice] = await Promise.all([
        getAccountCancellationEligibility(),
        getCurrentLegalDocument(NOTICE_TYPE)
      ]);
      if (requestId !== latestRequest) {
        return;
      }
      this.setData({
        loading: false,
        loaded: true,
        eligibility,
        blockers: buildAccountCancellationBlockers(eligibility),
        notice
      });
    } catch (error) {
      if (requestId === latestRequest) {
        this.setData({
          loading: false,
          loaded: false,
          errorText: errorMessage(error, "注销信息加载失败，请稍后重试")
        });
      }
    }
  },

  onCancelAccountTap() {
    if (this.data.submitting || !this.data.eligibility?.eligible || !this.data.notice) {
      return;
    }
    this.setData({ confirmOpen: true, noticeAcknowledged: false });
  },

  onCloseConfirm() {
    if (!this.data.submitting) {
      this.setData({ confirmOpen: false, noticeAcknowledged: false });
    }
  },

  onPreventMove() {},

  onToggleAcknowledgement() {
    if (!this.data.submitting) {
      this.setData({ noticeAcknowledged: !this.data.noticeAcknowledged });
    }
  },

  onOpenNotice() {
    if (!this.data.submitting) {
      wx.navigateTo({ url: buildLegalDocumentUrl(NOTICE_TYPE) });
    }
  },

  async onConfirmCancellation() {
    const notice = this.data.notice;
    if (this.data.submitting || !this.data.noticeAcknowledged || !notice) {
      return;
    }
    this.setData({ submitting: true });
    try {
      const wechatCode = await requestFreshWechatCode();
      await cancelAccount({
        wechatCode,
        noticeVersion: notice.version,
        noticeContentSha256: notice.contentSha256,
        noticeAcknowledged: true,
        miniProgramEnv: APP_ENV_VERSION
      });
      clearSession();
      this.setData({ submitting: false, confirmOpen: false });
      wx.showModal({
        title: "账号已注销",
        content: "注销已立即生效，再次登录将创建新账号。",
        showCancel: false,
        confirmText: "我知道了",
        success: () => wx.switchTab({ url: "/pages/profile/profile" })
      });
    } catch (error) {
      const noticeChanged = isApiError(error) && error.code === 120007;
      const obligationChanged = isApiError(error) && error.code === 120005;
      this.setData({
        submitting: false,
        confirmOpen: noticeChanged ? false : this.data.confirmOpen,
        noticeAcknowledged: noticeChanged ? false : this.data.noticeAcknowledged
      });
      wx.showToast({
        title: errorMessage(error, "注销失败，请稍后重试"),
        icon: "none"
      });
      if (noticeChanged || obligationChanged) {
        void this.loadCancellationState();
      }
    }
  }
});
