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

## Cart Smoke Checks

This is a real local smoke check for the cart phase. It uses the local backend and local database path. In the `test` profile, WeChat login is still backed by the mock WeChat mini program client described in `docs/dev-setup.md`; product catalog and cart requests go through the real local backend APIs, not product or cart mocks.

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

Mini program login:

```bash
APP_TOKEN=$(
  curl -s -X POST http://localhost:8080/app/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"code":"test-login-code"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.token));'
)
```

Create category:

```bash
CATEGORY_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/categories \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"parentId":0,"name":"购物车牛油锅底","icon":"","sortOrder":20,"status":"ENABLED"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Create SPU and SKU:

```bash
SPU_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/spus \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"categoryId\":${CATEGORY_ID},\"title\":\"购物车重庆牛油火锅底料\",\"subtitle\":\"厚重牛油香\",\"mainImage\":\"https://example.test/cart-main.jpg\",\"sellingPoints\":\"牛油浓香,手工炒制\",\"detailHtml\":\"<p>适合3-5人火锅。</p>\",\"sortOrder\":20,\"images\":[\"https://example.test/cart-gallery-1.jpg\"],\"skus\":[{\"skuCode\":\"CART-HY-NY-300G\",\"specJson\":\"{\\\"口味\\\":\\\"牛油\\\",\\\"重量\\\":\\\"300g\\\"}\",\"specText\":\"牛油 / 300g\",\"priceCent\":3990,\"originalPriceCent\":4990,\"stockAvailable\":100,\"weightGram\":300,\"image\":\"https://example.test/cart-sku-300.jpg\",\"status\":\"ENABLED\",\"sortOrder\":1}]}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Publish SPU:

```bash
curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/publish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Read SKU id from app detail:

```bash
SKU_ID=$(
  curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.skus[0].id));'
)
```

Add to cart:

```bash
CART_ITEM_ID=$(
  curl -s -X POST http://localhost:8080/app/cart/items \
    -H "Authorization: Bearer ${APP_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"skuId\":${SKU_ID},\"quantity\":2}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.quantity !== 2) process.exit(1); console.log(body.data.id); });'
)
```

List cart and verify current SKU price:

```bash
curl -s http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const item = body.data.items[0]; if (item.priceCent !== 3990 || item.lineAmountCent !== 7980 || !item.available) process.exit(1); console.log(`${item.productTitle} ${item.quantity} ${item.lineAmountCent}`); });'
```

Update quantity:

```bash
curl -s -X PUT "http://localhost:8080/app/cart/items/${CART_ITEM_ID}/quantity" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"quantity":3}' \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.quantity !== 3 || body.data.lineAmountCent !== 11970) process.exit(1); console.log(body.data.quantity); });'
```

Delete item:

```bash
curl -s -X DELETE "http://localhost:8080/app/cart/items/${CART_ITEM_ID}" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Add again and clear cart:

```bash
curl -s -X POST http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"skuId\":${SKU_ID},\"quantity\":1}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.quantity !== 1) process.exit(1); console.log(body.data.quantity); });'

curl -s -X DELETE http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'

curl -s http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.items.length !== 0) process.exit(1); console.log(body.data.items.length); });'
```

Expected result:

```text
success
购物车重庆牛油火锅底料 2 7980
3
success
1
success
0
```

## Coupon Smoke Checks

This is a real local smoke check for the coupon phase. It uses the local backend and local database path. In the test profile, WeChat login is still backed by the mock WeChat mini program client; product, cart, coupon, and promotion requests go through real local backend APIs, not product/cart/coupon mocks.

Start backend with the existing test-profile local command:

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

Mini program login:

```bash
APP_TOKEN=$(
  curl -s -X POST http://localhost:8080/app/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"code":"test-login-code"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.token));'
)
```

Create and enable a no-threshold coupon template:

