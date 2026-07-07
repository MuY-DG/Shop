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
