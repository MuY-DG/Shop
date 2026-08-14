# Docker 生产部署

生产环境采用“1Panel 负责运维入口，Docker Compose 负责应用栈”的结构：

```text
Internet
  -> 1Panel OpenResty :80/:443
  -> 宿主机 127.0.0.1:8080
  -> shop-server 容器 :8080
       -> mysql 容器 :3306（内部网络）
       -> redis 容器 :6379（内部网络）

Local Mac
  -> SSH tunnel
       -> 宿主机 127.0.0.1:3306 -> mysql 容器 :3306
       -> 宿主机 127.0.0.1:6379 -> redis 容器 :6379
```

MySQL 和 Redis 只发布到宿主机回环地址，公网不能直接访问 `3306`/`6379`。
Spring Boot 通过隔离的 `data` 网络访问；`ops` 网络只负责 Docker 回环端口发布所需的
默认网关，本机数据库工具必须通过 SSH 隧道访问。

## 文件分工

- `compose.prod.yaml`：完整声明后端、MySQL、Redis、持久卷、健康检查和资源限制。
- `.env.prod.local`：只含数据库/Redis、可信代理、通用主加密密钥和首次引导值。
- `.env.infrastructure.local`：只给 MySQL/Redis 使用的基础设施密码。
- `secrets/`：当前生产 Compose 不再挂载；只允许旧部署在第一阶段过渡版本中临时使用。
- `backups/`：本机 MySQL 备份目录。
- `scripts/deploy-prod.sh`：本地测试、精简源码上传、服务器缓存构建和远程切换的一条命令。
- `scripts/backup-mysql.sh`：供 1Panel 计划任务调用的 MySQL 备份脚本。

所有 `.env.*.local`、历史 `secrets/` 和 `backups/` 均被 Git 忽略。对象存储、微信平台、
支付和服务动态业务配置由后台数据库管理；生产校验会拒绝微信业务凭据或旧运行开关
重新进入 `.env.prod.local`。

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

升级已有环境时，初始化脚本会把旧的密钥、写入版本、active key、key ring 和轮换开关
迁移到 `SHOP_SECRET_ENCRYPTION_*`（其中旧 `SHOP_PAYMENT_SECRET_KEY` 改为
`SHOP_SECRET_ENCRYPTION_LEGACY_KEY`）。轮换延迟/批量与支付过期时间已经改为受版本控制的
技术默认值，脚本会删除对应旧覆盖。它还会删除所有
`SHOP_STORAGE_PROVIDER`、`SHOP_STORAGE_PUBLIC_BASE_URL`、
`SHOP_STORAGE_LOCAL_ROOT`、`SHOP_STORAGE_TENCENT_COS_*` 和
`SHOP_DIRECT_UPLOAD_*` 行。COS 区域、存储桶和凭证只在管理后台保存；上传限额等
技术默认值位于 `application.yaml`。校验脚本会拒绝仍含这些已移除变量的生产文件。

`.env.prod.example` 不再列出 `WECHAT_MINI_PROGRAM_*`、`WECHAT_PAY_*` 或微信发货开关。
新部署通过后台数据库配置这些业务值，真实值不得复制回 example、镜像或文档。

日常 Admin 的支付配置页面只管理数据库配置：创建、编辑、启用和软删除，不展示
`AUTO`、`ENV`、环境变量配置或环境导入入口。删除只会让非当前配置退出日常列表；当前启用
配置必须先切换到其他配置，仍有旧私有文件 ID 的配置必须先完成秘密文件迁移。历史支付、
退款、回调和对账仍按原配置 ID 或加密 ENV snapshot 回放，禁止手工物理删除对应数据库行、
密文或 snapshot。

### 旧部署两阶段迁移

未迁移的旧部署必须先使用仍保留 `.env.prod.local` 兼容值和 `secrets/` 只读挂载的
V98-V101 过渡版本完成第一阶段；当前主 Compose 已进入第二阶段，不再提供 PEM 挂载。
第一阶段只部署和迁移，不代表已经操作生产数据库：

1. 发布包含 V98、V99、V100、V101 的版本，确认 Flyway 完成且应用仍可用旧 ENV/文件回退启动。
2. 支付来源为 ENV 时，由运维直接调用兼容接口
   `POST /admin/pay/configs/import-environment` 创建禁用的 DB
   候选；已有 DB 配置仍引用旧私有文件 ID 时，对每个待迁移行调用
   `POST /admin/pay/configs/{configId}/import-legacy-secret-files`。后者把 PEM 正文校验、加密
   后写入 `payment_config`，并清空旧私有文件引用。确认列表中的
   `legacySecretFilesPendingImport=false`，必要时启用目标行，再用
   `PUT /admin/pay/configs/source` 显式保存 `{"source":"DB"}`。
