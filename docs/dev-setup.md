# Development Setup

## Runtime Targets

- Java source target: 21
- Node.js target for admin tooling: 20.19.0 or newer
- Package manager: pnpm

## Repository Layout

```text
Shop/
  backend/shop-server/  Spring Boot backend
  admin/                Art Design Pro admin console
  miniprogram/          Native WeChat mini program
  docs/                 Design and implementation docs
```

## Backend Checks

Environment-specific secrets are selected by the active Spring profile:

- `dev` imports the optional ignored file `backend/shop-server/.env.dev.local`.
- `prod` requires the ignored file `backend/shop-server/.env.prod.local`.
- `test` uses `src/test/resources/application-test.yaml`.

Choose the profile externally rather than storing `spring.profiles.active` in either
environment file:

```bash
cd backend/shop-server
./mvnw -Dspring-boot.run.profiles=dev spring-boot:run
```

Production systemd must pass `--spring.profiles.active=prod`. Do not edit the common
`application.yaml` when switching environments.

Focused authentication/RBAC tests:

```bash
cd backend/shop-server
./mvnw -Dtest='OpaqueTokenServiceTest,InMemoryTokenStoreTest,PathTokenKindResolverTest,SecurityConfigTest,AdminRbacSchemaTest,AdminAuthControllerTest,AdminMenuControllerTest,AppAuthControllerTest' test
```

Focused product catalog tests:

```bash
cd backend/shop-server
./mvnw -Dtest='ProductCatalogSchemaTest,AdminProductServiceTest,AdminProductCategoryControllerTest,AdminProductSpuControllerTest,AppProductControllerTest,SecurityConfigTest' test
```

Focused cart tests:

```bash
cd backend/shop-server
./mvnw -Dtest='CartSchemaTest,AppCartControllerTest,AppAuthControllerTest,SecurityConfigTest,PathTokenKindResolverTest' test
```

Focused coupon tests:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponSchemaTest,AdminCouponTemplateControllerTest,AppCouponControllerTest,CouponDiscountCalculatorTest test
```

Docker-free backend unit/H2 layer:

```bash
cd backend/shop-server
./mvnw test
```

This is the default fast layer. It intentionally excludes every JUnit 5 test tagged
`integration`, so `BUILD SUCCESS` here does not claim that MySQL, Redis, Testcontainers,
or database concurrency behavior was exercised.

Docker/Testcontainers integration layer:

```bash
cd backend/shop-server
docker info
./mvnw -Pintegration verify
./scripts/assert-integration-test-results.sh target/failsafe-reports
./scripts/verify-test-layers.sh
```

The integration profile runs the tagged suite through Maven Failsafe against the pinned
`mysql:8.4.10` and `redis:7.4.9-alpine` images. The report gate requires all current
integration suites to produce XML results, a non-zero executed test count, and zero
skipped tests. A missing Docker daemon is a failure, not an accepted skip.

Flyway file naming, uniqueness, and continuous integer versions are checked separately:

```bash
cd backend/shop-server
./scripts/verify-flyway-migrations.sh
```

Expected result for each executed layer:

```text
BUILD SUCCESS
```

## V85-V97 Focused Gates

Run the focused backend slices from `backend/shop-server` before both test layers. These
commands validate H2/Flyway and application behavior; they do not replace a disposable
MySQL migration/concurrency run or any production-provider smoke check.

```bash
# V85 fixed order deadline and timeout close
./mvnw -Dtest='OrderSchemaTest,CreatedOrderTimeoutCloseServiceTest,CreatedOrderTimeoutCloseSchedulerTest,PaymentInitiationServiceTest' test

# V86 verified refund finalization and inventory disposition
./mvnw -Dtest='AfterSaleSchemaTest,RefundCallbackServiceTest,RefundRecoveryServiceTest' test

# V87 recoverable WeChat shipment delivery and truthful receipt behavior
./mvnw -Dtest='ShipmentSchemaTest,WechatShippingUploadCoordinatorTest,WechatShippingUploadRecoveryTest,WechatShippingDeliverySchedulerTest,WechatReceiptReconciliationServiceTest,AppOrderWechatReceiptTest' test

# V88 immutable merchant/legal publication and exact privacy consent
./mvnw -Dtest='ComplianceControllerTest' test

# V89 product food disclosure and publication gate
./mvnw -Dtest='ProductFoodComplianceSchemaTest,ProductFoodComplianceServiceTest,ProductFoodComplianceControllerTest' test

# V90 account-rights state, authorization, and active-obligation gate
./mvnw -Dtest='AccountRightsSchemaTest,AccountRightsControllerTest,AccountRightsObligationServiceTest' test

# V93 and V97 WeChat trade-bill reconciliation and Admin runtime control
./mvnw -Dtest='*FinanceReconciliation*Test,AdminFinanceReconciliationControllerTest' test

# V94-V95 WeChat 2001 service-card delivery and Admin runtime control
./mvnw -Dtest='*WechatServiceCard*Test' test
```

Cross-surface gates:

```bash
cd admin
pnpm check
pnpm build

