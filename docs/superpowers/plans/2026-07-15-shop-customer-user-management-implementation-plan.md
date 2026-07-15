# Shop 小程序用户管理与手动发券实施计划

日期：2026-07-15

## 目标

在不改动小程序客户端的前提下，交付后台小程序用户列表、单用户手动发券、库存/限领并发保护、
RBAC 和发放审计的完整闭环。

## 当前基线与边界

- `app_user` 已包含用户名称、手机号授权、状态和登录时间。
- `coupon_template`、`user_coupon` 与 `coupon_claim_record` 已承载用户领券和订单优惠券生命周期。
- “系统管理 → 用户管理”当前管理的是 `admin_user`，不能直接复用为小程序用户页面。
- 当前工作区已有未提交的指定商品券与商品编辑改动，且涉及 `AdminCouponService` 和优惠券页面；
  本计划通过独立的客户管理服务和页面做增量集成，不覆盖或回退现有修改。
- 只实现后端与管理后台；不修改 `miniprogram/`。

## Task 1：迁移、菜单与 RBAC

1. 新增 V27 迁移，扩展优惠券领取记录的发放来源、操作管理员和备注。
2. 新增用户管理菜单以及 `customer:user:read`、`customer:coupon:issue` 权限。
3. 默认将菜单与权限授予超级管理员，并挂接菜单权限资源。
4. 更新 Flyway 版本断言和菜单路由测试。

## Task 2：后端用户查询与发券测试

先新增控制器集成测试，覆盖：

- 用户名称、手机号、用户 ID 和状态搜索。
- 列表返回优惠券总数、可用数和已使用数。
- 无读取权限不能访问列表。
- 可发模板只包含启用、有效、有库存且未达到个人限领的模板。
- 发券成功写入用户优惠券快照、扣减库存并记录管理员与备注。
- 用户停用、模板无效、库存耗尽、达到限领和缺少发券权限时拒绝操作。

## Task 3：后端实现

1. 新增客户查询、可发模板、发券请求与响应 DTO。
2. 新增 `/admin/customers` 控制器与服务。
3. 使用事务和模板行锁串行化库存/限领校验。
4. 对指定商品券校验商品与模板绑定仍有效。
5. 保持现有 App 领券接口和结算流程不变。

## Task 4：管理后台实现

1. 新增 `Api.Customer` 类型与 `customer.ts` API。
2. 新增用户管理列表，支持关键字、状态搜索和优惠券概况展示。
3. 新增手动发券弹窗，展示可发模板详情、库存、用户领取进度和有效期。
4. 发放前明确提示“占用库存、计入每人限领、不可从本页面撤回”。
5. 使用 `customer:coupon:issue` 控制操作按钮。

## Task 5：验证

后端：

```bash
cd backend/shop-server
./mvnw -Dtest='AdminCustomerControllerTest,AdminMenuControllerTest,AdminRbacSchemaTest,AppUserNicknameMigrationTest,CommerceFulfillmentMySqlMigrationTest,AssetModelMigrationTest' test
./mvnw test
```

管理端：

```bash
cd admin
pnpm exec eslint src/api/customer.ts src/views/customer/user/index.vue src/views/customer/user/modules/coupon-issue-dialog.vue
pnpm exec stylelint src/views/customer/user/index.vue src/views/customer/user/modules/coupon-issue-dialog.vue
pnpm typecheck
pnpm build
```

最终检查：

```bash
git diff --check
git status --short --branch
```

真实本地 smoke 由用户在当前 MySQL、后台登录态和业务数据上执行，自动化验证与真实 smoke 分开记录。

## Task 6：需求澄清后的专属券创建

1. 新增模板发放模式和唯一目标用户字段；历史模板默认 `PUBLIC`。
2. 新增专属券创建接口，在同一事务中写入隐藏的 `DIRECT` 追溯模板、用户券快照和
   `ADMIN_DIRECT` 审计记录。
3. 公共模板列表、用户主动领券和“发送已有优惠券”只读取 `PUBLIC`，确保专属券只能属于目标用户。
4. 发券弹窗改为双模式，默认“创建专属优惠券”，同时保留“发送已有优惠券”。
5. 新增 H2 控制器测试和 MySQL 8 隔离性测试，再运行管理端 lint、类型检查和构建。

## Task 7：营销优惠券中心与领取记录

1. 新增 V29 迁移，将原优惠券叶子菜单调整为父菜单，下设“优惠券”和“领取记录”，并新增
   `coupon:claim:read` 权限；已有菜单角色自动获得两个子菜单，避免升级后入口消失。
2. 先补控制器和 RBAC 回归测试，覆盖公开/专属券统一查询、专属券只读、三种领取来源查询、
   用户/优惠券筛选和领取记录权限拒绝。
3. 扩展优惠券后台列表响应，返回 `distributionMode` 与目标用户信息；默认同时展示 `PUBLIC` 和
   `DIRECT`，专属券不暴露编辑、启用或禁用操作。
4. 新增 `/admin/marketing/coupons/claims` 分页接口，以领取审计记录为事实来源关联用户券状态、
   用户、操作管理员和备注。
5. 新增领取记录管理端页面与 API 类型，并将动态菜单组件映射到
   `/marketing/coupon`、`/marketing/coupon-claim`。
6. 运行优惠券/菜单聚焦测试、完整后端测试、管理端 typecheck/lint/stylelint/build、V29 MySQL
   迁移验证和 `git diff --check`。
