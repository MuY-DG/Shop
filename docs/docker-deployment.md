# Docker 生产部署

生产环境采用“1Panel 负责运维入口，Docker Compose 负责应用栈”的结构：

```text
Internet
  -> 1Panel OpenResty :80/:443
  -> 宿主机 127.0.0.1:8080
  -> shop-server 容器 :8080
       -> mysql 容器 :3306（内部网络）
       -> redis 容器 :6379（内部网络）
```

MySQL 和 Redis 不发布宿主机端口，因此公网和同机其他进程都不能直接访问
`3306`/`6379`。只有 Spring Boot 容器能通过 Compose 内部网络访问它们。

## 文件分工

- `compose.prod.yaml`：完整声明后端、MySQL、Redis、持久卷、健康检查和资源限制。
- `.env.prod.local`：Spring Boot 生产配置、业务凭据以及应用使用的数据库/Redis 密码。
- `.env.infrastructure.local`：只给 MySQL/Redis 使用的基础设施密码。
- `secrets/`：微信支付 PEM 文件，只读挂载到应用容器。
- `var/`：本地上传文件持久化目录。
- `backups/`：本机 MySQL 备份目录。
- `scripts/deploy-prod.sh`：本地构建、SSH 上传和远程切换的一条命令。
- `scripts/backup-mysql.sh`：供 1Panel 计划任务调用的 MySQL 备份脚本。

所有 `.env.*.local`、`secrets/`、`var/` 和 `backups/` 均被 Git 忽略。

## 首次准备生产配置

现有生产配置只需运行一次初始化脚本。它会生成独立的 MySQL 业务密码、
MySQL root 密码和 Redis 密码，并同步应用侧的对应值：

```bash
cd /Users/muybaby/Project/Production/Shop/backend/shop-server
./scripts/init-prod-env.sh
./scripts/validate-prod-env.sh
```

如果明确不保留现有容器数据，需要重新生成整套基础设施密码：

```bash
./scripts/init-prod-env.sh --rotate-infrastructure
```

脚本不会打印密码，并会把两个本地环境文件权限设为 `600`。

全新数据库默认不会启用公共 Super。确实需要首次引导账号时：

1. 运行 `AdminPasswordHashTool` 为唯一的临时强密码生成 BCrypt 哈希。
2. 在 `.env.prod.local` 中临时设置
   `SHOP_DEFAULT_ADMIN_STATUS=ENABLED` 和生成的哈希。
3. 首次登录后立即创建正式管理员或修改密码。
4. 删除这两个覆盖值；Flyway 已执行的迁移不会重复创建账号。

## 一条命令部署

服务器首次使用时，先按 1Panel 官方脚本安装 Docker 与 1Panel V2：

```bash
backend/shop-server/scripts/bootstrap-1panel.sh txcloud
```

随机安全入口和密码保存在被 Git 忽略的
`backend/shop-server/.1panel.local`，文件权限为 `600`，脚本不会打印密码。
官方安装器首次显示登录信息后，脚本会立即轮换一次随机密码。以后如需再次轮换可运行：

```bash
backend/shop-server/scripts/secure-1panel.sh txcloud
```

然后在项目根目录运行完整应用部署：

```bash
backend/shop-server/scripts/deploy-prod.sh txcloud
```

脚本会依次执行：

1. 校验两个生产环境文件及重复密码是否一致。
2. 运行后端完整测试。
3. 默认只上传精简源码，由服务器构建 `linux/amd64` 分层镜像。
4. 通过 SSH 安全上传 Compose、环境文件和运维脚本。
5. 先启动并等待 MySQL/Redis 健康。
6. 停止旧 systemd Java 服务，启动 `prod` Profile 容器。
7. 检查 `127.0.0.1:8080/actuator/health`。
8. 健康后禁用旧 Java 服务；失败则自动恢复旧服务。

只有在已经单独运行过测试时，才可跳过测试：

```bash
SHOP_DEPLOY_SKIP_TESTS=true backend/shop-server/scripts/deploy-prod.sh txcloud
```

服务器是 x86-64；如果将来更换 ARM 服务器，可显式覆盖：

```bash
SHOP_DEPLOY_PLATFORM=linux/arm64 backend/shop-server/scripts/deploy-prod.sh txcloud
```

