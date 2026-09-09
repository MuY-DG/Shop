# Shop 部署

首次部署和日常更新使用同一个入口：

```bash
./deploy.sh txcloud
./deploy.sh shop
```

省略范围参数时，脚本同时部署后端和 Admin。也可通过第二个参数分别发布：

```bash
./deploy.sh shop admin           # 只更新管理后台
./deploy.sh shop backend         # 只更新后端
./deploy.sh shop all             # 更新管理后台和后端，与省略参数相同
./deploy.sh shop auto            # 根据服务器上各组件的部署记录选择更新范围
./deploy.sh shop auto --plan     # 只读取服务器状态、显示计划，不构建或发布
```

`txcloud` 支持完全相同的参数。这里的 `admin` 指管理后台；小程序仍由微信开发者工具单独上传。

服务器准备完成后，空库首次部署和已升级环境的日常更新都只执行对应的一条命令。
后端启动时由 Flyway 自动建表、校验并执行尚未应用的结构迁移，无需手工执行 SQL。
空库没有历史业务数据，已完成升级的数据库也不会重复执行旧迁移，因此不需要重复进行
一次性的旧数据预检。恢复旧版本数据库备份属于数据恢复，需要单独确认版本与数据兼容性。

## 两个目标

| 参数 | 用途 | API | Admin |
| --- | --- | --- | --- |
| `txcloud` | 开发/集成 | `api.muybaby6.icu` | `admin.muybaby6.icu` |
| `shop` | 正式生产 | `api.junxiangshiping.cn` | `admin.junxiangshiping.cn` |

两台服务器使用相同的 Docker Compose，数据库、Redis、密钥和业务配置互相独立。

## 服务器只需准备一次

服务器由你自行完成以下工作，仓库不会接管：

1. 安装 1Panel、OpenResty 和 Docker；Docker Compose 版本至少为 `2.33.1`。
2. 配置本机 SSH 别名 `txcloud`、`shop`，并允许部署用户免交互执行 `sudo`。
3. 在 1Panel 分别创建 API 网站和 Admin 网站，配置 DNS 与 HTTPS。
4. 确认 1Panel 的网站目录为
   `/opt/1panel/www/sites/<域名>`。

部署只通过 SSH 操作服务器，不会登录 1Panel 或调用其 API，因此 1Panel 的管理端口、
安全入口和密码不会被脚本使用。脚本会检查两个网站目录和公开路由，但不会创建网站、
修改 OpenResty 或申请证书。

## 1Panel OpenResty 首次配置

API 创建为“反向代理网站”，Admin 创建为“静态网站”。两台服务器使用相同配置，只替换
域名：

| 目标 | API 域名 | Admin 域名 |
| --- | --- | --- |
| `txcloud` | `api.muybaby6.icu` | `admin.muybaby6.icu` |
| `shop` | `api.junxiangshiping.cn` | `admin.junxiangshiping.cn` |

