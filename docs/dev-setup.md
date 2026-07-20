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

Full backend test suite:

```bash
cd backend/shop-server
./mvnw test
```

Expected result:

```text
BUILD SUCCESS
```

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

Expired `PREPARING` and `PAYING` payment orders are scanned every 60 seconds by default. A fresh prepay lease is never stolen. The scanner queries WeChat first, confirms a remotely paid order, and only closes an unpaid order; a provider-side `CLOSED` result completes an interrupted local close. The operational controls are `SHOP_PAY_TIMEOUT_SCAN_ENABLED`, `SHOP_PAY_TIMEOUT_SCAN_DELAY`, `SHOP_PAY_TIMEOUT_SCAN_BATCH_SIZE`, and `SHOP_PAY_TIMEOUT_SCAN_CLAIM_TIMEOUT`.

Refunds left in `PROCESSING` are reconciled with WeChat after one minute by default. Configure this with `SHOP_REFUND_RECOVERY_ENABLED`, `SHOP_REFUND_RECOVERY_DELAY`, `SHOP_REFUND_RECOVERY_BATCH_SIZE`, `SHOP_REFUND_RECOVERY_MIN_AGE`, and `SHOP_REFUND_RECOVERY_CLAIM_TIMEOUT`.

A refund that WeChat definitively closes is not resubmitted under the old merchant refund number. After resolving the cause (for example, recharging the merchant account), an administrator with `aftersale:audit` can call `POST /admin/after-sales/{afterSaleId}/refund-retry` with `{"note":"..."}`. The note is mandatory and stored in the operator audit trail. The endpoint accepts only the latest `FAILED/CLOSED` attempt without an active recovery lease, safely clears an expired orphan lease by its token, preserves the old attempt, creates a new merchant refund number, and leaves an indeterminate provider response in `PROCESSING/REQUEST_UNKNOWN` for the recovery job. A callback or query can finalize only the latest refund attempt for that after-sale record.

For the latest `PROCESSING` or `FAILED` attempt, the same permission can use `.../refunds/{refundOrderId}/provider-query`, `.../provider-resubmit`, or `.../manual-intervention`. Every request requires a nonblank operation note of at most 180 characters and produces audit records. Resubmit always queries WeChat first and submits the original merchant refund number only when WeChat reports `NOT_FOUND`; it never resubmits a known `PROCESSING`, `SUCCESS`, `CLOSED`, or `ABNORMAL` refund. Manual intervention records `FAILED/MANUAL_INTERVENTION` and stops automatic recovery without fabricating a successful refund, but it cannot overwrite a provider `CLOSED` terminal state. Operator notes stay in the admin audit log and are not returned through App refund error fields. The admin after-sale drawer exposes only the actions valid for the current state.

Once a database payment configuration has been referenced by a payment order, its merchant identity and key material are immutable. Create and enable a new configuration revision instead of editing the historical one; outstanding payment queries, closes, callbacks, and refunds continue to use their original merchant configuration. New payment orders also store a non-reversible configuration fingerprint, and every provider result is bound to that ID/fingerprint before local state changes.

For an ENV-sourced payment, migration `V41` adds an append-only, content-addressed configuration snapshot. Payment preparation encrypts the APIv3 key, merchant private key PEM, and WeChat public key PEM with the existing AES-GCM `SHOP_PAYMENT_SECRET_KEY` before inserting the payment order. Query, close, refund, recovery, and callback candidate selection can therefore restore the exact credential revision after `WECHAT_PAY_*` values or key files are rotated. For DB-sourced payments, preparation locks and revalidates the configuration row in the payment transaction before inserting its identity reference. Snapshot rows never expose key material through an API and application code has no update/delete path; a row whose decrypted contents no longer match its fingerprint fails closed with `PAYMENT_CONFIGURATION_CHANGED`.

`SHOP_PAYMENT_SECRET_KEY` is the root encryption key, not a WeChat credential. It must be present before creating an ENV payment and must remain stable for the lifetime of stored payments; back it up in the deployment secret manager. Rotating or losing it without an explicit re-encryption/key-ring procedure makes both database secrets and historical ENV snapshots unreadable. Upgrade-era ENV orders without a fingerprint/snapshot still fail closed and require manual reconciliation. Callback parsing tries the current revision first and then a bounded set of the 32 most recently used database or encrypted ENV revisions because the merchant order number is inside the encrypted notification resource; after a candidate exposes that route, the callback is reparsed and validated with the exact configuration identity stored on the payment.

## Local WeChat Mini Program Credentials

The backend imports an optional local properties file from `backend/shop-server/.env.local`. This file is ignored by Git and should hold real mini program credentials for local integration checks:

```properties
WECHAT_MINI_PROGRAM_APP_ID=your-app-id
WECHAT_MINI_PROGRAM_APP_SECRET=your-app-secret
```

The `dev` profile uses the real WeChat client. The `test` profile keeps the mock WeChat client for local smoke checks and automated tests.

## Local WeChat Pay Credentials

