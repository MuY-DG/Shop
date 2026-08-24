# Shop 运维速查

## 环境对应关系

| 目标 | Spring Profile | 运行时清单 | 用途 |
| --- | --- | --- | --- |
| 本机 | `local` | `config/runtime/local.env` | 日常开发与快速调试 |
| txcloud | `server` | 本机 `config/runtime/txcloud.env`，部署后为服务器 `config/runtime/runtime.env` | 开发/集成验证 |
| shop | `server` | 本机 `config/runtime/shop.env`，部署后为服务器 `config/runtime/runtime.env` | 正式生产 |
| 自动化测试 | `test` | `src/test/resources/application-test.yaml` | H2 与 Testcontainers |

三个真实清单均被 Git 忽略，不得互相复制。唯一模板是 `config/runtime/runtime.env.example`。

## 初始化与校验清单

```bash
cd backend/shop-server

./scripts/config/init-runtime-env.sh local
./scripts/config/init-runtime-env.sh txcloud
./scripts/config/init-runtime-env.sh shop

./scripts/config/validate-runtime-env.sh local
./scripts/config/validate-runtime-env.sh txcloud
./scripts/config/validate-runtime-env.sh shop
```

初始化脚本拒绝覆盖已有文件。Compose 固定 `edge` 网关，清单不再需要代理地址占位符。

运行时清单只保存数据库、Redis 和应用主密钥。微信、支付、COS、服务动态、发货和财务对账都在 Admin 配置。

## 本机启动后端

先确保本机 MySQL/Redis 的密码与 `local.env` 一致，再执行：

```bash
cd backend/shop-server
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

常用地址：

| 地址 | 用途 |
| --- | --- |
| `http://127.0.0.1:8080/actuator/health` | 健康检查 |
| `http://127.0.0.1:8080/swagger-ui/index.html` | 仅 `local` 开启的接口文档 |

## 测试门禁

```bash
cd backend/shop-server

./scripts/ci/verify-flyway-migrations.sh
./scripts/ci/verify-test-layers.sh

# 无 Docker 单元/H2 层
./mvnw test

# Docker/Testcontainers MySQL/Redis 层
./mvnw -Pintegration verify
./scripts/ci/assert-integration-test-results.sh target/failsafe-reports
```

管理后台：

```bash
cd admin
pnpm check
CI=true pnpm build
pnpm check:generated-imports
```

小程序：

```bash
cd miniprogram
pnpm check
```

## 部署后端

必须显式写目标：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh txcloud
backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

默认在服务器构建镜像。可选本地镜像传输：

```bash
SHOP_DEPLOY_TRANSPORT=image-stream \
  backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

脚本要求工作区干净，默认会执行后端两层测试，并在测试后、构建前后再次检查；目标架构只允许 `linux/amd64` 或 `linux/arm64`。远端使用唯一候选文件和非阻塞发布锁，常规部署不允许 5 项 runtime secret 发生变化。它只部署后端，不部署 Admin，也不删除数据卷。

## 服务器状态

```bash
ssh shop
cd /opt/shop/shop-server

sudo docker compose \
  --env-file config/runtime/runtime.env \
  -f compose.prod.yaml ps

sudo docker compose \
  --env-file config/runtime/runtime.env \
  -f compose.prod.yaml logs --tail=200 shop-server

curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/info
```

不要把 `docker compose config` 的完整结果粘贴到工单或聊天中；它可能展开秘密。

## 首次 Super 引导

仅限全新空库、后端健康后执行：

```bash
backend/shop-server/scripts/config/bootstrap-admin.sh local
backend/shop-server/scripts/config/bootstrap-admin.sh txcloud
backend/shop-server/scripts/config/bootstrap-admin.sh shop
```

临时凭据写入 `config/runtime/bootstrap-admin.<target>.txt`，不会打印到终端。首次登录并修改密码后删除该文件。脚本不是通用密码重置工具。

## MySQL 备份

```bash
ssh shop 'sudo /opt/shop/shop-server/scripts/deploy/backup-mysql.sh'
```

备份目录为 `/opt/shop/shop-server/backups/mysql`，每个 `.sql.gz` 都有同名 `.sha256` sidecar，默认成对保留 14 天。手工备份与发布共用目标级锁，发布期间会被拒绝。本机备份仍需复制到受控异机或对象存储并进行恢复演练；sidecar 校验成功不等于实际恢复成功。

## SSH 隧道

服务器的 MySQL、Redis 和后端端口都只绑定回环地址。需要本机诊断时建立临时隧道：

```bash
ssh -N \
  -L 13306:127.0.0.1:3306 \
  -L 16379:127.0.0.1:6379 \
  -L 18080:127.0.0.1:8080 \
  shop
```

用完按 `Ctrl+C` 关闭。数据库工具连接 `127.0.0.1:13306`，Redis 工具连接 `127.0.0.1:16379`。从目标清单读取凭据时不要回显、截图或提交文件。

## 配置文件职责

| 文件 | 是否提交 | 职责 |
| --- | --- | --- |
| `src/main/resources/application.yaml` | 是 | 全环境安全默认值和非秘密技术参数 |
| `src/main/resources/application-local.yaml` | 是 | 本机地址、DEBUG、Swagger |
| `src/main/resources/application-server.yaml` | 是 | Compose 服务地址、可信代理和服务器日志策略 |
| `src/test/resources/application-test.yaml` | 是 | 自动化测试覆盖 |
| `config/runtime/runtime.env.example` | 是 | 唯一运行时清单结构 |
| `config/runtime/local.env` | 否 | 本机秘密 |
| `config/runtime/txcloud.env` | 否 | txcloud 秘密 |
| `config/runtime/shop.env` | 否 | shop 秘密 |

1Panel 和 Docker 的安装、升级、账户与密码安全由服务器管理员维护，仓库脚本不接管这些职责。

## generation 2 断代

当前数据库基线是 V1-V7，只接受空库。旧 schema 不做升级兼容；要切换本机、txcloud 或 shop，必须在明确确认目标数据可丢弃并完成备份后，人工重建对应 MySQL/Redis 数据卷。普通部署命令不会代替这项破坏性确认。
