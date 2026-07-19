# Shop 运营管理与经营统计实施计划

日期：2026-07-15

## 目标

交付真实“运营管理”菜单、七个经营统计页面、统一统计接口，以及流量、活跃、支付尝试、成本快照和
低库存阈值等缺失事实采集。删除现有控制台演示数据，历史可回算指标自动回算，不能回算的指标从采集
启用日起明确展示。

## 当前基线与边界

- 当前分支为 `main@b900e2f8`，开始实施时工作树干净，仓库没有远端。
- 最新 Flyway 迁移为 V30；本阶段只新增迁移，不修改已应用迁移。
- 后台使用 backend 菜单模式，菜单、权限、角色关联与路由测试必须同步变更。
- 订单、支付、退款、商品、用户、优惠券、履约、售后与客服事实足以支撑历史核心统计。
- 当前控制台、analysis 与 ecommerce 页面均为演示数据，不作为统计数据源。
- 本阶段允许修改 `backend/`、`admin/` 与 `miniprogram/`；小程序改动仅限统计所需的匿名访客、会话、
  页面/交互埋点和订单归因字段，不重构现有业务页面。

## Task 1：统计迁移、RBAC 与索引

先新增迁移/模式测试，覆盖：

1. 将菜单 100/101 升级为“运营管理/运营总览”，新增六个统计子菜单和七个只读权限。
2. 保留已有控制台角色的父菜单与总览授权；仅超级管理员默认获得其余统计权限。
3. 新增 `analytics_event`、`app_user_daily_activity`、`payment_attempt`，并为手机号授权补首次事实时间。
4. 扩展 `order_item` 经营快照和 `product_sku.low_stock_threshold`。
5. 补支付、退款、用户、订单商品、库存、优惠券和行为查询索引。
6. 更新 Flyway 最新版本、菜单树、访问目录和 MySQL 迁移兼容测试。

## Task 2：统计查询契约与失败测试

新增 `AdminOperationsControllerTest`，先覆盖：

- 七个页面的权限 403 与成功响应。
- 默认/自定义日期、上海时区边界、最大范围和粒度校验。
- 支付日与退款日独立归属，支付 GMV、退款、净收款、订单数、买家数和客单价。
- 已退款订单仍保留原支付事实，虚拟销量不进入商品统计。
- 新增/活跃/支付/复购用户，零分母返回空比例。
- 商品排行、分类排行、低库存和旧订单成本覆盖率。
- 优惠券来源、使用、过期和模板排行。
- 履约、售后、退款与客服指标。
- 行为采集前后的流量可用状态和采集起始时间。
- 注册 cohort 的 D1/D7/D30 留存、三段交易耗时与手机号授权采集边界。
- 所有大整数 ID 以字符串返回。

## Task 3：经营统计后端实现

1. 新增统一日期查询、区间、粒度和元数据 DTO。
2. 新增 `AdminOperationsController`、页面响应 DTO 和 `OperationsStatisticsService`。
3. 使用事实时间戳和参数化 SQL 完成七页聚合；当前待办与区间统计分开。
4. 每页一次返回摘要、趋势、分布和受限排行；明细跳转现有管理页面。
5. 所有比例在后端计算；金额保持分，空值与不可回算状态显式返回。

## Task 4：订单成本、金额分摊、支付尝试与库存阈值

先扩展现有订单、支付和商品测试，再实现：

1. 新订单创建时快照 SKU 单位成本和行成本。
2. 按稳定余数算法分摊优惠、运费与实付金额，验证行合计严格等于订单头。
3. 成本缺失保持空值，不按零成本计算毛利。
4. 支付调用前创建 `payment_attempt`，预下单成功、失败、回调支付和超时关闭同步状态。
5. 商品后台 API、编辑器和 SKU 矩阵支持 `lowStockThreshold`，默认 10 且不能为负。

## Task 5：行为采集与用户日活

先新增 `AppAnalyticsControllerTest`、活动拦截测试和小程序跟踪单元测试，再实现：

1. 新增受控事件枚举、批量请求 DTO、幂等持久化服务和匿名可访问接口。
2. 有合法 App Token 时只从服务端 principal 关联用户；拒绝客户端支付/订单结果事件。
3. 校验商品引用，对匿名批量入口实施 Redis 双维度限流，并用原子幂等写入避免并发重复。
4. 已认证 `/app/**` 请求按上海日期 upsert 用户日活。
5. 小程序生成并持久化匿名访客 ID、每次启动会话 ID和入口场景。
6. 补齐商品搜索入口，上报启动、页面访问、商品详情、搜索和开始结算；失败不阻断业务，坏事件不阻塞
   后续队列。
7. 订单提交携带受控归因上下文，服务端将会话/访客与订单事实关联。
8. 原始行为事件默认保留 400 天并按批次定时清理。

## Task 6：管理后台菜单、API 与通用组件

1. 新增 operations API 和类型，统一日期查询与页面响应。
2. 新增日期工具栏、指标卡、采集状态、趋势图、分布图、排行表和待办组件。
3. 修正真实零、不可用、历史不可回算和请求失败的展示语义。
4. 新增旧 `/dashboard/console` 跳转，更新 frontend 模式备用路由、快速入口和中英文文案。
5. 页面路由与接口分别使用对应 `operation:*:read` 权限。

## Task 7：七个统计页面

依次实现并逐页验证：

1. 运营总览。
2. 交易统计。
3. 商品统计。
4. 用户统计。
5. 流量转化。
6. 营销统计。
7. 服务统计。

所有卡片、待办和排行提供口径提示；可行动数据跳转现有订单、商品、用户、售后或客服页面并携带筛选。

## Task 8：聚焦验证、复核与修复

后端聚焦：

```bash
cd backend/shop-server
./mvnw -Dtest='OperationsAnalyticsSchemaTest,AdminOperationsControllerTest,AppAnalyticsControllerTest,AdminMenuControllerTest,AdminRbacSchemaTest,CommerceFulfillmentMySqlMigrationTest' test
```

小程序：

```bash
cd miniprogram
pnpm test
pnpm test:typecheck
pnpm typecheck
```

管理端：

```bash
cd admin
pnpm exec eslint <本阶段新增或修改的脚本与 Vue 文件>
pnpm exec stylelint <本阶段新增或修改的 Vue 文件>
pnpm typecheck
pnpm build
```

每个切片完成后执行独立代码审查，修复后重跑聚焦测试。

## Task 9：完整回归与交付检查

```bash
cd backend/shop-server
./mvnw test

cd ../../admin
pnpm typecheck
pnpm build

cd ../miniprogram
pnpm test
pnpm test:typecheck
pnpm typecheck

cd ..
git diff --check
git status --short --branch
```

自动化验证与用户拥有的真实 MySQL/后台/微信开发者工具 smoke 分开记录；不因缺少本地人工 smoke 阻断
已通过的自动化交付。
