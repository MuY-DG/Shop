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
