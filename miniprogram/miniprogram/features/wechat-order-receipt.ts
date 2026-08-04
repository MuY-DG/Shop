import { isApiError } from "../utils/api-error";

const WECHAT_ORDER_CONFIRM_APP_ID = "wx1183b055aeec94d1";
const WECHAT_RECEIPT_NOT_CONFIRMED_CODE = 600002;

export type WechatReceiptComponentOutcome =
  | "SUCCESS"
  | "CANCELLED"
  | "FAILED"
  | "UNKNOWN";

export interface WechatReceiptComponentResult {
  outcome: WechatReceiptComponentOutcome;
  message?: string;
}

interface BusinessViewCallbackResult {
  errMsg?: string;
  extraData?: Record<string, unknown>;
}

interface BusinessViewOptions {
  businessType: "weappOrderConfirm";
  extraData: {
    transaction_id: string;
  };
  success?: (result: BusinessViewCallbackResult) => void;
  fail?: (result: BusinessViewCallbackResult) => void;
}

export interface WechatReceiptRuntime {
  openBusinessView?: (options: BusinessViewOptions) => void;
}

export interface WechatReceiptShowOptions {
  referrerInfo?: {
    appId?: string;
    extraData?: Record<string, unknown>;
  };
}

export interface ConfirmWechatOrderReceiptOptions {
  transactionId: string;
  confirmLocalReceipt: () => Promise<unknown>;
  runtime?: WechatReceiptRuntime;
}

interface PendingConfirmation {
  transactionId: string;
  resolve: (result: WechatReceiptComponentResult) => void;
}

let pendingConfirmation: PendingConfirmation | null = null;

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object"
    ? value as Record<string, unknown>
    : null;
}

function callbackResult(
  extraData: Record<string, unknown>,
  transactionId: string
): WechatReceiptComponentResult | null {
  const status = typeof extraData.status === "string"
    ? extraData.status.toLowerCase()
    : "";
  if (!status) {
    return null;
  }

  const requestData = asRecord(extraData.req_extradata);
  const returnedTransactionId = requestData?.transaction_id;
  if (
    typeof returnedTransactionId === "string" &&
    returnedTransactionId !== transactionId
  ) {
    return { outcome: "UNKNOWN" };
  }

  if (status === "success") {
    return { outcome: "SUCCESS" };
  }
  if (status === "cancel") {
    return { outcome: "CANCELLED" };
  }
  if (status === "fail") {
    return {
      outcome: "FAILED",
      message: "微信确认收货失败，请稍后重试"
    };
  }
  return { outcome: "UNKNOWN" };
}

function settlePending(result: WechatReceiptComponentResult): boolean {
  const pending = pendingConfirmation;
  if (!pending) {
    return false;
  }
  pendingConfirmation = null;
  pending.resolve(result);
  return true;
}

export function handleWechatReceiptAppShow(
  options?: WechatReceiptShowOptions
): boolean {
  const pending = pendingConfirmation;
  if (!pending) {
    return false;
  }
  const referrerInfo = options?.referrerInfo;
  if (referrerInfo?.appId !== WECHAT_ORDER_CONFIRM_APP_ID) {
    return settlePending({ outcome: "UNKNOWN" });
  }
  const extraData = asRecord(referrerInfo.extraData);
  return settlePending(
    extraData
      ? callbackResult(extraData, pending.transactionId) || { outcome: "UNKNOWN" }
      : { outcome: "UNKNOWN" }
  );
}

export function openWechatReceiptConfirmation(
  transactionId: string,
  runtime: WechatReceiptRuntime = wx as unknown as WechatReceiptRuntime
): Promise<WechatReceiptComponentResult> {
  const normalizedTransactionId = String(transactionId || "").trim();
  if (!normalizedTransactionId) {
    return Promise.resolve({
      outcome: "FAILED",
      message: "订单缺少微信支付单号，请联系客服处理"
    });
  }
  if (pendingConfirmation) {
    return Promise.resolve({
      outcome: "FAILED",
      message: "已有确认收货操作正在进行"
    });
  }
  if (typeof runtime.openBusinessView !== "function") {
    return Promise.resolve({
      outcome: "FAILED",
      message: "当前微信版本不支持确认收货，请升级微信后重试"
    });
  }

  return new Promise((resolve) => {
    pendingConfirmation = {
      transactionId: normalizedTransactionId,
      resolve
    };
    try {
      runtime.openBusinessView?.({
        businessType: "weappOrderConfirm",
        extraData: {
          transaction_id: normalizedTransactionId
        },
        success: (result) => {
          const extraData = asRecord(result?.extraData);
          const parsed = extraData
            ? callbackResult(extraData, normalizedTransactionId)
            : null;
          if (parsed) {
            settlePending(parsed);
          }
        },
        fail: () => {
          settlePending({
            outcome: "FAILED",
            message: "暂时无法打开微信确认收货，请稍后重试"
          });
        }
      });
    } catch {
      settlePending({
        outcome: "FAILED",
        message: "暂时无法打开微信确认收货，请稍后重试"
      });
    }
  });
}

export async function confirmWechatOrderReceipt(
  options: ConfirmWechatOrderReceiptOptions
): Promise<WechatReceiptComponentResult> {
  try {
    await options.confirmLocalReceipt();
    return { outcome: "SUCCESS" };
  } catch (error) {
    if (
      !isApiError(error)
      || error.code !== WECHAT_RECEIPT_NOT_CONFIRMED_CODE
    ) {
      throw error;
    }
  }

  const componentResult = await openWechatReceiptConfirmation(
    options.transactionId,
    options.runtime
  );
  if (
    componentResult.outcome === "CANCELLED"
    || componentResult.outcome === "FAILED"
  ) {
    return componentResult;
  }

  await options.confirmLocalReceipt();
  return { outcome: "SUCCESS" };
}
