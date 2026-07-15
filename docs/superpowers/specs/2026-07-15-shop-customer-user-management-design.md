# Shop 小程序用户管理与手动发券设计

日期：2026-07-15

## 目标

在管理后台增加面向小程序客户的用户管理入口，让有权限的运营人员可以：

- 按用户名称、手机号或用户 ID 查找小程序用户。
- 查看账号状态、手机号授权、注册/登录时间和优惠券概况。
- 为单个启用用户手动发送一张当前可用的优惠券。
- 留下可追溯的发放来源、操作管理员和备注。

现有“系统管理 → 用户管理”继续只管理后台管理员账号。本功能使用独立的“用户管理”业务菜单和
`/admin/customers` 接口，避免混淆后台账号与小程序客户。

## 用户列表

用户列表以 `app_user` 为事实来源，返回：

- 用户 ID 与用户名称。
- 手机号及是否已授权。
- `ENABLED` / `DISABLED` 状态。
- 优惠券总数、当前可用数和已使用数。
- 最后登录时间与注册时间。

搜索关键字同时匹配用户名称、手机号和完整用户 ID；状态为独立筛选条件。OpenID、UnionID 不在
列表中展示，减少非必要身份信息暴露。

## 手动发券模式

发券弹窗提供两种模式，默认使用“创建专属优惠券”：

1. **创建专属优惠券**：管理员现场填写名称、优惠门槛、优惠金额、有效期和备注，系统立即创建
   一张只属于当前用户的优惠券，不要求先在营销管理中建立公共模板。
2. **发送已有优惠券**：保留原有能力，从当前启用、有效、有库存且未达到用户限领的公共模板中发送。

专属券 V1 支持全场金额优惠，包括无门槛立减券和满减券。每次创建一张，不提供公开领取入口。
它会在后台“营销管理 → 优惠券 → 优惠券”中以只读的“专属券”类型展示，便于运营追溯，但不会
进入任何面向用户的公共领券入口，也不能在营销页面被编辑、启用或再次发放。

为兼容现有订单、支付和优惠券快照链路，专属券仍创建一条内部追溯模板，但标记为
`distribution_mode = DIRECT`、绑定唯一 `audience_user_id`、固定库存和已发数量均为 1，并保持
禁用状态。公共模板标记为 `PUBLIC`。所有公开领取、公共模板管理和“发送已有优惠券”查询都只处理
`PUBLIC`，因此内部模板不能被其他用户领取或被误当作公共券操作。

## 已有模板发券规则

手动发券复用现有 `coupon_template` 和 `user_coupon` 模型，不创建第二套优惠券：

1. 目标用户必须存在且状态为 `ENABLED`。
2. 模板必须为 `ENABLED`，当前时间位于有效期内，并且仍有库存。
3. 手动发券与用户主动领取共用 `per_user_limit`，避免运营操作绕过模板限领配置。
4. 每次操作只发一张；成功后 `claimed_count + 1`，并生成 `CLAIMED` 状态的 `user_coupon` 快照。
5. 指定商品券必须仍关联一个未彻底删除的商品。
6. 用户、模板、库存和限领校验在同一事务内完成；模板行锁保证并发发放不会超库存。
7. 发券成功后不能由本页面撤回；已锁定、已使用优惠券的生命周期继续由订单流程管理。

## 发放审计

扩展 `coupon_claim_record`：

- `issue_source`：历史与用户领取默认为 `SELF_CLAIM`，后台发放写入 `ADMIN_ISSUE`。
- 专属券后台创建写入 `ADMIN_DIRECT`。
- `issued_by_admin_user_id`：后台发放时记录操作管理员 ID。
- `issue_note`：可选运营备注，最长 200 个字符。

审计字段不改变小程序优惠券展示与结算语义。

## 营销优惠券中心

原“营销管理 → 优惠券”叶子页面调整为优惠券父菜单，包含两个页面：

1. **优惠券**：统一查看公共优惠券模板与专属券内部追溯模板，支持按名称、状态和发放方式筛选。
   公共券保留新增、编辑和启用/禁用操作；专属券显示唯一目标用户并保持只读。
2. **领取记录**：统一查看用户主动领取、后台发送已有券和后台创建专属券产生的记录。列表展示
   优惠券、用户、发放方式、用户券状态、有效期、领取/发放时间、操作管理员和备注。

领取记录以 `coupon_claim_record` 为审计事实，关联 `coupon_template`、`user_coupon`、`app_user`
和可选的 `admin_user` 返回展示信息。查询不会修改库存、用户券状态或订单占用状态。

## API

### `GET /admin/customers`

权限：`customer:user:read` 或 `customer:coupon:issue`。发券权限包含完成发券操作所必需的列表读取能力。

参数：`current`、`size`、`keyword`、`status`。

### `GET /admin/customers/{userId}/issuable-coupon-templates`

权限：`customer:coupon:issue`

只返回该用户当前仍可发放的模板，并携带库存、限领、用户已领取数、门槛、优惠、范围和有效期。

### `POST /admin/customers/{userId}/coupons`

权限：`customer:coupon:issue`

请求体：`templateId`、可选 `note`。成功返回本次生成的用户优惠券信息。

### `POST /admin/customers/{userId}/direct-coupons`

权限：`customer:coupon:issue`

请求体：`name`、可选 `description`、`couponType`、`thresholdCent`、`discountCent`、
`validStartAt`、`validEndAt`、可选 `note`。系统在同一事务中创建内部专属模板、用户优惠券快照和
管理员审计记录。

### `GET /admin/marketing/coupons/claims`

权限：`coupon:claim:read`

参数：`current`、`size`、`templateName`、`userKeyword`、`distributionMode`、`issueSource`、
`status`。返回公开券与专属券的领取/发放记录，并携带目标用户和操作管理员信息。

## 菜单与权限

- 新增根菜单 `CustomerUser`，路径 `/customers`，组件 `/customer/user`，标题“用户管理”。
- 新增 `customer:user:read` 与 `customer:coupon:issue`。
- 默认只授予超级管理员；其他角色由现有“系统管理 → 角色管理 → 授权配置”按需授权。
- 发券按钮同时受前端 `authList` 和后端 `@PreAuthorize` 保护。
- “营销管理 → 优惠券”调整为父菜单，下设“优惠券”和“领取记录”；已有优惠券菜单授权平移到两个
  子菜单，领取记录额外使用 `coupon:claim:read` 保护。

## 暂不包含

- 批量发券、用户分群、自动化营销任务和消息通知。
- 修改用户名称、手机号或微信身份信息。
- 启用、停用或删除小程序用户。
- 撤回已发优惠券、修改优惠券快照或绕过限领。
- 小程序页面改动。
