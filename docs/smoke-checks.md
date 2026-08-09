# Smoke Checks

## Backend

```bash
cd backend/shop-server
./mvnw test
```

Before running the backend, start local MySQL and create the dev database expected by the `dev` profile. The default credentials are `root` / `123456`; override them with `SHOP_DB_URL`, `SHOP_DB_USERNAME`, and `SHOP_DB_PASSWORD` when needed.

For mini program login and phone authorization against real WeChat APIs, keep `backend/shop-server/.env.dev.local` populated with the local mini program credentials.

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
  curl -s -X POST http://localhost:8080/admin/marketing/coupons/templates \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"新人无门槛券\",\"description\":\"首单无门槛立减5元\",\"couponType\":\"NO_THRESHOLD\",\"discountType\":\"AMOUNT_OFF\",\"thresholdCent\":0,\"discountCent\":500,\"scopeType\":\"ALL\",\"scopeValue\":\"\",\"strategyKey\":\"\",\"totalStock\":50,\"perUserLimit\":1,\"validStartAt\":\"${VALID_START_AT}\",\"validEndAt\":\"${VALID_END_AT}\",\"status\":\"DISABLED\",\"sortOrder\":1}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)

curl -s -X POST "http://localhost:8080/admin/marketing/coupons/templates/${TEMPLATE_ID}/enable" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Verify claimable list:

```bash
curl -s http://localhost:8080/app/coupons/claimable \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const templateId = Number(process.argv[1]); const coupon = body.data.find(item => item.templateId === templateId) || body.data.find(item => item.name === "新人无门槛券"); if (!coupon) process.exit(1); console.log(coupon.name); });' "${TEMPLATE_ID}"
```

Claim coupon:

```bash
USER_COUPON_ID=$(
  curl -s -X POST "http://localhost:8080/app/coupons/templates/${TEMPLATE_ID}/claim" \
    -H "Authorization: Bearer ${APP_TOKEN}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.status !== "CLAIMED") process.exit(1); console.log(body.data.userCouponId); });'
)

curl -s http://localhost:8080/app/coupons/mine \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const coupon = body.data.find(item => item.userCouponId === Number(process.argv[1])); if (!coupon) process.exit(1); console.log(coupon.status); });' "${USER_COUPON_ID}"
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

CART_ITEM_ID=$(
  curl -s -X POST http://localhost:8080/app/cart/items \
    -H "Authorization: Bearer ${APP_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"skuId\":${SKU_ID},\"quantity\":1}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.lineAmountCent !== 3990) process.exit(1); console.log(body.data.id); });'
)
```

Query `/app/coupons/available`:

```bash
curl -s -X POST http://localhost:8080/app/coupons/available \
  -H "Authorization: Bearer ${APP_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"cartItemIds\":[${CART_ITEM_ID}]}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const coupon = body.data.coupons.find(item => item.userCouponId === Number(process.argv[1])); if (!coupon || body.data.bestDiscountCent !== 500) process.exit(1); console.log(body.data.bestDiscountCent); });' "${USER_COUPON_ID}"
```

Expected result:

```text
success
新人无门槛券
CLAIMED
500
```

## Order Smoke Checks

This is a real local smoke check for the order phase. It uses the local backend and local database path. In the test profile, WeChat login is still backed by the mock WeChat mini program client described in docs/dev-setup.md; product, cart, coupon, promotion, order, and stock requests go through real local backend APIs, not product/cart/coupon/order mocks.

Use this section to distinguish real local smoke from mocked checks: only the test-profile WeChat login exchange is mocked here.

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

Create category:

```bash
CATEGORY_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/categories \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"parentId":0,"name":"订单锅底分类","icon":"","sortOrder":40,"status":"ENABLED"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)
```

Create SPU/SKU with deterministic SKU code and stock, then publish:

```bash
ORDER_SKU_CODE=ORDER-HY-NY-300G
ORDER_START_STOCK=100
ORDER_QUANTITY=1

SPU_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/spus \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"categoryId\":${CATEGORY_ID},\"title\":\"订单重庆牛油火锅底料\",\"subtitle\":\"订单链路联调\",\"mainImage\":\"https://example.test/order-main.jpg\",\"sellingPoints\":\"订单联调,库存锁定\",\"detailHtml\":\"<p>用于订单真实本地 smoke。</p>\",\"sortOrder\":40,\"images\":[\"https://example.test/order-gallery-1.jpg\"],\"skus\":[{\"skuCode\":\"${ORDER_SKU_CODE}\",\"specJson\":\"{\\\"口味\\\":\\\"牛油\\\",\\\"重量\\\":\\\"300g\\\"}\",\"specText\":\"牛油 / 300g\",\"priceCent\":3990,\"originalPriceCent\":4990,\"stockAvailable\":${ORDER_START_STOCK},\"weightGram\":300,\"image\":\"https://example.test/order-sku-300.jpg\",\"status\":\"ENABLED\",\"sortOrder\":1}]}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)

curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/publish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Read SKU id from app detail:

```bash
SKU_ID=$(
  curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
    -H "Authorization: Bearer ${APP_TOKEN}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const sku = body.data.skus.find(item => item.skuCode === process.argv[1]); if (!sku || sku.stockAvailable !== Number(process.argv[2])) process.exit(1); console.log(sku.id); });' "${ORDER_SKU_CODE}" "${ORDER_START_STOCK}"
)

curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const sku = body.data.skus.find(item => item.id === Number(process.argv[1])); if (!sku) process.exit(1); console.log(sku.skuCode); });' "${SKU_ID}"
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
  curl -s -X POST http://localhost:8080/admin/marketing/coupons/templates \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"订单无门槛券\",\"description\":\"订单链路立减5元\",\"couponType\":\"NO_THRESHOLD\",\"discountType\":\"AMOUNT_OFF\",\"thresholdCent\":0,\"discountCent\":500,\"scopeType\":\"ALL\",\"scopeValue\":\"\",\"strategyKey\":\"\",\"totalStock\":50,\"perUserLimit\":1,\"validStartAt\":\"${VALID_START_AT}\",\"validEndAt\":\"${VALID_END_AT}\",\"status\":\"DISABLED\",\"sortOrder\":1}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)

curl -s -X POST "http://localhost:8080/admin/marketing/coupons/templates/${TEMPLATE_ID}/enable" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Claim coupon and capture `USER_COUPON_ID`:

```bash
USER_COUPON_ID=$(
  curl -s -X POST "http://localhost:8080/app/coupons/templates/${TEMPLATE_ID}/claim" \
    -H "Authorization: Bearer ${APP_TOKEN}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.status !== "CLAIMED") process.exit(1); console.log(body.data.userCouponId); });'
)
```

Add SKU to cart and capture `CART_ITEM_ID`:

```bash
CART_ITEM_ID=$(
  curl -s -X POST http://localhost:8080/app/cart/items \
    -H "Authorization: Bearer ${APP_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"skuId\":${SKU_ID},\"quantity\":${ORDER_QUANTITY}}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.quantity !== Number(process.argv[1])) process.exit(1); console.log(body.data.id); });' "${ORDER_QUANTITY}"
)
```

Preview order with `cartItemIds` and `userCouponId`; assert coupon discount and payable amount:

```bash
curl -s -X POST http://localhost:8080/app/orders/preview \
  -H "Authorization: Bearer ${APP_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"cartItemIds\":[${CART_ITEM_ID}],\"userCouponId\":${USER_COUPON_ID}}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.couponDiscountCent !== 500 || body.data.payableAmountCent !== 3490) process.exit(1); console.log(body.data.couponDiscountCent); });'
```

Create order with an idempotency key and capture `ORDER_ID`:

```bash
IDEMPOTENCY_KEY="order-smoke-${SPU_ID}-${SKU_ID}"

ORDER_ID=$(
  curl -s -X POST http://localhost:8080/app/orders \
    -H "Authorization: Bearer ${APP_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"cartItemIds\":[${CART_ITEM_ID}],\"userCouponId\":${USER_COUPON_ID},\"idempotencyKey\":\"${IDEMPOTENCY_KEY}\"}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.status !== "CREATED" || body.data.couponDiscountCent !== 500 || body.data.payableAmountCent !== 3490) process.exit(1); console.error(body.data.status); console.log(body.data.orderId); });'
)
```

Call duplicate `POST /app/orders` with the same idempotency key and assert the same `ORDER_ID`:

```bash
curl -s -X POST http://localhost:8080/app/orders \
  -H "Authorization: Bearer ${APP_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"cartItemIds\":[${CART_ITEM_ID}],\"userCouponId\":${USER_COUPON_ID},\"idempotencyKey\":\"${IDEMPOTENCY_KEY}\"}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.orderId !== Number(process.argv[1])) process.exit(1); console.log("duplicate"); });' "${ORDER_ID}"
```

Read detail and assert item snapshot plus coupon lock fields on the order:

```bash
curl -s "http://localhost:8080/app/orders/${ORDER_ID}" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const item = body.data.items[0]; if (body.data.status !== "CREATED" || body.data.userCouponId !== Number(process.argv[1]) || item.skuCode !== process.argv[2] || item.quantity !== Number(process.argv[3]) || item.lineAmountCent !== 3990) process.exit(1); console.log(body.data.status); });' "${USER_COUPON_ID}" "${ORDER_SKU_CODE}" "${ORDER_QUANTITY}"
```

List created app orders and assert the order appears:

```bash
curl -s "http://localhost:8080/app/orders?current=1&size=10&status=CREATED" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const order = body.data.records.find(item => item.orderId === Number(process.argv[1])); if (!order || order.status !== "CREATED") process.exit(1); console.log(order.status); });' "${ORDER_ID}"
```

Verify the cart row has been removed and SKU stock available decreased by ordered quantity:

```bash
curl -s http://localhost:8080/app/cart/items \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const item = body.data.items.find(value => value.id === Number(process.argv[1])); if (item) process.exit(1); console.log("cart removed"); });' "${CART_ITEM_ID}"

curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const sku = body.data.skus.find(item => item.id === Number(process.argv[1])); const expectedStock = Number(process.argv[2]) - Number(process.argv[3]); if (!sku || sku.stockAvailable !== expectedStock) process.exit(1); console.log(sku.stockAvailable); });' "${SKU_ID}" "${ORDER_START_STOCK}" "${ORDER_QUANTITY}"
```

