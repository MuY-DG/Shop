# Shop 本机开发

本机使用 `local` Profile；两台服务器使用 `server` Profile。三个环境的数据库、
Redis 和运行密钥彼此独立。

## 依赖

- JDK 21
- Docker（运行集成测试时必需）
- MySQL 8.4
- Redis 7.4
- Node.js 20.19 或更高
- pnpm
- OpenSSL 与 `htpasswd`

## 后端

首次生成本机运行密钥：

```bash
cd backend/shop-server
./scripts/config/init-runtime-env.sh local
```

文件位于 `config/runtime/local.env`，已被 Git 忽略。脚本不会覆盖已有文件。
如果本机已有 MySQL/Redis，把文件中的三个连接密码改成真实值，然后校验：

```bash
./scripts/config/validate-runtime-env.sh local
```

创建空数据库 `hotpot_shop` 后启动：

```bash
./mvnw -Dspring-boot.run.profiles=local spring-boot:run
```

Flyway 会从 `src/main/resources/db/migration` 自动建立当前结构。健康与版本：

```text
http://127.0.0.1:8080/actuator/health
http://127.0.0.1:8080/actuator/info
http://127.0.0.1:8080/swagger-ui/index.html
```

全新空库首次启用 Super：

```bash
./scripts/config/bootstrap-admin.sh local
```

临时凭据写入 `config/runtime/bootstrap-admin.local.txt`。首次登录并修改密码后删除。

## Admin

```bash
pnpm --dir admin install --frozen-lockfile
pnpm --dir admin dev
```

开发服务器默认运行在 `http://127.0.0.1:3006`，并把 `/api`、`/admin` 和
`/realtime` 代理到 `http://127.0.0.1:8080`。

生产构建检查：

```bash
pnpm --dir admin check
CI=true pnpm --dir admin build
pnpm --dir admin check:generated-imports
```

自动导入声明是版本化构建输入；生产构建后如有变化，应先检查并提交。

## 小程序

```bash
pnpm --dir miniprogram install --frozen-lockfile
pnpm --dir miniprogram check
```

然后用微信开发者工具导入 `miniprogram` 目录。环境选择规则见
[小程序 README](../miniprogram/README.md)。

## 后端测试

```bash
cd backend/shop-server

# 无 Docker 的单元/H2 层
./mvnw test

# MySQL/Redis Testcontainers 层
./mvnw -Pintegration verify
./scripts/ci/assert-integration-test-results.sh target/failsafe-reports

# 测试分层和 Flyway 静态约束
./scripts/ci/verify-test-layers.sh
./scripts/ci/verify-flyway-migrations.sh
```

`./mvnw test` 通过不代表集成测试已经执行。

## 配置边界

`local.env` 只保存：

- MySQL 应用密码与 root 密码
- Redis 密码
- 数据库敏感字段的 AES 主密钥 ID 和 key ring

微信、支付、COS、服务动态和业务开关通过 Admin 保存到数据库。真实密码、APIv3 Key、
PEM 和 COS Secret 不写入 Git、YAML、文档或聊天记录。

## 常见问题

- 缺少 `local.env`：运行 `init-runtime-env.sh local`。
- Flyway 校验失败：确认使用的是当前代码对应的本机数据库；不要执行
  `flyway clean` 或 `repair` 掩盖问题。
- 后端正常但微信/支付/COS 不可用：先在 Admin 检查对应环境的业务配置。
- Admin 请求失败：确认 8080 后端已启动，且 `.env.development` 的代理目标正确。
