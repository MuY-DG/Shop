# Shop 开发环境

本文是当前开发入口。过往迁移过程与阶段计划保留在 `docs/superpowers/`，不应作为今天的启动或部署手册。

## 1. 环境模型

项目只有三个 Spring Profile：

| Profile | 使用位置 | 数据服务 |
| --- | --- | --- |
| `local` | 本机开发 | `127.0.0.1` 上的 MySQL/Redis |
| `server` | txcloud 与 shop | Compose 服务 `mysql`、`redis` |
| `test` | 自动化测试 | H2 或 Testcontainers |

txcloud 和 shop 不再各维护一套应用 YAML。它们共用 `application-server.yaml`，仅通过各自运行时清单提供不可提交的启动秘密。

## 2. 前置依赖

- JDK 21；
- Node.js `>=20.19.0`；
- pnpm；
- MySQL 8.4；
- Redis 7.4；
- Docker（运行 Testcontainers 和本地容器时需要）；
- 微信开发者工具（小程序真机/模拟器验证时需要）。

后端使用 Maven Wrapper，不要求全局安装 Maven。

## 3. 运行时清单

唯一受版本控制的模板：

```text
backend/shop-server/config/runtime/runtime.env.example
```

本机初始化：

```bash
cd backend/shop-server
./scripts/config/init-runtime-env.sh local
./scripts/config/validate-runtime-env.sh local
```

生成的 `config/runtime/local.env` 权限为 `0600` 且被 Git 忽略。脚本拒绝覆盖已有文件。

清单只允许：

- MySQL 业务账号密码；
- MySQL root 密码；
- Redis 密码；
- v2 AES-256 主密钥 ID 与 key ring。

微信小程序、微信支付、COS、服务动态、发货和财务对账属于业务配置：在 Admin 中保存、加密入库、按权限和审计流程修改。它们不属于应用启动条件，也不应出现在运行时清单或 tracked YAML。

### 为什么 `application.yaml` 仍有很多值

YAML 中保留的是代码行为的安全默认值和技术参数，例如超时、批量大小、缓存 TTL、重试节奏、上传上限和登录保护阈值。它们适合随代码评审、测试和发布，不适合全部做成可即时修改的后台开关。

判断标准：

- 需要产品/运营随时调整、且修改应留下权限与审计记录的业务配置：放数据库和 Admin；
- 决定进程能否安全连接基础设施或解密数据库密文的秘密：放运行时清单；
- 与算法、资源上限、超时、重试和安全边界绑定的技术参数：保留在 YAML/代码，随版本发布。

不要把所有 YAML 参数搬到数据库，否则应用会出现“读取配置本身还需要先连接数据库和解密”的循环依赖，也会失去代码评审、类型校验和可重复部署。

## 4. 本机 MySQL 与 Redis

本机服务的账号和密码必须与 `local.env` 一致。`init-runtime-env.sh local` 会生成强随机默认密码；如果本机已经有 MySQL/Redis，可以把 `local.env` 中的三个密码改成现有服务实际使用的非空值。本机校验不要求它们保持 64 位小写十六进制格式，但 txcloud/shop 仍执行严格格式校验。

当前数据库基线只支持全新空库；如果本机仍是旧 schema，先确认数据可丢弃，再用明确的实例/数据库名重建 `hotpot_shop`。不要把旧表保留在同一 schema 中尝试继续迁移。

Redis 也应在切换时清空，避免旧会话、登录失败计数和缓存污染新基线。重建动作是破坏性的，执行前先核对目标是本机而不是 txcloud/shop。

## 5. Flyway generation 2

`src/main/resources/db/migration` 只有七个按业务域拆分的迁移：

```text
V1__identity_and_access.sql
V2__catalog_content_and_storage.sql
V3__commerce_and_orders.sql
V4__payments_refunds_and_after_sales.sql
V5__fulfillment_and_wechat.sql
V6__operations_finance_service_and_compliance.sql
V7__reference_and_bootstrap_data.sql
```

它们表示当前最终模型，不是旧数据库的升级补丁。首次启动时：

- Flyway 从空库创建最终 schema；
- 写入 RBAC、菜单、字典与必要的安全默认行；
- 创建停用且使用哨兵密码哈希的 `Super`；
- 以关闭状态创建必要的运行时控制行；
- 不写入微信、支付、COS 等真实业务凭据。

迁移文件已发布后仍应遵守 Flyway 不可改写原则。generation 2 的断代只做这一次；后续 schema 变化从 V8 继续追加。

静态校验：

```bash
cd backend/shop-server
./scripts/ci/verify-flyway-migrations.sh
```

## 6. 启动后端