3. 先 `GET /admin/wechat/platform-config`；仅当返回
   `legacyEnvironmentImportAvailable=true` 时，以 `{"version":0}` 调用
   `POST /admin/wechat/platform-config/legacy-env-import`。再次 GET 必须显示
   `source=DATABASE` 且不再提供 legacy import。
4. 读取 `GET /admin/wechat-service-cards/config`；仅当返回
   `legacyEnvironmentImportAvailable=true` 时，以 `{"version":0}` 调用
   `POST /admin/wechat-service-cards/config/legacy-env-import`。导入会严格校验模板、公开图、
   host、Token/AESKey，并保持旧环境中已验证的 callback enabled 值；数据库行写入后立即
   优先生效。再次 GET 必须显示 `source=DATABASE`，但只能看到密钥掩码和 configured 状态。
5. 从 `GET /admin/wechat-service-cards/status` 读取当前 Capture/Worker 生效值和 version，
   用相同值及真实原因调用 `PUT /admin/wechat-service-cards/runtime`；从
   `GET /admin/wechat-shipping/runtime` 读取上传/投递/收货三个生效值和 version，再用相同
   值及真实原因 PUT 回去。这样先持久化旧开关，不在迁移时改变行为。
6. 依次验证小程序登录、服务动态 GET 握手/失败回调、低金额沙箱或受控真实支付、历史支付查询/退款解析、发货跳过或
   沙箱路径。核对支付 effective/source、平台 source、两个 runtime 的 persisted/version
   与审计记录；健康检查或单元测试不能替代这些外部证据。

上述支付 ENV/source/import 接口仅服务老部署升级或受控运维回滚，日常 Admin 不展示这些
入口。升级完成后不要借它们恢复双配置入口；但后端仍长期保留历史 ENV snapshot 的解析，
以处理旧支付的查询、退款、回调和对账。

第二阶段必须等上述验证、支付回调重试窗口、历史 ENV 支付快照和回滚窗口都满足后再做：

1. 从真实 `.env.prod.local` 删除 `WECHAT_PAY_*`、`WECHAT_MINI_PROGRAM_*`，以及已持久化的
   `SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED`、`SHOP_WECHAT_SHIPPING_DELIVERY_ENABLED`、
   `SHOP_WECHAT_RECEIPT_RECONCILIATION_ENABLED`、`SHOP_WECHAT_SERVICE_CARD_CAPTURE_ENABLED`、
   `SHOP_WECHAT_SERVICE_CARD_WORKER_ENABLED`、`SHOP_WECHAT_SERVICE_CARD_CALLBACK_ENABLED`、
   `SHOP_WECHAT_SERVICE_CARD_TEMPLATE_RECORD_ID`、`SHOP_WECHAT_SERVICE_CARD_FALLBACK_IMAGE`、
   `SHOP_WECHAT_SERVICE_CARD_IMAGE_HOSTS`、`SHOP_WECHAT_SERVICE_CARD_CALLBACK_TOKEN` 和
   `SHOP_WECHAT_SERVICE_CARD_CALLBACK_AES_KEY`。
2. 只有所有支付配置都显示 `legacySecretFilesPendingImport=false` 且不再需要旧版本回滚时，
   才移除 Compose 的 `secrets/` 挂载和服务器旧 PEM。删除服务器文件前先按运维策略留存
   受控备份；不要删除 `SHOP_SECRET_ENCRYPTION_*` 主密钥环。
3. 删除服务动态旧环境值前，确认 V101 配置 source 为 `DATABASE`、回调密钥 configured、
   handshake 与失败回调均通过，并保留满足回滚窗口的受控备份；不要输出密钥明文。

当前主部署要求第二阶段已经完成：生产校验脚本拒绝上述 26 个 legacy key，Compose 不再
挂载 `secrets/`。`.env.dev.local` 属于独立开发环境；只有开发数据库也完成相同迁移后才
删除其微信业务值，生产迁移不能代替本地迁移。

全新部署不执行 legacy import：先准备通用主密钥环，启动后直接在 Admin 创建微信平台和
支付 DB 配置，再一次性创建完整的服务动态接入配置，最后保存服务动态/发货运行开关。

全新数据库默认不会启用公共 Super。确实需要首次引导账号时：

1. 运行 `AdminPasswordHashTool` 为唯一的临时强密码生成 BCrypt 哈希。
2. 在 `.env.prod.local` 中临时设置
   `SHOP_DEFAULT_ADMIN_STATUS=ENABLED` 和生成的哈希。