Verify database lifecycle rows for `stock_lock`, `stock_log`, and `user_coupon`:

```sql
select status
from stock_lock
where order_id = ${ORDER_ID}
  and sku_id = ${SKU_ID};

select change_type
from stock_log
where sku_id = ${SKU_ID}
  and change_type = 'ORDER_LOCK'
order by id desc
limit 1;

select status, locked_order_id
from user_coupon
where id = ${USER_COUPON_ID};
```

Assert the live coupon status through the app API:

```bash
curl -s http://localhost:8080/app/coupons/mine \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const coupon = body.data.find(item => item.userCouponId === Number(process.argv[1])); if (!coupon || coupon.status !== "LOCKED") process.exit(1); console.log(coupon.status); });' "${USER_COUPON_ID}"
```

Admin list/detail checks:

```bash
curl -s "http://localhost:8080/admin/orders?current=1&size=10&status=CREATED" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const order = body.data.records.find(item => item.orderId === Number(process.argv[1])); if (!order || order.status !== "CREATED") process.exit(1); console.log(order.status); });' "${ORDER_ID}"

curl -s "http://localhost:8080/admin/orders/${ORDER_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const item = body.data.items[0]; if (body.data.orderId !== Number(process.argv[1]) || item.skuCode !== process.argv[2]) process.exit(1); console.log(item.skuCode); });' "${ORDER_ID}" "${ORDER_SKU_CODE}"
```

Admin close the created order:

```bash
curl -s -X POST "http://localhost:8080/admin/orders/${ORDER_ID}/close" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Verify order status is `CLOSED` and SKU stock is released:

```bash
curl -s "http://localhost:8080/app/orders/${ORDER_ID}" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.status !== "CLOSED") process.exit(1); console.log(body.data.status); });'

curl -s "http://localhost:8080/app/product/spus/${SPU_ID}" \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const sku = body.data.skus.find(item => item.id === Number(process.argv[1])); if (!sku || sku.stockAvailable !== Number(process.argv[2])) process.exit(1); console.log("stock released"); });' "${SKU_ID}" "${ORDER_START_STOCK}"
```

Verify database release rows for `stock_lock`, `stock_log`, and `user_coupon`:

```sql
select status
from stock_lock
where order_id = ${ORDER_ID}
  and sku_id = ${SKU_ID};

select change_type
from stock_log
where sku_id = ${SKU_ID}
  and change_type = 'ORDER_RELEASE'
order by id desc
limit 1;

select status, locked_order_id, locked_at, released_at
from user_coupon
where id = ${USER_COUPON_ID};
```

Assert the coupon lock is released and the user coupon is back to `CLAIMED` through the app API, so it can be selected again by later checkout flows:

```bash
curl -s http://localhost:8080/app/coupons/mine \
  -H "Authorization: Bearer ${APP_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const coupon = body.data.find(item => item.userCouponId === Number(process.argv[1])); if (!coupon || coupon.status !== "CLAIMED") process.exit(1); console.log(coupon.status); });' "${USER_COUPON_ID}"
```

Expected result includes:

```text
success
ORDER-HY-NY-300G
500
CREATED
duplicate
LOCKED
ORDER_LOCK
CLOSED
RELEASED
ORDER_RELEASE
CLAIMED
```

## File Storage And Home Banner Smoke Checks

This is a real local smoke checklist for the file upload, asset library, and home
banner phase. It uses the local backend and database, plus a dedicated real COS
test bucket with Cloud Infinite enabled. In the `test` profile, WeChat login is
backed by the mock WeChat mini program client, but upload, Cloud Infinite
processing, storage metadata, product usage, banner usage, delete protection,
and mini program banner APIs use the real application and COS paths.

Before starting, save a valid Tencent COS runtime configuration through
`开发配置 -> 对象存储配置`. The credentials must have the permissions listed in
[`docs/cos-direct-upload.md`](./cos-direct-upload.md). Do not use a production
bucket for this destructive smoke check.

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

Create tiny local smoke files:

```bash
SMOKE_DIR="$(mktemp -d)"
python3 - <<'PY' "${SMOKE_DIR}/tiny.png"
import base64
import sys
png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4x8AAAAASUVORK5CYII="
with open(sys.argv[1], "wb") as f:
    f.write(base64.b64decode(png))
PY
python3 - <<'PY' "${SMOKE_DIR}/tiny.mp4"
import sys
# Minimal non-empty ISO BMFF container used to exercise MP4 upload policy.
payload = bytes.fromhex(
    "000000186674797069736f6d0000020069736f6d69736f32"
    "000000086d646174"
)
with open(sys.argv[1], "wb") as f:
    f.write(payload)
PY
printf '%s\n' '-----BEGIN CERTIFICATE-----' 'smoke-only' '-----END CERTIFICATE-----' > "${SMOKE_DIR}/smoke.pem"
printf 'not an allowed file' > "${SMOKE_DIR}/bad.exe"
: > "${SMOKE_DIR}/empty.png"
python3 - <<'PY' "${SMOKE_DIR}/large.png"
import sys
with open(sys.argv[1], "wb") as f:
    f.write(b"\x89PNG\r\n\x1a\n")
    f.write(b"0" * (6 * 1024 * 1024))
