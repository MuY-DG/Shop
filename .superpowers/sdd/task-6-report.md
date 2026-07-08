# Task 6 Report: Backend After-Sale Refund Flow

## Status

DONE

## Scope

- Added app after-sale application/detail/list APIs for paid or shipped orders.
- Added admin after-sale list/detail/audit APIs.
- Added basic WeChat refund request flow through the existing payment provider abstraction.
- Added refund notification handling at `POST /wxpay/refund/notify`.
- Kept all new API responses in the existing `{ code, msg, data }` envelope.
- Did not modify Task 5 shipping implementation.
- Did not add real WeChat certificates, keys, APIv3 keys, tokens, openids, or user-provided sensitive test values.

## TDD Evidence

### RED

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=AppAfterSaleControllerTest,AdminAfterSaleControllerTest,RefundCallbackServiceTest test
```

Result:

- Failed as expected before implementation.
- Observed 9 test failures caused by missing after-sale/refund APIs, including 404 responses such as `No static resource app/orders/.../after-sales`.

### GREEN

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=AppAfterSaleControllerTest,AdminAfterSaleControllerTest,RefundCallbackServiceTest test
```

Result:

- Passed.
- Summary observed: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.
- Final Maven result: `BUILD SUCCESS`.

Command:

```bash
cd backend/shop-server
./mvnw -Dtest=AppAfterSaleControllerTest,AdminAfterSaleControllerTest,RefundCallbackServiceTest,StorageControllerTest test
```

Result:

- Passed.
- Summary observed: `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`.
- Final Maven result: `BUILD SUCCESS`.

Command:

```bash
cd backend/shop-server
./mvnw test
```

Result:

- Passed.
- Summary observed: `Tests run: 218, Failures: 0, Errors: 0, Skipped: 0`.
- Final Maven result: `BUILD SUCCESS`.

Command:

```bash
git diff --check
```

Result:

- Passed.
- Produced no output.

## Implementation Notes

- App users can apply only for their own `PAID` or `SHIPPED` orders.
- Supported after-sale types are `REFUND_ONLY` and `RETURN_REFUND`.
- `requestedAmountCent` must be positive and no more than the paid amount.
- Evidence files are validated as current app user uploads, `ACTIVE`, `PRIVATE`, and purpose `AFTER_SALE_IMAGE` or `REFUND_EVIDENCE`.
- Evidence file usage is protected through the existing storage usage service.
- Duplicate active after-sale requests are blocked for statuses `REQUESTED`, `APPROVED`, `REFUNDING`, and `REFUND_FAILED`.
- Admin reject records status, audit note, reviewer, and reviewed time without changing the order status.
- Admin approve locks the after-sale, order, and payment rows; requires a paid payment order with a transaction reference; creates a refund order; invokes the configured WeChat provider; and moves the order to `REFUNDING`.
- Refund callback parsing and verification/decryption goes through the configured provider.
- Refund callbacks write `payment_callback_log` entries with `callback_type='REFUND'`.
- Successful refund callbacks idempotently set refund order `SUCCESS`, after-sale `REFUNDED`, and order `REFUNDED`.
- Failed refund callbacks set refund order `FAILED` and after-sale `REFUND_FAILED`, while leaving the order in `REFUNDING`.

## Files Changed

- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AdminAfterSaleController.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AfterSaleStatus.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AfterSaleType.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AppAfterSaleController.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/RefundOrderStatus.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/AdminAfterSaleAuditRequest.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/AdminAfterSaleQueryRequest.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/AfterSaleResponse.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/AppAfterSaleApplyRequest.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/RefundOrderResponse.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/service/AdminAfterSaleService.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/service/AppAfterSaleService.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/service/RefundCallbackService.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/WechatPayCallbackController.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/MockWechatPayProvider.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/RealWechatPayProvider.java`
- `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatRefundNotification.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AdminAfterSaleControllerTest.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AppAfterSaleControllerTest.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/RefundCallbackServiceTest.java`
- `backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentTestSupport.java`
- `.superpowers/sdd/task-6-report.md`

## Commit

- Commit message: `feat: add after sale refund flow`

## Concerns

- The requested `WxPayNotifyController` file does not exist in this codebase. The refund notify endpoint was added to the existing `WechatPayCallbackController`.
- The current tool surface did not expose a subagent reviewer. I performed a local diff review and then ran the required verification commands.
- Maven test output includes existing project warnings such as SpringDoc default endpoint warnings, generated Spring Security passwords, Mockito dynamic-agent warnings, and an existing shipment upload warning in tests; none blocked the verified test results.