在 1Panel 进入“网站 → 对应网站 → 配置 → 资源”编辑当前网站的 OpenResty 配置
（参见 [1Panel 官方说明](https://1panel.cn/docs/v2/user_manual/websites/website_config_other/)）。只修改
现有 HTTPS `server {}` 或现有反向代理规则，不要新建第二个同域名网站或重复的
`location`。宿主机网站目录是 `/opt/1panel/www/sites/...`，OpenResty 配置中的挂载路径
是 `/www/sites/...`。

### API 网站

进入 API 网站的“配置 → 资源”，打开已有的 `proxy/root.conf` 根代理规则。如果主配置
只有下面这类 `include`，真正的代理内容就在这个文件中；不要在主配置中再添加第二个
`location /`，也不要通过“反向代理”页面新建同路径规则：

```nginx
include /www/sites/<API 域名>/proxy/*.conf;
```

将原有 `location /` 或 `location ^~ /` 替换为下面两个块：

```nginx
location = /realtime {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;

    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;

    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 75s;
}

location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;

    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;

    proxy_set_header Connection "";
    proxy_read_timeout 75s;
}
```

### Admin 网站

进入 Admin 网站的“配置 → 资源 → 配置文件”。保留 1Panel 生成的监听、证书、日志和
安全配置，确认静态根目录和首页如下，并把占位符替换为表格中的 Admin 域名：

```nginx
root /www/sites/<Admin 域名>/index;
index index.html;
```

在同一个 `server {}` 内加入以下四个块；已有同名 `location` 时替换旧块：

```nginx
		location = /realtime {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;

    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;

    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 75s;
		}

		location ^~ /admin/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;

    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;

    proxy_set_header Connection "";
    proxy_read_timeout 75s;
		}

		location ^~ /assets/ {
    try_files $uri =404;
		}

		location / {
    try_files $uri $uri/ /index.html;
		}
```

所有 `proxy_pass` 末尾都不能再加 `/`，否则可能剥掉后端需要的 `/admin/` 路径。
`X-Forwarded-For` 必须直接设为 `$remote_addr`，不能使用 1Panel 常见的
`$proxy_add_x_forwarded_for` 追加客户端传入的旧值。保存后让 1Panel 完成语法检查和
OpenResty 重载。

配置完成后可先检查 Admin API：

```bash
curl -fsS https://admin.muybaby6.icu/admin/auth/registration
# shop 使用 https://admin.junxiangshiping.cn/admin/auth/registration
```

正常响应包含 `"code":200`。部署脚本还会只读检查 Admin 随机深链的 SPA 回退，以及
API/Admin `/realtime` 是否路由到后端。它会使用不可能命中真实票据的无效 ticket，后端
返回 `401` 表示路由正确，不代表部署失败。这个检查不会创建票据，也不等同于真实
WebSocket 已完成 `101` 握手；真实连接可在登录 Admin 后通过浏览器开发者工具的
`Network → WS` 查看。如果部署因此处配置错误而退出，修正配置后重新执行同一条
`./deploy.sh <target>` 即可。

## 本机准备

所有范围都需要 Git、SSH 和 `shasum`，实际发布还需要 tar。
发布 Admin 才需要 Node.js、pnpm；发布后端才需要本机 OpenSSL、`htpasswd` 和目标服务器 Docker。
`--plan` 只读取记录和已部署组件的状态，不初始化密钥、不构建、不上传。
需要发布 Admin 时，先安装项目依赖：

```bash
pnpm --dir admin install --frozen-lockfile
```

目标运行密钥保存在以下 Git 忽略文件：

```text
backend/shop-server/config/runtime/txcloud.env
backend/shop-server/config/runtime/shop.env
```

首次部署时，如果本机和服务器都没有目标文件，`deploy.sh` 会自动生成。服务器已有运行
密钥而本机文件丢失时，脚本会停止，避免用新密码或新主密钥覆盖现有环境。本机与服务器
文件内容不一致时也会停止。

## 部署

确保准备发布的代码已经提交，然后执行目标命令：

```bash
./deploy.sh txcloud
# 或
./deploy.sh shop
```

`all` 或省略范围时，一次执行会完成：

1. 读取组件部署记录，显示计划，检查 SSH、Docker、Compose 和 1Panel 网站目录。
2. 校验目标运行密钥。
3. 构建 Admin，并确认自动导入声明没有变化。
4. 上传 Compose、运行密钥、后端构建上下文和 Admin 静态文件。
5. 在服务器构建 `shop-server:local`。
6. 初始化持久化日志卷权限，再启动或更新 MySQL、Redis 和后端；已有 Docker 数据卷会继续复用。
7. 用新 Admin 构建产物替换网站 `index` 目录。
8. 分组件检查后端健康和 Git SHA、API HTTPS、Admin 静态文件、SPA 回退、
   `/admin/` 代理及两个域名的 `/realtime` 后端路由，每个组件验收成功后独立保存记录。
9. 全新空库会自动引导一次 Super；日常部署会跳过。首次后端成功记录在引导完成后保存。

### 分别发布

- `admin` 只构建、上传管理后台静态文件，切换站点并验收页面、SPA 回退、Admin API 和
  实时代理。它要求已有后端健康可用，但不要求后端 SHA 等于本次仓库 SHA；不读取或上传
  运行密钥，不调用 Docker，不查询数据库或执行 Super 初始化。
- `backend` 只上传后端构建上下文、Compose 和经过一致性校验的运行密钥，构建镜像并更新
  后端。它仍会确认 MySQL、Redis 健康、初始化日志卷权限和执行正常 Flyway 启动迁移；
  不构建或替换 Admin，也不要求本机安装 pnpm。已有 Admin 网站时会检查其 API 和实时代理。
- `all` 显式重新发布两个组件，包括它们的构建输入没有变化的情况；单独指定 `admin` 或
  `backend` 也会重新发布所指定的组件。只有 `auto` 会自动跳过。

若显式只发布一端，而另一端的记录缺失或构建输入有变化，计划会提示另一端仍有待确认或
待发布内容。涉及配套接口、权限菜单或数据结构变更时使用 `all`；目录变化检测不能证明
前后端接口兼容。

### 自动判断与版本记录

每台服务器独立保存以下记录，内容为版本号、构建输入指纹、完整 Git SHA、构建时间和
验收用的文件摘要，不包含运行密钥：

```text
/opt/shop/.deploy-state/admin.state
/opt/shop/.deploy-state/backend.state
```

指纹基于已提交文件的路径、模式和内容计算：

- Admin：`admin/`、`.nvmrc`。
- 后端：Dockerfile、`.dockerignore`、`pom.xml`、`src/`、`compose.prod.yaml` 和运行配置辅助脚本。
- 两端共同包含 `deploy.sh` 与 `scripts/deploy/` 下的两个执行辅助脚本。

因此，`auto` 比较的是当前构建输入与目标服务器上该组件最后一次验收成功的输入，跨多个
提交也有效。根目录文档、`docs/` 或小程序的单独修改不会触发这两个组件更新；组件目录内
的文件采用保守检测，包括测试和说明文件。实际发布后端时，运行密钥继续采用原有一致性检查，不能通过
`auto` 自动轮换密钥。

读取记录时还会核对 Admin 当前 `index.html`，以及后端健康、实际 SHA/构建时间和服务器
Compose 文件摘要。记录缺失、格式不兼容或与实际运行状态不符时，该组件视为需要部署。
两端均没有记录的旧环境或首次环境，第一次执行 `auto` 会完整部署以建立基准；也可先显式
发布某个组件，只为该组件建立记录。

前端单独更新后，后端记录保留原来的 SHA 和构建时间，这是正常状态。两个组件都未变化时，
`auto` 只检查并退出。需要强制重新构建时使用显式范围。

计划读取和远端发布共用原来的目标部署锁。远端发布拿到锁后会重新核对计划时的记录；
如果构建期间其他部署改变了组件状态，本次会停止，请重新运行以重新计算计划。
不要删除锁文件来解除部署锁。

一个组件开始切换运行版本时会使它的旧记录失效，验收通过后再原子写入新记录。
例如后端已成功但 Admin 验收失败，后端的新记录仍保留，Admin 不会被误记为成功。
脚本不自动回滚或操作数据库备份；首次 Super 引导异常时，仍按下方临时凭据说明处理。

部署逻辑的隔离回归验证可以在本机执行，不需要 SSH、Docker 或真实服务器：

```bash
python3 -m unittest discover -s scripts/tests -p 'test_deploy.py' -v
```

这些验证使用临时仓库和替代命令，覆盖部署范围、上传内容、版本记录、失败及并发状态变化；
不能代替服务器上真实 Docker、1Panel 和 HTTPS 的发布验收。

首次 Super 凭据只写入本机：

```text
backend/shop-server/config/runtime/bootstrap-admin.<target>.txt
```

首次登录并修改密码后删除该文件。
如果服务器已经重置为全新空库，同名的旧环境临时凭据会由部署脚本先删除再重新生成。
如出现 `bootstrap-admin.<target>.pending.*.txt`，表示数据库提交结果无法确认；非空环境
会停止部署，必须先核对 Super 状态并处理该文件。

## 日常查看

```bash
ssh shop 'cd /opt/shop/shop-server && sudo docker compose --env-file config/runtime/runtime.env -f compose.prod.yaml ps'
ssh shop 'cd /opt/shop/shop-server && sudo docker compose --env-file config/runtime/runtime.env -f compose.prod.yaml logs -f --tail=200 shop-server'
```

查看 txcloud 时把 `shop` 替换为 `txcloud`。

## 明确边界

- 数据库定时备份由 1Panel 任务直接写入腾讯云 COS；部署脚本不创建、校验或恢复备份。
- 数据库结构升级由后端启动时的 Flyway 自动处理；部署脚本不提供停服、业务数据导入、
  跨库迁移、数据回滚或应用回滚。
- 部署脚本不安装服务器软件、不管理 1Panel/OpenResty/证书，也不上传小程序。
- 发布失败会以非零状态退出并保留现场结果；修正原因后重新执行同一条部署命令。

业务验收见 [smoke-checks.md](smoke-checks.md)。
