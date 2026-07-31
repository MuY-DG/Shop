# Shop 在线客服与实时通知第一版设计

日期：2026-07-15

## 目标

在现有 Spring Boot、Vue 管理后台和原生微信小程序中增加一套自建在线客服第一版：

- 小程序只有一个“在线客服”入口。
- 后台用户通过 `R_CUSTOMER_SERVICE` 角色成为客服，不限制业务上的客服人数。
- 用户首次发送内容后，新咨询进入公共待接待队列，由客服手动认领；仅打开页面的草稿不进入队列。
- 已认领会话只允许当前客服回复；普通转接仅面向在线、可接待且未满负荷的客服，并由接收方确认。
- 用户和客服都能发送文字消息，并可把属于该用户的订单关联到会话。
- 会话消息和状态以数据库为准；实时通道只负责提示客户端重新拉取。
- 支付成功订单通过同一实时通道通知有 `order:read` 权限的后台用户。

## 当前仓库约束

- 管理后台使用 Bearer Token，浏览器 WebSocket 不能安全地附加该请求头。
- 小程序也使用 Bearer Token，API 根地址来自 `app.globalData.apiBaseUrl`。
- 后端当前没有 WebSocket starter，前端模板中的示例 WebSocket 客户端只有一个消息处理器，不能直接承载多个业务订阅。
- 当前工作区已有订单、售后和用户昵称改动，本阶段不重写这些改动；新增类型放入独立声明文件，订单页只做最小实时刷新接入。

## 核心概念

### 客服入口与客服坐席

小程序只展示一个入口。客服坐席来自现有 `admin_user`：账号启用且拥有 `R_CUSTOMER_SERVICE` 角色时可出现在转接列表中。后台无需维护另一套客服账号。

### 会话状态

- `WAITING`：用户已发起咨询，尚未被客服认领。
- `ACTIVE`：已经分配给一名客服。
- `CLOSED`：客服已结束会话。

每个小程序用户只有一个长期会话。关闭后的用户再次发消息时，原会话回到 `WAITING`，保留历史消息并清空原客服归属。

### 认领、转接与并发

- `WAITING -> ACTIVE`：认领，写入 `assigned_admin_user_id`。
- `ACTIVE -> ACTIVE`：目标客服接受 60 秒内的转接申请后，原子更新负责人并记录分配日志。
- `ACTIVE -> WAITING`：当前客服或管理员把会话退回公共待接待队列。
- `ACTIVE -> CLOSED`：当前负责人结束会话。
- `CLOSED -> WAITING`：用户再次发送消息自动重开。
- 认领和接受转接都要求客服实时在线、工作状态为 `AVAILABLE` 且未达到默认 5 个会话的接待上限。
- 普通转接接受前负责人不变；拒绝或超时不影响当前接待。管理员可强制转给在线但忙碌或满负荷客服，不能转给离线客服。
- 认领、转接、退回和关闭都使用带当前状态/负责人条件的更新；更新行数不为 1 时返回状态冲突，避免重复认领和越权操作。

### 可见性与加载边界

- 普通客服都能看到 `WAITING` 队列中的用户头像、昵称和本次咨询的第一条用户消息，但不能在认领前读取会话详情、完整消息、订单、商品或图片。
- 会话进入 `ACTIVE` 后，只对当前负责人可读写；其他普通客服的列表、详情接口和实时事件都不再包含该会话。
- 转接申请只向源客服和目标客服发送。目标客服接受前只能看到转接摘要，接受成功后才获得会话详情及本次咨询消息的读取权限。
- 拥有 `customer-service:agent:manage` 的管理员可以监督全部会话，但查看其他客服的会话不会清除负责人的未读数。
- 消息接口只返回当前 `consultation_no` 的消息。首次加载取最近 50 条，`beforeId` 向前分页，`afterId` 用于实时增量同步，单次最多 100 条。
- 会话工作台通过一个分组接口同时取得待接入、接待中和最近结束三组数据，搜索和状态作用域都在服务端执行。

### 订单关联

会话与订单使用多对多关联表。只允许关联 `shop_order.user_id` 等于会话用户的订单。用户可以从订单详情进入客服并携带 `orderId`；客服可以从该用户的候选订单中选择关联。

## 数据模型

### `customer_service_conversation`

- `id`
- `app_user_id`，唯一
- `status`
- `assigned_admin_user_id`
- `last_message_at`
- `app_unread_count`
- `admin_unread_count`
- `claimed_at`
- `closed_at`
- `created_at` / `updated_at`

### `customer_service_message`