```bash
cd backend/shop-server
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

健康与接口文档：

```text
http://127.0.0.1:8080/actuator/health
http://127.0.0.1:8080/swagger-ui/index.html
```

Swagger 只在 `local` 开启。`server` 使用收紧后的日志和运维端点设置。

## 7. 本机 Super 引导

本机空库启动成功后执行：

```bash
cd backend/shop-server
./scripts/config/bootstrap-admin.sh local
```

脚本只在 `id=1` 的 `Super` 仍处于基线哨兵状态时写入新的 BCrypt 哈希、启用账号并记录
系统日志。临时密码仅保存到 ignored、`0600` 的
`config/runtime/bootstrap-admin.local.txt`，不会打印；首次登录并修改密码后删除该文件。
普通密码找回应走后台账户安全流程，不能反复套用首次引导。

## 8. 管理后台

```bash
cd admin
pnpm install
pnpm dev
```

`.env.development` 只保留一个 `VITE_API_PROXY_URL`，默认把 `/api`、`/admin` 和
`/realtime` 全部代理到 `http://127.0.0.1:8080`；本机开发不再混用 Apifox 与
txcloud 后端。

完整校验：

```bash
pnpm check
CI=true pnpm build
pnpm check:generated-imports
```

业务凭据录入后的响应只应返回脱敏状态或“是否已配置”，不能回传明文。修改当前支付配置前要考虑尚未完成的支付、退款和渠道回调；历史数据库配置通过软删除保留，供已绑定业务记录继续解析。

## 9. 微信小程序

```bash
cd miniprogram
pnpm install
pnpm check
```

随后在微信开发者工具中导入 `miniprogram/`。真实登录、手机号授权、支付、物流、服务动态和订阅能力依赖微信平台状态，自动化检查不能替代真机和真实测试订单验收。

## 10. 后端测试分层

快速默认层：

```bash
cd backend/shop-server
./mvnw test
```

该命令运行单元/H2 测试，明确不包含标记为 `integration` 的 Testcontainers 测试。

完整集成层：

```bash
./scripts/ci/verify-test-layers.sh
./mvnw -Pintegration verify
./scripts/ci/assert-integration-test-results.sh target/failsafe-reports
```

集成层要求 Docker 可用，MySQL/Redis 镜像版本固定，所有带 `integration` 标签的套件都生成报告，且执行数非零、跳过数为零。Docker 不可用是此层失败，不是可接受的跳过。

## 11. 敏感配置规则

### 应用主密钥

数据库敏感字段只使用 `v2:<keyId>:<nonce>:<ciphertext+tag>` 信封格式。`SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID` 必须存在于 `SHOP_SECRET_ENCRYPTION_KEY_RING`，每个 key 解码后必须是 32 字节。

轮换时：

1. 先在 key ring 中加入新 key，同时保留旧 key；
2. 把 active key ID 切到新 key；
3. 部署并验证新写入使用新 key；
4. 完成数据库密文轮换并核对无旧 key 引用；
5. 最后才移除旧 key。

不同环境必须使用不同的数据库/Redis密码和主密钥。复制环境时只复制结构，不复制秘密。

### 支付配置历史

新支付必须绑定数据库中的有效配置 ID 和不可逆指纹。已被业务记录引用的商户身份与密钥材料不应原地覆盖；新建一个配置版本并启用它。停用配置可以软删除，但历史行仍需保留以处理已创建支付、退款、查单、关闭、回调和对账。

Admin 中填写支付 `/wxpay/pay/notify` 和退款 `/wxpay/refund/notify` 的公网 HTTPS 基址，
不手工添加固定 token。系统为每笔支付或退款追加 `/r/{routeToken}`，用不可猜测的 token
精确绑定业务记录和历史数据库配置。部署前需确认最终路由地址可达、长度满足限制，并对未知路由和异常频率做监控。

### 微信与 COS

微信平台、服务动态、发货控制和 COS 凭据均从数据库读取。缺少配置时应明确失败或按安全默认值关闭，不能静默从进程变量兜底。录入后按 [smoke-checks.md](smoke-checks.md) 做真实平台验收。

## 12. 日常开发流程

1. `git status --short`，确认工作区范围。
2. 启动本机 MySQL/Redis。
3. 校验 `local.env`，用 `local` Profile 启动后端。
4. 启动 Admin 与小程序开发工具。
5. 修改代码并运行最接近改动的测试。
6. 运行后端默认层、Admin `pnpm check`、小程序 `pnpm check`。
7. 涉及 schema、事务、锁、MySQL 方言或 Redis 时，再运行完整集成层。
8. 执行 `git diff --check`，审查是否误提交秘密或生成物。
9. 提交后先部署到 txcloud 做集成验证，再按发布清单切换 shop。

## 13. 常见问题

### 启动时报缺少 `config/runtime/local.env`

运行 `./scripts/config/init-runtime-env.sh local`。不要创建第二套本机配置文件，也不要把服务器清单改名复用。

### 数据库提示 Flyway 校验或版本冲突

generation 2 不兼容旧 schema。确认连接目标后重建空库；不要对旧表执行 repair 来伪装兼容。

### 应用能启动但微信/支付/COS 不可用

先检查 Admin 中相应配置和安全运行开关，再检查外部平台权限、域名、证书与回调。不要向运行时清单添加业务凭据绕过缺失配置。

### txcloud 和 shop 配置是否应完全相同

结构相同，值不同。两者都使用 `server` Profile，但必须拥有独立数据库密码、Redis 密码、主密钥和 Admin 业务配置；可信代理边界由固定 Compose IPAM 与 `application-server.yaml` 共同管理。