3. 首次登录后立即创建正式管理员或修改密码。
4. 将状态恢复为 `DISABLED`；哈希继续留作受控的首次建库占位值。Flyway 已执行的迁移
   不会重复创建账号。

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

该模式先生成本地压缩镜像包，再使用 rsync 显示传输百分比、速度和预计剩余时间。
连接中断时默认自动尝试 3 次，并从远端已经收到的位置继续；传输完成后执行 SHA-256
校验，再由 `docker load` 加载镜像。可按需调整：

```bash
SHOP_DEPLOY_TRANSPORT=image-stream \
SHOP_DEPLOY_TRANSFER_ATTEMPTS=5 \
SHOP_DEPLOY_TRANSFER_RETRY_DELAY_SECONDS=10 \
backend/shop-server/scripts/deploy-prod.sh txcloud
```

本地和服务器需要额外容纳一份临时压缩包，成功加载后会自动清理。该模式仍会传输完整
镜像，不会只发送变化层。以后需要分层传输时，可接入腾讯云 TCR 或 GHCR，Compose
本身无需重构。

## 1Panel 与 OpenResty

1Panel 只负责 Docker 可视化、日志、监控、计划任务、证书和 OpenResty；项目的真实
部署定义仍以仓库中的 `compose.prod.yaml` 为准。

不要在 1Panel 中再次新建或导入同名编排，否则脚本与面板会同时拥有容器生命周期，
容易出现配置漂移。脚本负责 `docker compose` 的创建与更新；容器启动后仍可在 1Panel
的容器列表中查看状态、资源与日志。

OpenResty 在 1Panel 应用商店安装。创建“反向代理”网站：

- 主域名：生产域名。
- 代理地址：`http://127.0.0.1:8080`。
- HTTPS：选择 1Panel 申请或导入的证书并开启自动续签。
- WebSocket：开启或保留升级头，项目包含 WebSocket 接口。
- 覆盖客户端来源头：`proxy_set_header X-Forwarded-For $remote_addr;`，不要直接透传
  客户端提交的 `X-Forwarded-For`。

Docker 发布端口转发到容器后，应用看到的对端通常是 bridge 网关而不是
`127.0.0.1`。部署完成后先定位 `shop-server` 容器，再检查它所在网络的 `Gateway`：

```bash
shop_container_id="$(sudo docker compose -f compose.prod.yaml ps -q shop-server)"
sudo docker inspect "$shop_container_id" --format '{{json .NetworkSettings.Networks}}'
```

将实际承载宿主机转发流量的单个网关地址以 `/32` 写入 `.env.prod.local`，并把转发跳数
限制为 1，例如：

```properties
SHOP_TRUSTED_PROXY_CIDRS=127.0.0.0/8,::1/128,<核验出的网关-IP>/32
SHOP_MAX_FORWARDED_HOPS=1
```

不要信任整个 `172.16.0.0/12`、整个 bridge 子网或 `0.0.0.0/0`。Docker 网络重建后
应重新核验网关。上线烟测时，从公网携带伪造的 `X-Forwarded-For` 请求一次后台接口，
日志中仍必须显示真实客户端 IP；验证失败时保留网关 IP，也不要临时放宽可信网段。

同一时刻只能有一个服务监听 `80/443`。当前 OpenResty 已接管这两个端口，Caddy 的
配置仍保留但服务已禁用，可作为人工回滚方案。1Panel 应用安装后的 OpenResty 主端口
不要直接改动；如需重新迁移，应先在备用端口验证，再停止 Caddy，并以 `80/443`
重新安装或重建 OpenResty。

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
curl --fail http://127.0.0.1:8080/actuator/info
curl --fail https://api.muybaby6.icu/actuator/health
curl --fail https://api.muybaby6.icu/actuator/info
sudo docker compose -f /opt/shop/shop-server/compose.prod.yaml ps
```

`/actuator/info` 只允许出现 `gitSha`、`buildTime`、`version` 和
`flywayVersion`。标准部署脚本会在切换后核对 Git SHA 与 UTC 构建时间，
不一致时触发回滚。

每次部署都会保留形如 `shop-server:<git-sha>-<timestamp>` 的镜像。需要回滚时，将已验证
旧镜像重新标记为 `shop-server:local`，然后只重建应用容器：

```bash
sudo docker tag shop-server:<old-version> shop-server:local
cd /opt/shop/shop-server
sudo docker compose -f compose.prod.yaml up -d --no-deps --force-recreate shop-server
```

数据库迁移通常不可由旧二进制自动回滚。回滚应用前必须先确认该版本能读取当前数据库
结构；高风险升级应先创建 MySQL 备份。
