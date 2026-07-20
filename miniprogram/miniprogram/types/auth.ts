export type UserId = string;

export interface AppUserProfile {
  userId: UserId;
  nickname: string;
  openidMasked: string;
  phoneAuthorized: boolean;
  phoneNumberMasked?: string;
}

export interface AppSessionResponse {
  token: string;
  refreshToken: string;
  expiresIn: number;
  user: AppUserProfile;
}

export interface AppLoginRequest extends WechatMiniprogram.IAnyObject {
  code: string;
}

export interface RefreshTokenRequest extends WechatMiniprogram.IAnyObject {
  refreshToken: string;
}
