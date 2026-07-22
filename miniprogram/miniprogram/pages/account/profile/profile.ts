import {
  normalizeProfileNickname,
  profileHasChanges,
  validateProfileNickname
} from "../../../features/user-profile";
import {
  getMyProfile,
  updateMyProfile,
  uploadWechatAvatar
} from "../../../services/user-profile";
import {
  getSessionState,
  logoutSession
} from "../../../services/session";
import type { AppUserProfile } from "../../../types/auth";
import { isApiError } from "../../../utils/api-error";
import { openLoginPage } from "../../../utils/login-navigation";

const DEFAULT_AVATAR = "/assets/images/zaoxiangji-login-emblem.png";

interface ChooseAvatarEvent {
  detail: {
    avatarUrl?: string;
  };
}

let originalNickname = "";
let pendingAvatarPath = "";
let latestProfileRequest = 0;
let loginRequested = false;

function actionError(error: unknown, fallback: string): string {
  return isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : fallback;
}

Page({
  data: {
    initialized: false,
    loading: false,
    saving: false,
    loggingOut: false,
    nickname: "",
    avatarUrl: DEFAULT_AVATAR,
    hasAvatar: false,
    phoneNumberMasked: "未绑定",
    dirty: false,
    validationErrorText: "",
    loadErrorText: ""
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
      loginRequested = openLoginPage("/pages/account/profile/profile");
      return;
    }
    loginRequested = false;
    if (!this.data.initialized) {
      this.applyProfile(session.user);
      void this.loadProfile();
    }
  },

  onUnload() {
    latestProfileRequest += 1;
  },

  async loadProfile() {
    const requestId = ++latestProfileRequest;
    this.setData({ loading: true, loadErrorText: "" });
    try {
      const profile = await getMyProfile();
      if (requestId === latestProfileRequest) {
        this.applyProfile(profile);
      }
    } catch (error) {
      if (requestId === latestProfileRequest) {
        this.setData({
          loading: false,
          loadErrorText: actionError(error, "资料同步失败，请稍后重试")
        });
      }
    }
  },

  onAvatarChoose(event: ChooseAvatarEvent) {
    const avatarUrl = event.detail.avatarUrl?.trim() || "";
    if (!avatarUrl) {
      wx.showToast({ title: "未选择微信头像", icon: "none" });
      return;
    }
    pendingAvatarPath = avatarUrl;
    this.setData({
      avatarUrl,
      hasAvatar: true,
      dirty: profileHasChanges(this.data.nickname, originalNickname, pendingAvatarPath),
      validationErrorText: ""
    });
  },

  onNicknameInput(event: WechatMiniprogram.Input) {
    const nickname = event.detail.value;
    this.setData({
      nickname,
      dirty: profileHasChanges(nickname, originalNickname, pendingAvatarPath),
      validationErrorText: ""
    });
  },

  async onSaveTap() {
    if (this.data.saving || this.data.loggingOut) {
      return;
    }
    const nickname = normalizeProfileNickname(this.data.nickname);
    const validationErrorText = validateProfileNickname(nickname);
    if (validationErrorText) {
      this.setData({ validationErrorText });
      return;
    }
    if (!this.data.dirty) {
      wx.showToast({ title: "资料没有变化", icon: "none" });
      return;
    }

    this.setData({ saving: true, validationErrorText: "" });
    try {
      let profile = getSessionState().user;
      if (nickname !== originalNickname) {
        profile = await updateMyProfile(nickname);
      }
      if (pendingAvatarPath) {
        profile = await uploadWechatAvatar(pendingAvatarPath);
      }
      if (!profile) {
        throw new Error("用户资料不存在");
      }
      this.applyProfile(profile);
      wx.showToast({ title: "资料已保存", icon: "success" });
    } catch (error) {
      this.setData({
        saving: false,
        validationErrorText: actionError(error, "资料保存失败，请稍后重试")
      });
    }
  },

  onLogoutTap() {
    if (this.data.saving || this.data.loggingOut) {
      return;
    }
    wx.showModal({
      title: "退出登录",
      content: "退出后，订单和会员资料仍会安全保留，下次可重新登录查看。",
      confirmText: "退出登录",
      confirmColor: "#B42318",
      success: (result) => {
        if (result.confirm) {
          void this.logoutNow();
        }
      }
    });
  },

  async logoutNow() {
    this.setData({ loggingOut: true });
    try {
      await logoutSession();
    } catch {
      // 本地会话已立即清理，服务端撤销失败不阻塞用户退出。
    }
    wx.showToast({ title: "已退出登录", icon: "success" });
    wx.switchTab({ url: "/pages/profile/profile" });
  },

  applyProfile(profile: AppUserProfile) {
    originalNickname = profile.nickname;
    pendingAvatarPath = "";
    this.setData({
      initialized: true,
      loading: false,
      saving: false,
      nickname: profile.nickname,
      avatarUrl: profile.avatarUrl || DEFAULT_AVATAR,
      hasAvatar: Boolean(profile.avatarUrl),
      phoneNumberMasked: profile.phoneNumberMasked || "未绑定",
      dirty: false,
      validationErrorText: "",
      loadErrorText: ""
    });
  }
});
