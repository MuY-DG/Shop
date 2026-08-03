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

Page({
  data: {
    brandLogo: createBrandLogoView(176, 156),
    agreed: false,
    initializing: true,
    loading: false,
    loginPrepared: false,
    needsPhoneAuthorization: false
  },

  onLoad(options: LoginPageOptions) {
    redirectUrl = sanitizeLoginRedirect(options.redirect);
    pageDisposed = false;
    preparedLogin = null;
    latestLoginPreparation += 1;
    this.setData({ initializing: true });
    void this.prepareLogin();
  },

  onUnload() {
    pageDisposed = true;
    latestLoginPreparation += 1;
    this.discardUnfinishedLogin();
  },

  onAgreementChange(event: WechatMiniprogram.CheckboxGroupChange) {
    this.setData({ agreed: event.detail.value.includes("agree") });
  },

  async prepareLogin() {
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
        initializing: false,
        loading: false,
        loginPrepared: true,
        needsPhoneAuthorization: needsPhone
      });
    } catch (error) {
      if (!pageDisposed && preparationId === latestLoginPreparation) {
        this.setData({
          initializing: false,
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
        wx.showModal({
          title: "个人信息保护政策",
          content: "我们仅在提供账户、订单与售后服务所必需的范围内处理您的信息，具体以小程序隐私保护指引为准。",
          showCancel: false,
          confirmColor: "#B72B22"
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