cd ../miniprogram
pnpm check
```

The Mini Program gate includes runtime environment, explicit privacy-consent ordering,
customer-service order routing, public compliance rendering, food disclosure,
account-rights, and the source contract that preserves the current profile V frame,
crown, `金牌会员` text, member-card assets, and logged-in display condition.

## V87-V97 Runtime And Publication Controls

### V87 WeChat Shipment Delivery

`SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=false` is the safe default. When it is enabled for
a verified real Mini Program and paid order, local shipment creation writes durable
delivery work; the immediate provider call is only an optimization. Configure bounded
delivery and reconciliation explicitly:

```properties
SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=false
SHOP_WECHAT_SHIPPING_HTTP_CONNECT_TIMEOUT=3s
SHOP_WECHAT_SHIPPING_HTTP_READ_TIMEOUT=15s
SHOP_WECHAT_SHIPPING_HTTP_MAX_RESPONSE_SIZE=1MB
SHOP_WECHAT_SHIPPING_DELIVERY_ENABLED=true
SHOP_WECHAT_SHIPPING_DELIVERY_DELAY=15s
SHOP_WECHAT_SHIPPING_DELIVERY_BATCH_SIZE=50
SHOP_WECHAT_SHIPPING_DELIVERY_CLAIM_TIMEOUT=1m
SHOP_WECHAT_SHIPPING_DELIVERY_MAX_ATTEMPTS=8
SHOP_WECHAT_SHIPPING_DELIVERY_RETRY_BACKOFF=30s
SHOP_WECHAT_SHIPPING_DELIVERY_MAX_RETRY_BACKOFF=30m
SHOP_WECHAT_SHIPPING_UNKNOWN_RECHECK_INTERVAL=1m
SHOP_WECHAT_SHIPPING_UNKNOWN_NOT_UPLOADED_CONFIRMATIONS=2
SHOP_WECHAT_RECEIPT_RECONCILIATION_ENABLED=true
```

Do not blindly retry `UNKNOWN`: reconcile provider identity and shipment facts first.
`FAILED` remains an operator decision. `REAL + UPLOADED` keeps WeChat as receipt
authority; an ambiguous `UPLOADING/UNKNOWN` blocks local receipt, while audited local
confirmation is available for states that are truthfully not uploaded. A test-profile or
mock result is not evidence that the production WeChat order accepted shipment data.

### V88 Runtime Environment And Legal Publication

Mini Program privacy disclosure is owned by the WeChat Mini Program platform. The login
page reads the platform contract name with `wx.getPrivacySetting`, opens the read-only
contract with `wx.openPrivacyContract`, and does not send a backend legal-document version
to `/app/auth/login`. Login preloading starts only after the user checks the agreement.
The production profile therefore keeps `shop.compliance.privacy-consent-required=false`.

No merchant qualification or legal document is seeded. Use the admin compliance pages
to create, preview, and publish immutable revisions only after real data and managed
license images have been verified. Backend privacy revisions may be retained as independent
business records, but the Mini Program privacy entry does not use them. The anonymous read
routes are:

```text
GET /app/compliance/merchant
GET /app/compliance/documents/PRIVACY_POLICY/current
GET /app/compliance/documents/USER_AGREEMENT/current
GET /app/compliance/documents/AFTER_SALE_POLICY/current
```

The Mini Program resolves `develop`, `trial`, and `release` separately. The checked-in
release API is `https://api.muybaby6.icu`; release still rejects missing/placeholder,
localhost, loopback, non-HTTPS, and `pay-dev` hosts and never falls back to development.
The admin hostname is `https://admin.muybaby6.icu`. DNS and SAN TLS have been established,
but every release must still recheck ingress, WeChat legal domains, callbacks, and a real
device against the deployed Git SHA.

### V89 Product Publication

Every migrated product starts as `UNCLASSIFIED`. New publication is blocked until an
operator records `NON_FOOD` for a genuinely non-food item or `FOOD` with verified
structured facts, managed label images, truthful net content on every enabled SKU, and a
current published merchant food qualification. Rich-text detail is supplemental and does
not satisfy the gate.

