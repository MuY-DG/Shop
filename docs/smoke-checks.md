# Shop 验收清单

自动化只能证明代码和基础集成链路，不能代替真实微信、支付、物流、COS 和真机验收。

## 1. 记录版本

```bash
git status --short --branch
git rev-parse HEAD
```

记录目标环境、Git SHA、API 域名、Admin 域名、小程序 AppID 与验收账号。

## 2. 自动化

```bash
cd backend/shop-server
./mvnw test
./mvnw -Pintegration verify
./scripts/ci/assert-integration-test-results.sh target/failsafe-reports
./scripts/ci/verify-test-layers.sh
./scripts/ci/verify-flyway-migrations.sh

cd ../../
pnpm --dir admin check
CI=true pnpm --dir admin build
pnpm --dir admin check:generated-imports
pnpm --dir miniprogram check
git diff --check
```

## 3. 部署健康

部署命令：

```bash
./deploy.sh txcloud
# 或
./deploy.sh shop
```

服务器检查：

```bash
ssh shop 'cd /opt/shop/shop-server && sudo docker compose --env-file config/runtime/runtime.env -f compose.prod.yaml ps'
ssh shop 'curl -fsS http://127.0.0.1:8080/actuator/health'
ssh shop 'curl -fsS http://127.0.0.1:8080/actuator/info'
```

确认：

- MySQL、Redis 和 `shop-server` 均为 healthy。
- 8080、3306、6379 只绑定 `127.0.0.1`。
- `/actuator/info` 的 Git SHA 与发布提交一致，Flyway 版本是当前最高迁移。
- API 与 Admin HTTPS 可访问，Admin 前端路由刷新不返回 404。
- API 与 Admin 两个域名的 `/realtime` WebSocket 都能完成握手并到达后端。
- 容器没有持续重启，日志没有 Flyway、密钥或连接错误。

## 4. Admin

全新空库时，读取本机
`backend/shop-server/config/runtime/bootstrap-admin.<target>.txt` 登录 Super，立即修改
临时密码并删除该文件。

逐项确认：

- Super、普通管理员、禁用账号和权限菜单符合预期。
- 商家主体、法律文档、售后规则和退货地址使用真实内容。
- 微信平台、微信支付、COS 和物流配置属于当前环境。
- 商品、SKU、库存、运费模板、优惠券和首页装修可以保存并重新读取。
- 敏感配置页面不回显私钥、APIv3 Key 或 COS Secret 明文。

## 5. 小程序基础流程

- 使用正确 AppID 和版本打开，确认请求进入对应 API。
- 新用户登录、手机号授权、刷新令牌和再次进入均正常。
- 首页、分类、搜索、商品详情、购物车和收藏使用真实数据。
- 地址新增、微信地址导入、地图选点、编辑和默认地址正常。
- 订单预览的商品、优惠、运费、地址和应付金额一致。
- 取消、软删除、再次购买、评价、售后申请和售后详情符合状态限制。

## 6. 真实平台流程

必须使用测试商品、测试账号和可核对的真实平台记录：

- 微信登录：Code 只能由对应环境的 AppID/Secret 换取。
- 支付：下单、拉起支付、回调、主动查单和订单落账金额一致。
- 退款：部分/累计退款、回调或查单恢复、本地记录和商户平台金额一致。
- COS：Admin 与小程序直传、公开读取、私有签名读取和图片处理成功。
- 发货：拆分包裹、部分发货、微信发货上传、轨迹与确认收货正常。
- 财务对账：账单下载、差异展示、处理备注和审计记录正确。
- 客服：消息、图片、转接、离线恢复和订单/商品上下文正常。

## 7. 结论

验收结果至少记录：

```text
目标：
Git SHA：
Flyway：
自动化：
后端/Admin：
小程序：
支付/退款：
COS：
发货/物流：
未通过项：
结论：通过 / 不通过
```

部署前置与日常命令见 [deployment-guide.md](deployment-guide.md)。