- `id`
- `conversation_id`
- `sender_type`：`APP_USER`、`ADMIN`、`SYSTEM`
- `sender_id`
- `message_type`：第一版为 `TEXT`、`SYSTEM`
- `content`
- `client_message_id`，客户端消息幂等键
- `created_at`

### `customer_service_assignment_log`

记录 `CLAIM`、`TRANSFER`、`CLOSE`、`REOPEN`，保留原客服、目标客服、操作人和时间。

### `customer_service_conversation_order`

记录会话与订单的关联、关联发起方和时间，对 `(conversation_id, order_id)` 唯一。

## API

### 小程序

- `GET /app/customer-service/conversation`
- `POST /app/customer-service/conversation/open`
- `GET /app/customer-service/conversation/messages?afterId=&beforeId=&limit=`
- `POST /app/customer-service/conversation/messages`
- `POST /app/customer-service/conversation/orders/{orderId}`
- `POST /app/realtime/tickets`

### 管理后台

- `GET /admin/customer-service/conversations`
- `GET /admin/customer-service/conversations/workspace?keyword=`
- `GET /admin/customer-service/conversations/{conversationId}`
- `GET /admin/customer-service/conversations/{conversationId}/messages?afterId=&beforeId=&limit=`
- `POST /admin/customer-service/conversations/{conversationId}/claim`
- `POST /admin/customer-service/conversations/{conversationId}/transfer-requests`
- `GET /admin/customer-service/transfer-requests/pending`
- `POST /admin/customer-service/transfer-requests/{requestId}/accept`
- `POST /admin/customer-service/transfer-requests/{requestId}/reject`
- `POST /admin/customer-service/conversations/{conversationId}/release`
- `POST /admin/customer-service/conversations/{conversationId}/force-transfer`
- `POST /admin/customer-service/conversations/{conversationId}/close`
- `POST /admin/customer-service/conversations/{conversationId}/messages`
- `GET /admin/customer-service/conversations/{conversationId}/order-candidates`
- `POST /admin/customer-service/conversations/{conversationId}/orders/{orderId}`
- `GET /admin/customer-service/agents`
- `GET /admin/customer-service/agent-state`
- `PUT /admin/customer-service/agent-state`
- `POST /admin/realtime/tickets`

## 实时通道

### 鉴权

客户端先通过正常 Bearer API 请求一次性票据，再连接：

`wss://host/realtime?ticket=<one-time-ticket>`

票据只使用一次，短时过期，保存用户类型、用户 ID 和权限快照。第一版票据存放在当前后端进程内，因此部署约束为单后端实例；多实例阶段再迁移到 Redis 并增加跨实例发布订阅。

### 事件信封

```json
{
  "eventId": "uuid",
  "type": "CUSTOMER_SERVICE_CONVERSATION_UPDATED",
  "occurredAt": "2026-07-15T12:00:00",
  "data": {}
}
```

第一版事件：

- `CUSTOMER_SERVICE_CONVERSATION_UPDATED`
- `ORDER_PAID`
- `PONG`

数据库事务提交后才发布业务事件，避免客户端先刷新却读不到刚写入的数据。重复支付回调不重复发送 `ORDER_PAID`。

## 客户端行为

### 管理后台

- 全局实时客户端支持多订阅者、心跳、断线重新申请票据和指数退避重连。
- 客服页按“待接入 / 接待中 / 最近结束”纵向展开；待接入使用头像卡片，悬浮后直接接入，接入成功后移入普通会话列表。
- 客服页收到消息事件后只增量拉取当前会话的新消息；状态变化时重新加载详情和分组列表。
- 订单页收到 `ORDER_PAID` 后弹出支付通知，并自动刷新列表与状态数量。
- 实时连接不可用时，客服页 15 秒轮询，订单页保留手动刷新。

### 小程序

- 登录后进入客服页时打开实时连接。
- 收到当前用户的客服事件后重新拉取当前会话。
- 连接失败时 5 秒轮询当前会话消息；离开客服页后关闭页面级连接和轮询。

## 权限

- 角色：`R_CUSTOMER_SERVICE`
- `customer-service:conversation:read`
- `customer-service:conversation:claim`
- `customer-service:conversation:transfer`
- `customer-service:conversation:close`
- `customer-service:message:send`
- `customer-service:order:link`
- `customer-service:agent:manage`（仅超级管理员）

超级管理员自动获得菜单和以上权限。客服角色默认获得客服菜单和全部第一版客服权限。

## 第一版不包含

- 语音、视频和文件消息。
- 自动分配。
- 机器人或 AI 自动回复。
- 消息撤回、搜索、敏感词、满意度与统计报表。
- 多实例 WebSocket 路由、Redis Pub/Sub 和持久化通知中心。
- 微信原生客服或企业微信客服的双向同步。