`V89` does not invent classifications and does not automatically take historical
`ON_SALE + UNCLASSIFIED` rows off sale. Run the read-only report in
[smoke-checks.md](smoke-checks.md#v89-product-classification-gate) and resolve every row
before enabling production traffic. Never copy the document's non-food fixture
classification onto a real food.

### V90 Account Rights

Users can submit, inspect, and withdraw rights requests under
`/app/account-rights/requests`; authorized operators handle them under
`/admin/account-rights/requests`. Every admin transition requires the current version, a
nonblank reason, and a nonblank retention explanation; the retained-data category list
may be empty only when the operator truthfully declares that no category is retained.

Account cancellation additionally requires a fresh WeChat code bound to the same user
and cannot complete while active orders, payments, refunds, or after-sales exist.
Completion invalidates access and refresh sessions and minimally anonymizes optional
identity data while preserving required transaction/audit records. Assign a real owner,
review SLA, escalation route, and reviewed retention rules before release; automated
tests cannot decide those merchant/legal obligations.

### V93/V97 WeChat Trade-Bill Reconciliation

V93 downloads only the WeChat `ALL` trade bill and compares it with local payment and refund
facts. V97 keeps the worker and daily scheduler installed, but gates each execution through a
versioned database runtime override exposed on Admin **财务管理 → 财务对账**. The deployment
properties below are fallback defaults only until the first database override is saved:

```properties
SHOP_FINANCE_RECONCILIATION_WORKER_ENABLED=false
SHOP_FINANCE_RECONCILIATION_DAILY_ENABLED=false
SHOP_FINANCE_RECONCILIATION_DAILY_CRON=0 30 10 * * *
```

Runtime endpoints are:

```text
GET /admin/finance/reconciliation/runtime
PUT /admin/finance/reconciliation/runtime
```

Reading requires `finance:reconciliation:read`; changing the override requires
`finance:reconciliation:runtime:write`, which V97 grants only to Super. The PUT body is
`{workerEnabled, dailyEnabled, version, reason}`. Changes use CAS and an append-only audit.
Enabling checks usable payment reconciliation credentials and configured private COS storage;
readiness failures never block emergency shutdown. Enable the worker alone, manually verify a
real bill, then enable daily scheduling in a later revision. No restart or deployment is required
for later switch changes.

### V94-V95 WeChat 2001 Shopping Service Dynamic

V94 implements only the new WeChat `notify_type=2001` **购物（实体物流）服务动态**.
It is not the traditional `wx.requestSubscribeMessage` flow, does not send a private
template ID in `set_user_notify`, and requires no new Mini Program page or consent call.
`SHOP_WECHAT_SERVICE_CARD_TEMPLATE_RECORD_ID` stores the account's existing template
record ID only for readiness/audit. The provider sends the payment's immutable WeChat
`transaction_id` as `notify_code`; it never substitutes the merchant order number.

The deployment values below are safe defaults. V95 adds a database-backed runtime override
for Capture and Worker, exposed only through Admin **开发配置 → 微信服务动态**. Once an
override exists, it survives restarts and takes precedence over the two deployment defaults.
Callback remains environment-only because its Token/AES material must never enter the database
or Admin API.

```properties
SHOP_WECHAT_SERVICE_CARD_CAPTURE_ENABLED=false
SHOP_WECHAT_SERVICE_CARD_WORKER_ENABLED=false
SHOP_WECHAT_SERVICE_CARD_CALLBACK_ENABLED=false

SHOP_WECHAT_SERVICE_CARD_TEMPLATE_RECORD_ID=<account-template-record-id>
SHOP_WECHAT_SERVICE_CARD_FALLBACK_IMAGE=https://admin.muybaby6.icu/wechat/service-card-placeholder.png
SHOP_WECHAT_SERVICE_CARD_IMAGE_HOSTS=admin.muybaby6.icu

SHOP_WECHAT_SERVICE_CARD_DELAY=15s
SHOP_WECHAT_SERVICE_CARD_BATCH_SIZE=50
SHOP_WECHAT_SERVICE_CARD_CLAIM_TIMEOUT=2m
SHOP_WECHAT_SERVICE_CARD_MAX_SET_ATTEMPTS=8
SHOP_WECHAT_SERVICE_CARD_RETRY_BACKOFF=1m
SHOP_WECHAT_SERVICE_CARD_MAX_RETRY_BACKOFF=30m
SHOP_WECHAT_SERVICE_CARD_UNKNOWN_RECHECK=1m
SHOP_WECHAT_SERVICE_CARD_MAX_UNKNOWN_RECHECK=6h
SHOP_WECHAT_SERVICE_CARD_NOT_APPLIED_CONFIRMATIONS=2
SHOP_WECHAT_SERVICE_CARD_CONNECT_TIMEOUT=3s
SHOP_WECHAT_SERVICE_CARD_READ_TIMEOUT=15s
SHOP_WECHAT_SERVICE_CARD_MAX_RESPONSE_SIZE=1MB
SHOP_WECHAT_SERVICE_CARD_MAX_PAYLOAD_SIZE=64KB

SHOP_WECHAT_SERVICE_CARD_CALLBACK_TOKEN=<3-to-32-character-alphanumeric-token>
SHOP_WECHAT_SERVICE_CARD_CALLBACK_AES_KEY=<43-character-alphanumeric-encoding-aes-key>
SHOP_WECHAT_SERVICE_CARD_CALLBACK_MAX_SKEW=5m
```

The checked-in common configuration deliberately keeps
`shop.wechat.service-card-2001.prefer-order-snapshot-images=false`: current product COS
images require a Referer and are not safe service-card image inputs. The fallback is the
merchant-owned Admin static file `admin/public/wechat/service-card-placeholder.png`.
Before enabling outbound calls, an unauthenticated, no-Referer request to the URL above
must return `200`, `image/png`, and actual PNG bytes. Only explicitly allowlisted public
HTTPS image hosts without credentials, query strings, fragments, or temporary signatures
may be used.

Configure the Mini Program account's single message-push endpoint as follows; this is an
account-level setting, not a payment callback URL:

```text
URL: https://api.muybaby6.icu/wechat/mini/message
Message encryption: Safe mode
Data format: JSON
Token: exactly SHOP_WECHAT_SERVICE_CARD_CALLBACK_TOKEN
EncodingAESKey: exactly SHOP_WECHAT_SERVICE_CARD_CALLBACK_AES_KEY
```

The endpoint validates the GET handshake, `msg_signature`, timestamp window, AES-CBC
plaintext, and Mini Program AppID. The asynchronous event is a send-failure diagnostic;
it does not prove successful delivery and must not overwrite an already confirmed remote
state. `get_user_notify` reconciliation remains the provider-state evidence.

V95 Admin runtime endpoints are:

```text
GET /admin/wechat-service-cards/status
GET /admin/wechat-service-cards/deliveries
PUT /admin/wechat-service-cards/runtime
```

Read operations require `wechat-service-card:read`; changing the runtime override requires
`wechat-service-card:runtime:write`. The PUT body is
`{captureEnabled, workerEnabled, version, reason}`. It uses CAS, records the before/after values,
operator, reason and revision in an append-only audit table, rejects unknown fields, and never
accepts callback credentials. Enabling is deliberately staged: a disabled installation must
first save `capture=true, worker=false`, inspect the Repair Scanner candidates and durable queue,
then save a later revision with `worker=true`. Readiness failures block only enabling; an operator
can always perform an emergency disable. No application restart is required.

For a first-time installation, use this three-stage release order; do not enable all three
switches at once. Current production has already completed the Safe+JSON GET handshake, so a
V95 rollout must preserve the environment-only Callback credentials and follow the current-state
runbook in `ops/README.md` instead of regenerating them.

1. Deploy V94-V95 and the Admin placeholder with capture, worker, and callback disabled. Verify
   Flyway, readiness configuration, Admin asset bytes, and that no outbound request occurs.
2. Supply a newly generated Token/AES key, enable only the callback, configure SAFE+JSON in
   the WeChat console, and complete the public GET handshake. Before enabling capture, list
   every complete paid payment from the preceding 24 hours that has no card: the repair
   scanner will enqueue those real payments as well as new events. In Admin, save a capture-only
   revision while the worker remains off, create a controlled new paid order, and inspect all durable cards
   and ordered delivery intents without sending them.
3. In Admin, enable the worker in a later revision only after the queue, AppID identity,
   immutable payment facts, image,
   callback, and monitoring are ready and no unintended repaired card remains eligible for
   outbound work. Activate one controlled real payment within 24 hours of its WeChat payment
   time, then verify shipped/signed/after-sale transitions and active `get_user_notify`
   reconciliation. Updates are allowed only during the provider's 30-day window; expired
   rows must remain truthful instead of being force-sent.

The 24-hour activation window belongs to WeChat 2001 and is separate from the Shop order's
15-minute payment deadline. Automated/mock tests cannot prove the account template is live,
the callback setting is accepted, WeChat displays the card, or a real failure callback is
delivered. Preserve those as explicit external release evidence.

The complete external release gate is [production-release-checklist.md](production-release-checklist.md).

## Seeded Administrator Safety

The historical `Super / 123456` account is enabled only by the `dev` and `test` profiles for local smoke checks. The default/production profile disables that known credential during Flyway migration `V36` and replaces its hash unless the password was already rotated.

For a controlled first-time deployment, provide a BCrypt hash and explicitly enable the seed only for the migration run:

```properties
SHOP_DEFAULT_ADMIN_STATUS=ENABLED
SHOP_DEFAULT_ADMIN_PASSWORD_HASH=<bcrypt-hash-for-a-unique-bootstrap-password>
```

Log in once, rotate the password or create the real administrator, and remove both variables. Never enable the documented local password on a shared environment.

OpenAPI is disabled by default and enabled in the local `dev` profile. Override it with `SHOP_OPENAPI_ENABLED` when needed.

Administrator login protection is enabled by default and uses Redis in non-test profiles. The pair/account/IP limits are controlled by `SHOP_ADMIN_LOGIN_PAIR_FAILURE_LIMIT`, `SHOP_ADMIN_LOGIN_ACCOUNT_FAILURE_LIMIT`, `SHOP_ADMIN_LOGIN_IP_FAILURE_LIMIT`, `SHOP_ADMIN_LOGIN_FAILURE_WINDOW`, and `SHOP_ADMIN_LOGIN_LOCK_DURATION`. A Redis outage intentionally rejects login with HTTP 503 instead of bypassing the protection.

Spring's container-level forwarded-header rewriting is disabled so the application has one IP trust model. Configure every real reverse proxy or load balancer network in `SHOP_TRUSTED_PROXY_CIDRS`; the edge proxy must replace or safely append `X-Forwarded-For`. Do not trust broad application or cluster networks merely for convenience.

Every new order snapshots one immutable payment deadline at submission. The default is 15 minutes (`SHOP_PAY_EXPIRE_MINUTES` remains an explicit override), and payment initiation inherits that exact timestamp instead of extending the buyer's window. Expired `CREATED` orders without any payment row and expired `PREPARING`/`PAYING` payment orders are scanned every 60 seconds by default. Both paths use leased claims; the `CREATED` path refuses to release an anomalous order that already has payment evidence, while the payment path queries WeChat first and only closes a verified unpaid order. The operational controls are `SHOP_PAY_TIMEOUT_SCAN_ENABLED`, `SHOP_PAY_TIMEOUT_SCAN_DELAY`, `SHOP_PAY_TIMEOUT_SCAN_BATCH_SIZE`, and `SHOP_PAY_TIMEOUT_SCAN_CLAIM_TIMEOUT`.

`V85` intentionally leaves historical `CREATED` deadlines null. Before any one-time assignment, run the read-only classification and invariant queries in [transaction-reconciliation.md](transaction-reconciliation.md); never bulk-close or blindly backfill historical orders.

Refunds left in `PROCESSING` are reconciled with WeChat after one minute by default. Configure this with `SHOP_REFUND_RECOVERY_ENABLED`, `SHOP_REFUND_RECOVERY_DELAY`, `SHOP_REFUND_RECOVERY_BATCH_SIZE`, `SHOP_REFUND_RECOVERY_MIN_AGE`, and `SHOP_REFUND_RECOVERY_CLAIM_TIMEOUT`.

When a refund is prepared for a `PAID` order that has neither `shipped_at` nor an `order_shipment`, `V86` snapshots an immutable restock requirement. A verified successful refund then restores each confirmed SKU quantity, transitions the order's stock locks to `RESTOCKED`, writes one unique `REFUND_RESTOCK` log per SKU, and finalizes refund/after-sale/order state in one transaction. Shipped and completed refunds never increase sellable inventory. Historical successful refunds remain report-only; use [transaction-reconciliation.md](transaction-reconciliation.md) and never auto-restock them.

A refund that WeChat definitively closes is not resubmitted under the old merchant refund number. After resolving the cause (for example, recharging the merchant account), an administrator with `aftersale:audit` can call `POST /admin/after-sales/{afterSaleId}/refund-retry` with `{"note":"..."}`. The note is mandatory and stored in the operator audit trail. The endpoint accepts only the latest `FAILED/CLOSED` attempt without an active recovery lease, safely clears an expired orphan lease by its token, preserves the old attempt, creates a new merchant refund number, and leaves an indeterminate provider response in `PROCESSING/REQUEST_UNKNOWN` for the recovery job. A callback or query can finalize only the latest refund attempt for that after-sale record.

For the latest `PROCESSING` or `FAILED` attempt, the same permission can use `.../refunds/{refundOrderId}/provider-query`, `.../provider-resubmit`, or `.../manual-intervention`. Every request requires a nonblank operation note of at most 180 characters and produces audit records. Resubmit always queries WeChat first and submits the original merchant refund number only when WeChat reports `NOT_FOUND`; it never resubmits a known `PROCESSING`, `SUCCESS`, `CLOSED`, or `ABNORMAL` refund. Manual intervention records `FAILED/MANUAL_INTERVENTION` and stops automatic recovery without fabricating a successful refund, but it cannot overwrite a provider `CLOSED` terminal state. Operator notes stay in the admin audit log and are not returned through App refund error fields. The admin after-sale drawer exposes only the actions valid for the current state.

Once a database payment configuration has been referenced by a payment order, its merchant identity and key material are immutable. Create and enable a new configuration revision instead of editing the historical one; outstanding payment queries, closes, callbacks, and refunds continue to use their original merchant configuration. New payment orders also store a non-reversible configuration fingerprint, and every provider result is bound to that ID/fingerprint before local state changes.

For an ENV-sourced payment, migration `V41` adds a content-addressed configuration snapshot. Payment preparation encrypts the APIv3 key, merchant private key PEM, and WeChat public key PEM before inserting the payment order. Query, close, refund, recovery, and callback processing can therefore restore the exact credential revision after `WECHAT_PAY_*` values or key files are rotated. For DB-sourced payments, preparation locks and revalidates the configuration row in the payment transaction before inserting its identity reference. Snapshot business content is append-only; only a verified envelope rewrap may update its ciphertext metadata. A row whose decrypted contents no longer match its fingerprint fails closed with `PAYMENT_CONFIGURATION_CHANGED` and is not re-encrypted.

Migration `V44` adds a nullable opaque callback route to each payment/refund. New routes are 192-bit random Base64URL values and produce `/wxpay/pay/notify/r/{token}` or `/wxpay/refund/notify/r/{token}`. They reveal neither row IDs nor configuration fingerprints. The routed handler resolves the exact historical configuration before decrypting, compares the persisted token byte-for-byte after the database lookup, and compares the decrypted merchant number with the routed row. Existing token-null rows keep using the legacy fixed endpoints. Invalid, unknown, unverified, or unbound callback input is rejected without growing `payment_callback_log`. Because the fixed endpoints must still scan a bounded set of historical configurations, rate-limit and alert on them at ingress until token-null inventory has drained; then retire them after the provider retry window.

Migration `V45` adds envelope metadata and a monotonic secret revision for DB payment APIv3 keys, ENV snapshots, persisted COS credentials, and other database-managed secrets. Version 2 uses AES-256-GCM as `v2:<keyId>:<base64url nonce>:<base64url ciphertext+tag>` and AAD binds every ciphertext to its domain, row, and field. `SHOP_SECRET_ENCRYPTION_LEGACY_KEY` remains the legacy v1 read key during transition. This application master key is generated independently; it is not a Tencent COS SecretId or SecretKey. Version 2 keys are supplied explicitly as semicolon-separated `id=base64:<32-byte-key>` entries; key IDs must use canonical lowercase ASCII, and an unknown key ID never falls back to trial-decryption with other keys. Background rotation verifies metadata and snapshot fingerprints, atomically claims each domain's next keyset range through a durable database checkpoint, and uses revision compare-and-set updates so process restarts, another node, or an administrator update cannot starve or overwrite healthy work.

Use this rolling deployment order:

1. Deploy V44/V45 and all callback handlers with `SHOP_PAY_NOTIFICATION_ROUTE_ENABLED=false`, `SHOP_SECRET_ENCRYPTION_WRITE_VERSION=1`, and rotation disabled.
2. After every application instance accepts routed paths, verify configured callback bases pass routed readiness (public HTTPS, valid port, no user-info/query/fragment, and at most 220 characters after trailing-slash normalization), then enable `SHOP_PAY_NOTIFICATION_ROUTE_ENABLED=true` for newly created payments/refunds. Keep the two ingress-limited legacy handlers while token-null work can still receive retries.
3. Put the active and any old v2 keys in `SHOP_SECRET_ENCRYPTION_KEY_RING`; keep the existing key available as `SHOP_SECRET_ENCRYPTION_LEGACY_KEY` for v1 rows. After every instance can read v2 and the rollback window to a v1-only binary has closed, set write version 2. Once any v2 row exists, do not roll back to a binary that understands only v1; use the current dual-reader binary with write version temporarily returned to 1, or roll forward.
4. Enable the small-batch rotation. Remove an old key only after all three tables have zero inventory for it and the replica, database backup, and restore retention windows have expired.

## Local WeChat Mini Program Credentials

The `dev` profile imports the optional local properties file `backend/shop-server/.env.dev.local`. This file is ignored by Git and should hold real mini program credentials for local integration checks:

```properties
WECHAT_MINI_PROGRAM_APP_ID=your-app-id
WECHAT_MINI_PROGRAM_APP_SECRET=your-app-secret
```

The `dev` profile uses the real WeChat client. The `test` profile keeps the mock WeChat client for local smoke checks and automated tests.

## Local WeChat Pay Credentials

Keep local WeChat Pay credentials in `backend/shop-server/.env.dev.local` or configure them through the admin payment configuration screen. Use placeholders in documentation and commits only:

```properties
WECHAT_PAY_ENABLED=true
WECHAT_PAY_CONFIG_SOURCE=AUTO
WECHAT_PAY_APP_ID=<wechat-mini-program-app-id>
WECHAT_PAY_MCH_ID=<wechat-pay-merchant-id>
WECHAT_PAY_MERCHANT_SERIAL_NO=<merchant-certificate-serial-no>
WECHAT_PAY_PRIVATE_KEY_PATH=<absolute-path-to-local-merchant-private-key.pem>
WECHAT_PAY_API_V3_KEY=<wechat-pay-api-v3-key>
WECHAT_PAY_NOTIFY_URL=https://<public-tunnel-domain>/wxpay/pay/notify
WECHAT_PAY_REFUND_NOTIFY_URL=https://<public-tunnel-domain>/wxpay/refund/notify
WECHAT_PAY_VERIFY_MODE=PUBLIC_KEY
WECHAT_PAY_PUBLIC_KEY_ID=<wechat-pay-public-key-id>
WECHAT_PAY_PUBLIC_KEY_PATH=<absolute-path-to-local-wechat-pay-public-key.pem>
SHOP_PAY_EXPIRE_MINUTES=15
SHOP_SECRET_ENCRYPTION_LEGACY_KEY=<local-32-byte-application-master-key>
SHOP_PAY_NOTIFICATION_ROUTE_ENABLED=false
SHOP_SECRET_ENCRYPTION_WRITE_VERSION=1
SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID=
SHOP_SECRET_ENCRYPTION_KEY_RING=
SHOP_SECRET_ENCRYPTION_ROTATION_ENABLED=false
SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=false
SHOP_WECHAT_RECEIPT_RECONCILIATION_ENABLED=true
```

The configured callback bases must be complete public HTTPS paths with no query, fragment, or user-info. With route issuance disabled, callbacks use `/wxpay/pay/notify` and `/wxpay/refund/notify`. With it enabled, the backend gives WeChat the corresponding `/r/{token}` path per payment/refund; do not pre-append a token in configuration. A real local WeChat Pay smoke check needs an HTTPS tunnel to the local backend.

`WECHAT_PAY_CONFIG_SOURCE=AUTO` is the startup/default source: it uses complete environment credentials first and otherwise falls back to the enabled database payment config. Use `ENV` when the active profile's environment-file values should be mandatory, or `DB` when payment credentials are managed through `/admin/pay/configs`.

The `开发配置 -> 支付配置` menu has a separate runtime source selector for `AUTO`, `ENV`, and `DB`. Saving that selector stores one row in `payment_runtime_setting` and takes effect without restarting the backend; if no row exists, the backend uses `WECHAT_PAY_CONFIG_SOURCE` from the active profile's environment file. The DB config list's candidate action only chooses which DB config is used when the runtime source is `DB` or when `AUTO` falls back to DB.

Current production operational decision (recorded 2026-08-10): new payment preparation
uses runtime source `ENV`, and its payment/refund callback bases use
`api.muybaby6.icu`. The existing DB configuration and `pay-dev.muybaby6.icu` route are
retained only for legacy payment/refund callback compatibility during the provider retry
window. `pay-dev` is not the release Mini Program API and must not be selected for new
payments. Verify the source and immutable snapshot on the first new real payment after each
release; do not infer it from the enabled DB candidate alone.

For DB config, upload the required merchant private key and WeChat Pay public key, plus the optional merchant certificate, only through the payment-owned `/admin/pay/configs/secret-files` endpoint. Updating a config with a null merchant-certificate ID explicitly clears that optional reference; the old secret enters its 24-hour release window after its final active reference is removed. These secret assets never appear in the reusable asset library.

Never commit `.env.*.local`, merchant certificates, private keys, APIv3 keys, public-key files, or screenshots/logs containing merchant IDs, AppIDs, serial numbers, API keys, certificate paths, public key IDs, callback domains, or other secret material.

## Object Storage

The application supports Tencent Cloud COS only. Configure the region, bucket,
SecretId, and SecretKey through `开发配置 -> 对象存储配置`. The COS client
domain can be left empty for the bucket's default COS origin, or set to the
HTTPS custom origin that COS has bound to the same bucket. It must be a legal
root hostname without credentials, a port, path, query, or fragment. The backend
verifies the configured origin, while both clients consume the upload origin
dynamically from the signed upload-session response and enforce the same URL
shape. This COS custom origin points directly to the bucket and does not require
or enable CDN. The values are read
from `storage_runtime_setting` and take effect without restarting the backend.
There is no local provider and no storage environment-file fallback; uploads
fail with `STORAGE_NOT_CONFIGURED` until the database configuration is complete.

Only non-provider upload policy remains configurable through environment defaults.
Retention, cleanup batch size, schedule, and upload-pending grace are stored in
`data_cleanup_task_setting` and are managed from **配置管理 → 数据清理配置**:

```properties
SHOP_STORAGE_IMAGE_MAX_SIZE=5MB
SHOP_STORAGE_IMAGE_MAX_WIDTH=8192
SHOP_STORAGE_IMAGE_MAX_HEIGHT=8192
SHOP_STORAGE_IMAGE_MAX_PIXELS=25000000
SHOP_STORAGE_VIDEO_MAX_SIZE=50MB
SHOP_STORAGE_PRIVATE_FILE_MAX_SIZE=10MB
SHOP_DIRECT_UPLOAD_MAX_ACTIVE_SESSIONS=10
SHOP_DIRECT_UPLOAD_MAX_SESSIONS_PER_HOUR_APP=60
SHOP_DIRECT_UPLOAD_MAX_SESSIONS_PER_HOUR_ADMIN=600
```

JPEG, PNG, WebP, and GIF now use a signed COS POST upload session. The browser or
mini program sends the source object directly to a private staging key. The
completion request performs only COS HEAD, Cloud Infinite processing, metadata
writes, and database updates; the application JVM never reads the image body.
Cloud Infinite reports the decoded source format, dimensions, and frame count,
which are checked against the same storage limits before the WebP output becomes
an active asset.

Tinify, its environment variables, runtime configuration page, quota probe, and
database-managed key are no longer used. SVG remains on the legacy Multipart
endpoint because it requires the application safety parser and is not part of
the normal Cloud Infinite raster pipeline. Legacy clients can temporarily keep
using the Multipart endpoints during rollout, but those requests no longer call
Tinify.

All persistent raster outputs use WebP. Library images use a 2560px longest
edge, avatars 1024px, after-sale evidence 4096px, and chat display images
1920px. Chat completion creates a 720px thumbnail in the same Cloud Infinite
request. The sender keeps an immediate local preview, while the mini program
caches downloaded thumbnails for seven days. AVIF and CDN delivery are
intentionally not used.

For the current bucket, direct POST uploads, public file URLs, and private signed
image URLs all use the configured COS origin (custom or default), without a CDN
layer. The POST Policy remains scoped to one bucket/key/type/size, while private
URLs are signed for the configured Host rather than rewritten after signing.
Historical locations from a different bucket or region retain their own default
COS origin. Every asset records the COS bucket and region used at upload time;
current credentials must retain access to every recorded bucket. Private files,
including payment certificates and keys, never receive a public URL. COS
credentials are envelope-encrypted in the database
with the independent `SHOP_SECRET_ENCRYPTION_*` master key configuration and are
never returned in full.

If production data predates this migration and already embeds a CDN URL in
product HTML, order snapshots, avatars, or historical asset rows, inventory and
rewrite those stored strings to their matching COS origin before disabling the
CDN. The application intentionally does not perform a blind migration because
those values can belong to different historical buckets or unrelated domains.

Before enabling direct upload, configure the admin Origin in the COS bucket's
CORS rules and add the configured COS origin to the mini program `uploadFile`
and `downloadFile` allowlists. Keep the default COS origin in both allowlists
during client rollout for old versions, historical objects, and rollback. Also
verify that the bucket is bound to
Cloud Infinite. A one-day lifecycle deletion rule for
`private/direct-upload/` is recommended as defense in depth. The exact
permissions, CORS values, output profiles, rollout order, and smoke checklist
are documented in
[`docs/cos-direct-upload.md`](./cos-direct-upload.md).

Migration `V69` removes the local runtime-setting columns and adds a database constraint that permits only `TENCENT_COS` assets. It intentionally fails when any `storage_asset.provider = 'LOCAL'` row remains. Before deploying it to an existing installation, copy those objects to COS and migrate every stored URL/reference plus the asset provider, bucket, and region metadata; do not delete the old files until the migrated application and backups have been verified.

Unclaimed after-sale evidence expires after 24 hours; staged payment secrets expire after two hours, and replaced secrets receive a 24-hour release window. These windows, their validation, and cleanup all use the database clock, so the JVM and database may safely run in different time zones. The cleanup job leases expired, unreferenced private assets and retries provider failures with bounded backoff; fresh expirations use a separate batch so failed deletions cannot block them.

File storage and home banner smoke checks are documented in `docs/smoke-checks.md#file-storage-and-home-banner-smoke-checks`.

### WeChat Integration Notes

Mini program login and phone authorization use two different WeChat exchanges:

- Login: `wx.login()` returns a code, and the backend exchanges it through `jscode2session`.
- Phone authorization: `getPhoneNumber` returns a phone code, and the backend obtains a stable access token before calling `getuserphonenumber`.

WeChat can return JSON with `Content-Type: text/plain`, so the backend reads WeChat responses as strings before parsing JSON. WeChat's `stable_token` endpoint also rejects chunked request bodies with `HTTP 412 PRECONDITION_FAILED` and an empty response body. The backend serializes WeChat POST request bodies to JSON strings before sending them so the request has a concrete content length.

Relevant safe diagnostic logs:

```text
WeChat code2Session failed: errcode=40029, errmsg=invalid code
WeChat stableToken request failed: status=412 PRECONDITION_FAILED, empty response body
WeChat getPhoneNumber failed: errcode=40029, errmsg=invalid code
```

These logs intentionally do not print `WECHAT_MINI_PROGRAM_APP_SECRET`, access tokens, login codes, or phone codes. If `stableToken` returns `412 PRECONDITION_FAILED`, verify the backend is running the current code that sends non-chunked JSON request bodies. If `getPhoneNumber` returns `40029 invalid code`, retry with a fresh one-time phone authorization code from WeChat DevTools or a real device.

### WeChat Electronic Waybill And Logistics Query

Electronic-waybill configuration is stored in the database and managed from
`订单管理 / 电子面单配置` (or `GET/PUT
/admin/logistics/wechat-express/config`). It has three modes:

- `DISABLED`: keep the existing manual shipment path only.
- `SANDBOX`: the backend forces the official `TEST`, `test_biz_id`, service
  type `1`, and `test_service_name` values regardless of any saved production
  draft.
- `PRODUCTION`: use the saved carrier, customer number, and service values.

Configure a complete structured sender and the default single-parcel weight and
dimensions before enabling sandbox or production. Production carrier passwords
are intentionally neither requested nor stored; a future carrier-account bind
must forward any one-time credential directly to WeChat and discard it.

The sandbox still calls the real WeChat express-business APIs. It is limited to
10 waybills per day, and its recipient OpenID must belong to a Mini Program
administrator, operator, or developer. Creating a label keeps the Shop order in
`PAID`; only the explicit “确认发货” action creates `order_shipment` and changes
the order to `SHIPPED`.

The Mini Program declares the official logistics plugin. A saved physical
shipment always renders its carrier and tracking number locally. Opening the
official trajectory additionally requires a successful server-side
`trace_waybill` or `follow_waybill` registration for the real paid order,
recipient phone, payer OpenID, transaction ID, carrier, and waybill. A fabricated
tracking number can test only the local card and copy action.

The order detail also renders a Shop-owned logistics data area. Its two sources
are deliberately independent:

- `query_trace` or `query_follow_trace` reads the WeChat summary status from the
  registered `waybill_token`.
- Electronic-waybill `getPath` reads `path_item_list` from the saved upstream
  order ID, payer OpenID, carrier ID, and waybill ID.

An empty or failed `getPath` response does not hide the area, and a failure from
one source does not discard a successful result from the other. The admin order
drawer exposes both source statuses, safe errors, and stored path nodes; manual
refresh requires `order:shipping:tracking:sync`. Tracking synchronization does
**not** change `shop_order.status` or write order status logs. The official Mini
Program “查看全部物流” plugin entry remains available when token registration
succeeds.

Optional runtime bounds keep repeated reads and provider responses controlled:

```env
SHOP_WECHAT_TRACKING_REFRESH_INTERVAL=5m
SHOP_WECHAT_TRACKING_CLAIM_TIMEOUT=5m
SHOP_WECHAT_TRACKING_MAX_PATH_ITEMS=200
```

## Authentication Smoke Checks

Backend test profile uses an in-memory token store and a mock WeChat mini program client. Use this only for local smoke checks, not on a shared or exposed environment. The smoke command opts into Maven's test classpath so H2 and `src/test/resources/application-test.yaml` are available at runtime.

```bash
cd backend/shop-server
./mvnw -Dspring-boot.run.profiles=test \
  -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.arguments=--spring.config.additional-location=file:src/test/resources/ \
  spring-boot:run
```

Admin login:

Run the remaining commands in a second terminal while the backend is running.

```bash
ADMIN_TOKEN=$(
  curl -s -X POST http://localhost:8080/admin/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"userName":"Super","password":"123456"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.token));'
)
```

Mini program login:

```bash
APP_TOKEN=$(
  curl -s -X POST http://localhost:8080/app/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"code":"test-login-code"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.token));'
)
```

Admin and app tokens are intentionally isolated. The issued app access token must receive HTTP 401 on `/admin/**`, and the issued admin access token must receive HTTP 401 on `/app/**`.

```bash
curl -i http://localhost:8080/admin/auth/current-user \
  -H "Authorization: Bearer ${APP_TOKEN}"

curl -i -X POST http://localhost:8080/app/auth/phone \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -d '{"code":"phone-code"}'
```

Expected result: both isolation checks return HTTP 401.

## Product Catalog Smoke Checks

Product catalog smoke checks are documented in `docs/smoke-checks.md#product-catalog-smoke-checks`. They run against the local backend on the `test` profile and the local test database path. The `test` profile still uses the mock WeChat mini program client for login, but category, SPU, SKU, publish/unpublish, and mini program product list/detail requests go through the real local backend product APIs, not product mocks.

## Coupon Checks

Run automated checks with:

```bash
cd backend/shop-server
./mvnw -Dtest=CouponSchemaTest,AdminCouponTemplateControllerTest,AppCouponControllerTest,CouponDiscountCalculatorTest test
cd ../../miniprogram
pnpm typecheck
cd ../admin
pnpm build
```

For real local coupon smoke, follow `docs/smoke-checks.md#coupon-smoke-checks`.

## Admin Checks

```bash
cd admin
pnpm install
pnpm build
```

Expected result: Vite production build completes without TypeScript or bundling errors.

## Mini Program Checks

```bash
cd miniprogram
pnpm install
pnpm typecheck
```

Expected result: TypeScript completes without diagnostics.

## Local Secret Policy

Do not commit real WeChat app secrets, merchant certificates, private keys, database passwords, Redis passwords, or production URLs. Use local environment files ignored by Git.