## Spring Boot Layers 的实际作用

Dockerfile 使用 Spring Boot `jarmode=tools` 将可执行 JAR 拆成：

1. `dependencies`
2. `spring-boot-loader`
3. `snapshot-dependencies`
4. `application`

业务代码变化时，同一构建端会复用前三层。使用私有镜像仓库 push/pull 时，网络也只传输
变化的层。默认 `remote-build` 模式只上传精简源码，并复用服务器 Maven 与 Docker
缓存，适合本机到服务器上传较慢的情况。

如需改回本机构建并通过 SSH 传输完整压缩镜像：

```bash
SHOP_DEPLOY_TRANSPORT=image-stream backend/shop-server/scripts/deploy-prod.sh txcloud
```

该模式使用 `docker save | docker load`，不会只发送变化层。以后需要分层传输时，可接入
腾讯云 TCR 或 GHCR，Compose 本身无需重构。

## 1Panel 与 OpenResty

1Panel 只负责 Docker 可视化、日志、监控、计划任务、证书和 OpenResty；项目的真实
部署定义仍以仓库中的 `compose.prod.yaml` 为准。

在 1Panel 的“容器 -> 编排”中，通过路径导入：

```text
/opt/shop/shop-server/compose.prod.yaml
```

OpenResty 在 1Panel 应用商店安装。创建“反向代理”网站：

- 主域名：生产域名。
- 代理地址：`http://127.0.0.1:8080`。
- HTTPS：选择 1Panel 申请或导入的证书并开启自动续签。
- WebSocket：开启或保留升级头，项目包含 WebSocket 接口。

同一时刻只能有一个服务监听 `80/443`。迁移时先让 OpenResty 在备用端口验证，
再停止 Caddy 并把 OpenResty 改为 `80/443`；验证失败时可立即重新启动 Caddy。

生产后端必须继续使用：

```yaml
server:
  address: ${SERVER_ADDRESS:127.0.0.1}
```

Compose 只在容器内部覆盖为 `0.0.0.0`，宿主机端口仍绑定
`127.0.0.1:8080`。不得将其改成 `0.0.0.0:8080`。

## MySQL 与 Redis 持久化

- `shop_mysql-data` 保存 MySQL 数据。
- `shop_redis-data` 保存 Redis AOF/快照。
- Redis 使用 AOF `everysec`。
- 删除或重建容器不会删除命名卷。
- `docker compose down -v` 会删除数据库和 Redis 数据，生产环境禁止执行。

查看状态：

```bash
cd /opt/shop/shop-server
sudo docker compose -f compose.prod.yaml ps
sudo docker compose -f compose.prod.yaml logs --tail=200 shop-server
```

## 自动备份

手动验证一次：

```bash
sudo /opt/shop/shop-server/scripts/backup-mysql.sh
```

默认保留 14 天，可通过环境变量调整：

```bash
sudo SHOP_BACKUP_RETENTION_DAYS=30 /opt/shop/shop-server/scripts/backup-mysql.sh
```

在 1Panel“计划任务”中新建 Shell 任务，每天低峰期执行：

```bash
/opt/shop/shop-server/scripts/backup-mysql.sh
```

本机备份只能防止误操作，不能防止整机或磁盘故障。后续应在 1Panel 中增加对象存储
备份，或由云厂商备份 `/opt/shop/shop-server/backups` 和 Docker 数据卷。

## 检查与回滚

生产检查：

```bash
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail https://pay-dev.muybaby6.icu/actuator/health
sudo docker compose -f /opt/shop/shop-server/compose.prod.yaml ps
```

每次部署都会保留形如 `shop-server:<git-sha>-<timestamp>` 的镜像。需要回滚时，将已验证
旧镜像重新标记为 `shop-server:local`，然后只重建应用容器：

```bash
sudo docker tag shop-server:<old-version> shop-server:local
cd /opt/shop/shop-server
sudo docker compose -f compose.prod.yaml up -d --no-deps --force-recreate shop-server
```

数据库迁移通常不可由旧二进制自动回滚。回滚应用前必须先确认该版本能读取当前数据库
结构；高风险升级应先创建 MySQL 备份。
