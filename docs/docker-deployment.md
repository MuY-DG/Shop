# 后端 Docker 部署

本文只描述 Shop 后端在 `txcloud`（开发/集成）和 `shop`（正式生产）上的当前部署方式。两台服务器使用同一套 Compose、同一个 `server` Profile 和同一种运行时清单结构，差异只存在于各自的秘密和业务数据库配置。面向操作者的完整发布顺序见 [deployment-guide.md](deployment-guide.md)。

> 当前 Flyway generation 2 只有 V1-V7。这是一条不兼容旧数据库的全新基线，不能把它直接启动在旧 schema 上。切换时必须先确认数据确实可丢弃，再删除目标环境的 MySQL/Redis 数据卷并从空库启动。Git 历史仅用于查阅，不参与新环境迁移。

## 1. 边界与前置条件

仓库负责：

- 构建后端镜像；
- 上传 Compose、运行时清单和部署脚本；
- 启动 MySQL、Redis、后端容器；
- 在切换前执行测试、静态门禁和 MySQL 备份；
- 校验容器健康状态以及 `/actuator/info` 的版本信息。

服务器管理员负责：

- 安装和升级 Docker Engine、Docker Compose；
- 安装、升级和保护 1Panel（如使用）；
- 配置 OpenResty、域名、HTTPS 证书、防火墙和云安全组；
- 监控、异机备份和操作系统安全。

仓库不再提供 Docker/1Panel 安装脚本，也不保存或轮换 1Panel 密码。

本机还需满足：

- `ssh txcloud` 与 `ssh shop` 可用；
- JDK/Maven Wrapper、Docker/Testcontainers、Git、`tar` 可用；
- 工作区已提交且无未跟踪改动；
- 服务器至少有 `docker`、Compose 插件、`curl`、`gzip`、`flock`、`sha256sum`，远程构建模式还需能构建 Docker 镜像。

## 2. 配置模型

唯一受版本控制的模板是：

```text
backend/shop-server/config/runtime/runtime.env.example
```

本机生成三个互不复用、且被 Git 忽略的清单：

```text
backend/shop-server/config/runtime/local.env
backend/shop-server/config/runtime/txcloud.env
backend/shop-server/config/runtime/shop.env
```

清单仅允许以下启动边界：

```text
SHOP_DB_PASSWORD
SHOP_DB_ROOT_PASSWORD
SHOP_REDIS_PASSWORD
SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID
SHOP_SECRET_ENCRYPTION_KEY_RING
```

三个密码固定使用初始化脚本生成的 64 位小写十六进制格式；key ring 允许 1-16 个条目。
微信小程序、微信支付、COS、服务动态、发货和财务对账的凭据与业务开关必须在 Admin 中配置并加密写入数据库，不得放入运行时清单。

生成并校验服务器清单：

```bash
cd backend/shop-server
./scripts/config/init-runtime-env.sh txcloud
./scripts/config/init-runtime-env.sh shop

./scripts/config/validate-runtime-env.sh txcloud
./scripts/config/validate-runtime-env.sh shop
```

初始化脚本拒绝覆盖已有文件。普通部署要求服务器当前清单与待部署清单的全部 5 项秘密逐字一致；任何密码或主密钥变化都必须走独立维护流程。密钥轮换时应保留旧 key ID 和原 key bytes，直到数据库密文完成重加密，不能直接丢弃仍被引用的 key。

## 3. 可信代理

Compose 只把后端端口发布到宿主机 `127.0.0.1:8080`。OpenResty 应反向代理到该地址，并覆盖而不是追加来源头：

```nginx
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
```

Compose 为三张网络固定独立 `/24`：`data=172.22.0.0/24`、`edge=172.23.0.0/24`、
`ops=172.24.0.0/24`。`application-server.yaml` 只额外信任精确的 edge 网关
`172.23.0.1/32`，不再接受环境变量覆盖；local 只信任回环地址。

首次重建前必须用 `ip route` 和 Docker network inspect 确认宿主机没有占用这三个网段。
固定 IPAM 避免网络重建后网关漂移，也消除了“先部署网络还是先填写清单”的循环依赖。

建议在服务器上查看：

```bash
cd /opt/shop/shop-server
ip route
sudo docker network inspect shop_edge
```

## 4. 首次 generation 2 切换

这一节包含破坏性操作。执行前确认目标是 `txcloud` 还是 `shop`，确认该目标的数据可以丢弃，并保留一份可恢复备份。不要把目标名、省略的 SSH 别名或 Compose 项目名交给通配符。

推荐顺序：

1. 在本机完成第 5 节的全部校验并提交发布版本。
2. 在服务器停止旧应用，导出一份最后备份。
3. 明确删除该目标旧 Compose 的 MySQL/Redis 命名卷。
4. 用新的目标清单执行部署；Flyway 从空库运行 V1-V7。
5. 确认 `/actuator/health`、`/actuator/info` 和 Flyway 版本。
6. 运行一次性 Super 管理员引导。
7. 登录 Admin 修改临时密码，再录入业务配置。
8. 依次完成微信登录、支付、退款、COS、发货和对账的真实平台验收。

删除旧数据卷的命令必须根据服务器上 `docker volume ls` 的实际结果人工确认，本文不提供可直接复制的通配删除命令。代码整理或普通发布也不等于已经授权删除线上数据。

## 5. 发布前校验

```bash
cd backend/shop-server

./scripts/ci/verify-flyway-migrations.sh
./scripts/ci/verify-test-layers.sh

# 快速单元/H2 层；不包含 Testcontainers。
./mvnw test

# MySQL/Redis Testcontainers 层；要求 Docker 可用且零跳过。
./mvnw -Pintegration verify
./scripts/ci/assert-integration-test-results.sh target/failsafe-reports
```

