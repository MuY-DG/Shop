import type { AppUserProfile } from "../../types/api";
import type { AuthStateV1 } from "../../services/session";
import {
  authorizePhone,
  ensureAppLogin,
  getCurrentUser,
  getSessionState,
  restoreSession,
  updateProfile
} from "../../services/auth";

interface GetPhoneNumberEvent {
  detail?: {
    code?: string;
    errno?: number;
    errMsg?: string;
  };
}

interface ProfileActionItem {
  title: string;
  path: string;
}

interface ActionTapEvent {
  currentTarget: {
    dataset: Record<string, string | undefined>;
  };
}

export interface ProfilePageDependencies {
  restoreSession(): AuthStateV1;
  ensureSession(): Promise<AuthStateV1>;
  getCurrentUser(): Promise<AppUserProfile>;
  updateProfile(profile: AppUserProfile): unknown;
  getSessionState(): AuthStateV1;
  authorizePhone(code: string): Promise<AppUserProfile>;
}

export interface ProfilePageData {
  loginStatus: string;
  phoneStatus: string;
  profileWarning: string;
  phoneButtonText: string;
  isLoggingIn: boolean;
  isLoggedIn: boolean;
  phoneAuthorizing: boolean;
  actionItems: ProfileActionItem[];
}

export interface ProfilePageContext {
  data: ProfilePageData;
  setData(values: Partial<ProfilePageData>): void;
  applyProfile(profile: AppUserProfile): void;
  applyLoggedOutState(): void;
}

export function createProfilePageDefinition(
  dependencies: ProfilePageDependencies
) {
  return {
    data: {
      loginStatus: "未登录",
      phoneStatus: "手机号未授权",
      profileWarning: "",
      phoneButtonText: "授权手机号",
      isLoggingIn: false,
      isLoggedIn: false,
      phoneAuthorizing: false,
      actionItems: [
        {
          title: "我的订单",
          path: "/pages/order/list/list"
        },
        {
          title: "领券中心",
          path: "/pages/coupon/list/list"
        },
        {
          title: "我的优惠券",
          path: "/pages/coupon/mine/mine"
        }
      ] as ProfileActionItem[]
    },
    applyProfile(this: ProfilePageContext, profile: AppUserProfile) {
      const phoneStatus = profile.phoneAuthorized
        ? profile.phoneNumberMasked
          ? `已授权：${profile.phoneNumberMasked}`
          : "手机号已授权"
        : "手机号未授权";
      this.setData({
        loginStatus: `已登录：${profile.openidMasked}`,
        phoneStatus,
        phoneButtonText: profile.phoneAuthorized ? "更换手机号" : "授权手机号",
        profileWarning: "",
        isLoggedIn: true
      });
    },
    applyLoggedOutState(this: ProfilePageContext) {
      this.setData({
        loginStatus: "未登录",
        phoneStatus: "手机号未授权",
        phoneButtonText: "授权手机号",
        profileWarning: "",
        isLoggedIn: false
      });
    },
    async onShow(this: ProfilePageContext) {
      const restored = dependencies.restoreSession();
      if (restored.profile) {
        this.applyProfile(restored.profile);
      }
      this.setData({
        isLoggingIn: true,
        profileWarning: ""
      });

      try {
        await dependencies.ensureSession();
        const currentProfile = await dependencies.getCurrentUser();
        dependencies.updateProfile(currentProfile);
        this.applyProfile(currentProfile);
      } catch {
        const current = dependencies.getSessionState();
        if (current.accessToken) {
          if (current.profile) {
            this.applyProfile(current.profile);
          } else {
            this.setData({
              loginStatus: "已登录",
              phoneStatus: "资料暂不可用",
              phoneButtonText: "授权手机号",
              isLoggedIn: true
            });
          }
          this.setData({ profileWarning: "资料刷新失败，请稍后重试" });
        } else {
          this.applyLoggedOutState();
        }
      } finally {
        this.setData({ isLoggingIn: false });
      }
    },
    async onGetPhoneNumber(
      this: ProfilePageContext,
      event: GetPhoneNumberEvent
    ) {
      if (!this.data.isLoggedIn) {
        this.setData({ phoneStatus: "请先登录" });
        return;
      }

      const code = event.detail?.code;
      if (!code) {
        const cancelled =
          event.detail?.errno === 1400001 ||
          /cancel/i.test(event.detail?.errMsg ?? "");
        this.setData({
          phoneStatus: cancelled
            ? "已取消手机号授权"
            : "未获取到手机号授权信息"
        });
        return;
      }

      this.setData({ phoneAuthorizing: true });
      try {
        const currentProfile = await dependencies.authorizePhone(code);
        dependencies.updateProfile(currentProfile);
        this.applyProfile(currentProfile);
      } catch (error) {
        const message = error instanceof Error ? error.message : "";
        this.setData({
          phoneStatus: /stable_token|capability|能力|1400001/i.test(message)
            ? "手机号快速验证暂不可用，请稍后重试"
            : "手机号授权失败，请稍后重试"
        });
      } finally {
        this.setData({ phoneAuthorizing: false });
      }
    },
    onActionTap(this: ProfilePageContext, event: ActionTapEvent) {
      const path = event.currentTarget.dataset.path;
      if (path) {
        wx.navigateTo({ url: path });
      }
    }
  };
}

Page(
  createProfilePageDefinition({
    restoreSession,
    ensureSession: async () => {
      await ensureAppLogin();
      return getSessionState();
    },
    getCurrentUser,
    updateProfile,
    getSessionState,
    authorizePhone
  })
);