PY
```

Create a library folder, upload reusable public image/video assets directly to
COS, and upload one staged payment-owned secret document through its intentionally
server-mediated endpoint. The image asset is deliberately reused below as product
cover, category icon, gallery/SKU image, rich-text image, and home banner:

```bash
ASSET_FOLDER_ID=$(
  curl -s -X POST http://localhost:8080/admin/asset-folders \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"parentId":0,"name":"本地 smoke 素材","sortOrder":10,"status":"ENABLED"}' \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.parentId !== 0) process.exit(1); console.log(body.data.id); });'
)

# The helper first asks the business API for a single-object POST Policy, sends
# the file body to the returned COS source URL, and then completes the session.
# It prints only the completed asset JSON so callers can inspect the result.
direct_asset_upload() {
  node - "$ADMIN_TOKEN" "$ASSET_FOLDER_ID" "$1" "$2" "$3" <<'NODE'
const fs = require("node:fs");
const [token, folderId, filePath, filename, contentType] = process.argv.slice(2);
const source = fs.readFileSync(filePath);
const api = "http://localhost:8080";

async function readApi(response) {
  const body = await response.json();
  if (!response.ok || body.code !== 200) {
    throw new Error(JSON.stringify(body));
  }
  return body.data;
}

(async () => {
  const session = await readApi(await fetch(`${api}/admin/assets/upload-sessions`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      folderId: Number(folderId),
      originalFilename: filename,
      contentType,
      sizeBytes: source.length
    })
  }));

  const form = new FormData();
  for (const [key, value] of Object.entries(session.formData)) {
    form.append(key, value);
  }
  // POST Object requires the binary file part to be last.
  form.append("file", new Blob([source], { type: contentType }), filename);
  const uploaded = await fetch(session.uploadUrl, { method: "POST", body: form });
  if (!uploaded.ok) {
    throw new Error(`COS POST failed: ${uploaded.status} ${await uploaded.text()}`);
  }

  const completed = await fetch(
    `${api}/admin/assets/upload-sessions/${encodeURIComponent(session.uploadId)}/complete`,
    { method: "POST", headers: { Authorization: `Bearer ${token}` } }
  );
  const completedBody = await completed.json();
  if (!completed.ok || completedBody.code !== 200) {
    throw new Error(JSON.stringify(completedBody));
  }
  process.stdout.write(JSON.stringify(completedBody));
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
NODE
}

SHARED_ASSET_JSON=$(
  direct_asset_upload \
    "${SMOKE_DIR}/tiny.png" \
    "tiny.png" \
    "image/png"
)

SHARED_ASSET_ID=$(
  printf '%s' "${SHARED_ASSET_JSON}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.scope !== "LIBRARY" || body.data.mediaKind !== "IMAGE" || body.data.visibility !== "PUBLIC" || !body.data.url) process.exit(1); console.log(body.data.id); });'
)

SHARED_ASSET_URL=$(
  printf '%s' "${SHARED_ASSET_JSON}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data.url));'
)

LIBRARY_VIDEO_ID=$(
  direct_asset_upload \
    "${SMOKE_DIR}/tiny.mp4" \
    "tiny.mp4" \
    "video/mp4" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.scope !== "LIBRARY" || body.data.mediaKind !== "VIDEO" || body.data.visibility !== "PUBLIC" || !body.data.url) process.exit(1); console.log(body.data.id); });'
)

PRODUCT_FILE_ID="${SHARED_ASSET_ID}"
CATEGORY_ICON_FILE_ID="${SHARED_ASSET_ID}"
BANNER_FILE_ID="${SHARED_ASSET_ID}"
PRODUCT_IMAGE_URL="${SHARED_ASSET_URL}"
CATEGORY_ICON_URL="${SHARED_ASSET_URL}"
BANNER_IMAGE_URL="${SHARED_ASSET_URL}"

PRIVATE_CERT_ID=$(
  curl -s -X POST http://localhost:8080/admin/pay/configs/secret-files \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -F file=@"${SMOKE_DIR}/smoke.pem;type=application/x-pem-file" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.scope !== "SECRET" || body.data.mediaKind !== "DOCUMENT" || body.data.visibility !== "PRIVATE" || body.data.url || body.data.publicUrl || !body.data.expiresAt) process.exit(1); console.log(body.data.id); });'
)

curl -s "http://localhost:8080/admin/assets?folderId=${ASSET_FOLDER_ID}&mediaKind=IMAGE" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.total !== 1 || body.data.records[0].id !== Number(process.argv[1])) process.exit(1); console.log("library asset listed"); });' "${SHARED_ASSET_ID}"

curl -s "http://localhost:8080/admin/assets?keyword=smoke.pem" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.total !== 0) process.exit(1); console.log("private scopes hidden"); });'

