# Shop 部署

首次部署和日常更新使用同一个入口：

```bash
./deploy.sh txcloud
./deploy.sh shop
```

脚本同时部署后端和 Admin。小程序仍由微信开发者工具单独上传。

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

OpenResty 只需满足这些路由：

- API 网站：全部请求反向代理到 `http://127.0.0.1:8080`，其中
  `/realtime` 必须开启 WebSocket。
- Admin 网站：静态根目录使用该网站的 `index` 子目录。
- Admin 的 `/admin/` 反向代理到 `http://127.0.0.1:8080`。
- Admin 的 `/realtime` 反向代理到同一后端并开启 WebSocket。
- Admin 其他路径回退到 `/index.html`，供前端路由刷新。
- 代理必须覆盖 `X-Forwarded-For` 为客户端 IP，不能追加未经信任的旧值。

脚本会检查两个网站目录是否存在；不会创建网站、修改 OpenResty 或申请证书。

## 本机准备

需要 Git、SSH、Node.js、pnpm、OpenSSL、`htpasswd`、tar 和 `shasum`。先安装项目依赖：

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

一次执行会完成：

1. 检查 SSH、Docker、Compose 和 1Panel 网站目录。
2. 校验目标运行密钥。
3. 构建 Admin，并确认自动导入声明没有变化。
4. 上传 Compose、运行密钥、后端构建上下文和 Admin 静态文件。
5. 在服务器构建 `shop-server:local`。
6. 启动或更新 MySQL、Redis 和后端；已有 Docker 数据卷会继续复用。
7. 用新 Admin 构建产物替换网站 `index` 目录。
8. 检查后端健康、Git SHA、API HTTPS 和 Admin HTTPS。
9. 全新空库会自动引导一次 Super；日常部署会跳过。

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
- 部署脚本不提供停服流程、数据迁移、数据回滚或应用回滚。
- 部署脚本不安装服务器软件、不管理 1Panel/OpenResty/证书，也不上传小程序。
- 发布失败会以非零状态退出并保留现场结果；修正原因后重新执行同一条部署命令。

业务验收见 [smoke-checks.md](smoke-checks.md)。
