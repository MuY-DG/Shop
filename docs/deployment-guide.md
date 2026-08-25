# Shop 部署教程（txcloud 与 shop）

> 最后核对：2026-08-24。除特别说明外，命令都在仓库根目录
> `/Users/muybaby/Project/Production/Shop` 执行。

本文是面向操作者的端到端顺序教程。发布时必须同时遵守：

- [生产发布检查清单](production-release-checklist.md)：强制门禁和破坏性操作授权；
- [后端 Docker 部署](docker-deployment.md)：Compose、runtime、备份和回滚机制；
- [Smoke 检查](smoke-checks.md)：自动化、主机和真实平台验收；
- [环境与边缘入口](../ops/README.md)：1Panel/OpenResty 职责边界；
- [运维速查](operations-cheatsheet.md)：日常状态、日志、备份和隧道。

如果本文与强制清单冲突，以强制清单和当前脚本为准。本文不得保存 1Panel、数据库、
Redis、微信、支付或 COS 的用户名、入口、密码、Secret、私钥和密钥原文。

## 1. 先认清三个环境

| 目标 | 用途 | SSH/Profile | API | Admin | 小程序 AppID |
| --- | --- | --- | --- | --- | --- |
| `local` | 本机开发 | `local` | 本机 | 本机 Vite | 按开发者工具 |
| `txcloud` | 开发/集成 | `ssh txcloud` / `server` | `api.muybaby6.icu` | `admin.muybaby6.icu` | `wx2c59f00275b9057a` |
| `shop` | 正式生产 | `ssh shop` / `server` | `api.junxiangshiping.cn` | `admin.junxiangshiping.cn` | `wxd2c02e4864389d80` |

两台服务器的数据库、Redis、runtime 清单、主加密密钥、微信、支付和 COS 配置必须独立。
禁止在 `txcloud` 与 `shop` 之间复制 runtime 文件、数据库、数据卷、OpenID 或业务凭据。

固定发布顺序是：

```text
本机门禁 -> txcloud 部署与验收 -> 单独批准 shop -> shop 部署与正式验收
```

不能因为 txcloud 正常就直接认定 shop 正常；shop 使用正式 AppID、正式商户和正式资源，
必须重新验收。

## 2. 区分首次重建和日常发布

### 2.1 Generation 2 首次重建

只在目标第一次从旧 V1-V107 切换到 V1-V7 时执行。它会丢弃目标 MySQL/Redis 数据卷，
是一次性、破坏性操作。

必须完成停写、最终备份、异机复制、隔离恢复、旧系统回滚点和精确卷名确认。部署脚本
不会自动删除旧数据卷；发现旧 Compose 状态但缺少 Generation 2 canonical 配置时会拒绝
继续。

### 2.2 Generation 2 日常发布

目标已经使用 Generation 2 后，普通后续发布（包括无迁移发布和未来 V8+）：

- 不删除数据卷；
- 不重新初始化 runtime；
- 不重新引导 Super；
- 不把五项 runtime secret 轮换夹带进普通发布；
- 只发布发生变化的后端、Admin 或小程序。

判断现场状态时读取真实服务器，不从旧文档推测：

```bash
curl --fail --silent --show-error \
  https://api.muybaby6.icu/actuator/info

curl --fail --silent --show-error \
  https://api.junxiangshiping.cn/actuator/info
```

Generation 2 空库基线应报告 `flywayVersion=7`。服务器若仍报告旧版本或保留旧目录布局，
必须走首次重建流程。

## 3. 1Panel 与 OpenResty 只准备一次

仓库负责后端容器和 Admin 静态版本；服务器管理员负责 Docker、1Panel、OpenResty、域名、
HTTPS 证书、防火墙和云安全组。

### 3.1 部署脚本不会管理什么

| 动作 | 脚本会做 | 脚本不会做 |
| --- | --- | --- |
| 后端部署 | 更新 `/opt/shop/shop-server`、镜像和 Compose | 创建 1Panel 网站、修改 OpenResty/证书、删除数据卷、部署 Admin |
| Admin 部署 | 上传 `admin/dist` 到版本目录并切换 `index` 软链接 | 创建网站、修改 vhost/证书、部署后端 |
| 小程序发布 | 无服务器脚本 | SSH 上传、微信审核和发布 |

仓库 `ops/openresty/*.conf` 只是版本化参考，修改它们不会自动同步到服务器。

### 3.2 新服务器的网站结构

