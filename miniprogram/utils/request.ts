import type { ApiResponse, RequestBody, RequestOptions } from "../types/api";

const SUCCESS_CODE = 200;

export function request<TData, TBody extends RequestBody = WechatMiniprogram.IAnyObject>(
  options: RequestOptions<TBody>
): Promise<TData> {
  const app = getApp<{
    globalData: {
      apiBaseUrl: string;
      token: string;
    };
  }>();
  const headers: Record<string, string> = {
    "Content-Type": "application/json"
  };

  if (options.auth !== false && app.globalData.token) {
    headers.Authorization = `Bearer ${app.globalData.token}`;
  }

  return new Promise<TData>((resolve, reject) => {
    wx.request<ApiResponse<TData>>({
      url: `${app.globalData.apiBaseUrl}${options.url}`,
      method: options.method ? options.method : "GET",
      data: options.data,
      header: headers,
      success: (response) => {
        const body = response.data;
        if (body && body.code === SUCCESS_CODE) {
          resolve(body.data);
          return;
        }
        reject(new Error(body?.msg || "请求失败"));
      },
      fail: (error) => {
        reject(new Error(error.errMsg));
      }
    });
  });
}
