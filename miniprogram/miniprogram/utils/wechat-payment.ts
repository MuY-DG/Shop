import type { WechatPaymentParamsResponse } from "../types/order";

export function requestWechatPayment(
  params: WechatPaymentParamsResponse
): Promise<void> {
  return new Promise((resolve, reject) => {
    wx.requestPayment({
      timeStamp: params.timeStamp,
      nonceStr: params.nonceStr,
      package: params.package,
      signType: params.signType as "MD5" | "HMAC-SHA256" | "RSA",
      paySign: params.paySign,
      success: () => resolve(),
      fail: reject
    });
  });
}

export function isPaymentCancelled(error: unknown): boolean {
  if (typeof error !== "object" || error === null || !("errMsg" in error)) {
    return false;
  }
  return String(error.errMsg).toLowerCase().includes("cancel");
}
