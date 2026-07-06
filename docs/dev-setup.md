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

Full backend test suite:

```bash
cd backend/shop-server
./mvnw test
```

Expected result:

```text
BUILD SUCCESS
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
