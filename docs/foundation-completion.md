# 当前工程基线

本文描述代码仓库当前具备的能力和证据边界。早期按迁移版本编写的阶段报告保留在 `docs/superpowers/`，不再作为部署说明。

## 系统定位

Shop 是单商家、单仓为主的微信小程序自营电商，包含 Spring Boot 后端、Vue Admin 和原生微信小程序。

当前代码覆盖：

- 管理员认证、Redis 会话、登录保护、RBAC、后端驱动菜单和系统日志；
- SPU/SKU、分类、素材、库存、购物车、优惠券、首页装修和内容管理；
- 下单、超时关闭、微信支付、回调、退款恢复和售后；
- 部分退款、商品数量级售后、库存回补和人工异常处置；
- 拆分包裹、微信发货、物流轨迹、电子面单和收货对账；
- 微信服务动态、客服、用户状态与账号权利请求；
- COS 直传与清理、商家资质、法律文档和食品披露；
- 运营统计、交易账单对账、差异处置和受控导出。

“代码覆盖”不等于正式平台已经配置或验收。真实微信、支付、COS、物流、证照和恢复演练仍要逐环境取得外部证据。

## 数据库 generation 2

当前 Flyway 基线是按领域拆分的 V1-V7，只接受全新空库：

| 版本 | 领域 |
| --- | --- |
| V1 | 身份、认证与权限 |
| V2 | 商品、内容与存储 |
| V3 | 交易、购物车与订单 |
| V4 | 支付、退款与售后 |
| V5 | 履约、物流与微信能力 |
| V6 | 运营、财务、客服与合规 |
| V7 | RBAC、字典和安全引导数据 |

该基线与旧 schema 不兼容，不提供存量升级路径。本机、txcloud 和 shop 都已被业务方确认数据可清理，但真正删除各目标数据库/数据卷仍应在明确目标、完成备份和发布门禁后分别执行。

首次启动只写结构、参考数据、安全关闭的运行时控制行和停用的 `Super` 哨兵账号；不会写入真实微信、支付或 COS 凭据。

## 配置基线

- Spring Profile 只有 `local`、`server`、`test`；
- txcloud/shop 共用 `server` Profile；
- 唯一 tracked 模板是 `config/runtime/runtime.env.example`；
- ignored 目标清单是 `local.env`、`txcloud.env`、`shop.env`；
- 清单只承载 DB/Redis 和应用主密钥；
- 可信代理由固定 Compose edge IPAM 与 server Profile 精确 `/32` 管理；
- 业务凭据与业务运行开关由 Admin 写入加密数据库；
- 数据库敏感字段只接受 v2 key-ring 加密格式；
- 缺少业务配置时明确失败或保持安全关闭，不从进程变量兜底。

## 验证矩阵

| 层级 | 命令 | 证明范围 |
| --- | --- | --- |
| Flyway 静态 | `cd backend/shop-server && ./scripts/ci/verify-flyway-migrations.sh` | V1-V7 连续、无重复、命名合规 |
| 测试分层 | `./scripts/ci/verify-test-layers.sh` | Testcontainers 标签、禁止静默跳过、固定镜像版本 |
| 后端单元/H2 | `./mvnw test` | 无 Docker 的快速默认层 |
| 后端集成 | `./mvnw -Pintegration verify` | MySQL/Redis、迁移、事务、锁和并发路径 |
| 集成报告 | `./scripts/ci/assert-integration-test-results.sh target/failsafe-reports` | 所有 integration 套件实际执行且零跳过 |
| Admin | `cd admin && pnpm check && CI=true pnpm build && pnpm check:generated-imports` | 类型、lint、测试、生产构建和生成元数据 |
| 小程序 | `cd miniprogram && pnpm check` | 运行时/测试类型与行为测试 |

`./mvnw test` 绿色只能声明默认层通过，不能写成“后端全部测试通过”。发布候选必须同时保存集成层报告。

## 发布识别

镜像构建固化 Git SHA 和 UTC 构建时间。`/actuator/info` 只公开：

```text
gitSha
buildTime
version
flywayVersion
```

服务器部署脚本会核对这些字段。不得向该端点加入路径、分支、清单内容、连接串、商户号或密钥摘要。

## 自动化未证明的事项

- 真实微信登录、手机号授权、支付、部分/累计退款及回调；
- 微信发货、服务动态、物流插件、电子面单和客户端展示；
- COS 桶权限、CORS、自定义域名、数据万象和对象生命周期；
- 正式域名、TLS、微信合法域名和真机行为；
- 商家证照、法律文本、食品标签与商品事实；
- 真实商户交易账单、差异处置和资金/银行到账；
- 线上告警、异机备份、恢复演练和故障切换；
- 高并发和长时间运行下的容量边界。

完整发布门禁见 [production-release-checklist.md](production-release-checklist.md)，开发入口见 [dev-setup.md](dev-setup.md)，验收步骤见 [smoke-checks.md](smoke-checks.md)。
