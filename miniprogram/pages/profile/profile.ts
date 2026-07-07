import { authorizePhone, clearAppTokens, silentLogin } from "../../services/auth";

interface GetPhoneNumberEvent {
  detail?: {
    code?: string;
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

Page({
  data: {
    loginStatus: "Not logged in",
    phoneStatus: "Phone not authorized",
    isLoggingIn: false,
    isLoggedIn: false,
    phoneAuthorizing: false,
    actionItems: [
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
  async onShow() {
    this.setData({
      isLoggingIn: true
    });

    try {
      const response = await silentLogin();
      this.setData({
        loginStatus: `Logged in as ${response.user.openidMasked}`,
        phoneStatus: response.user.phoneAuthorized ? "Phone authorized" : "Phone not authorized",
        isLoggedIn: true
      });
    } catch (error) {
      clearAppTokens();
      this.setData({
        loginStatus: "Not logged in",
        phoneStatus: "Phone not authorized",
        isLoggedIn: false
      });
    } finally {
      this.setData({
        isLoggingIn: false
      });
    }
  },
  async onGetPhoneNumber(event: GetPhoneNumberEvent) {
    if (!this.data.isLoggedIn) {
      this.setData({
        phoneStatus: "Not logged in"
      });
      return;
    }

    const code = event.detail?.code;
    if (!code) {
      this.setData({
        phoneStatus: "Phone authorization cancelled"
      });
      return;
    }

    this.setData({
      phoneAuthorizing: true
    });

    try {
      const response = await authorizePhone(code);
      this.setData({
        phoneStatus: response.phoneAuthorized
          ? `Phone authorized (${response.phoneNumberMasked})`
          : "Phone not authorized"
      });
    } catch (error) {
      this.setData({
        phoneStatus: "Phone not authorized"
      });
    } finally {
      this.setData({
        phoneAuthorizing: false
      });
    }
  },
  onActionTap(event: ActionTapEvent) {
    const path = event.currentTarget.dataset.path;
    if (!path) {
      return;
    }

    wx.navigateTo({
      url: path
    });
  }
});
