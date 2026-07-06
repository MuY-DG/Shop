import { authorizePhone, silentLogin } from "../../services/auth";
import type { AppLoginResponse, PhoneAuthorizeResponse } from "../../types/api";

interface GetPhoneNumberEvent {
  detail?: {
    code?: string;
  };
}

Page({
  data: {
    loginStatus: "Not logged in",
    phoneStatus: "Phone not authorized"
  },
  async onShow() {
    try {
      const response = await silentLogin();
      this.syncAuthStatus(response);
    } catch (error) {
      this.setData({
        loginStatus: "Not logged in",
        phoneStatus: "Phone not authorized"
      });
    }
  },
  async onGetPhoneNumber(event: GetPhoneNumberEvent) {
    const code = event.detail?.code;
    if (!code) {
      this.setData({
        phoneStatus: "Phone authorization cancelled"
      });
      return;
    }

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
    }
  },
  syncAuthStatus(response: AppLoginResponse | PhoneAuthorizeResponse) {
    if ("token" in response) {
      this.setData({
        loginStatus: `Logged in as user ${response.user.userId}`,
        phoneStatus: response.user.phoneAuthorized ? "Phone authorized" : "Phone not authorized"
      });
      return;
    }

    this.setData({
      phoneStatus: response.phoneAuthorized
        ? `Phone authorized (${response.phoneNumberMasked})`
        : "Phone not authorized"
    });
  }
});
