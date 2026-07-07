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

## Product Catalog Smoke Checks

This is a real local smoke check for product catalog. It uses the local backend and local database path. In the `test` profile, WeChat login is still backed by the mock WeChat mini program client described in `docs/dev-setup.md`; product catalog requests are not mocked.

Start backend:

```bash
cd backend/shop-server
./mvnw -Dspring-boot.run.profiles=test \
  -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.arguments=--spring.config.additional-location=file:src/test/resources/ \
  spring-boot:run
```

Admin login:

```bash
ADMIN_TOKEN=$(
  curl -s -X POST http://localhost:8080/admin/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"userName":"Super","password":"123456"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.token));'
)
```

Create category:

```bash
CATEGORY_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/categories \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"parentId":0,"name":"牛油锅底","icon":"","sortOrder":10,"status":"ENABLED"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Create SPU and SKU:

```bash
SPU_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/spus \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"categoryId\":${CATEGORY_ID},\"title\":\"重庆牛油火锅底料\",\"subtitle\":\"厚重牛油香\",\"mainImage\":\"https://example.test/hotpot-main.jpg\",\"sellingPoints\":\"牛油浓香,手工炒制\",\"detailHtml\":\"<p>适合3-5人火锅。</p>\",\"sortOrder\":10,\"images\":[\"https://example.test/hotpot-gallery-1.jpg\"],\"skus\":[{\"skuCode\":\"HY-NY-300G\",\"specJson\":\"{\\\"口味\\\":\\\"牛油\\\",\\\"重量\\\":\\\"300g\\\"}\",\"specText\":\"牛油 / 300g\",\"priceCent\":3990,\"originalPriceCent\":4990,\"stockAvailable\":100,\"weightGram\":300,\"image\":\"https://example.test/hotpot-sku-300.jpg\",\"status\":\"ENABLED\",\"sortOrder\":1}]}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Publish SPU:

```bash
curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/publish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Mini program product list:

```bash
curl -s "http://localhost:8080/app/product/spus?current=1&size=10&categoryId=${CATEGORY_ID}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.total < 1) process.exit(1); console.log(body.data.records[0].title); });'
```

Mini program product detail:

```bash
curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.skus.length < 1) process.exit(1); console.log(body.data.skus[0].skuCode); });'
```

Unpublish SPU and verify it disappears from app detail:

```bash
curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/unpublish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'

curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200001) process.exit(1); console.log(body.msg); });'
```

Expected result:

```text
success
重庆牛油火锅底料
HY-NY-300G
success
Product unavailable
```

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