Keep local WeChat Pay credentials in `backend/shop-server/.env.local` or configure them through the admin payment configuration screen. Use placeholders in documentation and commits only:

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
SHOP_PAYMENT_SECRET_KEY=<local-32-byte-payment-secret-key>
SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=false
```

Payment callbacks are handled by `/wxpay/pay/notify`; refund callbacks are handled by `/wxpay/refund/notify`. A real local WeChat Pay smoke check needs an HTTPS tunnel to the local backend, and both callback URLs must use that public tunnel domain so WeChat can reach the local service.

`WECHAT_PAY_CONFIG_SOURCE=AUTO` is the startup/default source: it uses complete environment credentials first and otherwise falls back to the enabled database payment config. Use `ENV` when the local `.env.local` values should be mandatory, or `DB` when payment credentials are managed through `/admin/pay/configs`.

The `开发配置 -> 支付配置` menu has a separate runtime source selector for `AUTO`, `ENV`, and `DB`. Saving that selector stores one row in `payment_runtime_setting` and takes effect without restarting the backend; if no row exists, the backend uses `WECHAT_PAY_CONFIG_SOURCE` from `.env.local`. The DB config list's candidate action only chooses which DB config is used when the runtime source is `DB` or when `AUTO` falls back to DB.

For DB config, upload the required merchant private key and WeChat Pay public key, plus the optional merchant certificate, only through the payment-owned `/admin/pay/configs/secret-files` endpoint. Updating a config with a null merchant-certificate ID explicitly clears that optional reference; the old secret enters its 24-hour release window after its final active reference is removed. These secret assets never appear in the reusable asset library; do not commit uploaded files or the local upload directory.

Never commit `.env.local`, merchant certificates, private keys, APIv3 keys, public-key files, local upload roots, or screenshots/logs containing merchant IDs, AppIDs, serial numbers, API keys, certificate paths, public key IDs, callback domains, or other secret material.

## Object Storage

The admin `开发配置 -> 对象存储配置` page selects the active provider at runtime. Its database setting takes effect without restarting the backend; when no database row exists, the backend falls back to `backend/shop-server/.env.local`.

Local storage defaults can be configured with:

```properties
SHOP_STORAGE_PROVIDER=LOCAL
SHOP_STORAGE_LOCAL_ROOT=var/uploads
SHOP_STORAGE_PUBLIC_BASE_URL=http://localhost:8080
SHOP_STORAGE_IMAGE_MAX_SIZE=5MB
SHOP_STORAGE_IMAGE_MAX_WIDTH=8192
SHOP_STORAGE_IMAGE_MAX_HEIGHT=8192
SHOP_STORAGE_IMAGE_MAX_PIXELS=25000000
SHOP_STORAGE_VIDEO_MAX_SIZE=50MB
SHOP_STORAGE_PRIVATE_FILE_MAX_SIZE=1MB
SHOP_STORAGE_CLEANUP_INITIAL_DELAY=10m
SHOP_STORAGE_CLEANUP_FIXED_DELAY=10m
SHOP_STORAGE_CLEANUP_BATCH_SIZE=100
```

Image uploads are rejected when the extension, declared MIME, and decoded format disagree, or when width, height, GIF logical canvas, frame count, or cumulative pixels exceed the validation limits. JPEG, PNG, GIF, and WebP are decoded through ImageIO; WebP support is an explicit runtime dependency. Keep the byte-size limit as well: it controls transfer/storage size, while the dimension/frame limits protect the JVM from decompression-bomb memory pressure.

Tencent Cloud COS can also be supplied through environment defaults:

```properties
SHOP_STORAGE_PROVIDER=TENCENT_COS
SHOP_STORAGE_TENCENT_COS_REGION=ap-guangzhou
SHOP_STORAGE_TENCENT_COS_BUCKET=<bucket-name-appid>
SHOP_STORAGE_TENCENT_COS_SECRET_ID=<secret-id>
SHOP_STORAGE_TENCENT_COS_SECRET_KEY=<secret-key>
SHOP_STORAGE_TENCENT_COS_PUBLIC_BASE_URL=https://<bucket-name-appid>.cos.ap-guangzhou.myqcloud.com
```

`SHOP_STORAGE_LOCAL_ROOT` should point to a writable local directory and should not be committed to Git. Local public files are served by the backend through `/files/public/**`; COS public files return the configured COS default or custom source domain directly. Every asset records the LOCAL root or COS bucket/region used at upload time, so changing the active provider or location does not redirect existing objects. COS reads and deletes still use the currently configured credentials, which must retain access to each recorded bucket. Private files, including payment certificates and keys, never receive a public URL. Unclaimed after-sale evidence expires after 24 hours; staged payment secrets expire after two hours, and replaced secrets receive a 24-hour release window. These windows, their validation, and cleanup all use the database clock, so the JVM and database may safely run in different time zones. The cleanup job leases expired, unreferenced private assets and retries provider failures with bounded backoff; fresh expirations use a separate batch so failed deletions cannot block them. COS credentials saved from the admin page are encrypted in the database and are never returned in full.

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
