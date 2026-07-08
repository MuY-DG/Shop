# Shop WeChat Pay Shipping Refund Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first complete WeChat Pay JSAPI, payment configuration, admin shipment, WeChat shipping upload, after-sale, and WeChat refund loop without breaking the existing order, stock lock, coupon, file storage, admin, or mini program smoke paths.

**Architecture:** Keep the Spring Boot modular monolith and add focused `payment`, `logistics`, and `aftersale` modules. Payment provider calls go through replaceable interfaces with real WeChat implementations for `dev/prod` and deterministic mock providers for `test`; order state transitions remain owned by backend callbacks and explicit backend sync APIs, never by mini program `wx.requestPayment` success alone. Existing file storage is reused for payment certificate/private-key/public-key files and after-sale evidence, and existing response envelopes stay unchanged.

**Tech Stack:** Spring Boot 3.5.16, Java 21, MySQL/H2 through Flyway and `JdbcClient`, `wechatpay-java` 0.2.17, Art Design Pro with backend menu/RBAC mode, native WeChat mini program TypeScript, TDesign MiniProgram, Element Plus, pnpm.

## Global Constraints

- Current repo state was checked before planning: `git status --short` was clean and `git log --oneline -n 12` starts with `50ea1437 chore: clean up file storage local artifacts`.
- Do not commit real certificates, private keys, APIv3 keys, access tokens, upload directories, `target`, `node_modules`, `dist`, or `.pnpm-store`.
- Keep all JSON APIs wrapped as `{ code, msg, data }`.
- Keep admin paged APIs wrapped as `{ records, total, current, size }` inside `data`.
- Keep `dev` capable of real WeChat mini program and WeChat Pay checks, but keep `test` fully mocked for WeChat login, payment, shipping upload, and refund provider calls.
- Do not print APIv3 key, private key contents, certificate contents, access tokens, WeChat notify plaintext, or request authorization headers in logs.
- Do not return APIv3 key, private key contents, certificate contents, private file object keys, or decrypted secrets from admin or app APIs.
- Env payment config may be visible in admin only as masked metadata with `source=ENV`.
- DB payment config may be created, edited, and activated in admin, but secret fields are write-only or masked on read.
- If env config is complete and `WECHAT_PAY_CONFIG_SOURCE` is `AUTO` or `ENV`, env config is the effective runtime config; active DB config remains manageable as DB standby.
- Payment callback, refund callback, and active backend query decide final order/payment/refund state. Mini program `wx.requestPayment` success only triggers a backend refresh.
- Existing order smoke behavior must keep passing: created order closes, stock releases, and locked coupon returns to available `CLAIMED` state.
- Existing file storage purpose enum already includes `PAYMENT_CERTIFICATE`, `AFTER_SALE_IMAGE`, and `REFUND_EVIDENCE`; use these before adding new purpose values.
- Use `PRIVATE` file visibility for payment certificates, private keys, public keys, and after-sale evidence.
- Use official WeChat Pay docs as implementation inputs:
  - JSAPI/小程序下单: https://pay.weixin.qq.com/doc/v3/merchant/4012791897
  - 小程序调起支付: https://pay.weixin.qq.com/doc/v3/merchant/4012791898
  - 商户订单号查询订单: https://pay.weixin.qq.com/doc/v3/merchant/4012791900
  - 关闭订单: https://pay.weixin.qq.com/doc/v3/merchant/4012791901
  - 支付成功回调: https://pay.weixin.qq.com/doc/v3/merchant/4012791902
  - 退款申请: https://pay.weixin.qq.com/doc/v3/merchant/4012791903
  - 退款结果回调: https://pay.weixin.qq.com/doc/v3/merchant/4012791906
  - 小程序发货信息管理服务: https://developers.weixin.qq.com/miniprogram/dev/platform-capabilities/business-capabilities/order-shipping/order-shipping.html
- Use callback paths `POST /wxpay/pay/notify` and `POST /wxpay/refund/notify`; local real smoke needs HTTPS public tunnel URLs ending in these paths.
- Execute tasks sequentially. Do not parallelize tasks that touch the same migration, order service, shared API types, admin type declaration file, or mini program order page.
- After each implementation task: run the task-specific verification, request review, fix review findings, request re-review, then commit only that task's files.

---

## Current Evidence Read

- Existing plan/spec inputs:
  - `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`: V1 includes JSAPI payment, payment callback, timeout close, admin shipment, WeChat shipping upload, after-sale request, refund audit, and WeChat refund.
  - `docs/superpowers/specs/2026-07-08-shop-file-storage-design.md`: storage exists to support payment certificate/private-key files and after-sale/refund evidence.
  - `docs/superpowers/plans/2026-07-07-shop-order-implementation-plan.md`: order phase explicitly excluded payment, shipment, refund, and after-sale.
  - `docs/superpowers/plans/2026-07-08-shop-file-storage-implementation-plan.md`: latest phase added reusable storage, private file upload, usage relations, admin storage page, and home banner.
- Existing docs:
  - `docs/dev-setup.md` documents `.env.local`, real `dev` WeChat mini program credentials, `test` mock WeChat client, and local file storage.
  - `docs/smoke-checks.md` has order smoke and file storage smoke; new payment/shipping/refund sections must be appended there and must clearly separate automated mock provider checks from real local WeChat smoke.
- Existing backend facts:
  - Latest migration is `V7__storage.sql`; next migration is `V8__pay_shipping_refund.sql`.
  - `shop_order`, `order_item`, and `stock_lock` already exist.
  - `OrderStatus` currently has `CREATED`, `PAID`, `CLOSED`, `REFUNDED`.
  - `StockLockStatus` already has `LOCKED`, `RELEASED`, `CONFIRMED`.
  - `UserCouponStatus` currently has `CLAIMED`, `LOCKED`, `USED`, `EXPIRED`.
  - `OrderCloseService` currently releases coupons back to `CLAIMED`; keep that available-coupon semantic.
  - `StorageProvider#open(objectKey)` exists, but no internal private-file reader service exists yet.
  - `SecurityConfig` already permits `/wxpay/**` and `/wechat/**`.
  - `ErrorCode` already includes `PAYMENT_PENDING`, `WECHAT_SHIPPING_UPLOAD_FAILED`, and `WECHAT_REFUND_FAILED`.
  - `wechatpay-java` 0.2.17 is already in `backend/shop-server/pom.xml`.

## File Structure

Create or modify these files in order. A later task may touch files created by earlier tasks, but independent subagents must not edit the same file concurrently.

### Backend Migration And Shared Contracts

