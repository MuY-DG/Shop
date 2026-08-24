# Shop 生产发布检查清单

**适用范围：** Flyway generation 2（V1-V7）首次切换及后续发布

**最后更新：** 2026-08-24

本清单是发布门禁，不是“示例配置”。只有在目标环境执行并保存证据后才能勾选。自动化测试不能证明真实微信、支付、退款、COS、物流、证照、域名、真机或恢复演练已经通过。

> generation 2 与旧 schema 不兼容。首次切换必须重建空数据库，不能直接升级旧库，也不能通过 Flyway repair 绕过校验。普通代码发布、提交 Git 或执行部署脚本都不等于授权删除数据卷。

## 1. 发布范围与负责人

- [ ] 记录目标：`txcloud` 或 `shop`，不使用含糊的“服务器”。
- [ ] 记录 Git SHA、构建时间、变更摘要和工作区状态。
- [ ] 指定发布负责人、数据库负责人、业务验收人、微信/支付负责人和回滚决策人。
- [ ] 明确维护窗口、停止写入时间、预计恢复时间和通知方式。
- [ ] 明确本次是 generation 2 首次重建，还是 generation 2 上的普通向前发布。
- [ ] 建立证据目录，保存命令退出码、脱敏日志、数据库摘要、接口响应和真机截图；不得保存密码、完整 OpenID、手机号、地址、PEM、APIv3 key 或应用主密钥。
- [ ] 检查微信商户平台是否仍有未结束支付、退款或回调重试。即使本地数据库准备清空，也不能从“本地无数据”推断渠道侧无在途交易。

## 2. 代码与自动化门禁

从仓库根目录执行：

```bash
cd backend/shop-server

./scripts/ci/verify-flyway-migrations.sh
./scripts/ci/verify-test-layers.sh

# 默认单元/H2 层
./mvnw test

# Docker/Testcontainers 集成层
docker info
./mvnw -Pintegration verify
./scripts/ci/assert-integration-test-results.sh target/failsafe-reports

cd ../../admin
pnpm check
CI=true pnpm build
pnpm check:generated-imports

cd ../miniprogram
pnpm check

cd ..
git diff --check
git status --short --branch
```

- [ ] Flyway 静态门禁报告 V1-V7 连续且无重复。
- [ ] 后端默认层零失败、零错误；结果没有被描述成完整集成测试。
- [ ] Testcontainers 层零失败、零错误、零跳过，报告中所有 `integration` 套件实际执行。
- [ ] MySQL 8.4.10 和 Redis 7.4.9-alpine 测试镜像固定。
- [ ] Admin 检查、生产构建和生成元数据一致性通过。
- [ ] 小程序检查通过。
- [ ] 人工审查差异，没有真实运行时清单、临时 Super 凭据、证书、数据库导出、备份、上传文件或构建目录。
- [ ] 发布提交已创建且工作区干净；部署脚本会拒绝脏工作区。

## 3. 配置门禁

唯一 tracked 模板：

```text
backend/shop-server/config/runtime/runtime.env.example
```

目标清单：

```text
backend/shop-server/config/runtime/txcloud.env
backend/shop-server/config/runtime/shop.env
```

校验：

```bash
cd backend/shop-server
./scripts/config/validate-runtime-env.sh txcloud
./scripts/config/validate-runtime-env.sh shop
```

每次只需运行本次目标对应命令，但正式切换前建议两份都检查，避免误用。

- [ ] txcloud 和 shop 使用独立 DB/Redis 密码与独立主密钥。
- [ ] 清单只含 DB/Redis、active key ID 和 key ring 五个白名单键。
- [ ] `SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID` 存在于 key ring，密钥解码为 32 字节。
- [ ] 宿主机未占用 Compose 固定的 `172.22.0.0/24`、`172.23.0.0/24`、`172.24.0.0/24`。
- [ ] server Profile 只信任回环地址和固定 `edge` 网关 `172.23.0.1/32`。
- [ ] 不信任整个 Docker 私网、云内网或 `0.0.0.0/0`。
- [ ] 微信、支付、COS、服务动态、发货和财务对账的凭据/业务开关不在清单或 tracked YAML 中。
- [ ] 业务敏感配置由 Admin 加密入库，缺失时功能明确失败或保持安全关闭。
- [ ] 主密钥不会写入数据库；数据库中的 v2 密文能用目标 key ring 解密。

