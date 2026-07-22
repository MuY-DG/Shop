import {
  isTabRoute,
  needsPhoneAuthorization,
  sanitizeLoginRedirect
} from "../../../features/login";
import {
  authorizePhoneNumber,
  clearSession,
  getSessionState,
  loginWithWechat,
  logoutSession
} from "../../../services/session";
import { isApiError } from "../../../utils/api-error";

interface LoginPageOptions {
  redirect?: string;
}

let redirectUrl = "";
let loginStarted = false;
let loginCompleted = false;
let pendingPhoneBinding = false;
let pageDisposed = false;
let initialHadSession = false;

function errorMessage(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function showAgreementRequired(): void {
  wx.showToast({
    title: "请先阅读并同意相关协议",
    icon: "none"
  });
}

Page({
  data: {
    agreed: false,
    loading: false,
    needsPhoneAuthorization: false
  },

  onLoad(options: LoginPageOptions) {
    redirectUrl = sanitizeLoginRedirect(options.redirect);
    loginStarted = false;
    loginCompleted = false;
    pendingPhoneBinding = false;
    pageDisposed = false;
    initialHadSession = Boolean(getSessionState().accessToken);
  },

  onUnload() {
    pageDisposed = true;
    if (!loginCompleted && (pendingPhoneBinding || (loginStarted && !initialHadSession))) {
      this.discardUnfinishedLogin();
    }
  },

  onAgreementChange(event: WechatMiniprogram.CheckboxGroupChange) {
    this.setData({ agreed: event.detail.value.includes("agree") });
  },

  async onLoginTap() {
    if (!this.data.agreed) {
      showAgreementRequired();
      return;
    }
    if (this.data.loading) {
      return;
    }

    loginStarted = true;
    this.setData({ loading: true });
    try {
      const session = await loginWithWechat();
      if (pageDisposed) {
        return;
      }
      if (!session.user) {
        throw new Error("登录响应缺少用户信息");
      }
      if (!needsPhoneAuthorization(session.user)) {
        this.completeLogin("登录成功");
        return;
      }
      pendingPhoneBinding = true;
      this.setData({
        loading: false,
        needsPhoneAuthorization: true
      });
    } catch (error) {
      if (!pageDisposed) {
        this.setData({ loading: false });
        wx.showToast({
          title: errorMessage(error, "登录失败，请稍后重试"),
          icon: "none"
        });
      }
    }
  },

  onPhoneAgreementRequired() {
    showAgreementRequired();
  },

  async onGetPhoneNumber(event: WechatMiniprogram.ButtonGetPhoneNumber) {
    if (this.data.loading) {
      return;
    }
    const code = event.detail.code?.trim();
    if (!code) {
      wx.showToast({
        title: "未授权手机号，可稍后再试",
        icon: "none"
      });
      return;
    }

    this.setData({ loading: true });
    try {
      await authorizePhoneNumber(code);
      if (!pageDisposed) {
        pendingPhoneBinding = false;
        this.completeLogin("登录成功");
      }
    } catch (error) {
      if (!pageDisposed) {
        this.setData({ loading: false });
        wx.showToast({
          title: errorMessage(error, "手机号绑定失败，请稍后重试"),
          icon: "none"
        });
      }
    }
  },

  onPrivacyTap() {
    wx.openPrivacyContract({
      fail: () => {
        wx.showModal({
          title: "个人信息保护政策",
          content: "我们仅在提供账户、订单与售后服务所必需的范围内处理您的信息，具体以小程序隐私保护指引为准。",
          showCancel: false,
          confirmColor: "#B72B22"
        });
      }
    });
  },

  onTermsTap() {
    wx.showModal({
      title: "用户服务协议",
      content: "登录即表示您同意遵守灶香集的账户、下单、配送与售后服务规则。正式发布前请替换为经审核的完整协议正文。",
      showCancel: false,
      confirmColor: "#B72B22"
    });
  },

  onSkipTap() {
    this.discardUnfinishedLogin();
    this.leavePage();
  },

  completeLogin(message: string) {
    loginCompleted = true;
    pendingPhoneBinding = false;
    this.setData({ loading: false });
    wx.showToast({ title: message, icon: "success" });
    this.leavePage();
  },

  discardUnfinishedLogin() {
    if (!pendingPhoneBinding && !(loginStarted && !initialHadSession)) {
      return;
    }
    pendingPhoneBinding = false;
    loginStarted = false;
    if (getSessionState().accessToken) {
      void logoutSession().catch(() => undefined);
    } else {
      clearSession();
    }
  },

  leavePage() {
    if (getCurrentPages().length > 1) {
      wx.navigateBack();
      return;
    }
    if (redirectUrl) {
      if (isTabRoute(redirectUrl)) {
        wx.switchTab({ url: redirectUrl.split("?")[0] });
      } else {
        wx.redirectTo({ url: redirectUrl });
      }
      return;
    }
    wx.switchTab({ url: "/pages/profile/profile" });
  }
});