- Create: `backend/shop-server/src/main/resources/db/migration/V8__pay_shipping_refund.sql`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/OrderStatus.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/StockChangeType.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageFileUsageType.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentSchemaTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/ShipmentSchemaTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AfterSaleSchemaTest.java`

### Backend Configuration, Secrets, And Private File Loading

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/PaymentProperties.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/config/PaymentConfigSource.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/config/PaymentVerifyMode.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/config/ResolvedPaymentConfig.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/config/PaymentConfigResolver.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/config/PaymentSecretCipher.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/config/AesGcmPaymentSecretCipher.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/config/PaymentConfigMasker.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/service/PrivateStorageFileService.java`
- Modify: `backend/shop-server/src/main/resources/application.yaml`
- Modify: `backend/shop-server/src/test/resources/application-test.yaml`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentConfigResolverTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/storage/PrivateStorageFileServiceTest.java`

### Backend Payment

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/PaymentOrderStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/PaymentCallbackStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatPayProvider.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatJsapiPrepayRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatJsapiPrepayResult.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatPayOrderQueryResult.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatPayNotification.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatRefundRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatRefundResult.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatRefundNotification.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/RealWechatPayProvider.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/MockWechatPayProvider.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/service/AppPaymentService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/service/PaymentCallbackService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/service/PaymentTimeoutCloseService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/AppPaymentController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/WxPayNotifyController.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/OrderCloseService.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/payment/AppPaymentControllerTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentCallbackServiceTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentTimeoutCloseServiceTest.java`

### Backend Admin Payment Config

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/dto/AdminPaymentConfigRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/dto/AdminPaymentConfigResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/dto/EffectivePaymentConfigResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/service/AdminPaymentConfigService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/AdminPaymentConfigController.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/payment/AdminPaymentConfigControllerTest.java`

### Backend Shipment

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/WechatAccessTokenProvider.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/RestWechatMiniProgramClient.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/MockWechatMiniProgramClient.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/ShipmentStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/WechatShippingUploadStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/ShippingProperties.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/WechatShippingProvider.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/RealWechatShippingProvider.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/MockWechatShippingProvider.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/dto/AdminShipOrderRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/dto/OrderShipmentResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/AdminShipmentService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/AdminShipmentController.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderDetailResponse.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AdminOrderService.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/AdminShipmentControllerTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/WechatShippingProviderTest.java`

