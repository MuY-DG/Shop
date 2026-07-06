export interface ApiResponse<T> {
  code: number;
  msg: string;
  data: T;
}

export interface AppUserSummary {
  userId: number;
  openidMasked: string;
  phoneAuthorized: boolean;
}

export interface AppLoginResponse {
  token: string;
  refreshToken: string;
  expiresIn: number;
  user: AppUserSummary;
}

export interface PhoneAuthorizeResponse {
  phoneAuthorized: boolean;
  phoneNumberMasked: string;
}

export type RequestBody = string | WechatMiniprogram.IAnyObject | ArrayBuffer;

export interface RequestOptions<TBody extends RequestBody = WechatMiniprogram.IAnyObject> {
  url: string;
  method?: "GET" | "POST" | "PUT" | "DELETE";
  data?: TBody;
  auth?: boolean;
}
