import { createBrandLogoView } from "../../../config/brand-logo";
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
import { isApiError } from "../../../utils/api-error";

interface LoginPageOptions {
  redirect?: string;
}

let redirectUrl = "";
let pageDisposed = false;
let latestLoginPreparation = 0;
let preparedLogin: PreparedWechatLogin | null = null;

const DEFAULT_PRIVACY_CONTRACT_NAME = "《MuYbaby隐私保护指引》";

function errorMessage(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

function showAgreementRequired(): void {
  wx.showToast({
    title: "请先阅读并同意微信隐私保护指引",
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
    privacyContractName: DEFAULT_PRIVACY_CONTRACT_NAME
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
      privacyContractName: DEFAULT_PRIVACY_CONTRACT_NAME
    });
    this.loadPrivacyContractName();
  },

  onUnload() {
    pageDisposed = true;
    latestLoginPreparation += 1;
    this.discardUnfinishedLogin();
  },

  onAgreementChange(event: WechatMiniprogram.CheckboxGroupChange) {
    const agreed = event.detail.value.includes("agree");
    this.setData({ agreed });
    if (agreed) {
      void this.prepareLogin();
      return;
    }
    latestLoginPreparation += 1;
    this.discardUnfinishedLogin();
  },

  loadPrivacyContractName() {
    if (typeof wx.getPrivacySetting !== "function") {
      return;
    }
    wx.getPrivacySetting({
      success: (result) => {
        const privacyContractName = result.privacyContractName?.trim();
        if (!pageDisposed && privacyContractName) {
          // 名称由微信小程序平台返回，通常已包含书名号。
          this.setData({
            privacyContractName
          });
        }
      }
    });
  },

  async prepareLogin() {
    if (!this.data.agreed) {
      showAgreementRequired();
      return;
    }
    if (this.data.loading || this.data.loginPrepared) {
      return;
    }

    const preparationId = ++latestLoginPreparation;
    this.setData({ loading: true });
    try {
      const pending = await prepareWechatLogin();
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
    wx.openPrivacyContract({
      fail: () => {
        wx.showToast({
          title: "暂时无法打开微信隐私保护指引",
          icon: "none"
        });
      }
    });
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