## 4. generation 2 首次重建

本节只适用于每个目标第一次切换到 V1-V7。操作具有破坏性。

### 4.1 重建前

- [ ] 再次确认目标 SSH 别名、主机 IP、Compose 项目名和数据卷全名。
- [ ] 再次取得“该目标数据可丢弃”的业务确认。
- [ ] 停止旧系统写入并记录停止时间。
- [ ] 导出最后 MySQL 备份，记录文件名、大小、时间和 SHA-256。
- [ ] 在隔离 MySQL 实际恢复该备份；文件存在不等于可恢复。
- [ ] 备份复制到不同故障域。
- [ ] 记录旧容器、镜像、Compose、清单和数据卷，形成完整回滚点。
- [ ] 确认渠道侧没有需要旧数据库处理的支付、退款和回调；如有，先完成处置或推迟重建。

当前服务器备份入口：

```bash
ssh shop 'sudo /opt/shop/shop-server/scripts/deploy/backup-mysql.sh'
```

对 txcloud 操作时替换目标。generation 2 尚未部署前，服务器可能仍保留旧脚本路径；应先只读确认实际路径，不能假设命令已经更新。

### 4.2 删除与初始化

- [ ] 先停止旧后端，避免删除过程中继续写入。
- [ ] 用 `docker volume ls` 获取准确卷名，人工逐个核对 MySQL/Redis 卷。
- [ ] 只删除本次目标明确确认的卷，不使用通配符或未展开变量。
- [ ] 不删除镜像、上传素材或其他 Compose 项目资源，除非另有明确批准。
- [ ] 使用目标清单启动新的 MySQL/Redis/后端。
- [ ] Flyway 从空 schema 执行 V1-V7，日志无失败和 checksum 告警。
- [ ] 第二次启动不重复创建参考数据。
- [ ] `/actuator/info` 的 `flywayVersion` 为 `7`。
- [ ] `Super` 为停用哨兵状态，不能用默认值登录。
- [ ] 三类运行控制行存在且默认关闭。
- [ ] 微信、支付、COS 等配置表没有示例秘密。

删除数据卷的确切命令不写入本文。执行人必须基于现场只读清单构造明确目标，并由第二人复核。

## 5. txcloud 集成验证