curl -s "http://localhost:8080/admin/assets/${PRIVATE_CERT_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code === 200) process.exit(1); console.log("secret detail rejected"); });'
```

Focused tests cover recorded-location routing, database-clock TTLs, the staged/claimed/replaced-or-cleared payment-secret lifecycle, and token-leased retryable cleanup:

```bash
cd backend/shop-server
./mvnw -Dtest='RoutingStorageProviderTest,StorageAssetTimezoneTest,PrivateStorageFileServiceTest,AdminPaymentConfigControllerTest,StorageAssetCleanupServiceTest' test
```

Create a category and product using uploaded file ids, then publish the product:

```bash
CATEGORY_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/categories \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"parentId\":0,\"name\":\"素材库分类\",\"icon\":\"${CATEGORY_ICON_URL}\",\"iconFileId\":${CATEGORY_ICON_FILE_ID},\"sortOrder\":90,\"status\":\"ENABLED\"}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)

SPU_ID=$(
  curl -s -X POST http://localhost:8080/admin/product/spus \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"categoryId\":${CATEGORY_ID},\"title\":\"素材库牛油火锅底料\",\"subtitle\":\"上传主图 smoke\",\"mainImage\":\"${PRODUCT_IMAGE_URL}\",\"mainImageFileId\":${PRODUCT_FILE_ID},\"sellingPoints\":\"素材库,本地上传\",\"detailHtml\":\"<p><img src=\\\"${PRODUCT_IMAGE_URL}\\\" /></p>\",\"sortOrder\":90,\"images\":[{\"url\":\"${PRODUCT_IMAGE_URL}\",\"fileId\":${PRODUCT_FILE_ID}}],\"skus\":[{\"skuCode\":\"FILE-SMOKE-300G\",\"specJson\":\"{\\\"规格\\\":\\\"300g\\\"}\",\"specText\":\"300g\",\"priceCent\":3990,\"originalPriceCent\":4990,\"stockAvailable\":20,\"weightGram\":300,\"image\":\"${PRODUCT_IMAGE_URL}\",\"imageFileId\":${PRODUCT_FILE_ID},\"status\":\"ENABLED\",\"sortOrder\":1}]}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)

curl -s -X POST "http://localhost:8080/admin/product/spus/${SPU_ID}/publish" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code !== 200) process.exit(1); console.log(body.msg); });'
```

Create and enable a home banner using the uploaded banner image:

```bash
BANNER_ID=$(
  curl -s -X POST http://localhost:8080/admin/home/banners \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"title\":\"首页轮播 smoke\",\"subtitle\":\"本地素材库上传\",\"imageFileId\":${BANNER_FILE_ID},\"imageUrl\":\"${BANNER_IMAGE_URL}\",\"jumpType\":\"PRODUCT\",\"jumpTargetId\":${SPU_ID},\"jumpPath\":\"\",\"status\":\"ENABLED\",\"sortOrder\":1}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => console.log(JSON.parse(b).data));'
)

curl -s http://localhost:8080/app/home/banners \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const banner = body.data.find(item => item.id === Number(process.argv[1])); if (!banner || banner.imageUrl !== process.argv[2]) process.exit(1); console.log(banner.title); });' "${BANNER_ID}" "${BANNER_IMAGE_URL}"
```

Verify every role appears as an actual usage on the same asset detail:

```bash
curl -s "http://localhost:8080/admin/assets/${PRODUCT_FILE_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); const types = body.data.usages.map(item => item.usageType); if (!types.includes("PRODUCT_SPU_MAIN") || !types.includes("PRODUCT_SPU_GALLERY") || !types.includes("PRODUCT_SKU_IMAGE") || !types.includes("PRODUCT_DETAIL_HTML")) process.exit(1); console.log(types.join(",")); });'

curl -s "http://localhost:8080/admin/assets/${CATEGORY_ICON_FILE_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (!body.data.usages.some(item => item.usageType === "PRODUCT_CATEGORY_ICON" && item.status === "ACTIVE")) process.exit(1); console.log("category icon usage"); });'

curl -s "http://localhost:8080/admin/assets/${BANNER_FILE_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (!body.data.usages.some(item => item.usageType === "HOME_BANNER" && item.status === "ACTIVE")) process.exit(1); console.log("banner usage"); });'
```

Verify in-use product/banner files cannot be deleted:

```bash
curl -s -X DELETE "http://localhost:8080/admin/assets/${PRODUCT_FILE_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code === 200) process.exit(1); console.log(body.msg); });'

curl -s -X DELETE "http://localhost:8080/admin/assets/${BANNER_FILE_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code === 200) process.exit(1); console.log(body.msg); });'
```

Verify invalid uploads are rejected:

```bash
assert_session_rejected() {
  curl -s -X POST http://localhost:8080/admin/assets/upload-sessions \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"folderId\":${ASSET_FOLDER_ID},\"originalFilename\":\"$1\",\"contentType\":\"$2\",\"sizeBytes\":$3}" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code === 200) process.exit(1); console.log(body.msg); });'
}

assert_session_rejected "bad.exe" "application/octet-stream" 19
assert_session_rejected "empty.png" "image/png" 0
assert_session_rejected "large.png" "image/png" $((6 * 1024 * 1024 + 8))
assert_session_rejected "../tiny.png" "image/png" 68