### Backend After-Sale And Refund

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AfterSaleType.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AfterSaleStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/RefundOrderStatus.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/AppAfterSaleApplyRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/AdminAfterSaleQueryRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/AdminAfterSaleAuditRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/AfterSaleResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/dto/RefundOrderResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/service/AppAfterSaleService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/service/AdminAfterSaleService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/service/RefundCallbackService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AppAfterSaleController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AdminAfterSaleController.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/WxPayNotifyController.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/WechatPayProvider.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/RealWechatPayProvider.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/payment/provider/MockWechatPayProvider.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AppAfterSaleControllerTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AdminAfterSaleControllerTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/RefundCallbackServiceTest.java`

### Admin Frontend

- Create: `admin/src/api/payment.ts`
- Create: `admin/src/api/aftersale.ts`
- Modify: `admin/src/api/order.ts`
- Modify: `admin/src/types/api/api.d.ts`
- Create: `admin/src/views/payment/config/index.vue`
- Modify: `admin/src/views/order/list/index.vue`
- Create: `admin/src/views/aftersale/list/index.vue`

### Mini Program

- Modify: `miniprogram/types/api.ts`
- Modify: `miniprogram/services/order.ts`
- Create: `miniprogram/services/aftersale.ts`
- Modify: `miniprogram/app.json`
- Modify: `miniprogram/pages/order/list/list.ts`
- Modify: `miniprogram/pages/order/list/list.wxml`
- Modify: `miniprogram/pages/order/detail/detail.ts`
- Modify: `miniprogram/pages/order/detail/detail.wxml`
- Modify: `miniprogram/pages/order/detail/detail.wxss`
- Create: `miniprogram/pages/aftersale/apply/apply.json`
- Create: `miniprogram/pages/aftersale/apply/apply.ts`
- Create: `miniprogram/pages/aftersale/apply/apply.wxml`
- Create: `miniprogram/pages/aftersale/apply/apply.wxss`

### Documentation

- Modify: `docs/dev-setup.md`
- Modify: `docs/smoke-checks.md`

## Data Contracts

### Migration V8 Tables

`V8__pay_shipping_refund.sql` should add the following tables and seed data in one migration so schema, RBAC, and menu contracts land together:

```sql
CREATE TABLE payment_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_name VARCHAR(80) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    mch_id VARCHAR(32) NOT NULL,
    merchant_serial_no VARCHAR(128) NOT NULL,
    api_v3_key_ciphertext TEXT NOT NULL,
    private_key_file_id BIGINT NULL,
    merchant_certificate_file_id BIGINT NULL,
    verify_mode VARCHAR(32) NOT NULL DEFAULT 'PUBLIC_KEY',
    wechat_public_key_id VARCHAR(128) NOT NULL DEFAULT '',
    wechat_public_key_file_id BIGINT NULL,
    notify_url VARCHAR(255) NOT NULL,
    refund_notify_url VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    payment_config_id BIGINT NULL,
    out_trade_no VARCHAR(64) NOT NULL,
    prepay_id VARCHAR(128) NOT NULL DEFAULT '',
    transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    payer_openid VARCHAR(128) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL,
    amount_cent BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'CNY',
    request_digest VARCHAR(64) NOT NULL DEFAULT '',
    callback_digest VARCHAR(64) NOT NULL DEFAULT '',
    last_error_code VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message VARCHAR(255) NOT NULL DEFAULT '',
    expires_at TIMESTAMP NOT NULL,
    paid_at TIMESTAMP NULL,
    closed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment_callback_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    callback_type VARCHAR(20) NOT NULL,
    notify_id VARCHAR(128) NOT NULL DEFAULT '',
    out_trade_no VARCHAR(64) NOT NULL DEFAULT '',
    out_refund_no VARCHAR(64) NOT NULL DEFAULT '',
    transaction_id VARCHAR(64) NOT NULL DEFAULT '',
    refund_id VARCHAR(64) NOT NULL DEFAULT '',
    event_type VARCHAR(64) NOT NULL DEFAULT '',
    resource_digest VARCHAR(64) NOT NULL DEFAULT '',
    raw_body_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(64) NOT NULL DEFAULT '',
    error_message VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    express_company VARCHAR(80) NOT NULL,
    tracking_no VARCHAR(80) NOT NULL,
    shipment_note VARCHAR(255) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL,
    wechat_upload_status VARCHAR(20) NOT NULL,
    wechat_error_code VARCHAR(64) NOT NULL DEFAULT '',
    wechat_error_message VARCHAR(255) NOT NULL DEFAULT '',
    retry_count INT NOT NULL DEFAULT 0,
    shipped_at TIMESTAMP NULL,
    wechat_uploaded_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE after_sale_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    after_sale_type VARCHAR(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    requested_amount_cent BIGINT NOT NULL,
    approved_amount_cent BIGINT NULL,
    audit_note VARCHAR(255) NOT NULL DEFAULT '',
    reviewed_by BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE after_sale_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE refund_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sale_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    payment_order_id BIGINT NOT NULL,
    out_refund_no VARCHAR(64) NOT NULL,
    refund_id VARCHAR(64) NOT NULL DEFAULT '',
    refund_amount_cent BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    callback_status VARCHAR(32) NOT NULL DEFAULT '',
    callback_digest VARCHAR(64) NOT NULL DEFAULT '',
    last_error_code VARCHAR(64) NOT NULL DEFAULT '',
    last_error_message VARCHAR(255) NOT NULL DEFAULT '',
    requested_at TIMESTAMP NOT NULL,
    success_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Also add:

```sql
ALTER TABLE shop_order ADD COLUMN paid_at TIMESTAMP NULL;
ALTER TABLE shop_order ADD COLUMN shipped_at TIMESTAMP NULL;
ALTER TABLE shop_order ADD COLUMN completed_at TIMESTAMP NULL;
ALTER TABLE shop_order ADD COLUMN refunding_at TIMESTAMP NULL;
ALTER TABLE shop_order ADD COLUMN refunded_at TIMESTAMP NULL;

CREATE UNIQUE INDEX uk_payment_order_out_trade_no ON payment_order(out_trade_no);
CREATE INDEX idx_payment_order_order_status ON payment_order(order_id, status);
CREATE INDEX idx_payment_order_expires_status ON payment_order(expires_at, status);
CREATE UNIQUE INDEX uk_order_shipment_order ON order_shipment(order_id);
CREATE INDEX idx_order_shipment_wechat_status ON order_shipment(wechat_upload_status, retry_count);
CREATE INDEX idx_after_sale_order_status ON after_sale_request(order_id, status);
CREATE UNIQUE INDEX uk_refund_order_out_refund_no ON refund_order(out_refund_no);
CREATE INDEX idx_refund_order_after_sale ON refund_order(after_sale_id);
```

Enforce "one active after-sale request per order" in `AppAfterSaleService` by locking the order row and rejecting existing statuses `REQUESTED`, `APPROVED`, `REFUNDING`, or `REFUND_FAILED`.

Seed RBAC:

- Payment config menu `/payment/config`, permission marks `payment:config:read`, `payment:config:write`, `payment:config:enable`.
- Shipment buttons under existing order page, permission marks `order:ship`, `order:shipping:retry`.
- After-sale menu `/aftersale/list`, permission marks `aftersale:read`, `aftersale:audit`.

### Order Status Contract

Expand backend/admin/mini-program status unions to:

```text
CREATED, PAYING, PAID, SHIPPED, COMPLETED, CLOSED, REFUNDING, REFUNDED
```

State meanings for this phase:

- `CREATED`: local order exists, stock and coupon locked, no prepay created.
- `PAYING`: WeChat prepay exists and payment is waiting for callback or backend query.
- `PAID`: payment confirmed, stock lock confirmed, coupon used, waiting for admin shipment.
- `SHIPPED`: admin shipment saved and local order is waiting for receipt.
- `COMPLETED`: reserved for later receipt completion; only add type support in this phase.
- `CLOSED`: unpaid order closed, stock released, coupon available as `CLAIMED`.
- `REFUNDING`: admin approved after-sale and refund is processing.
- `REFUNDED`: refund callback or backend query confirmed refund success.

### Payment Env Contract

Document these variables in `backend/shop-server/.env.local` and `docs/dev-setup.md`. Use placeholders only:

```properties
WECHAT_PAY_ENABLED=false
WECHAT_PAY_CONFIG_SOURCE=AUTO
WECHAT_PAY_APP_ID=your-mini-program-app-id
WECHAT_PAY_MCH_ID=your-merchant-id
WECHAT_PAY_MERCHANT_SERIAL_NO=your-merchant-certificate-serial-no
WECHAT_PAY_PRIVATE_KEY_PATH=/absolute/path/to/merchant_private_key.pem
WECHAT_PAY_API_V3_KEY=your-api-v3-key
WECHAT_PAY_NOTIFY_URL=https://your-public-domain/wxpay/pay/notify
WECHAT_PAY_REFUND_NOTIFY_URL=https://your-public-domain/wxpay/refund/notify
WECHAT_PAY_VERIFY_MODE=PUBLIC_KEY
WECHAT_PAY_PUBLIC_KEY_ID=your-wechat-public-key-id
WECHAT_PAY_PUBLIC_KEY_PATH=/absolute/path/to/wechat_public_key.pem
SHOP_PAY_EXPIRE_MINUTES=15
SHOP_PAYMENT_SECRET_KEY=base64-32-byte-key-for-db-secret-encryption
SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=false
```

The user-provided real values must remain only in local `.env.local` or external secret storage.

## Task 1: Schema, Enums, And RBAC Seeds

**Files:**
- Create: `backend/shop-server/src/main/resources/db/migration/V8__pay_shipping_refund.sql`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/order/OrderStatus.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/product/StockChangeType.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageFileUsageType.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentSchemaTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/ShipmentSchemaTest.java`
- Test: `backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AfterSaleSchemaTest.java`

**Interfaces:**
- Produces: V8 schema tables listed in Data Contracts.
- Produces: `OrderStatus.PAYING`, `OrderStatus.SHIPPED`, `OrderStatus.COMPLETED`, `OrderStatus.REFUNDING`.
- Produces: `StockChangeType.ORDER_CONFIRM`.
- Produces: `StorageFileUsageType.PAYMENT_CONFIG_CERT`, `StorageFileUsageType.AFTER_SALE_EVIDENCE`.
- Consumed by: all later payment, shipment, after-sale, admin, and mini program tasks.

- [ ] **Step 1: Write failing schema tests**

Add tests that insert representative rows into `payment_config`, `payment_order`, `payment_callback_log`, `order_shipment`, `after_sale_request`, `after_sale_evidence`, and `refund_order`. Assert seeded menus and permissions exist. Assert old order tables still accept an existing `CREATED` row and a new `PAYING` row.

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=PaymentSchemaTest,ShipmentSchemaTest,AfterSaleSchemaTest test
```

Expected: FAIL because V8 and enum values do not exist.

- [ ] **Step 2: Add V8 migration and enum values**

Implement the SQL in Data Contracts. Use IDs above existing seed ranges:

- Payment config menu IDs: `800`, `801`; permission IDs `8001` to `8003`.
- After-sale menu IDs: `820`, `821`; permission IDs `8201` and `8202`.
- Shipment permissions: `8101` and `8102` attached to existing order detail/list menu `501`.

Add `ORDER_CONFIRM` to `StockChangeType` and keep existing `ORDER_LOCK` and `ORDER_RELEASE` unchanged.

- [ ] **Step 3: Run focused schema tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=PaymentSchemaTest,ShipmentSchemaTest,AfterSaleSchemaTest test
```

Expected: PASS.

- [ ] **Step 4: Review, fix, re-review, commit**

Review for migration portability across H2 MySQL mode and MySQL. Confirm seed IDs do not collide with V1 to V7. Commit:

```bash
git add backend/shop-server/src/main/resources/db/migration/V8__pay_shipping_refund.sql backend/shop-server/src/main/java/org/muybaby/shopserver/order/OrderStatus.java backend/shop-server/src/main/java/org/muybaby/shopserver/product/StockChangeType.java backend/shop-server/src/main/java/org/muybaby/shopserver/storage/StorageFileUsageType.java backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentSchemaTest.java backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/ShipmentSchemaTest.java backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AfterSaleSchemaTest.java
git commit -m "feat: add pay shipping refund schema"
```

## Task 2: Payment Configuration, Secret Handling, And Private File Reads

**Files:**
- Create and modify the files listed under Backend Configuration, Secrets, And Private File Loading.
- Test: `PaymentConfigResolverTest`, `PrivateStorageFileServiceTest`.

**Interfaces:**
- Produces: `ResolvedPaymentConfig` with fields `source`, `enabled`, `appId`, `mchId`, `merchantSerialNo`, `apiV3Key`, `privateKeyPem`, `notifyUrl`, `refundNotifyUrl`, `verifyMode`, `wechatPublicKeyId`, `wechatPublicKeyPem`.
- Produces: masked DTO helpers that expose only `appIdMasked`, `mchIdMasked`, `merchantSerialNoMasked`, `apiV3KeyConfigured`, file IDs, and `source`.
- Consumes: `storage_file` private records and `StorageProvider#open`.

- [ ] **Step 1: Write failing config tests**

Test cases:

- Env config resolves when required env properties are present and source mode is `AUTO`.
- Active DB config resolves when env is disabled or source mode is `DB`.
- Env config response masks IDs and never exposes APIv3 key or key file contents.
- DB config stores encrypted APIv3 key and decrypts only inside resolver.
- Private file reader accepts active `PRIVATE` files with purpose `PAYMENT_CERTIFICATE` and rejects public, deleted, or wrong-purpose files.

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=PaymentConfigResolverTest,PrivateStorageFileServiceTest test
```

Expected: FAIL because services do not exist.

- [ ] **Step 2: Add properties and resolver**

Add configuration under `shop.pay`:

```yaml
shop:
  pay:
    enabled: ${WECHAT_PAY_ENABLED:false}
    config-source: ${WECHAT_PAY_CONFIG_SOURCE:AUTO}
    app-id: ${WECHAT_PAY_APP_ID:}
    mch-id: ${WECHAT_PAY_MCH_ID:}
    merchant-serial-no: ${WECHAT_PAY_MERCHANT_SERIAL_NO:}
    private-key-path: ${WECHAT_PAY_PRIVATE_KEY_PATH:}
    api-v3-key: ${WECHAT_PAY_API_V3_KEY:}
    notify-url: ${WECHAT_PAY_NOTIFY_URL:}
    refund-notify-url: ${WECHAT_PAY_REFUND_NOTIFY_URL:}
    verify-mode: ${WECHAT_PAY_VERIFY_MODE:PUBLIC_KEY}
    public-key-id: ${WECHAT_PAY_PUBLIC_KEY_ID:}
    public-key-path: ${WECHAT_PAY_PUBLIC_KEY_PATH:}
    expire-minutes: ${SHOP_PAY_EXPIRE_MINUTES:15}
    secret-key: ${SHOP_PAYMENT_SECRET_KEY:}
```

In `application-test.yaml`, set `shop.pay.enabled: true`, `shop.pay.config-source: DB`, `shop.pay.expire-minutes: 15`, and a deterministic test encryption key.

- [ ] **Step 3: Add replaceable encryption service**

Implement `PaymentSecretCipher`:

```java
public interface PaymentSecretCipher {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
```

Implement AES-GCM with random 12-byte nonce and encoded format:

```text
v1:<base64-nonce>:<base64-ciphertext>
```

If DB payment config is used and `SHOP_PAYMENT_SECRET_KEY` is missing, throw a validation error on create/update instead of silently storing plaintext. Tests may provide a deterministic local key. Do not log plaintext or ciphertext.

- [ ] **Step 4: Add private storage reader**

Implement `PrivateStorageFileService.readPrivateText(fileId, allowedPurposes)`:

- Lock down to `visibility='PRIVATE'` and `status='ACTIVE'`.
- Accept only explicit allowed purposes.
- Open through `StorageProvider#open(objectKey)`.
- Return text content for backend-only use.
- Never expose object key or content through DTOs.

- [ ] **Step 5: Run focused config tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=PaymentConfigResolverTest,PrivateStorageFileServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Review, fix, re-review, commit**

Review for accidental secret logging and DTO leaks. Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/payment backend/shop-server/src/main/java/org/muybaby/shopserver/storage/service/PrivateStorageFileService.java backend/shop-server/src/main/resources/application.yaml backend/shop-server/src/test/resources/application-test.yaml backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentConfigResolverTest.java backend/shop-server/src/test/java/org/muybaby/shopserver/storage/PrivateStorageFileServiceTest.java
git commit -m "feat: add payment config resolver"
```

## Task 3: Admin Payment Configuration API

**Files:**
- Create: `AdminPaymentConfigRequest`, `AdminPaymentConfigResponse`, `EffectivePaymentConfigResponse`, `AdminPaymentConfigService`, `AdminPaymentConfigController`.
- Test: `AdminPaymentConfigControllerTest`.

**Interfaces:**
- Produces:
  - `GET /admin/pay/configs/effective`
  - `GET /admin/pay/configs`
  - `POST /admin/pay/configs`
  - `PUT /admin/pay/configs/{configId}`
  - `POST /admin/pay/configs/{configId}/enable`
- Consumes: `PaymentConfigResolver`, `PaymentSecretCipher`, `PrivateStorageFileService`, storage usage relations.

- [ ] **Step 1: Write failing admin payment config tests**

Test cases:

- Admin token and `payment:config:read` are required for reads.
- `GET /admin/pay/configs/effective` returns masked env config with `source='ENV'` when env is effective.
- Creating DB config stores encrypted APIv3 key, references private file IDs, and returns masked response.
- Updating DB config with blank secret leaves existing encrypted APIv3 key unchanged.
- Enabling one DB config disables other DB configs.
- Public files or wrong purpose files cannot be used for payment config.

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminPaymentConfigControllerTest test
```

Expected: FAIL because API does not exist.

- [ ] **Step 2: Implement request and response DTOs**

Request fields:

```text
configName, appId, mchId, merchantSerialNo, apiV3Key, privateKeyFileId,
merchantCertificateFileId, verifyMode, wechatPublicKeyId, wechatPublicKeyFileId,
notifyUrl, refundNotifyUrl
```

Response fields:

```text
id, source, configName, appIdMasked, mchIdMasked, merchantSerialNoMasked,
apiV3KeyConfigured, privateKeyFileId, merchantCertificateFileId, verifyMode,
wechatPublicKeyIdMasked, wechatPublicKeyFileId, notifyUrl, refundNotifyUrl,
enabled, status, createdAt, updatedAt
```

- [ ] **Step 3: Implement service and controller**

Use `@PreAuthorize`:

- `payment:config:read` for list/effective/detail.
- `payment:config:write` for create/update.
- `payment:config:enable` for enable.

On create/update, call `StorageUsageService.replaceOwnerUsages(PAYMENT_CONFIG, configId, configName, ...)` with `PAYMENT_CONFIG_CERT` protected usages for every configured private file.

- [ ] **Step 4: Run focused backend tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminPaymentConfigControllerTest,PaymentConfigResolverTest,StorageControllerTest test
```

Expected: PASS.

- [ ] **Step 5: Review, fix, re-review, commit**

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/payment backend/shop-server/src/test/java/org/muybaby/shopserver/payment/AdminPaymentConfigControllerTest.java
git commit -m "feat: add admin payment configuration api"
```

## Task 4: JSAPI Prepay, Mini Program Payment Params, Payment Callback, Query Sync, And Timeout Close

**Files:**
- Create/modify files listed under Backend Payment.
- Test: `AppPaymentControllerTest`, `PaymentCallbackServiceTest`, `PaymentTimeoutCloseServiceTest`.

**Interfaces:**
- Produces:
  - `POST /app/orders/{orderId}/pay`
  - `POST /app/orders/{orderId}/cancel`
  - `POST /app/orders/{orderId}/payment/sync`
  - `POST /wxpay/pay/notify`
- Consumes:
  - `PaymentConfigResolver`
  - `WechatPayProvider`
  - existing `shop_order`, `stock_lock`, `user_coupon`, `app_user.openid`.

- [ ] **Step 1: Write failing payment flow tests**

Test cases:

- App token required; users cannot pay another user's order.
- `POST /app/orders/{orderId}/pay` on `CREATED` creates one `payment_order`, sets order to `PAYING`, stores `prepay_id`, and returns `timeStamp`, `nonceStr`, `package`, `signType`, and `paySign`.
- Repeating pay for the same active `PAYING` order returns the existing active payment params without creating another active payment row.
- Mock payment callback with matching `out_trade_no` sets `payment_order=PAID`, `shop_order=PAID`, `stock_lock=CONFIRMED`, `user_coupon=USED`, `paid_amount_cent`, `paid_at`, `payment_transaction_id`, and `merchant_trade_no`.
- Duplicate callback returns success and does not duplicate state changes.
- Invalid signature or decrypt failure returns non-success and logs no plaintext secret.
- Timeout close calls provider close order when `PAYING` expires; it sets payment order and order to `CLOSED`, releases stock, and returns coupon to `CLAIMED`.
- Frontend success is not part of backend finalization; only callback or sync changes final state.

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AppPaymentControllerTest,PaymentCallbackServiceTest,PaymentTimeoutCloseServiceTest test
```

Expected: FAIL because APIs and provider do not exist.

- [ ] **Step 2: Define provider interface**

Use this interface shape:

```java
public interface WechatPayProvider {
    WechatJsapiPrepayResult createJsapiPrepay(ResolvedPaymentConfig config, WechatJsapiPrepayRequest request);
    WechatPayOrderQueryResult queryOrder(ResolvedPaymentConfig config, String outTradeNo);
    void closeOrder(ResolvedPaymentConfig config, String outTradeNo);
    WechatPayNotification parsePayNotification(ResolvedPaymentConfig config, String timestamp, String nonce, String serial, String signature, String body);
    WechatRefundResult requestRefund(ResolvedPaymentConfig config, WechatRefundRequest request);
    WechatRefundNotification parseRefundNotification(ResolvedPaymentConfig config, String timestamp, String nonce, String serial, String signature, String body);
}
```

`MockWechatPayProvider` must be active in `test` and return deterministic values. `RealWechatPayProvider` must use `wechatpay-java` and official endpoints:

- `POST /v3/pay/transactions/jsapi`
- `GET /v3/pay/transactions/out-trade-no/{out_trade_no}?mchid=<mchid>`
- `POST /v3/pay/transactions/out-trade-no/{out_trade_no}/close`
- `POST /v3/refund/domestic/refunds`

- [ ] **Step 3: Implement app payment service**

Rules:

- Only `CREATED` and active `PAYING` orders are payable.
- `out_trade_no` format: `P` + order number or `PAY` + order id + timestamp, max 32 characters if using official JSAPI constraints.
- Use order payable amount as WeChat `amount.total`.
- Use `app_user.openid` as payer `openid`.
- Use `time_expire` from `SHOP_PAY_EXPIRE_MINUTES`.
- Use `notify_url` from resolved config.
- Response field named `package` must equal `prepay_id=<prepay_id>`.
- Do not set order `PAID` from this endpoint.

- [ ] **Step 4: Implement callback service**

Rules:

- Verify and decrypt callback through provider.
- Insert `payment_callback_log` with digest before state mutation.
- Lock `payment_order` and `shop_order` rows.
- If already paid, mark callback duplicate and return success.
- Require amount and out trade number match local payment order.
- Set `stock_lock.status='CONFIRMED'`.
- Set locked coupon to `USED`, with `used_order_id` and `used_at`.
- Set order status to `PAID` and paid fields.
- Never log decrypted callback body.

- [ ] **Step 5: Implement query sync and timeout close**

`payment/sync` must call `WechatPayProvider#queryOrder` for active `PAYING` records. If the provider reports paid success, route through the same state transition method used by payment callbacks so stock confirmation, coupon use, payment order, and shop order updates stay idempotent. Timeout close must use the official close-order endpoint through provider for active `PAYING` records, then reuse `OrderCloseService` after allowing `PAYING` close.

- [ ] **Step 6: Run focused payment tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AppPaymentControllerTest,PaymentCallbackServiceTest,PaymentTimeoutCloseServiceTest,AdminOrderControllerTest,AppOrderControllerTest test
```

Expected: PASS and existing order close tests keep `CLAIMED` coupon release behavior.

- [ ] **Step 7: Review, fix, re-review, commit**

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/payment backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/payment backend/shop-server/src/test/java/org/muybaby/shopserver/order
git commit -m "feat: add wechat jsapi payment flow"
```

## Task 5: Backend Shipment And WeChat Shipping Upload

**Files:**
- Create/modify files listed under Backend Shipment.
- Test: `AdminShipmentControllerTest`, `WechatShippingProviderTest`.

**Interfaces:**
- Produces:
  - `POST /admin/orders/{orderId}/ship`
  - `POST /admin/orders/{orderId}/shipping/retry-wechat-upload`
- Consumes:
  - `payment_order.transaction_id`
  - `shop_order.merchant_trade_no`
  - `app_user.openid`
  - existing stable token client.

- [ ] **Step 1: Write failing shipment tests**

Test cases:

- Only admin with `order:ship` can ship.
- Shipping requires order status `PAID`.
- Shipping stores express company, tracking number, note, `shipped_at`, and returns shipment fields in admin/app order detail.
- With shipping upload disabled, local shipment succeeds and `wechat_upload_status='SKIPPED'`.
- With mock shipping upload enabled, local shipment succeeds, mock provider records upload request, and `wechat_upload_status='UPLOADED'`.
- If transaction ID is missing when real upload is enabled, shipment remains local but upload status is `FAILED` with safe error code/message and retry count.
- Retry increments retry count and does not duplicate local shipment rows.

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminShipmentControllerTest,WechatShippingProviderTest test
```

Expected: FAIL because shipment APIs do not exist.

- [ ] **Step 2: Extract access token provider**

Create `WechatAccessTokenProvider#getAccessToken()` and move stable token fetching logic out of private methods in `RestWechatMiniProgramClient`. Keep the existing non-chunked JSON request body behavior. Mock provider returns `mock-access-token` in `test`.

- [ ] **Step 3: Implement shipping provider**

Real provider calls:

```text
POST https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info?access_token=<stable-token>
```

Payload must include:

- Order key using `transaction_id` when present.
- Fallback merchant order key only when official payload supports it and local payment row has `out_trade_no`.
- Payer `openid`.
- Logistics type and tracking number.

Default config:

```yaml
shop:
  wechat:
    shipping:
      upload-enabled: ${SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED:false}
```

- [ ] **Step 4: Implement admin ship and retry APIs**

On successful local shipment, set order status to `SHIPPED`. If WeChat upload fails, keep order `SHIPPED` but record `wechat_upload_status='FAILED'`, error code, message, and retry count for operator follow-up.

- [ ] **Step 5: Run focused shipment tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminShipmentControllerTest,WechatShippingProviderTest,AdminOrderControllerTest,AppOrderControllerTest test
```

Expected: PASS.

- [ ] **Step 6: Review, fix, re-review, commit**

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/logistics backend/shop-server/src/main/java/org/muybaby/shopserver/wechat backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/logistics
git commit -m "feat: add admin shipment and wechat upload"
```

## Task 6: After-Sale Application And WeChat Refund

**Files:**
- Create/modify files listed under Backend After-Sale And Refund.
- Test: `AppAfterSaleControllerTest`, `AdminAfterSaleControllerTest`, `RefundCallbackServiceTest`.

**Interfaces:**
- Produces:
  - `POST /app/orders/{orderId}/after-sales`
  - `GET /app/orders/{orderId}/after-sales`
  - `GET /app/after-sales/{afterSaleId}`
  - `GET /admin/after-sales`
  - `GET /admin/after-sales/{afterSaleId}`
  - `POST /admin/after-sales/{afterSaleId}/approve`
  - `POST /admin/after-sales/{afterSaleId}/reject`
  - `POST /wxpay/refund/notify`
- Consumes:
  - app evidence file upload
  - `WechatPayProvider#requestRefund`
  - `payment_order` successful payment fields.

- [ ] **Step 1: Write failing after-sale/refund tests**

Test cases:

- App user can apply for `REFUND_ONLY` or `RETURN_REFUND` only on their own paid/shipped order.
- Requested amount must be positive and no greater than `paid_amount_cent`.
- Evidence file IDs must belong to current app user, be active private files, and use `AFTER_SALE_IMAGE` or `REFUND_EVIDENCE`.
- Only one active after-sale request is allowed per order in this phase.
- Admin list returns paged `{ records, total, current, size }`.
- Admin reject sets status `REJECTED`, audit note, reviewer, reviewed time, and leaves order paid/shipped.
- Admin approve calls mock refund provider, creates `refund_order`, sets after-sale `APPROVED`, sets order `REFUNDING`, and records `out_refund_no`.
- Refund callback verifies/decrypts, is idempotent, sets refund success, sets order `REFUNDED`, and records callback digest.

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AppAfterSaleControllerTest,AdminAfterSaleControllerTest,RefundCallbackServiceTest test
```

Expected: FAIL because after-sale APIs do not exist.

- [ ] **Step 2: Implement app after-sale APIs**

Request fields:

```text
afterSaleType, reason, requestedAmountCent, description, evidenceFileIds
```

Types:

```text
REFUND_ONLY, RETURN_REFUND
```

Statuses:

```text
REQUESTED, APPROVED, REJECTED, REFUNDING, REFUNDED, REFUND_FAILED
```

Create protected `AFTER_SALE_EVIDENCE` usages for evidence files.

- [ ] **Step 3: Implement admin audit APIs**

Approve request fields:

```text
approvedAmountCent, auditNote
```

Reject request fields:

```text
auditNote
```

Approval rules:

- Lock after-sale, order, and payment rows.
- Require paid payment order with transaction ID or out trade number.
- Create `out_refund_no` with a deterministic prefix and unique suffix, max 64 chars.
- Call real WeChat refund provider in `dev/prod`; mock provider in `test`.
- Store provider result and set order `REFUNDING`.

- [ ] **Step 4: Implement refund callback**

Rules:

- Verify and decrypt through provider.
- Insert `payment_callback_log` with `callback_type='REFUND'`.
- Match `out_refund_no`.
- If already refunded, return success without duplicate mutations.
- On success, set `refund_order.status='SUCCESS'`, `after_sale_request.status='REFUNDED'`, and `shop_order.status='REFUNDED'`.
- On failure callback, set `refund_order.status='FAILED'` and `after_sale_request.status='REFUND_FAILED'`, keep order `REFUNDING` or restore `PAID` only if business review chooses that explicitly. For this phase, keep `REFUNDING` and expose failure to admin.

- [ ] **Step 5: Run focused after-sale tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AppAfterSaleControllerTest,AdminAfterSaleControllerTest,RefundCallbackServiceTest,StorageControllerTest test
```

Expected: PASS.

- [ ] **Step 6: Review, fix, re-review, commit**

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale backend/shop-server/src/main/java/org/muybaby/shopserver/payment backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale
git commit -m "feat: add after sale refund flow"
```

## Task 7: Admin Frontend Payment Config, Order Shipment Fields, And After-Sale Audit

**Files:**
- Create/modify files listed under Admin Frontend.

**Interfaces:**
- Consumes backend APIs from Tasks 3, 5, and 6.
- Produces admin pages and buttons wired to backend RBAC marks.

- [ ] **Step 1: Add admin API wrappers and types**

Add:

- `fetchEffectivePaymentConfig`, `fetchPaymentConfigs`, `createPaymentConfig`, `updatePaymentConfig`, `enablePaymentConfig`.
- `shipOrder`, `retryOrderShippingUpload`.
- `fetchAfterSales`, `fetchAfterSaleDetail`, `approveAfterSale`, `rejectAfterSale`.

Update `Api.Storage.UsageType` from `PAYMENT_CONFIGURATION` to `PAYMENT_CONFIG_CERT` so frontend matches backend.

- [ ] **Step 2: Build payment config page**

Route/menu path: `/payment/config`, component `/payment/config`.

Page behavior:

- Shows effective config card with source `ENV` or `DB`.
- Shows masked env fields and read-only source badge when env is effective.
- Lists DB configs and active flag.
- Create/edit form accepts app id, merchant id, serial no, APIv3 key, callback URLs, verify mode, WeChat public key id, and file picker fields.
- File picker restricts to `PAYMENT_CERTIFICATE` private files.
- APIv3 key input is write-only on edit and displays only "已配置".

- [ ] **Step 3: Extend order detail drawer**

Add:

- Payment fields: out trade no, transaction id, paid time, payment status.
- Shipment fields: express company, tracking number, shipment note, shipped time, WeChat upload status, error, retry count.
- Ship action for `PAID` rows guarded by `v-auth="'order:ship'"`.
- Retry action for failed WeChat upload guarded by `v-auth="'order:shipping:retry'"`.

- [ ] **Step 4: Build after-sale audit page**

Route/menu path: `/aftersale/list`, component `/aftersale/list`.

Page behavior:

- Paged table with after-sale id, order no, type, status, requested amount, approved amount, reason, created time.
- Detail drawer with order summary, evidence file ids/metadata, refund order fields, callback status.
- Approve dialog with approved amount and audit note.
- Reject dialog with audit note.
- Buttons guarded with `v-auth="'aftersale:audit'"`.

- [ ] **Step 5: Run admin checks**

Run:

```bash
cd admin
pnpm typecheck
CI=true pnpm build
```

Expected: PASS.

- [ ] **Step 6: Review, fix, re-review, commit**

Commit:

```bash
git add admin/src/api/payment.ts admin/src/api/aftersale.ts admin/src/api/order.ts admin/src/types/api/api.d.ts admin/src/views/payment/config admin/src/views/order/list/index.vue admin/src/views/aftersale/list
git commit -m "feat: add admin pay shipment refund pages"
```

## Task 8: Mini Program Pay, Cancel, Refund Application, And After-Sale Status

**Files:**
- Create/modify files listed under Mini Program.

**Interfaces:**
- Consumes:
  - `POST /app/orders/{orderId}/pay`
  - `POST /app/orders/{orderId}/cancel`
  - `POST /app/orders/{orderId}/payment/sync`
  - app after-sale APIs.
- Produces:
  - order detail pay/cancel/refund actions
  - after-sale apply page using existing upload service.

- [ ] **Step 1: Add mini program types and services**

Add types:

```text
OrderStatus = CREATED | PAYING | PAID | SHIPPED | COMPLETED | CLOSED | REFUNDING | REFUNDED
PaymentPrepayResponse = { paymentOrderId, outTradeNo, timeStamp, nonceStr, package, signType, paySign, expiresAt }
AfterSaleType = REFUND_ONLY | RETURN_REFUND
AfterSaleStatus = REQUESTED | APPROVED | REJECTED | REFUNDING | REFUNDED | REFUND_FAILED
```

Add service methods:

- `payOrder(orderId)`
- `cancelOrder(orderId)`
- `syncOrderPayment(orderId)`
- `applyAfterSale(orderId, payload)`
- `getOrderAfterSales(orderId)`

- [ ] **Step 2: Wire payment button**

On `CREATED` or `PAYING`, show primary payment button. Flow:

1. Call `payOrder`.
2. Call `wx.requestPayment` with returned `timeStamp`, `nonceStr`, `package`, `signType`, `paySign`.
3. On `success`, call `syncOrderPayment(orderId)` and reload detail.
4. On `fail` or cancel, do not mark order paid locally; show current backend state after reload.

- [ ] **Step 3: Wire cancel button**

On `CREATED` or `PAYING`, call `cancelOrder`, then reload detail. Backend handles stock/coupon release.

- [ ] **Step 4: Build after-sale apply page**

Fields:

- Type segmented choice: 仅退款, 退货退款.
- Reason selector.
- Refund amount input in yuan, converted to cents.
- Description textarea.
- Evidence image picker using `uploadEvidenceFile(filePath, "REFUND_EVIDENCE")`.

Submit to `POST /app/orders/{orderId}/after-sales` and return to order detail.

- [ ] **Step 5: Show after-sale status**

Order detail shows latest after-sale status and disables duplicate application while active. Show refund amount and audit note when available.

- [ ] **Step 6: Run mini program check**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 7: Review, fix, re-review, commit**

Commit:

```bash
git add miniprogram/types/api.ts miniprogram/services/order.ts miniprogram/services/aftersale.ts miniprogram/app.json miniprogram/pages/order miniprogram/pages/aftersale
git commit -m "feat: add mini program pay and refund actions"
```

## Task 9: Documentation And Smoke Checks

**Files:**
- Modify: `docs/dev-setup.md`
- Modify: `docs/smoke-checks.md`

**Interfaces:**
- Produces local setup documentation and smoke checklists.
- Consumes all backend/admin/mini program features from earlier tasks.

- [ ] **Step 1: Update dev setup**

Add a "Local WeChat Pay Credentials" section with placeholder env only:

- `WECHAT_PAY_ENABLED`
- `WECHAT_PAY_CONFIG_SOURCE`
- `WECHAT_PAY_APP_ID`
- `WECHAT_PAY_MCH_ID`
- `WECHAT_PAY_MERCHANT_SERIAL_NO`
- `WECHAT_PAY_PRIVATE_KEY_PATH`
- `WECHAT_PAY_API_V3_KEY`
- `WECHAT_PAY_NOTIFY_URL`
- `WECHAT_PAY_REFUND_NOTIFY_URL`
- `WECHAT_PAY_VERIFY_MODE`
- `WECHAT_PAY_PUBLIC_KEY_ID`
- `WECHAT_PAY_PUBLIC_KEY_PATH`
- `SHOP_PAY_EXPIRE_MINUTES`
- `SHOP_PAYMENT_SECRET_KEY`
- `SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED`

Document:

- Payment callback path: `/wxpay/pay/notify`.
- Refund callback path: `/wxpay/refund/notify`.
- For real local smoke, expose backend through HTTPS tunnel and use those public URLs.
- Do not commit `.env.local`, cert files, key files, upload root, or screenshots containing secrets.

- [ ] **Step 2: Add mock payment automated smoke**

Append `docs/smoke-checks.md#mock-payment-automated-smoke-checks`:

- Start backend in `test` profile.
- Create product/cart/coupon/order using existing order smoke setup.
- Call `POST /app/orders/{orderId}/pay`.
- Assert response contains `timeStamp`, `nonceStr`, `package`, `signType`, `paySign`.
- Use test-only mock callback helper or backend test endpoint only if implemented under test profile; otherwise instruct running backend tests as the automated mock smoke.
- Assert order becomes `PAID`, stock lock becomes `CONFIRMED`, coupon becomes `USED`.
- Run timeout close scenario for a new unpaid order and assert `CLOSED`, stock release, coupon `CLAIMED`.

- [ ] **Step 3: Add real WeChat payment local smoke checklist**

Add checklist clearly marked "manual real smoke, not automated":

- Fill `.env.local` with real credentials.
- Use HTTPS tunnel.
- Set callback URLs to:
  - `https://<public-domain>/wxpay/pay/notify`
  - `https://<public-domain>/wxpay/refund/notify`
- Upload payment private files through admin storage if using DB config.
- Create order in real mini program.
- Tap payment, complete WeChat payment.
- Verify backend receives callback and order becomes `PAID`.
- If callback is delayed, use backend sync and verify final state still comes from backend.

- [ ] **Step 4: Add shipment smoke checklist**

Add:

- With `SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=false`, admin ships paid order and sees local `SHIPPED` with `SKIPPED` upload status.
- With real upload enabled and paid transaction id present, admin ships order and sees WeChat upload status.
- Retry failed upload from admin.
- Verify no access token appears in logs.

- [ ] **Step 5: Add refund smoke checklist**

Add:

- Mini program uploads `REFUND_EVIDENCE` file.
- Mini program applies for refund-only.
- Admin approves and mock provider marks refund processing in automated test.
- Real local smoke completes WeChat refund and waits for refund callback.
- Verify refund order status and order `REFUNDED`.
- Include rejection path smoke.

- [ ] **Step 6: Review, fix, re-review, commit**

Commit:

```bash
git add docs/dev-setup.md docs/smoke-checks.md
git commit -m "docs: add pay shipping refund smoke checks"
```

## Task 10: Full Verification And Handoff

**Files:**
- No feature file changes unless verification exposes fixes.

**Interfaces:**
- Consumes every previous task.
- Produces final verification evidence and clean git state.

- [ ] **Step 1: Run backend full tests**

Run:

```bash
cd backend/shop-server
./mvnw test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run admin checks**

Run:

```bash
cd admin
pnpm typecheck
CI=true pnpm build
```

Expected: both PASS.

- [ ] **Step 3: Run mini program typecheck**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 4: Check git state including ignored files**

Run:

```bash
git status --short --ignored
```

Expected:

- Tracked files are clean after commits.
- Ignored/local-only entries may include upload roots, `target`, `node_modules`, `.pnpm-store`, or dev-tool generated files, and must be reported separately.

- [ ] **Step 5: Final report**

Report:

- Plan file path.
- Completed task commits.
- Automated verification results.
- Which smoke checks are mock provider automation.
- Which smoke checks require the user to run real WeChat payment, shipping upload, and refund in WeChat/merchant environments.
- Explicitly state that real local WeChat smoke is not claimed unless the user runs it and provides evidence.

## Acceptance Mapping

- 微信支付配置: Tasks 1, 2, 3, 7, 9.
- Env and `.env.local`: Tasks 2 and 9.
- Admin DB config and active selection: Tasks 3 and 7.
- Env masked admin visibility: Tasks 2, 3, 7.
- Private file upload for certificates/keys: existing storage plus Tasks 2, 3, 7.
- No plaintext secret return/logging: Tasks 2, 3, 4, 6, 9.
- JSAPI prepay and `wx.requestPayment`: Tasks 4 and 8.
- Backend callback and active sync authority: Tasks 4 and 8.
- Payment callback verification, decryption, idempotency: Task 4.
- Timeout close and stock/coupon release: Task 4.
- Test profile mock provider: Tasks 2, 4, 5, 6, 9.
- `payment_order` equivalent table: Task 1.
- Order state compatibility: Tasks 1, 4, 5, 6, 7, 8.
- Stock confirmation and coupon use on pay success: Task 4.
- Admin shipment fields and WeChat shipping upload: Tasks 1, 5, 7, 9.
- Reuse stable token client: Task 5.
- Shipping upload switch and retry recording: Tasks 5, 7, 9.
- After-sale refund-only and return-refund: Tasks 1, 6, 8, 9.
- Evidence upload reuse: Tasks 6 and 8.
- Admin after-sale audit: Tasks 6 and 7.
- Real WeChat refund in first version with mock tests: Tasks 4, 6, 9.
- Refund callback verification, decryption, idempotency: Task 6.
- Response and pagination contracts: all backend API tasks.
- Art Design Pro menu/RBAC seed and frontend route alignment: Tasks 1 and 7.
- Dev setup and smoke docs: Task 9.
- Final verification commands: Task 10.

## Self-Review

- Spec coverage: every numbered user requirement maps to at least one task in Acceptance Mapping.
- Placeholder scan: no implementation task depends on unspecified callback paths; paths are `/wxpay/pay/notify` and `/wxpay/refund/notify`.
- Secret scan requirement: before committing the plan or docs, run a search for the actual user-provided APIv3 key, merchant serial, app id, merchant id, and local certificate path fragments. The plan and docs must contain only variable names and placeholder values.
- Type consistency: backend enum values are mirrored in admin `Api.Order.OrderStatus` and mini program `OrderStatus`; backend `PAYMENT_CONFIG_CERT` usage type must replace the stale admin `PAYMENT_CONFIGURATION` type.
- Scope control: complex customer-service negotiation, multiple partial refunds, return logistics tracking, member points, and third-party logistics tracking are outside this phase.
