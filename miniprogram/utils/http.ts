import type { ApiResponse, RequestBody } from "../types/api";

export interface RawHttpResult<T> {
  statusCode: number;
  body: ApiResponse<T> | null;
  authTokenUsed: string | null;
}

export interface RawRequestOptions<
  TBody extends RequestBody = WechatMiniprogram.IAnyObject
> {
  url: string;
  method?: "GET" | "POST" | "PUT" | "DELETE";
  data?: TBody;
  authToken?: string | null;
}

function getApiBaseUrl(): string {
  return getApp<{
    globalData: {
      apiBaseUrl: string;
    };
  }>().globalData.apiBaseUrl;
}

function normalizeApiResponse<T>(value: unknown): ApiResponse<T> | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  const candidate = value as Partial<ApiResponse<T>>;
  if (typeof candidate.code !== "number" || typeof candidate.msg !== "string") {
    return null;
  }
  return {
    code: candidate.code,
    msg: candidate.msg,
    data: ("data" in candidate ? candidate.data : null) as T
  };
}

export function rawRequest<
  TData,
  TBody extends RequestBody = WechatMiniprogram.IAnyObject
>(options: RawRequestOptions<TBody>): Promise<RawHttpResult<TData>> {
  const authTokenUsed =
    typeof options.authToken === "string" && options.authToken.length > 0
      ? options.authToken
      : null;
  const headers: Record<string, string> = {
    "Content-Type": "application/json"
  };
  if (authTokenUsed) {
    headers.Authorization = `Bearer ${authTokenUsed}`;
  }

  return new Promise((resolve, reject) => {
    wx.request<ApiResponse<TData>>({
      url: `${getApiBaseUrl()}${options.url}`,
      method: options.method ?? "GET",
      data: options.data,
      header: headers,
      success: (response) => {
        resolve({
          statusCode: response.statusCode,
          body: normalizeApiResponse<TData>(response.data),
          authTokenUsed
        });
      },
      fail: (error) => {
        reject(new Error(error.errMsg));
      }
    });
  });
}