第一次准备新服务器时，在 1Panel 中建立：

1. API 反向代理网站：转发到 `http://127.0.0.1:8080`，支持 `/realtime` WebSocket；
2. Admin 静态网站：提供 SPA 文件，把 `/admin/**` 和 `/realtime` 转发到后端；
3. 两个域名各自的有效 HTTPS 证书；
4. 覆盖式 `X-Forwarded-For`、`X-Real-IP` 和 `X-Forwarded-Proto` 代理头。

参考配置位于：

- `ops/openresty/api.muybaby6.icu.conf`
- `ops/openresty/admin.muybaby6.icu.conf`
- `ops/openresty/api.junxiangshiping.cn.conf`
- `ops/openresty/admin.junxiangshiping.cn.conf`

截至 2026-08-24 的只读现场核对结果如下；以后操作前仍须重新确认：

- `shop` 的 API 反向代理和 Admin 静态网站都已登记在 1Panel，不要重复创建；
- `txcloud` 的两个 OpenResty 配置有效，但没有登记到 1Panel 网站列表。它们当前可以正常
  使用；如果以后需要纳入面板管理，应单独迁移，不能直接创建同名网站覆盖现有配置。

Admin 第一次部署到 1Panel 新建的空静态网站时，会把原 `index` 目录保存为
`index.bootstrap-<时间>`，然后把 `index` 切换为新版本软链接。以后发布只切换该软链接，
不会改变网站记录、域名、证书或 OpenResty 配置。

## 4. runtime 清单

受版本控制的唯一模板是：

```text
backend/shop-server/config/runtime/runtime.env.example
```

每个环境只使用自己的 ignored 文件：

```text
backend/shop-server/config/runtime/local.env
backend/shop-server/config/runtime/txcloud.env
backend/shop-server/config/runtime/shop.env
```

新环境首次生成；初始化脚本拒绝覆盖已有文件：

```bash
backend/shop-server/scripts/config/init-runtime-env.sh txcloud
backend/shop-server/scripts/config/init-runtime-env.sh shop
```

每次部署前只校验目标文件，不打印值：

```bash
backend/shop-server/scripts/config/validate-runtime-env.sh txcloud
backend/shop-server/scripts/config/validate-runtime-env.sh shop
```

runtime 只允许五项启动秘密：

```text
SHOP_DB_PASSWORD
SHOP_DB_ROOT_PASSWORD
SHOP_REDIS_PASSWORD
SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID
SHOP_SECRET_ENCRYPTION_KEY_RING
```

微信 AppSecret、支付、COS、发货、服务动态和财务开关全部在对应 Admin 中配置并加密写入
数据库，不得加回 ENV、YAML 或本文档。

## 5. 每次发布前的本机门禁

先确认准备发布的 Git SHA，工作区必须已提交且干净：

```bash
git status --short --branch
git rev-parse HEAD
git diff --check
```

运行后端完整门禁：

```bash
cd backend/shop-server

./scripts/ci/verify-flyway-migrations.sh
./scripts/ci/verify-test-layers.sh
./mvnw test

docker info
./mvnw -Pintegration verify
./scripts/ci/assert-integration-test-results.sh target/failsafe-reports

cd ../..
```

运行 Admin 和小程序门禁：

```bash
pnpm --dir admin check
CI=true pnpm --dir admin build
pnpm --dir admin check:generated-imports

pnpm --dir miniprogram check

git diff --check
git status --short --branch
```

生成文件、格式化或构建导致工作区变化时，先审查并提交，再部署。部署脚本不会替你
`git commit` 或 `git push`；提交、推送、后端部署、Admin 部署和小程序上传是不同动作。

`SHOP_DEPLOY_SKIP_TESTS=true` 只允许用于同一个 Git SHA 已经完整通过两层后端测试后的受控
重试，不能作为日常或首次生产部署选项。

## 6. Generation 2 首次重建

> 警告：本节会造成停机和数据丢失。每台服务器必须分别批准；对 `txcloud` 的授权不能
> 自动用于 `shop`。本文故意不提供可直接复制的数据卷删除命令。

### 6.1 只读清点目标

以下以 `txcloud` 为例；检查 shop 时只把 SSH 目标替换为 `shop`：

```bash
ssh txcloud '
  hostname
  sudo docker compose ls
  sudo docker ps --all \
    --filter label=com.docker.compose.project=shop
  sudo docker volume ls \
    --filter label=com.docker.compose.project=shop
  ip route
'
```

