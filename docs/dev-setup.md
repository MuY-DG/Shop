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
```

The configured callback bases must be complete public HTTPS paths with no query, fragment, or user-info. With route issuance disabled, callbacks use `/wxpay/pay/notify` and `/wxpay/refund/notify`. With it enabled, the backend gives WeChat the corresponding `/r/{token}` path per payment/refund; do not pre-append a token in configuration. A real local WeChat Pay smoke check needs an HTTPS tunnel to the local backend.

`WECHAT_PAY_CONFIG_SOURCE=AUTO` is the startup/default source: it uses complete environment credentials first and otherwise falls back to the enabled database payment config. Use `ENV` when the active profile's environment-file values should be mandatory, or `DB` when payment credentials are managed through `/admin/pay/configs`.

The `开发配置 -> 支付配置` menu has a separate runtime source selector for `AUTO`, `ENV`, and `DB`. Saving that selector stores one row in `payment_runtime_setting` and takes effect without restarting the backend; if no row exists, the backend uses `WECHAT_PAY_CONFIG_SOURCE` from the active profile's environment file. The DB config list's candidate action only chooses which DB config is used when the runtime source is `DB` or when `AUTO` falls back to DB.

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

Only non-provider upload policy remains configurable through environment defaults:

```properties
SHOP_STORAGE_IMAGE_MAX_SIZE=5MB
SHOP_STORAGE_IMAGE_MAX_WIDTH=8192
SHOP_STORAGE_IMAGE_MAX_HEIGHT=8192
SHOP_STORAGE_IMAGE_MAX_PIXELS=25000000
SHOP_STORAGE_VIDEO_MAX_SIZE=50MB
SHOP_STORAGE_PRIVATE_FILE_MAX_SIZE=10MB
SHOP_DIRECT_UPLOAD_SESSION_RETENTION=7d
SHOP_DIRECT_UPLOAD_MAX_ACTIVE_SESSIONS=10
SHOP_DIRECT_UPLOAD_MAX_SESSIONS_PER_HOUR_APP=60
SHOP_DIRECT_UPLOAD_MAX_SESSIONS_PER_HOUR_ADMIN=600
SHOP_STORAGE_CLEANUP_INITIAL_DELAY=10m
SHOP_STORAGE_CLEANUP_FIXED_DELAY=10m
SHOP_STORAGE_CLEANUP_BATCH_SIZE=100
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