还应按 [smoke-checks.md](smoke-checks.md) 校验 Admin 和小程序。`SHOP_DEPLOY_SKIP_TESTS=true` 仅用于已经取得同一提交完整测试证据的受控重试，不应作为日常捷径。

## 6. 部署 txcloud 或 shop

部署命令必须显式指定目标：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh txcloud
backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

默认 `remote-build` 会上传精简源码并在目标服务器构建镜像。网络条件允许时也可在本机构建并流式上传完整镜像：

```bash
SHOP_DEPLOY_TRANSPORT=image-stream \
  backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

目标架构默认是 `linux/amd64`，确需 ARM 时显式覆盖：

```bash
SHOP_DEPLOY_PLATFORM=linux/arm64 \
  backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

脚本会：

1. 在测试前以及测试后、构建前后重复拒绝脏工作区，避免镜像内容与 Git SHA 不一致；
2. 校验目标清单、Flyway 文件和测试分层；
3. 默认运行后端两层测试；
4. 生成包含 Git SHA、UTC 时间和随机后缀的唯一 `deploy_id`，把目标清单、Compose 和调用脚本上传为带该 ID 的候选文件；远端在修改任何 canonical 文件、镜像标签或数据服务前先解析候选 Compose；
5. 构建并标记独立版本镜像；
6. 已有容器或数据卷时，先用部署前的 canonical Compose/runtime 启动旧 MySQL 并强制备份，再切换候选清单；首次部署则先初始化数据服务，再生成空库基线备份；
7. 对应备份成功后，以同目录原子 `mv` 切换 runtime/Compose，再切换后端容器；
8. 检查健康状态、Git SHA、构建时间和 Flyway 版本，成功后才提升 canonical 运维脚本；
9. 在服务器取得非阻塞发布锁后才允许修改 canonical 清单和 `shop-server:local`；另一条发布或人工备份正在占用同一目标锁时立即失败，各自只清理自己的候选文件；
10. 任一步骤失败或收到 HUP/INT/TERM 时先停止候选应用，再恢复部署前 Compose/runtime、运维脚本、数据服务和应用镜像（如果存在），并要求旧应用重新通过健康检查。

部署脚本不会发布 Admin，也不会删除旧数据卷。

## 7. 首次管理员引导

空库基线会创建停用且不可登录的 `Super` 哨兵账号。引导脚本同时校验第二代 schema marker
与 Flyway V7 历史，不能拿旧 V1-V107 数据库的相似账号状态绕过。后端健康启动后，从本机执行：

```bash
backend/shop-server/scripts/config/bootstrap-admin.sh txcloud
backend/shop-server/scripts/config/bootstrap-admin.sh shop
```

脚本先把明文临时密码写入同目录、被 Git 忽略且权限为 `0600` 的临时文件，密码通过 stdin 交给 `htpasswd`；只有哨兵状态完全匹配并完成 CAS 更新及系统日志后，才把临时文件原子发布为：

```text
backend/shop-server/config/runtime/bootstrap-admin.<target>.txt
```

首次登录后立即修改密码并删除该文件。脚本拒绝覆盖已有凭据，也不能用于普通密码重置。

## 8. 部署后验收

服务器本机：

```bash
cd /opt/shop/shop-server
sudo docker compose \
  --env-file config/runtime/runtime.env \
  -f compose.prod.yaml ps
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/info
```

公网侧还要检查：

- HTTPS 证书链、域名和反向代理；
- 后台和小程序不暴露内网端口；
- 未认证请求不泄露配置或运维信息；
- 真实客户端 IP 解析符合预期；
- Admin 能登录、保存配置并留下审计日志；
- 外部平台验收使用真实测试订单，自动化测试结果不能替代平台结果。

## 9. 备份与回滚

手工备份：

```bash
ssh shop 'sudo /opt/shop/shop-server/scripts/deploy/backup-mysql.sh'
```

每个 `.sql.gz` 同时生成同名 `.sql.gz.sha256` sidecar；默认将备份及 sidecar 成对保留 14 天。手工备份与发布共用目标级发布锁，并另有备份写锁，因此不会在数据服务切换期间并发导出。可在受控计划任务中覆盖保留天数：

```bash
sudo SHOP_BACKUP_RETENTION_DAYS=30 \
  /opt/shop/shop-server/scripts/deploy/backup-mysql.sh
```

本机磁盘上的备份不能防止整机或磁盘故障，应另行复制到受控的异机/对象存储，并定期执行恢复演练。

可在备份目录验证 sidecar：

```bash
cd /opt/shop/shop-server/backups/mysql
sha256sum --check hotpot_shop-<timestamp>.sql.gz.sha256
```

普通代码发布会先把新清单上传为 `runtime.env.next.<deploy_id>`，并以同一 ID 隔离 Compose 候选和调用脚本。检测到已有容器或数据卷时，远程脚本在切换候选清单前先使用旧 canonical Compose/runtime 完成 MySQL 备份；首次部署则在初始化数据服务后生成空库基线备份。Compose 校验、数据服务、强制备份、应用健康或版本核对任一步失败，都会先停止候选应用，再尝试恢复旧清单、旧数据服务和上一个镜像，并验证旧应用健康。

该恢复明确不撤销已经执行的数据库迁移，也不会自动回灌备份。若旧应用恢复健康失败，发布输出会给出严重告警，执行人必须停止继续发布，结合部署前备份人工判断是否恢复数据库。generation 2 首次切换使用空库，因此恢复旧系统必须同时恢复旧镜像、旧 Compose 配置和对应数据库备份，不能让旧二进制连接新基线。