记录 SSH 别名、主机 IP、Compose 项目名、容器、镜像和两个数据卷的完整名称。确认
`172.22.0.0/24`、`172.23.0.0/24`、`172.24.0.0/24` 没有与宿主机网络冲突。

### 6.2 停写、最终备份和回滚点

必须按顺序完成：

1. 取得“该目标数据允许丢弃”的业务确认；
2. 进入维护窗口并阻断旧系统写入，记录停写时间；
3. 在微信商户平台确认没有未结束支付、退款或待重试回调；
4. 只读查找现场真实备份脚本：

   ```bash
   ssh txcloud \
     'sudo find /opt/shop/shop-server/scripts -maxdepth 2 \
       -type f -name backup-mysql.sh -print'
   ```

5. 执行现场确认过的备份脚本，记录文件名、大小、时间和 SHA-256；
6. 对 `.sql.gz` 执行 `gzip -t` 和 sidecar 校验；
7. 把备份复制到另一故障域；
8. 在隔离 MySQL 8.4 中实际恢复成功；
9. 保存匹配的旧镜像、Compose、runtime、脚本和容器/卷清单，形成完整回滚点；
10. 停止旧后端，再次读取卷标签和完整卷名；
11. 由第二人复核，只删除明确批准的 MySQL/Redis 卷，不使用通配符或未展开变量。

不要把“文件存在”当成“备份可恢复”，也不要删除镜像、COS 素材、1Panel/OpenResty 或其他
Compose 项目。

### 6.3 部署空库基线

txcloud：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh txcloud
```

shop：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

默认 `remote-build` 在目标服务器构建镜像。如果服务器资源紧张而本机 Docker/buildx 与网络
可靠，可以改用：

```bash
SHOP_DEPLOY_TRANSPORT=image-stream \
  backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

脚本会使用唯一 `deploy_id`、候选清单和服务器发布锁，执行备份、原子切换、健康、Git SHA
和 Flyway 校验。它不会删除旧卷，也不会发布 Admin。

### 6.4 后端回环验收

以下以 `txcloud` 为例；验收 shop 时只把 SSH 目标替换为 `shop`：

```bash
ssh txcloud '
  cd /opt/shop/shop-server
  sudo docker compose \
    --env-file config/runtime/runtime.env \
    -f compose.prod.yaml ps
  curl --fail --silent --show-error \
    http://127.0.0.1:8080/actuator/health
  curl --fail --silent --show-error \
    http://127.0.0.1:8080/actuator/info
'
```

必须确认：

- MySQL、Redis、后端全部 healthy；
- Git SHA 是批准版本；
- `flywayVersion=7`；
- 空库基线 `.sql.gz` 和 `.sha256` 存在且验证通过；
- 8080、3306、6379 只绑定服务器回环；
- 公网 HTTPS、反向代理和真实客户端 IP 正常。

## 7. 部署 Admin 和首次引导 Super

构建并部署到明确目标：

```bash
pnpm --dir admin check
CI=true pnpm --dir admin build
pnpm --dir admin check:generated-imports

ops/deploy-admin.sh txcloud
# 或：ops/deploy-admin.sh shop
```

首次空库才执行一次 Super 引导：

```bash
backend/shop-server/scripts/config/bootstrap-admin.sh txcloud
# 或：backend/shop-server/scripts/config/bootstrap-admin.sh shop
```

临时凭据不会打印到终端，只写入本机 ignored、`0600` 文件：

```text
backend/shop-server/config/runtime/bootstrap-admin.<target>.txt
```

随后必须：

1. 使用临时凭据登录；
2. 立即修改密码；
3. 删除对应的 `bootstrap-admin.<target>.txt`；
4. 确认第二次引导被拒绝且数据库不变。

引导脚本不是普通密码重置工具，日常发布不能再次运行。

## 8. txcloud 先完成业务首录和真实验收

在 txcloud Admin 从零配置：

1. 开发/集成 COS；
2. 开发 AppID `wx2c59f00275b9057a` 及其 AppSecret；
3. 测试商户支付与回调；
4. 发货、服务动态；
5. 财务对账；
6. 商家主体、法律文档、联系方式、客服和业务内容。

必须验证开发 AppID 的登录、手机号、刷新与退出，以及支付、部分/全额退款、COS 公开/私有
访问与清理、拆分包裹、微信发货、服务动态、真实测试账单对账、Admin 权限和会话。