# A valid-looking name and MIME do not bypass decoded-format validation. The
# bytes go to COS, but completion must fail before an active asset is created.
if direct_asset_upload \
  "${SMOKE_DIR}/bad.exe" \
  "spoofed.png" \
  "image/png"
then
  echo "spoofed image unexpectedly completed"
  exit 1
else
  echo "spoofed image rejected after Cloud Infinite inspection"
fi
```

UI smoke checklist after starting the admin dev server and opening the mini program in WeChat DevTools:

- In browser DevTools, an admin upload from `/storage/files` sends the large
  multipart request to the COS source host; the two business API requests contain
  only JSON/session metadata and no image body.
- The completed raster asset is WebP at or below the configured profile size and
  returns from the configured COS source domain with a long immutable cache
  header; a custom COS origin is allowed, but no CDN domain is involved.
- Admin reuses that image as product, SKU, specification value, category icon,
  guarantee icon, banner, or rich-text media without any purpose restriction.
- Admin creates, renames, disables, and deletes empty folders; disabled folders reject uploads and moves.
- Admin asset detail shows every actual usage location and the referenced/unreferenced filter follows active usages.
- Assets referenced by product, banner, order snapshots, or other active usages cannot be deleted.
- Payment configuration uploads a `.pem` only through `/admin/pay/configs/secret-files`; the secret never appears in the asset library and has no public URL or preview.
- Mini program avatar, after-sale evidence, and customer-service images send the
  file body to the configured COS `uploadFile` domain. After-sale evidence never
  appears in the reusable asset library.
- A customer-service sender sees the local preview immediately. The other side
  loads the 720px signed COS thumbnail first, and reopening the conversation
  reuses the mini program's local image cache; opening preview fetches the 1920px
  signed display image.
- A simulated `800007` completion failure retries the same upload session after
  backoff and does not issue a second COS POST; all other completion errors stop
  without falling back to the business upload endpoint.
- A blocked, failed, or manually aborted COS POST issues one best-effort DELETE
  for the same `uploadId`; retrying uploads does not exhaust the ten active-session
  slots. Completion-stage failures must not issue that DELETE.
- Illegal extension, oversized file, empty file, and path traversal filename are rejected.

## Mock Payment Automated Smoke Checks

This section is automated or test-profile smoke only. It uses the backend mock WeChat Pay provider from `application-test.yaml`; it must not be reported as a real WeChat request.

Recommended backend checks:

```bash
cd backend/shop-server
./mvnw -Dtest='AppPaymentControllerTest,PaymentCallbackServiceTest,PaymentNotificationRouteIssuanceTest,PaymentNotificationRouteRejectionTest,PaymentNotificationConfigSelectorRouteTest,PaymentNotificationRouteServiceTest,PaymentTimeoutCloseServiceTest,PaymentSchemaTest,PaymentConfigResolverTest,AdminPaymentConfigControllerTest,AesGcmPaymentSecretCipherTest,PaymentSecretRotationServiceTest' test
```

Optional local endpoint smoke can reuse the existing Order Smoke Checks setup: start the backend with the `test` profile, create product/cart/coupon/order data through the real local backend APIs, then call:

```bash
curl -s -X POST "http://localhost:8080/app/orders/${ORDER_ID}/pay" \
  -H "Authorization: Bearer ${APP_TOKEN}"
