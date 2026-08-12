# 当前工程基线

> 本文替代早期的“下一阶段做认证和 RBAC”交接。认证、会话隔离、RBAC 和
> 后端驱动菜单已是当前基线，不再是待实施计划。

## 系统定位

当前是可运行的单商家、单仓为主、微信小程序渠道自营电商 V1。已具备：

- SPU/SKU、购物车、优惠券、库存锁定、下单和超时关单。
- 微信支付、退款、回调、异常恢复和按售后商品数量幂等库存回补。
- V93 微信交易账单（`ALL`）下载、完整性校验、与本地成功支付/退款的日对账、
  差异处理和受控 CSV 导出。
- V97 财务对账 Admin 运行控制：数据库覆盖、处理器/每日任务分阶段启用、CAS、追加审计、
  支付与私有 COS readiness，以及不依赖重启的即时停用。
- 后台发货、微信发货可靠投递、物流轨迹和电子面单。
- V94 微信新版 `notify_type=2001` “购物（实体物流）服务动态”：交易状态意图可靠
  入库、顺序外呼、未知结果主动对账和 SAFE+JSON 失败回调审计。
- V95 Admin “微信服务动态”运维页：数据库运行时 Capture/Worker 开关、两阶段开启、
  CAS 防覆盖、append-only 变更审计、修复候选与投递队列只读诊断；Callback 密钥仍只在
  服务器环境中维护，不返回前端。
- 售后 V2：商品/数量级申请与审批、服务端退款金额计算、部分/累计退款、仅退款、
  退货退款、商家退货地址、用户退货物流、商家验收/拒收和退货超时关闭。
- 历史整单 V1 售后数据及在途退款回调兼容。
- 评价、收藏、足迹、客服、用户管理、账号注销和个人信息权利。
- 商家资质、法律文档、食品披露、COS 存储、运营统计和数据清理。
- 管理员认证、会话管理、登录保护、RBAC 和后端驱动菜单。

## 验证矩阵

| 层级 | 命令 | 证明范围 |
| --- | --- | --- |
| 后端单元/H2 | `cd backend/shop-server && ./mvnw test` | 无 Docker 的快速默认层，刻意排除 `integration` 标签 |
| 后端集成 | `./mvnw -Pintegration verify` | Testcontainers 中的 MySQL 8.4.10、Redis 7.4.9-alpine、并发锁与迁移 |
| 集成报告 | `./scripts/assert-integration-test-results.sh target/failsafe-reports` | 当前必需套件全部出现，执行数非零，跳过数为零 |
| 测试分层 | `./scripts/verify-test-layers.sh` | Testcontainers 必须带 `integration` 标签，不允许无 Docker 静默跳过，镜像版本固定 |
| Flyway 静态 | `./scripts/verify-flyway-migrations.sh` | 文件命名合规、版本从 V1 连续且无重复 |
| 管理后台 | `cd admin && pnpm check && CI=true pnpm build` | 类型、lint、测试和生产构建 |
| 小程序 | `cd miniprogram && pnpm check` | 运行时与测试类型检查、行为测试 |

`./mvnw test` 绿色只能声明单元/H2 层通过，不得书写为“后端全部测试通过”。
发布候选版必须同时保存两层结果。GitHub Actions 已经把两层分开为独立作业。

2026-08-10 本轮 V95 发布候选已记录的默认单元/H2 层为 1254 项，
Docker/Testcontainers 集成层为 56 项，两层均为 0 failures / 0 errors /
0 skipped，分别用时 4 分钟和 3 分 42 秒。V95 聚焦测试 49 项通过；
Failsafe 11 个必需套件共 56 项且零跳过，分层门禁确认 10 类 MySQL 8.4.10 和
1 类 Redis 7.4.9-alpine Testcontainers 套件都实际执行。Flyway V1-V95 共
95 个版本连续且无重复；同期 Admin `pnpm check` 167 项、生产构建和
`git diff --check` 均通过。

## 发布识别

镜像构建固化 Git SHA 和 UTC 构建时间。生产 `/actuator/info` 的公开白名单只有：

```text
gitSha
buildTime
version
flywayVersion
```

部署脚本在健康后核对 Git SHA、构建时间、应用版本和已执行 Flyway 版本。该端点不得
加入路径、分支、环境变量、连接串、商户号或密钥摘要。

## 仍未由自动化证明的事项

- 真实微信登录、支付、售后 V2 部分/累计退款及退款回调、发货信息、物流插件和电子面单打印。
- V92-V95 在生产 MySQL 实际存量数据上的升级、锁竞争与回滚/恢复演练；Testcontainers
  的 MySQL 8.4.10 通过不能替代生产环境发布验证。
- `api.muybaby6.icu` / `admin.muybaby6.icu` 的 DNS 与 SAN TLS 基线已建立，小程序
  `request`、`uploadFile`、`downloadFile` 合法域名已在微信公众平台配置；每次发布仍需
  用正式配置包在真机发起实际请求，并核对真实生产回调和完整链路。
- 真实商家资质、法律文本、食品标签事实和商品逐一审核。
- V93 真实商户交易账单的下载、摘要校验、差异处置、导出权限和每日调度尚未由生产
  证据证明。V93 只核对微信交易账单 `ALL` 与本地支付/退款，不下载微信资金账单，
  也不证明结算或银行到账。
- V93 首版单商户单日账单默认最多 50,000 行；明细与新差异采用分块 SQL 写入，但整批
  证据发布仍是一个数据库事务，不应被描述为已完成 staging/分段提交架构。
- V94/V95 代码实现和 Admin 开关不等于已完成生产送达验收：生产账号已完成
  `/wechat/mini/message` SAFE+JSON 配置和 GET 握手，但支付后 24 小时内的真实激活、
  30 天更新窗口、失败事件 POST 回调和微信客户端展示仍需外部验收。
  它不是传统 `wx.requestSubscribeMessage` 订阅消息；支付成功、客服回复、低库存等其他通知
  仍未实现真实模板授权、用户同意和送达验收。
- 发票、采购入库、盘点、多包裹和多仓仍属后续能力。
- 线上告警、备份失败告警和恢复演练属于外部运营证据，不能由仓库测试代替。

完整发布前置条件见 [production-release-checklist.md](production-release-checklist.md)。
日常开发命令见 [dev-setup.md](dev-setup.md)，可执行本地检查见
[smoke-checks.md](smoke-checks.md)。早期 `docs/superpowers` 设计/计划文档仅作为历史证据。