完成观察窗口和配置后备份，记录“通过 / 有条件通过 / 不通过”。未验证能力只能在明确记录
并保持关闭的条件下判定为“有条件通过”。txcloud 结果复核前不得开始 shop 的破坏性重建。

## 9. shop 首次部署和正式配置

只有 txcloud 结果已复核、shop 维护窗口和精确卷删除另行批准后，才能对 shop 重复第 6 节。

shop 后端、Admin 和 Super 命令分别是：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh shop

pnpm --dir admin check
CI=true pnpm --dir admin build
pnpm --dir admin check:generated-imports
ops/deploy-admin.sh shop

backend/shop-server/scripts/config/bootstrap-admin.sh shop
```

shop Admin 必须从零录入正式 COS、正式 AppID/AppSecret、正式支付、发货、服务动态、对账、
主体和业务内容。不得复制 txcloud 的凭据、OpenID、回调地址、支付配置或测试数据。

## 10. 小程序双 AppID 与发布

仓库跟踪的 `miniprogram/project.config.json` 固定为正式 AppID。个人开发 AppID 放在 Git
ignored 的 `miniprogram/project.private.config.json`；不要用一个只有 `appid` 的示例覆盖整个
私有配置文件。

发布前检查：

```bash
pnpm --dir miniprogram check
git diff --exit-code -- miniprogram/project.config.json
git diff --cached --exit-code -- miniprogram/project.config.json
```

每次在微信开发者工具上传前，必须在“详情 -> 基本信息”核对实际 AppID：

- 开发 AppID `wx2c59f00275b9057a` 只能连接 txcloud，禁止发布为正式版；
- 正式 AppID `wxd2c02e4864389d80` 固定连接 `api.junxiangshiping.cn`；
- 两个 Admin 分别保存对应 AppID 的 AppSecret；
- 小程序没有 SSH 部署脚本，体验版、审核、发布和版本回退都在微信平台完成。

生产发布前还要核对 request、socket、uploadFile、downloadFile 合法域名以及支付/退款回调。
正式体验版和正式版必须重新做真机登录、支付、退款、COS、客服、发货和物流验收。

## 11. Generation 2 日常发布

日常发布先完成第 5 节门禁，再按顺序发布 txcloud。txcloud 验收通过后才发布 shop。

后端：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh txcloud
backend/shop-server/scripts/deploy/deploy-backend.sh shop
```

仅在 Admin 有变化时：

```bash
pnpm --dir admin check
CI=true pnpm --dir admin build
pnpm --dir admin check:generated-imports
ops/deploy-admin.sh txcloud
ops/deploy-admin.sh shop
```

仅在小程序有变化时，重新运行小程序检查并在微信开发者工具中分别上传。

日常发布禁止删除数据卷、重新运行 runtime 初始化或 Super 引导。普通部署要求本机目标 runtime
五项秘密与服务器完全一致；密码和 key ring 轮换必须走独立维护窗口。

## 12. 备份、观察和回滚

Generation 2 手工备份入口：

```bash
ssh txcloud \
  'sudo /opt/shop/shop-server/scripts/deploy/backup-mysql.sh'

ssh shop \
  'sudo /opt/shop/shop-server/scripts/deploy/backup-mysql.sh'
```

每个 `.sql.gz` 都应有 `.sql.gz.sha256` sidecar。本机备份不能防止整机故障，必须安排异机
复制和定期隔离恢复演练。

发布后观察：

- 容器重启、CPU、内存、磁盘和日志增长；
- MySQL 连接、慢查询、锁等待和 Flyway；
- Redis 内存、AOF 和认证失败；
- 401/403/429/5xx、未知回调和验签失败；
- 支付、退款、发货、服务动态、对账和 COS 失败队列。

回滚边界：

- Admin 发布失败会尝试恢复上一个 `index`；
- 普通 G2 后端失败会尝试恢复旧清单和旧镜像，但不会撤销数据库迁移或自动回灌备份；
- G1 -> G2 首次切换失败，必须同时恢复匹配的旧镜像、旧 Compose/runtime 和旧数据库；
- 旧镜像不能连接 G2 数据库，G2 镜像也不能连接旧数据库；
- 小程序版本回退、Admin 回退和后端/数据库回滚互不替代；
- 不使用 Flyway `clean`、`repair` 或临时反向 SQL 伪装回滚。

只有真实平台证据、观察窗口、异机备份和可恢复回滚点均满足后，才能恢复全部生产流量并
在发布清单中记录最终结论。
