import { createBrandLogoView } from "../../../config/brand-logo";
import { APP_ENV_VERSION } from "../../../config/app-config";
import { buildLegalDocumentUrl } from "../../../features/compliance";
import {
  isTabRoute,
  needsPhoneAuthorization,
  sanitizeLoginRedirect
} from "../../../features/login";
import {
  authorizePreparedWechatPhoneNumber,
  commitPreparedWechatLogin,
  discardPreparedWechatLogin,
  prepareWechatLogin,
  type PreparedWechatLogin
} from "../../../services/session";
import { getCurrentLegalDocument } from "../../../services/compliance";
import { isApiError } from "../../../utils/api-error";

interface LoginPageOptions {
  redirect?: string;
}

let redirectUrl = "";
let pageDisposed = false;
let latestLoginPreparation = 0;
let latestPolicyRequest = 0;
let preparedLogin: PreparedWechatLogin | null = null;

function errorMessage(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function showAgreementRequired(): void {
  wx.showToast({
    title: "请先阅读并同意个人信息保护政策",
    icon: "none"
  });
}

function showPolicyUnavailable(): void {
  wx.showToast({
    title: "当前隐私政策尚不可用，请先重试加载",
    icon: "none"
  });
}

Page({
  data: {
    brandLogo: createBrandLogoView(176, 156),
    agreed: false,
    loading: false,
    loginPrepared: false,
    needsPhoneAuthorization: false,
    policyLoading: true,
    policyReady: false,
    policyVersion: "",
    policyTitle: "MuYbaby个人信息保护政策",
    policyErrorText: ""
  },

  onLoad(options: LoginPageOptions) {
    redirectUrl = sanitizeLoginRedirect(options.redirect);
    pageDisposed = false;
    preparedLogin = null;
    latestLoginPreparation += 1;
    this.setData({
      loading: false,
      loginPrepared: false,
      needsPhoneAuthorization: false,
      agreed: false,
      policyLoading: true,
      policyReady: false,
      policyVersion: "",
      policyErrorText: ""
    });
    void this.loadPrivacyPolicy();
  },

  onUnload() {
    pageDisposed = true;
    latestLoginPreparation += 1;
    latestPolicyRequest += 1;
    this.discardUnfinishedLogin();
  },

  onAgreementChange(event: WechatMiniprogram.CheckboxGroupChange) {
    if (!this.data.policyReady) {
      this.setData({ agreed: false });
      showPolicyUnavailable();
      return;
    }
    this.setData({ agreed: event.detail.value.includes("agree") });
  },

  async loadPrivacyPolicy() {
    const requestId = ++latestPolicyRequest;
    this.setData({
      policyLoading: true,
      policyReady: false,
      policyVersion: "",
      policyErrorText: "",
      agreed: false
    });
    try {
      const document = await getCurrentLegalDocument("PRIVACY_POLICY");
      if (!pageDisposed && requestId === latestPolicyRequest) {
        this.setData({
          policyLoading: false,
          policyReady: true,
          policyVersion: document.version,
          policyTitle: document.title,
          policyErrorText: ""
        });
      }
    } catch (error) {
      if (!pageDisposed && requestId === latestPolicyRequest) {
        this.setData({
          policyLoading: false,
          policyReady: false,
          policyVersion: "",
          policyErrorText: errorMessage(error, "当前隐私政策加载失败")
        });
      }
    }
  },

  onPolicyRetry() {
    if (!this.data.policyLoading && !this.data.loading) {
      void this.loadPrivacyPolicy();
    }
  },

  async prepareLogin() {
    if (!this.data.agreed) {
      showAgreementRequired();
      return;
    }
    if (!this.data.policyReady || !this.data.policyVersion) {
      showPolicyUnavailable();
      return;
    }
    if (this.data.loading || this.data.loginPrepared) {
      return;
    }

    const preparationId = ++latestLoginPreparation;
    this.setData({ loading: true });
    try {
      const pending = await prepareWechatLogin({
        privacyPolicyVersion: this.data.policyVersion,
        privacyPolicyAccepted: true,
        miniProgramEnv: APP_ENV_VERSION
      });
      if (
        pageDisposed ||
        preparationId !== latestLoginPreparation
      ) {
        void discardPreparedWechatLogin(pending).catch(() => undefined);
        return;
      }
      preparedLogin = pending;
      const needsPhone = needsPhoneAuthorization(pending.user);
      this.setData({
        loading: false,
        loginPrepared: true,
        needsPhoneAuthorization: needsPhone
      });
    } catch (error) {
      if (!pageDisposed && preparationId === latestLoginPreparation) {
        this.setData({
          loading: false,
          loginPrepared: false,
          needsPhoneAuthorization: false
        });
        wx.showToast({
          title: errorMessage(error, "登录失败，请稍后重试"),
          icon: "none"
        });
      }
    }
  },

  onPrepareLoginTap() {
    if (!this.data.agreed) {
      showAgreementRequired();
      return;
    }
    if (!this.data.policyReady || !this.data.policyVersion) {
      showPolicyUnavailable();
      return;
    }
    void this.prepareLogin();
  },

  onLoginTap() {
    if (!this.data.agreed) {
      showAgreementRequired();
      return;
    }
    if (this.data.loading || !this.data.loginPrepared) {
      return;
    }

    const pending = preparedLogin;
    if (!pending) {
      this.setData({ loginPrepared: false });
      wx.showToast({ title: "登录准备状态已失效，请重试", icon: "none" });
      return;
    }

    try {
      commitPreparedWechatLogin(pending);
      preparedLogin = null;
      if (!pageDisposed) {
        this.completeLogin("登录成功");
      }
    } catch (error) {
      const sessionInvalid = isApiError(error) && error.kind === "AUTH";
      if (sessionInvalid) {
        this.discardUnfinishedLogin();
      }
      this.setData({ loginPrepared: !sessionInvalid });
      wx.showToast({
        title: errorMessage(error, "登录失败，请稍后重试"),
        icon: "none"
      });
    }
  },

  onAgreementRequired() {
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
    const pending = preparedLogin;
    if (!pending) {
      this.setData({ loading: false, loginPrepared: false });
      wx.showToast({ title: "登录准备状态已失效，请重试", icon: "none" });
      return;
    }
    try {
      await authorizePreparedWechatPhoneNumber(pending, code);
      commitPreparedWechatLogin(pending);
      preparedLogin = null;
      if (!pageDisposed) {
        this.completeLogin("登录成功");
      }
    } catch (error) {
      if (!pageDisposed) {
        const sessionInvalid = isApiError(error) && error.kind === "AUTH";
        if (sessionInvalid) {
          this.discardUnfinishedLogin();
        }
        this.setData({
          loading: false,
          loginPrepared: !sessionInvalid,
          needsPhoneAuthorization: !sessionInvalid
        });
        wx.showToast({
          title: errorMessage(error, "手机号绑定失败，请稍后重试"),
          icon: "none"
        });
      }
    }
  },

  onPrivacyTap() {
    if (!this.data.policyReady) {
      showPolicyUnavailable();
      return;
    }
    wx.navigateTo({ url: buildLegalDocumentUrl("PRIVACY_POLICY") });
  },

  onSkipTap() {
    this.discardUnfinishedLogin();
    this.leavePage();
  },

  completeLogin(message: string) {
    this.setData({ loading: false, loginPrepared: true });
    wx.showToast({ title: message, icon: "success" });
    this.leavePage();
  },

  discardUnfinishedLogin() {
    const pending = preparedLogin;
    preparedLogin = null;
    if (!pageDisposed) {
      this.setData({
        loading: false,
        loginPrepared: false,
        needsPhoneAuthorization: false
      });
    }
    if (pending) {
      void discardPreparedWechatLogin(pending).catch(() => undefined);
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
