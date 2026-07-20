# 灶香集微信小程序

`miniprogram` 是基于现有 Spring Boot 后端重新搭建的原生微信小程序，业务数据以当前后端接口为准。

## 当前阶段：首页 02

- TypeScript + Less 严格模式与 pnpm 工具链
- 集中式颜色、排版、间距、圆角、阴影设计令牌
- 自定义导航、按钮、卡片、标签、加载/空/错状态组件
- 环境配置、结构化 `ApiError`、JSON 请求层
- 微信会话持久化、刷新令牌 single-flight、HTTP 401 最多恢复一次
- 公开 `GET /app/home` schema v2 接入（Redis 缓存由后端透明处理）
- 真实轮播、分类、`HOT/FEATURED` 与 `RECOMMENDED/COMPACT` 商品区块
- 首屏骨架、空状态、失败重试、下拉刷新与图片缺失降级
- 首页业务路径白名单；详情和分类页完成前安全提示，不执行外部链接

## 目录约定

```text
miniprogram/
  components/   无业务状态的通用 UI 组件
  config/       环境和运行时配置
  constants/    API 路径等稳定常量
  features/     后端 DTO 到页面 view-model 的纯映射
  pages/        页面，只负责展示与交互编排
  services/     会话与按业务域组织的接口
  styles/       tokens、mixins 等设计基础
  types/        与后端 DTO 对齐的 TypeScript 契约
  utils/        HTTP、错误和系统能力适配
```

页面不能直接调用 `wx.request`，也不要在业务 Less 中重复定义已有语义色；分别通过 `utils/request.ts` 和 `styles/tokens.less` 使用统一能力。

## 本地检查

```bash
pnpm install --frozen-lockfile
pnpm check
```

`check` 会依次执行生产代码类型检查、测试代码类型检查，以及会话并发/401 恢复、首页契约映射、价格和安全路径等测试。

微信开发者工具导入当前目录即可预览。开发 API 默认指向 `https://pay-dev.muybaby6.icu`，配置入口为 `miniprogram/config/app-config.ts`。

当前以 WebView 和基础库 2.32.3 为兼容基线。正式版启动时会拒绝 `development` 配置；发布前必须设置生产环境与 HTTPS API，并确认微信公众平台的 request 合法域名 `https://pay-dev.muybaby6.icu` 与图片/downloadFile 合法域名 `https://oss.muybaby6.icu`（正式环境请替换为对应域名）。

## 下一阶段

补齐分类列表和商品详情页，让首页已校验的业务路径可以正式跳转；随后再按购物车、订单、个人中心逐域推进。