shop 首次重建前先在 txcloud 走完整流程：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh txcloud
```

- [ ] 部署脚本校验清单和测试门禁。
- [ ] MySQL、Redis、后端容器 healthy。
- [ ] `/actuator/info` Git SHA、构建时间、应用版本和 Flyway 版本正确。
- [ ] 使用一次性脚本引导 txcloud Super，并在登录后修改密码、删除临时凭据。
- [ ] 在 Admin 从零录入 txcloud 业务配置，不复制 shop 秘密。
- [ ] Admin 与小程序基础流程通过。
- [ ] 使用开发/测试商户和测试资源完成微信、支付、退款、COS、发货与对账验证。
- [ ] 记录所有未验证能力；txcloud 通过不能自动等同 shop 通过。

一次性管理员引导：

```bash
backend/shop-server/scripts/config/bootstrap-admin.sh txcloud
```

## 6. shop 部署

只有 txcloud 结果已复核、shop 的破坏性重建已单独批准时执行：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

- [ ] 发布窗口仍有效，外部写入已按计划阻断。
- [ ] 目标清单是 `shop.env`，没有误用 txcloud 文件。
- [ ] 清单、Compose 和远端调用脚本使用同一个唯一 `deploy_id` 候选文件名；远程脚本切换前保留旧 canonical 文件，没有人工提前覆盖。
- [ ] 发布取得服务器目标级 `flock`；并发发布会被拒绝，不通过删除锁文件“解锁”。
- [ ] 常规部署的 5 项 runtime secret 与服务器当前值完全一致；密码/key ring 轮换另走维护流程。
- [ ] 部署输出中的 Git SHA 与批准版本一致。
- [ ] 已有容器或数据卷时，候选数据服务启动前已用旧 canonical Compose/runtime 完成 MySQL 备份；首次部署在初始化后已生成空库基线备份；`.sql.gz` 与 `.sha256` sidecar 均存在且校验通过，随后才切换后端容器。
- [ ] MySQL、Redis、后端容器均 healthy。
- [ ] `/actuator/health` 和 `/actuator/info` 只通过服务器本机回环访问也成功。
- [ ] 公网 HTTPS 域名指向正确网关，证书有效。
- [ ] 8080/3306/6379 不对公网开放。
- [ ] OpenResty 覆盖代理头，后端取得真实客户端 IP。
- [ ] 未认证接口不泄露运维、配置或用户信息。

首次 shop Super 引导：

```bash
backend/shop-server/scripts/config/bootstrap-admin.sh shop
```

- [ ] 脚本只成功一次并写入系统日志。
- [ ] 临时密码未打印到终端。
- [ ] 凭据先以同目录 `0600` 临时文件写入，数据库 CAS 成功后才原子发布为正式凭据文件。
- [ ] 使用临时凭据登录后立即修改密码。
- [ ] 删除 `config/runtime/bootstrap-admin.shop.txt`。
- [ ] 第二次运行脚本被拒绝且数据库不变。

## 7. 业务配置首录

建议顺序：

1. COS 桶、区域、自定义域名与最小权限凭据；
2. 微信小程序 AppID/Secret；
3. 微信支付商户号、APIv3 key、平台公钥/序列号、商户私钥，以及支付
   `/wxpay/pay/notify`、退款 `/wxpay/refund/notify` HTTPS 回调基址（不含 `/r/token`）；
4. 微信发货、服务动态和相关模板配置；
5. 财务对账运行开关；
6. 商家主体、证照、法律文档、客服电话和首页内容。

- [ ] 每项保存后 API 只返回掩码或 configured 状态。
- [ ] 数据库保存 v2 密文、key ID 和 revision，不保存明文。
- [ ] Admin 权限和 CAS 冲突保护生效。
- [ ] 关键变更写入审计日志。
- [ ] 运行开关先保持关闭，依照“配置完整 → readiness 通过 → 小流量启用”逐项开启。
- [ ] shop 不导入 txcloud 的凭据、OpenID、支付配置、回调地址或测试数据。

## 8. 真实平台验收

### 8.1 微信登录与小程序

- [ ] 正式小程序 AppID、代码包和后端配置一致。
- [ ] `request`、`uploadFile`、`downloadFile` 合法域名与实际请求一致。
- [ ] 真机完成登录、刷新会话、手机号授权和退出。
- [ ] 新 AppID 产生的新 OpenID 按新环境处理，不假设能与旧 AppID 用户自动合并。

### 8.2 支付与退款

- [ ] 用最小金额真实订单完成 JSAPI 支付。
- [ ] AppID、OpenID、商户号、金额和支付配置 ID 匹配。
- [ ] 系统逐笔追加 `/r/{routeToken}` 后，支付和退款最终 HTTPS 回调可达，重复通知幂等。
- [ ] 查单、关单、超时关闭和恢复任务符合预期。
- [ ] 完成一次部分退款和一次全额退款（若业务允许），核对渠道金额、本地退款行、订单汇总和库存回补。
- [ ] 未知回调、验签失败和商户身份不匹配不推进状态。
- [ ] 历史数据库支付配置通过软删除保留；已绑定交易不因切换当前配置而失效。

渠道返回 `REFUND` 不能单独证明全额退款完成。必须核对实际退款金额、本地明细和订单聚合。

### 8.3 COS

- [ ] 真机/Admin 完成图片和视频直传。
- [ ] CORS、HTTPS、自定义域名、数据万象和 CAM 最小权限生效。
- [ ] 私有对象签名访问、公开对象地址和清理任务符合预期。
- [ ] 在用素材不会被误删，失败清理可重试且幂等。

### 8.4 发货与服务动态

- [ ] 单包裹和拆分包裹发货都通过。
- [ ] 微信发货信息、物流轨迹和本地状态一致。
- [ ] 重复投递与未知结果对账不造成重复状态推进。
- [ ] 服务动态的真实激活、更新、失败回调和微信客户端展示通过。
- [ ] 代码中的 mock/provider 测试没有被当作平台验收。

### 8.5 财务对账

- [ ] 用真实商户交易账单完成下载、摘要校验和差异匹配。
- [ ] 支付/退款金额、业务号和商户号匹配。
- [ ] 重复任务幂等，失败可接管重试。
- [ ] 人工处置差异只更新对账状态和审计，不暗改订单或退款业务状态。
- [ ] 明确交易账单对账不等于资金账单或银行到账核对。

## 9. 商家主体与法律内容

- [ ] 法定主体名称、统一社会信用代码、经营地址真实且已审核。
- [ ] 客服/投诉联系方式可用。
- [ ] 营业执照及适用食品经营许可/备案真实、有效、主体一致。
- [ ] 隐私政策、用户协议、售后政策和注销须知无占位文本。
- [ ] 文档版本、生效时间和历史记录可追溯。
- [ ] 商品食品标签、配料、过敏原、净含量、保质期和储存方式来自真实资料。

仓库测试不能替代商家和合规负责人的事实审核。

## 10. 安全与权限

- [ ] 使用 Super、普通管理员和无权限账号分别验证菜单与接口权限。
- [ ] 前端隐藏按钮之外，后端返回正确的 401/403。
- [ ] 登录失败限制、会话上限、踢出和密码修改生效。
- [ ] 日志不包含密码、token、手机号、地址、支付/COS密钥、PEM 或应用主密钥。
- [ ] Actuator 只暴露允许的 health/info 字段。
- [ ] MySQL/Redis 仅绑定回环或内部网络，Redis requirepass 生效。
- [ ] 数据库备份目录权限受控，证据目录不含秘密。
- [ ] 1Panel/Docker 的安装、升级、账户、密码和面板暴露策略由服务器管理员单独审查；仓库不接管。

## 11. 观察窗口

- [ ] 观察容器重启、CPU、内存、磁盘和日志增长。
- [ ] 观察 MySQL 连接数、慢查询、锁等待和 Flyway 版本。
- [ ] 观察 Redis 内存、AOF、认证失败和会话异常。
- [ ] 观察 401/403/429/5xx、未知回调和验签失败。
- [ ] 观察支付、退款、发货、服务动态、对账和 COS 清理失败队列。
- [ ] 执行一次备份并记录摘要；安排异机复制与恢复演练。
- [ ] 业务负责人确认真实订单、库存、支付、退款、发货和售后状态。

## 12. 回滚

### 普通 generation 2 发布

- [ ] 部署前保留当前镜像标签和数据库备份。
- [ ] 明确本次新增迁移是否向后兼容；不能仅因旧容器能启动就判定可回滚。
- [ ] 新容器失败时，远程脚本尝试恢复部署前镜像。
- [ ] Compose/备份/健康/版本校验失败时，远程脚本同时恢复部署前 `runtime.env`；确认服务器没有残留待切换的 `.next` 文件。
- [ ] 数据库已经向前变化时，优先向前修复；需要恢复备份必须先阻断写入并评估备份时点后的交易。

### 首次 generation 2 重建

- [ ] generation 2 新镜像不能连接旧 schema，旧镜像也不能连接新基线。
- [ ] 恢复旧系统必须同时恢复匹配的旧镜像、旧 Compose/配置和旧数据库备份。
- [ ] 备份时点后的支付、退款和回调可能丢失，恢复流量前必须与微信渠道重新对账。
- [ ] 不编写临时反向 SQL 删除新表/列，不使用 Flyway clean/repair 伪装回滚。

## 13. 发布结论

- [ ] 记录已验证项、未验证项、写入的数据、异常和负责人。
- [ ] 记录最终 Git SHA、镜像、Flyway 版本、清单摘要（不含值）和备份摘要。
- [ ] 结论为“通过 / 有条件通过 / 不通过”，有条件通过必须写清限制与截止时间。
- [ ] 只有回滚点、观察窗口和真实平台证据均满足后才恢复全部流量。

详细部署步骤见 [docker-deployment.md](docker-deployment.md)，执行性验收见 [smoke-checks.md](smoke-checks.md)。