```

Assert the response contains all WeChat JSAPI payment fields:

```text
timeStamp
nonceStr
package
signType
paySign
```

The current local runtime does not expose a public test-only endpoint that marks a mock provider payment as paid. Use a test-only mock callback helper or backend test endpoint only if one is implemented under the `test` profile. Otherwise, treat the backend tests above as the automated mock payment smoke for callback and sync behavior.

Automated verification points:

- `POST /app/orders/{orderId}/pay` creates or reuses one active mock payment and returns JSAPI payment params.
- Mock callback or mock sync finalizes a paid order as `PAID`.
- Paid finalization changes `stock_lock.status` to `CONFIRMED`.
- Paid finalization changes a locked coupon to `USED`.
- Timeout close changes the unpaid payment/order to `CLOSED`.
- Timeout close releases stock locks and returns the coupon to `CLAIMED`.

## Real WeChat Payment Local Smoke Checklist

This is manual real smoke, not automated. Do not mark it passed until a real mini program payment completes against a local backend exposed through HTTPS.

1. Fill `backend/shop-server/.env.dev.local` with local-only placeholders replaced by real local credentials:

```properties
WECHAT_PAY_ENABLED=true
WECHAT_PAY_CONFIG_SOURCE=ENV
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
SHOP_SECRET_ENCRYPTION_ROTATION_ENABLED=false
SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=false
SHOP_WECHAT_RECEIPT_RECONCILIATION_ENABLED=true
```

2. Start the backend with the `dev` profile and expose it with an HTTPS tunnel.
3. Set payment callback URLs to the public tunnel domain:

```text
https://<public-tunnel-domain>/wxpay/pay/notify
https://<public-tunnel-domain>/wxpay/refund/notify
```

4. If using DB-managed credentials, upload each private key or certificate through `POST /admin/pay/configs/secret-files`, configure `/admin/pay/configs` with the returned secret asset IDs, set that DB config as the DB candidate, and save the admin runtime source as `DB`. Confirm `/admin/pay/configs/source` returns `DB` with `persisted=true`, and `/admin/pay/configs/effective` returns masked values and the same callback URLs.
5. In a real mini program session, create an order and tap payment.
6. Complete WeChat payment with the real payer account.
7. Verify the backend receives `POST /wxpay/pay/notify` while route issuance is disabled, or `/wxpay/pay/notify/r/{opaque-token}` after the routed handlers are deployed everywhere and `SHOP_PAY_NOTIFICATION_ROUTE_ENABLED=true`. Confirm the callback log reaches `SUCCESS` and the order becomes `PAID`.
8. If the callback is delayed, call `POST /app/orders/{orderId}/payment/sync` from the mini program/backend client and verify final state still comes from the backend payment provider query.
9. Verify `payment_transaction_id` is present before using this order for real WeChat shipping upload.

Do not commit `.env.*.local`, certificate/key files, upload directories, or screenshots/logs containing credential values, certificate paths, public key IDs, merchant identifiers, AppIDs, callback domains, or payment responses with sensitive data.

## Shipment Smoke Checklist

Automated shipment checks use backend tests and mock or mocked HTTP providers; they do not prove a real WeChat shipping upload:

```bash
cd backend/shop-server
./mvnw -Dtest='AdminShipmentControllerTest,ShipmentSchemaTest,WechatShippingProviderTest,WechatReceiptReconciliationServiceTest,WechatReceiptReconciliationSchedulerTest' test
```

Local skipped path:

- Set `SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=false`.
- Start the backend and create or reuse a paid order.
- Admin ships the order through `POST /admin/orders/{orderId}/ship` with `expressCompany`, `trackingNo`, and optional `shipmentNote`.
- Verify order status is `SHIPPED`.
- Verify shipment `wechatUploadStatus` is `SKIPPED` in admin and mini program order detail.
- Confirm backend logs do not contain WeChat access tokens.

Real upload enabled path:

- Set `SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=true`.
- Use a real paid order with `payment_transaction_id`, payer `openid`, and real WeChat mini program credentials so the backend can obtain an access token internally.
- Admin ships the order through `POST /admin/orders/{orderId}/ship`.
- Verify shipment `wechatUploadStatus` becomes `UPLOADED`, or `FAILED` with a safe `wechatErrorCode` and `wechatErrorMessage`.
- If upload fails, retry through `POST /admin/orders/{orderId}/shipping/retry-wechat-upload` and verify `retryCount` increments without creating a duplicate shipment row.
- Confirm logs include only safe error summaries and never print access tokens.

### Electronic Waybill And Mini Program Logistics

Automated checks cover the database contract, configuration modes, provider
payloads, lifecycle/idempotency, explicit shipment confirmation, registration,
and Mini Program fallback behavior. They do not prove that WeChat accepted a
real carrier/waybill relationship:

```bash
cd backend/shop-server
./mvnw -Dtest='WechatWaybillSchemaTest,AdminWechatExpressConfigControllerTest,WechatExpressProviderTest,AdminElectronicWaybillControllerTest,AdminShipmentControllerTest,OrderAggregateCleanupServiceTest' test
cd ../../admin
pnpm typecheck
pnpm build
cd ../miniprogram
pnpm check
```

Sandbox smoke:

1. In `订单管理 / 电子面单配置`, select `SANDBOX`, fill the structured sender
   and single-parcel defaults, and save. Confirm the effective account shows
   `TEST / test_biz_id / 1 / test_service_name`.
2. Use a real paid order whose payer OpenID belongs to a Mini Program
   administrator, operator, or developer. The official sandbox permits at most
   10 waybills per day.
3. Open the order's shipment dialog, choose “生成电子面单”, create the label,
   and verify the order remains `PAID` with no `order_shipment` row.
4. Preview and print the existing label. Preview/reprint must call only the
   print endpoint and must not create a second upstream order.
5. Exercise the server-provided sandbox actions for pickup, transit, delivery,
   and signed status. Do not expose any action outside the server whitelist.
6. Click “确认发货” once or twice. Verify exactly one shipment exists, its
   source is `WECHAT_WAYBILL`, the waybill attempt is `CONFIRMED`, and the order
   becomes `SHIPPED` once.

Manual and Mini Program smoke:

- Keep `POST /admin/orders/{orderId}/ship` available for an existing real
  carrier and tracking number. An active electronic waybill must block manual
  shipment and receiver-address changes until it is canceled or recovered.
- In Mini Program order detail, verify the static logistics card shows carrier,
  full tracking number, copy action, and shipped time even if official
  registration fails.
- A random/fabricated tracking number is valid only for that static-card test.
  It is not expected to yield an official waybill token or trajectory.
- For official trajectory smoke, use WeChat DevTools **Preview** and a real
  device with the actual buyer/order/phone/transaction/carrier/waybill match.
  The logistics plugin cannot be fully simulated in the DevTools simulator.
- Verify the token and label responses include `Cache-Control: no-store`, and
  confirm logs/client storage never contain access tokens, waybill tokens,
  OpenIDs, phone/address data, transaction IDs, or label HTML.

Automatic receipt reconciliation:

- Keep `SHOP_WECHAT_RECEIPT_RECONCILIATION_ENABLED=true`. The default scan delay is 5 minutes, the minimum shipped age is 1 hour, and one still-unconfirmed order is queried at most every 30 minutes.
- Use a `SHIPPED` order whose shipment has `wechatProviderMode=REAL` and `wechatUploadStatus=UPLOADED`.
- Let WeChat confirm or automatically confirm receipt without tapping the mini program's local confirmation button.
- After the configured reconciliation interval, verify the local order becomes `COMPLETED`, `completed_at` is populated, and `order_status_log` contains `ORDER_AUTO_COMPLETED` with `operator_type=SYSTEM`.
- Verify a WeChat order that is still unconfirmed remains local `SHIPPED`, and a local order with an active blocking after-sale is not automatically completed.
- Confirm concurrent scheduler runs make one provider query for a leased shipment and that an interrupted lease can be reclaimed after the configured claim timeout.

## Refund Smoke Checklist

Automated refund checks use the backend mock provider and mock notifications; they do not prove a real WeChat refund:

```bash
cd backend/shop-server
./mvnw -Dtest='AppAfterSaleControllerTest,AdminAfterSaleControllerTest,RefundCallbackServiceTest,RefundRecoveryServiceTest,PaymentNotificationRouteIssuanceTest,AfterSaleSchemaTest' test
```

Mini program and admin smoke:

Before uploading evidence, set these values explicitly:

- `APP_TOKEN`: token for the owner of the target order.
- `ADMIN_TOKEN`: admin token with `asset:read`, used only to prove the private asset is rejected by generic library detail.
- `AFTER_SALE_ORDER_ID`: an order owned by `APP_TOKEN` whose status is `PAID`, `SHIPPED`, or `COMPLETED`. Do not reuse the `CREATED`/`CLOSED` order from the Order Smoke Checks.
- `AFTER_SALE_IMAGE`: path to a non-empty, valid PNG image.

```bash
AFTER_SALE_EVIDENCE_ID=$(
  curl -s -X POST "http://localhost:8080/app/orders/${AFTER_SALE_ORDER_ID}/after-sale-evidence" \
    -H "Authorization: Bearer ${APP_TOKEN}" \
    -F file=@"${AFTER_SALE_IMAGE};type=image/png" \
  | node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.data.scope !== "ATTACHMENT" || body.data.mediaKind !== "IMAGE" || body.data.visibility !== "PRIVATE" || body.data.url || body.data.publicUrl || !body.data.expiresAt) process.exit(1); console.log(body.data.id); });'
)

