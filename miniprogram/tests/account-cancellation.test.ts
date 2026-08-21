import assert from "node:assert/strict";
import test from "node:test";
import { buildAccountCancellationBlockers } from "../miniprogram/features/account-cancellation";

test("account cancellation blockers only expose active obligations", () => {
  assert.deepEqual(buildAccountCancellationBlockers({
    eligible: false,
    activeOrderCount: 2,
    activePaymentCount: 0,
    activeRefundCount: 1,
    activeAfterSaleCount: 0
  }), [
    { key: "order", label: "进行中订单", count: 2 },
    { key: "refund", label: "待处理退款", count: 1 }
  ]);
});
