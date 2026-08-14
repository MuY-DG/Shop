# Shop Admin

「灶香集」微信小程序电商的管理后台：Vue 3 + Vite + Element Plus，
基于 [Art Design Pro](https://github.com/Daymychen/art-design-pro)（MIT）模板二次开发。

## 环境要求

- Node.js >= 20.19.0
- pnpm >= 8.8.0

## 常用命令

```bash
pnpm install               # 安装依赖（CI 使用 --frozen-lockfile）
pnpm dev                   # 本地开发（Vite 开发服务器 + 代理）
pnpm build                 # vue-tsc 类型检查 + 生产构建
pnpm check                 # typecheck + lint + test
pnpm test                  # 运行测试
pnpm check:generated-imports  # 校验自动导入生成物与仓库一致
```

## 环境变量

三个环境文件均已入库（不含密钥）：

| 文件 | 用途 |
| --- | --- |
| `.env` | 通用配置；`VITE_ACCESS_MODE=backend` 启用后端驱动菜单与 RBAC |
| `.env.development` | 开发环境：`VITE_API_PROXY_URL` / `VITE_ADMIN_API_PROXY_URL` 指向 Vite 代理目标 |
| `.env.production` | 生产环境：同源路径，由边缘网关把 `/admin/**` 转发到后端 |

## 后端契约

- 所有 JSON API 使用信封：`{ "code": 200, "msg": "success", "data": ... }`。
- 列表页数据在 `data` 内：`{ "records": [], "total": 0, "current": 1, "size": 10 }`。
- Snowflake ID 一律以字符串传输，避免 JavaScript 精度丢失。

## 后端驱动路由与 RBAC

菜单和权限由后端返回，前端负责渲染与守卫：

- `src/router/core/ComponentLoader.ts`：按白名单 `import.meta.glob` 打包视图，
  新增业务视图目录必须在此登记，否则不会被生产构建包含。
- `src/router/core/MenuProcessor.ts`：把后端菜单记录转换为路由。
- `src/router/core/RoutePermissionValidator.ts` / `RouteRegistry.ts`：路由权限校验。
- iframe 菜单统一使用 `src/views/outside/Iframe.vue`。

## 生成文件必须入库

`unplugin-auto-import` / `unplugin-vue-components` 的生成物是可重复构建输入，
必须随代码一起提交：

- `.auto-import.json`
- `src/types/import/auto-imports.d.ts`
- `src/types/import/components.d.ts`

CI 在生产构建后运行 `pnpm check:generated-imports`，校验生成结果无差异，
保证全新 clone 不依赖预先启动 Vite。

## 目录结构

```text
src/
  api/        后端 API 封装
  views/      业务页面（后端菜单按组件路径加载）
  router/     静态路由、异步路由与后端菜单处理
  components/ 通用组件
  store/      Pinia 状态
  config/     运行配置
```

## 相关文档

- [开发环境与命令](../docs/dev-setup.md)
- [生产发布检查清单](../docs/production-release-checklist.md)
- [后端架构与演进规则](../docs/backend-architecture.md)

## 许可

本项目基于 Art Design Pro（MIT License，见 `LICENSE`）二次开发。
