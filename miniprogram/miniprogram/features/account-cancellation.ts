import type { AccountCancellationEligibilityResponse } from "../types/account-cancellation";

export interface AccountCancellationBlocker {
  key: "order" | "payment" | "refund" | "afterSale";
  label: string;
  count: number;
}

export function buildAccountCancellationBlockers(
  eligibility: AccountCancellationEligibilityResponse | null
): AccountCancellationBlocker[] {
  if (!eligibility) {
    return [];
  }
  const blockers: AccountCancellationBlocker[] = [
    { key: "order", label: "进行中订单", count: eligibility.activeOrderCount },
    { key: "payment", label: "待处理支付", count: eligibility.activePaymentCount },
    { key: "refund", label: "待处理退款", count: eligibility.activeRefundCount },
    { key: "afterSale", label: "进行中售后", count: eligibility.activeAfterSaleCount }
  ];
  return blockers.filter((item) => item.count > 0);
}