```bash
VALID_START_AT=$(
  node -e 'const d = new Date(); d.setHours(0, 0, 0, 0); console.log(fmt(d)); function fmt(v) { const p = n => String(n).padStart(2, "0"); return `${v.getFullYear()}-${p(v.getMonth() + 1)}-${p(v.getDate())}T${p(v.getHours())}:${p(v.getMinutes())}:${p(v.getSeconds())}`; }'
)

VALID_END_AT=$(
  node -e 'const d = new Date(); d.setHours(23, 59, 59, 0); d.setDate(d.getDate() + 365); console.log(fmt(d)); function fmt(v) { const p = n => String(n).padStart(2, "0"); return `${v.getFullYear()}-${p(v.getMonth() + 1)}-${p(v.getDate())}T${p(v.getHours())}:${p(v.getMinutes())}:${p(v.getSeconds())}`; }'
)

TEMPLATE_ID=$(
  curl -s -X POST http://localhost:8080/admin/marketing/coupon-templates \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"新人无门槛券\",\"type\":\"CASH\",\"scope\":\"ALL\",\"discountAmountCent\":500,\"minimumSpendCent\":0,\"totalCount\":50,\"onePerUser\":true,\"status\":\"DISABLED\",\"claimStartAt\":\"${VALID_START_AT}\",\"claimEndAt\":\"${VALID_END_AT}\",\"validDays\":30,\"description\":\"首单无门槛立减5元\"}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.id));'
)

curl -s -X PUT "http://localhost:8080/admin/marketing/coupon-templates/${TEMPLATE_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"新人无门槛券\",\"type\":\"CASH\",\"scope\":\"ALL\",\"discountAmountCent\":500,\"minimumSpendCent\":0,\"totalCount\":50,\"onePerUser\":true,\"status\":\"ENABLED\",\"claimStartAt\":\"${VALID_START_AT}\",\"claimEndAt\":\"${VALID_END_AT}\",\"validDays\":30,\"description\":\"首单无门槛立减5元\"}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Verify claimable list:

```bash
curl -s http://localhost:8080/app/coupons/claimable \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const coupon = body.data.records.find(item => item.id === Number(process.argv[1]) || item.name === "新人无门槛券"); if (!coupon) process.exit(1); console.log(coupon.name); });' "${TEMPLATE_ID}"
```

Claim coupon:

```bash
USER_COUPON_ID=$(
  curl -s -X POST "http://localhost:8080/app/coupons/templates/${TEMPLATE_ID}/claim" \
    -H "Authorization: Bearer ${APP_TOKEN}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.status !== "CLAIMED") process.exit(1); console.log(body.data.id); });'
)

curl -s http://localhost:8080/app/coupons/mine \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const coupon = body.data.records.find(item => item.id === Number(process.argv[1])); if (!coupon) process.exit(1); console.log(coupon.status); });' "${USER_COUPON_ID}"
```

Create category/SPU/SKU and publish:

```bash
CATEGORY_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/categories \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"parentId":0,"name":"优惠券锅底分类","icon":"","sortOrder":30,"status":"ENABLED"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)

SPU_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/spus \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"categoryId\":${CATEGORY_ID},\"title\":\"购物车优惠券测试锅底\",\"subtitle\":\"满减券联调\",\"mainImage\":\"https://example.test/coupon-main.jpg\",\"sellingPoints\":\"优惠联调,实时计算\",\"detailHtml\":\"<p>用于优惠券可用列表联调。</p>\",\"sortOrder\":30,\"images\":[\"https://example.test/coupon-gallery-1.jpg\"],\"skus\":[{\"skuCode\":\"COUPON-HY-001\",\"specJson\":\"{\\\"口味\\\":\\\"牛油\\\",\\\"重量\\\":\\\"300g\\\"}\",\"specText\":\"牛油 / 300g\",\"priceCent\":3990,\"originalPriceCent\":4990,\"stockAvailable\":100,\"weightGram\":300,\"image\":\"https://example.test/coupon-sku-300.jpg\",\"status\":\"ENABLED\",\"sortOrder\":1}]}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)

curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/publish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Add SKU to cart:

```bash
SKU_ID=$(
  curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
    -H "Authorization: Bearer ${APP_TOKEN}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.skus[0].id));'
)

curl -s -X POST http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"skuId\":${SKU_ID},\"quantity\":1}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.lineAmountCent !== 3990) process.exit(1); console.log(`${body.data.productTitle} ${body.data.quantity} ${body.data.lineAmountCent}`); });'
```

Query `/app/coupons/available`:

```bash
curl -s http://localhost:8080/app/coupons/available \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const coupon = body.data.availableCoupons.find(item => item.id === Number(process.argv[1])); if (!coupon || body.data.discountAmountCent !== 500) process.exit(1); console.log(body.data.discountAmountCent); });' "${USER_COUPON_ID}"
```

Expected result:

```text
success
新人无门槛券
CLAIMED
购物车优惠券测试锅底 1 3990
500
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
