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

For DB config, upload merchant private key, merchant certificate, and WeChat Pay public key files as private payment files through admin storage; do not commit the uploaded files or the local upload directory.

Never commit `.env.local`, merchant certificates, private keys, APIv3 keys, public-key files, local upload roots, or screenshots/logs containing merchant IDs, AppIDs, serial numbers, API keys, certificate paths, public key IDs, callback domains, or other secret material.

## Object Storage

The admin `开发配置 -> 对象存储配置` page selects the active provider at runtime. Its database setting takes effect without restarting the backend; when no database row exists, the backend falls back to `backend/shop-server/.env.local`.

Local storage defaults can be configured with:

```properties
SHOP_STORAGE_PROVIDER=LOCAL
SHOP_STORAGE_LOCAL_ROOT=var/uploads
SHOP_STORAGE_PUBLIC_BASE_URL=http://localhost:8080
SHOP_STORAGE_IMAGE_MAX_SIZE=5MB
SHOP_STORAGE_VIDEO_MAX_SIZE=50MB
SHOP_STORAGE_PRIVATE_FILE_MAX_SIZE=1MB
```

Tencent Cloud COS can also be supplied through environment defaults:

```properties
SHOP_STORAGE_PROVIDER=TENCENT_COS
SHOP_STORAGE_TENCENT_COS_REGION=ap-guangzhou
SHOP_STORAGE_TENCENT_COS_BUCKET=<bucket-name-appid>
SHOP_STORAGE_TENCENT_COS_SECRET_ID=<secret-id>
SHOP_STORAGE_TENCENT_COS_SECRET_KEY=<secret-key>
SHOP_STORAGE_TENCENT_COS_PUBLIC_BASE_URL=https://<bucket-name-appid>.cos.ap-guangzhou.myqcloud.com
```

`SHOP_STORAGE_LOCAL_ROOT` should point to a writable local directory and should not be committed to Git. Local public files are served by the backend through `/files/public/**`; COS public files return the configured COS default or custom source domain directly. Private files, including payment certificates and keys, never receive a public URL and are read through their recorded provider. COS credentials saved from the admin page are encrypted in the database and are never returned in full.

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
