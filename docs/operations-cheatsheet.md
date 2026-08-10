# Shop 操作命令速查

以下命令默认从项目根目录执行：

```bash
cd /Users/muybaby/Project/Production/Shop
```

## 日常开发

IDEA 直接运行 `ShopServerApplication`，有效配置文件填写 `dev`。

终端启动后端：

```bash
cd backend/shop-server
./mvnw -Dspring-boot.run.profiles=dev spring-boot:run
```

停止前台进程使用 `Ctrl+C`。

## 测试

只运行相关测试，适合日常开发：

```bash
cd backend/shop-server
./mvnw -Dtest=具体测试类名 test
```

正式发布前分别运行无 Docker 层和 Testcontainers 层：

```bash
cd backend/shop-server
# 仅单元/H2，明确排除 integration 标签
./mvnw test

# 必须有 Docker，执行 MySQL/Redis 集成测试
./mvnw -Pintegration verify
./scripts/assert-integration-test-results.sh target/failsafe-reports
./scripts/verify-test-layers.sh
```

测试源码位于 `backend/shop-server/src/test/java`。默认报告在 `target/surefire-reports`，
集成报告在 `target/failsafe-reports`。不得用第一条命令的结果代替完整后端门禁。

## Git

先确认改动范围：

```bash
git status --short
git diff
```

只暂存准备提交的文件：

```bash
git add <文件或目录>
git commit -m "类型(范围): 简要说明"
```

生产环境变量、1Panel 凭据、证书、上传文件和备份已由 `.gitignore` 排除，不得使用
强制添加参数将它们提交到 Git。

## 生产部署

推荐方式：完整测试、上传精简源码、服务器缓存构建并自动切换容器。

```bash
backend/shop-server/scripts/deploy-prod.sh txcloud
```

已经对完全相同的代码运行过完整测试时，可在本次部署中跳过重复测试：

```bash
SHOP_DEPLOY_SKIP_TESTS=true \
backend/shop-server/scripts/deploy-prod.sh txcloud
```

可选的本地镜像构建模式需要本机 Docker Hub 网络正常，并会上传完整压缩镜像：

```bash
SHOP_DEPLOY_TRANSPORT=image-stream \
backend/shop-server/scripts/deploy-prod.sh txcloud
```

该模式显示传输百分比、速度和预计剩余时间；中断后默认自动重试 3 次，并对已经上传的
部分执行断点续传。需要增加尝试次数时：

```bash
SHOP_DEPLOY_TRANSPORT=image-stream \
SHOP_DEPLOY_TRANSFER_ATTEMPTS=5 \
backend/shop-server/scripts/deploy-prod.sh txcloud
```

部署脚本会包含工作区内尚未提交的源码。发布前必须先检查 `git status --short`。

## 生产状态与日志

公网健康检查：

```bash
curl --fail https://api.muybaby6.icu/actuator/health
curl --fail https://api.muybaby6.icu/actuator/info
```

`/actuator/info` 应只包含 `gitSha`、`buildTime`、`version` 和 `flywayVersion`。

查看全部容器：

```bash
ssh txcloud
cd /opt/shop/shop-server
sudo docker compose -f compose.prod.yaml ps
```

持续查看后端日志：

```bash
sudo docker compose -f compose.prod.yaml logs -f --tail=200 shop-server
```

只重启后端，不构建新版本：

```bash
sudo docker compose -f compose.prod.yaml restart shop-server
```

## 1Panel、MySQL 与 Redis 隧道

本地用一条命令同时建立 1Panel、MySQL 和 Redis 安全隧道：

```bash
ssh -o ExitOnForwardFailure=yes -N \
  -L 18080:127.0.0.1:18080 \
  -L 13306:127.0.0.1:3306 \
  -L 16379:127.0.0.1:6379 \
  txcloud
```

登录地址和凭据保存在 `backend/shop-server/.1panel.local`。使用结束后在隧道终端按
`Ctrl+C`。1Panel 负责 OpenResty、HTTPS 证书、日志和监控；不要在其中重复创建同名
Compose 编排。

隧道运行期间，本机连接地址如下：

| 服务 | 本机地址 |
| --- | --- |
| 1Panel | `127.0.0.1:18080` |
| MySQL | `127.0.0.1:13306` |
| Redis | `127.0.0.1:16379` |

## MySQL

本地查看数据库名、业务账号和两类密码：

```bash
grep -E '^MYSQL_(DATABASE|USER|PASSWORD|ROOT_PASSWORD)=' \
  backend/shop-server/.env.infrastructure.local
```

进入生产 MySQL，密码使用 `MYSQL_PASSWORD`：

```bash
ssh txcloud
cd /opt/shop/shop-server
sudo docker compose -f compose.prod.yaml exec mysql \
  mysql -ushop -p hotpot_shop
```

使用 DataGrip 或 IDEA 时填写：

```text
Host: 127.0.0.1
Port: 13306
Database: hotpot_shop
User: shop
Password: MYSQL_PASSWORD 的值
```

Redis GUI 使用 `127.0.0.1:16379`，密码为 `.env.infrastructure.local` 中
`REDIS_PASSWORD` 的值。

手动备份：

```bash
ssh txcloud \
  'sudo /opt/shop/shop-server/scripts/backup-mysql.sh'
```

## 配置文件

| 文件 | 用途 | 是否提交 |
| --- | --- | --- |
| `application.yaml` | 所有环境公共配置 | 是 |
| `application-dev.yaml` | 本地开发配置 | 是 |
| `application-prod.yaml` | 生产 Profile 规则 | 是 |
| `.env.dev.local` | 本机开发变量 | 否 |
| `.env.prod.local` | 生产应用变量与密钥 | 否 |
| `.env.infrastructure.local` | MySQL、Redis 密码 | 否 |
| `.1panel.local` | 1Panel 登录信息 | 否 |
| `.env.*.example` | 无真实秘密的配置模板 | 是 |

修改普通生产配置后重新执行部署命令。已经初始化的 MySQL 密码不能只靠修改环境文件
完成轮换，必须同时修改数据库内部账号密码。

## 人工回滚

先查看保留的版本镜像：

```bash
sudo docker images shop-server
```

确认目标版本与当前数据库结构兼容后：

```bash
sudo docker tag shop-server:<旧版本标签> shop-server:local
cd /opt/shop/shop-server
sudo docker compose -f compose.prod.yaml \
  up -d --no-deps --force-recreate shop-server
```

数据库迁移可能无法由旧应用自动回滚，高风险发布前应先创建 MySQL 备份。

## 禁止事项

- 不执行 `docker compose down -v`，它会删除 MySQL 和 Redis 数据卷。
- 不向 Git 提交 `.env.*.local`、`.1panel.local`、PEM、私钥或生产密码。
- 不将 MySQL `3306`、Redis `6379` 或应用 `8080` 暴露到公网。
- 不同时启动 Caddy 与 OpenResty，它们会争用 `80/443`。
- 不在未检查工作区和未验证健康检查的情况下发布。