curl -s "http://localhost:8080/admin/assets/${AFTER_SALE_EVIDENCE_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
| node -e 'let b=""; process.stdin.on("data", c => b += c); process.stdin.on("end", () => { const body = JSON.parse(b); if (body.code === 200) process.exit(1); console.log("attachment detail rejected"); });'
```

- The server fixes the evidence contract to `ATTACHMENT + IMAGE + PRIVATE`, binds it to `AFTER_SALE_ORDER_ID`, and returns a 24-hour staging expiry.
- In MySQL, verify `TIMESTAMPDIFF(MINUTE, CURRENT_TIMESTAMP, expires_at)` is about `120` for the staged secret and `1440` for the staged evidence. A negative value indicates a JVM/database clock regression and must block cutover.
- Mini program applies refund-only through `POST /app/orders/{orderId}/after-sales` with `afterSaleType=REFUND_ONLY`, `requestedAmountCent`, `reason`, and `evidenceFileIds`.
- The current full-order-refund policy rejects `RETURN_REFUND` and partial amounts; the mini program exposes only `REFUND_ONLY` with the immutable full paid amount.
- Admin rejects one request through `POST /admin/after-sales/{afterSaleId}/reject` and verifies status `REJECTED` while the order remains paid or shipped.
- Admin approves one request through `POST /admin/after-sales/{afterSaleId}/approve` with `approvedAmountCent`; with the mock provider, verify the after-sale moves to `REFUNDING` and `refund_order.status` starts as `PROCESSING`.
- Backend mock refund callback tests verify successful callback transitions to after-sale `REFUNDED`, refund order `SUCCESS`, and order `REFUNDED`.

Real local WeChat refund smoke:

- Start from a real paid, shipped, or completed order created through the real payment smoke.
- Use HTTPS tunnel callback URL `https://<public-tunnel-domain>/wxpay/refund/notify`.
- Upload order-scoped evidence, then apply the full-order refund-only request from the mini program with the returned evidence asset IDs.
- Approve the after-sale in admin and verify WeChat accepts the refund request.
- Wait for `POST /wxpay/refund/notify`.
- Verify refund callback log reaches `SUCCESS`, `refund_order.status` becomes `SUCCESS`, after-sale status becomes `REFUNDED`, and order status becomes `REFUNDED`.
- Do not claim real refund smoke passed when only mock provider tests or test-profile callbacks were run.

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
