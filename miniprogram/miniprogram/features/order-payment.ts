import {
  initiateOrderPayment,
  syncOrderPayment
} from "../services/order";
import type { PaymentSyncResponse } from "../types/order";
import {
  isPaymentCancelled,
  requestWechatPayment
} from "../utils/wechat-payment";

export type OrderPaymentOutcome = "PAID" | "PENDING" | "CANCELLED";

export async function executeOrderPayment(orderId: number): Promise<OrderPaymentOutcome> {
  const params = await initiateOrderPayment(orderId);
  try {
    await requestWechatPayment(params);
  } catch (error) {
    if (isPaymentCancelled(error)) {
      return "CANCELLED";
    }
    try {
      const uncertainResult = await syncOrderPayment(orderId);
      if (uncertainResult.status === "PAID") {
        return "PAID";
      }
    } catch {
      // 原始支付错误更能说明本地失败；页面仍会重新加载服务端订单状态。
    }
    throw error;
  }
  const result = await syncOrderPayment(orderId);
  return result.status === "PAID" ? "PAID" : "PENDING";
}

export async function recoverOrderPayment(
  orderId: number
): Promise<PaymentSyncResponse> {
  return syncOrderPayment(orderId);
}
