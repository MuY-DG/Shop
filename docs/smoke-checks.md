# Smoke Checks

## Backend

```bash
cd backend/shop-server
./mvnw test
```

Before running the backend, start local MySQL and create the dev database expected by the `dev` profile. The default credentials are `root` / `123456`; override them with `SHOP_DB_URL`, `SHOP_DB_USERNAME`, and `SHOP_DB_PASSWORD` when needed.

For mini program login and phone authorization against real WeChat APIs, keep `backend/shop-server/.env.local` populated with the local mini program credentials.

```bash
mysql -uroot -p123456 -e "CREATE DATABASE IF NOT EXISTS hotpot_shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
./mvnw -Dspring-boot.run.profiles=dev spring-boot:run
```

With Spring Boot running, confirm the health response from another terminal:

```bash
curl http://localhost:8080/app/health
```

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "status": "UP",
    "service": "shop-server"
  }
}
```

For real mini program phone authorization, the backend should not log `stableToken` `412 PRECONDITION_FAILED`. That status means WeChat rejected a chunked request body; current backend code sends serialized JSON strings to avoid chunked POST bodies. If phone authorization still returns HTTP 400, check the backend log for the safe WeChat diagnostic line and use the reported `errcode` / `errmsg`.

## Admin

```bash
cd admin
pnpm install
pnpm dev
```

Expected:

```text
Local: http://localhost:3006/
```

Confirm `.env` contains:

```env
VITE_ACCESS_MODE = backend
```

## Mini Program

```bash
cd miniprogram
pnpm install
pnpm typecheck
```

Expected:

```text
TypeScript completes without diagnostics.
```

Open `miniprogram/` in WeChat DevTools and compile. The home page should request `/app/health` from `http://localhost:8080`.
