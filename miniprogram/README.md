# 灶香集微信小程序

`miniprogram` 是基于现有 Spring Boot 后端重新搭建的原生微信小程序，业务数据以当前后端接口为准。

## 当前阶段：支付与订单中心 06

- TypeScript + Less 严格模式与 pnpm 工具链
- 集中式颜色、排版、间距、圆角、阴影设计令牌
- 自定义导航、按钮、卡片、标签、加载/空/错状态组件
- 环境配置、结构化 `ApiError`、JSON 请求层
- 微信会话持久化、刷新令牌 single-flight、HTTP 401 最多恢复一次
- 公开 `GET /app/home` schema v2 接入（Redis 缓存由后端透明处理）
- 真实轮播、分类、`HOT/FEATURED` 与 `RECOMMENDED/COMPACT` 商品区块
- 首屏骨架、空状态、失败重试、下拉刷新与图片缺失降级
- 首页业务路径白名单，不执行外部链接
- 分类商品列表、关键词搜索、分页加载与库存状态
- 商品详情图片、参数、SKU、库存、数量和批发阶梯价联动
- 首页轮播、分类和商品卡片已打通列表/详情浏览路径
- 自定义底部导航：首页、分类、购物车、我的
- 分类 Tab 与分类列表页复用同一商品目录业务组件
- 首页拆分轮播、分类宫格和商品区块，详情拆分画廊、摘要、参数、SKU、批发价和数量组件
- 真实购物车列表、商品勾选、数量调整、失效状态、批发价、移除和清空
- 购物车 `CART` 与商品详情 `DIRECT` 共用安全结算参数和订单预览页
- 账户收货地址选择、微信地址导入、服务端最优优惠券与运费实时预览
- 幂等订单提交与支付成功页；购物车订单创建后由后端清理选中项
- 微信 JSAPI 支付参数获取、`wx.requestPayment` 拉起与服务端支付结果同步
- 确认订单页原位切换等待支付、服务端时限倒计时、继续支付与安全取消
- 已取消订单支持软删除和按当前库存、价格重新购买
- 订单中心状态分组、分页列表、订单详情和确认收货
- 个人中心订单入口与待付款、待发货、待收货、已完成快捷入口

## 目录约定

```text
miniprogram/
  custom-tab-bar/ 微信四入口自定义底部导航
  components/   可复用的通用 UI 与业务组件
  config/       环境和运行时配置
  constants/    API 路径等稳定常量
  features/     后端 DTO 到页面 view-model 的纯映射
  pages/        页面，只负责展示与交互编排
  services/     会话与按业务域组织的接口
  styles/       tokens、mixins 等设计基础
  types/        与后端 DTO 对齐的 TypeScript 契约
  utils/        HTTP、错误和系统能力适配
```

页面不能直接调用 `wx.request`，也不要在业务 Less 中重复定义已有语义色；分别通过 `utils/request.ts` 和 `styles/tokens.less` 使用统一能力。页面只负责任务编排与路由，组件边界和维护规则见 `miniprogram/components/README.md`。

## 本地检查

```bash
pnpm install --frozen-lockfile
pnpm check
```

`check` 会依次执行生产代码类型检查、测试代码类型检查，以及会话并发/401 恢复、首页契约映射、购物车选择、CART/DIRECT 结算参数、价格和安全路径等测试。

微信开发者工具导入当前目录即可预览。开发 API 默认指向 `https://pay-dev.muybaby6.icu`，配置入口为 `miniprogram/config/app-config.ts`。

当前以 WebView 和基础库 2.32.3 为兼容基线。正式版启动时会拒绝 `development` 配置；发布前必须设置生产环境与 HTTPS API，并确认微信公众平台的 request 合法域名 `https://pay-dev.muybaby6.icu` 与图片/downloadFile 合法域名 `https://oss.muybaby6.icu`（正式环境请替换为对应域名）。

## 下一阶段

完善个人中心的地址、优惠券、收藏与浏览记录入口，再按消息、售后和客服逐域推进。
