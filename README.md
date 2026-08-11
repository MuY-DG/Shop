# Shop

面向微信小程序渠道的单商家自营电商系统。当前定位是“单商家、单仓为主”的
V1，采用 Spring Boot 模块化单体后端、Vue 3 管理后台和原生微信小程序。

## 当前能力

| 领域 | 已实现 | 边界 |
| --- | --- | --- |
| 商品 | SPU/SKU、规格、库存、图片、运费模板、食品信息披露 | 以单仓为主，无采购、盘点和仓间调拨 |
| 交易 | 购物车、优惠券、下单、库存锁定、15 分钟支付截止时间、超时关单 | 未支持拆单、多包裹和部分发货 |
| 支付 | 微信支付、回调验签、退款恢复、按售后商品数量幂等回补库存、微信交易账单（`ALL`）下载与本地支付/退款日对账 | V93 不含微信资金账单或银行到账核对；真实商户账单链路仍需发布验收 |
| 履约 | 后台发货、微信发货可靠投递、物流轨迹、电子面单 | 真实微信生产链路、插件和打印仍需发布验收 |
| 服务动态 | V94 微信新版 `2001` “购物（实体物流）服务动态”、可靠状态投递与主动对账；V95 Admin 动态运行控制、CAS 与变更审计 | 不是传统订阅消息；必须先开 Capture 验收队列，再单独开 Worker，生产真实展示仍待真机验收 |
| 售后 | 商品/数量级申请与审批、部分/累计退款、仅退款、退货退款、商家退货地址、用户退货物流、商家验收/拒收、退货超时关闭、历史 V1 工单兼容 | 真实微信退款回调和生产 MySQL 实际数据迁移仍需发布验收 |
| 用户 | 微信登录、地址、收藏、足迹、评价、客服、账号注销和个人信息权利 | 会员等级、积分、储值为后续经营能力 |
| 合规 | 商家资质、法律文档版本、食品标签和上架门禁 | 仓库不提供虚构生产资质，发布前必须录入真实主体与商品事实 |
| 后台 | RBAC、商品/订单/用户/客服/运营管理、COS 素材 | 部分大文件和 Art Design Pro 模板残留待分阶段清理 |

数据库使用 Flyway 只向前迁移。请以
`backend/shop-server/src/main/resources/db/migration` 和已部署应用的
`/actuator/info.flywayVersion` 为准，不从旧设计文档推测当前版本。

## 工程门禁

后端测试明确分为两层：

```bash
cd backend/shop-server

# 默认层：无 Docker 的单元/H2 测试，刻意排除 Testcontainers
./mvnw test

# 集成层：必须有 Docker，使用 MySQL 8.4.10 和 Redis 7.4.9-alpine
./mvnw -Pintegration verify
./scripts/assert-integration-test-results.sh target/failsafe-reports
./scripts/verify-test-layers.sh
```

`./mvnw test` 绿色只代表默认单元/H2 层，不代表 MySQL/Redis 集成层已执行。
GitHub Actions 会分别执行两层，并要求集成测试套件全部出现且零跳过。

2026-08-10 本轮 V95 发布候选已记录的默认单元/H2 层为 1254 项，Docker/Testcontainers
集成层为 56 项，两层均为零失败、零错误、零跳过，分别用时 4 分钟和 3 分 42 秒。
V95 聚焦测试 49 项通过；Failsafe 11 个必需套件共 56 项、零跳过，测试分层门禁确认
10 类 MySQL 8.4.10 与 1 类 Redis 7.4.9-alpine Testcontainers 套件全部执行。Flyway
V1-V95 共 95 个版本连续且无重复；Admin `pnpm check` 167 项和生产构建、
`git diff --check` 均通过。任何自动化结果都不代替真实微信商户回调、
交易账单、2001 服务动态或生产 MySQL 实际数据升级验证。

其他检查：

```bash
cd admin && pnpm check && CI=true pnpm build
cd ../miniprogram && pnpm check
```

CI 还会校验 Flyway 版本连续性，并在 Pull Request 中拒绝新引入的高危依赖漏洞。
当前本地仓库尚未配置 Git remote，因此 `.github/workflows/ci.yml` 只是已落地的门禁配置；
必须先建立 GitHub 仓库、推送并启用 dependency graph，才会真正执行。依赖审查拒绝
新增高危漏洞，不等于已对历史依赖做过一次性清零。

## 版本识别

生产镜像构建会固化 Git SHA 和 UTC 构建时间。`GET /actuator/info` 仅公开：

- `gitSha`
- `buildTime`
- `version`
- `flywayVersion`

部署脚本会在健康检查后核对这些版本信息，不公开分支、路径、环境变量或密钥。

## 目录

```text
Shop/
  backend/shop-server/  Spring Boot 后端、Flyway、Docker 部署脚本
  admin/                Vue 3 管理后台
  miniprogram/          原生微信小程序
  docs/                 架构、运维、发布和历史设计文档
```

## 文档入口

- [当前工程基线](docs/foundation-completion.md)
- [开发环境与命令](docs/dev-setup.md)
- [本地 Smoke 检查](docs/smoke-checks.md)
- [生产发布检查清单](docs/production-release-checklist.md)
- [Docker 生产部署](docs/docker-deployment.md)
- [后端架构与演进规则](docs/backend-architecture.md)

`docs/superpowers/specs` 和 `docs/superpowers/plans` 保留历史设计与实施证据，不代表当前待办。
