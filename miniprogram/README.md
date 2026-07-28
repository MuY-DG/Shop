# 灶香集微信小程序

`miniprogram` 是基于现有 Spring Boot 后端重新搭建的原生微信小程序，业务数据以当前后端接口为准。

## 当前阶段：售后闭环 08

- TypeScript + Less 严格模式与 pnpm 工具链
- 集中式颜色、排版、间距、圆角、阴影设计令牌
- 自定义导航、按钮、卡片、标签、加载/空/错状态组件
- 环境配置、结构化 `ApiError`、JSON 请求层
- 用户主动微信登录、已注册用户免重复手机号授权、刷新令牌 single-flight 与 HTTP 401 最多恢复一次
- 公开 `GET /app/home` schema v3 接入（Redis 缓存静态内容，可售状态由后端实时覆盖）
- 真实轮播、分类、`HOT/FEATURED` 与 `RECOMMENDED/COMPACT` 商品区块
- 首屏骨架、空状态、失败重试、下拉刷新与图片缺失降级
- 首页业务路径白名单，不执行外部链接
- 分类商品列表、关键词搜索、分页加载、销量与可售状态
- 商品详情图片、参数、SKU、可售状态、数量和批发阶梯价联动
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
- 收货地址列表、新增编辑、默认地址与安全删除
- 高德小程序 SDK 地图选址、当前定位、附近地点、关键词搜索和收货地址回填
- 领券中心、我的优惠券与待使用/已使用/已过期状态筛选
- 收藏商品与浏览记录列表、分页、单项移除和记录清空
- 个人中心微信原生在线客服入口
- 订单详情售后入口、整单全额仅退款申请与重复申请拦截
- 订单私有售后凭证选择、大小校验、上传鉴权恢复与提交绑定
- 售后记录分页、六状态进度详情、审核说明与退款结果查询

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

`check` 会依次执行生产代码类型检查、测试代码类型检查，以及主动登录/手机号按需授权、会话并发/401 恢复、首页契约映射、购物车选择、CART/DIRECT 结算参数、售后状态与凭证上传、价格和安全路径等测试。

微信开发者工具导入当前目录即可预览。开发 API 默认指向 `https://pay-dev.muybaby6.icu`，配置入口为 `miniprogram/config/app-config.ts`。

当前以 WebView 和基础库 2.32.3 为兼容基线。正式版启动时会拒绝 `development` 配置；发布前必须设置生产环境与 HTTPS API，并在微信公众平台分别配置：

- request 合法域名：业务 API 域名与 `https://restapi.amap.com`
- uploadFile 合法域名：业务 API 域名（头像和售后凭证上传依赖此配置）
- downloadFile 合法域名：`https://oss.muybaby6.icu`、`https://thirdwx.qlogo.cn`、`https://wx.qlogo.cn` 和 `https://mmbiz.qpic.cn`

正式环境请将上述业务域名替换为实际生产域名。

## 下一阶段

补齐订单物流信息展示，再进行真机登录、支付、发货、退款和生产环境发布验收。
