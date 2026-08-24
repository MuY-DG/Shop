# Shop 环境与边缘入口

项目只保留三套明确目标。txcloud 与 shop 的容器结构和 Spring 配置完全相同，差异只存在
于各自的 runtime manifest、域名和外部平台配置。

| 目标 | 用途 | Spring Profile | 本机私密文件 |
| --- | --- | --- | --- |
| `local` | 本机开发 | `local` | `backend/shop-server/config/runtime/local.env` |
| `txcloud` | 开发/集成服务器 | `server` | `backend/shop-server/config/runtime/txcloud.env` |
| `shop` | 正式生产服务器 | `server` | `backend/shop-server/config/runtime/shop.env` |

上述三个私密文件结构一致、互不复制且不会提交 Git。微信、小程序、支付、COS、服务动态
和业务运行开关均在 Admin 中配置；runtime manifest 只保存数据库、Redis 和数据库敏感
字段的主加密密钥。

## OpenResty

1Panel 网站记录继续负责域名和证书，版本化的参考配置位于 `ops/openresty/`。修改仓库
文件不会自动修改服务器。

- API 域名把普通 HTTP 流量转发到 `127.0.0.1:8080`，`/realtime` 支持 WebSocket。
- Admin 域名提供 SPA 静态文件，把 `/admin/**` 和 `/realtime` 转给后端，并对前端路由
  回退到 `index.html`。
- OpenResty 必须覆盖 `X-Forwarded-For`；后端只信任 Compose 固定 edge 网关
  `172.23.0.1/32`，该边界受版本控制而不再由环境变量覆盖。

1Panel、Docker 的安装以及 1Panel 密码轮换由服务器管理员完成，仓库不再提供相关脚本。

## 新环境

在本机生成并校验目标清单：

```bash
cd backend/shop-server
./scripts/config/init-runtime-env.sh shop
./scripts/config/validate-runtime-env.sh shop
```

txcloud 把参数改成 `txcloud`。不要把一个环境的数据库密码或主加密密钥复制到另一个环境。

这次第二代 Flyway 基线与旧 V1-V107 数据库不兼容。只有在确认目标数据可以全部丢弃时，
才可在对应服务器删除旧 Compose 数据卷；该动作不由常规部署脚本自动执行。重建后，从
已提交且工作区干净的版本部署：

```bash
backend/shop-server/scripts/deploy/deploy-backend.sh shop
pnpm --dir admin check
CI=true pnpm --dir admin build
pnpm --dir admin check:generated-imports
ops/deploy-admin.sh shop
backend/shop-server/scripts/config/bootstrap-admin.sh shop
```

一次性 Super 凭据只写到本机 ignored 文件
`backend/shop-server/config/runtime/bootstrap-admin.shop.txt`，不会在终端打印。首次登录并
修改密码后删除该文件。txcloud 使用相同命令并把目标替换为 `txcloud`。

## 外部平台

小程序合法域名、AppID/Secret、微信支付商户绑定与回调、ICP、COS CNAME/CORS/Referer
都需要在相应平台人工核验。后端健康、Flyway 成功或 Admin 页面可访问，都不能替代真实
登录、支付、退款、发货、服务动态和对象存储验收。
