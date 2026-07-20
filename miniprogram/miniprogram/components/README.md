# 小程序组件边界

页面负责生命周期、路由参数、接口任务编排和跨组件状态；组件负责可复用的展示与局部交互。页面和组件都不能直接调用 `wx.request`，统一通过 `services/`。

## 通用 UI

- `navigation-bar`：自定义顶部导航
- `ui-button`、`ui-card`、`ui-state`、`ui-tag`：基础视觉组件
- `tab-placeholder`：尚未接通业务的 Tab 根页面状态

## 商品与首页业务组件

- `product-card`：首页和目录共用的商品卡片
- `catalog-browser`：分类、搜索、分页和目录状态；分类 Tab 与分类列表页复用
- `home-banner`、`home-category-grid`、`home-product-section`：首页区块
- `product-gallery`、`product-summary`、`product-parameters`：详情展示
- `sku-selector`、`wholesale-pricing`、`quantity-stepper`：详情选择与价格联动

## 维护规则

1. 后端 DTO 只进入 `types/`，页面展示模型由 `features/` 生成。
2. 跨页面复用或独立变化的业务区块必须放在 `components/`。
3. 组件通过事件上报路由意图，不直接决定页面栈；页面执行 `navigateTo` 或 `switchTab`。
4. 价格、库存、SKU 和批发规则保持为 `features/` 中的纯函数，并配套 Node 测试。
5. 组件样式使用 `styles/tokens.less`，不复制已有语义色。
