# Shop 在线客服按次咨询与富消息设计

日期：2026-07-15

## 目标

在在线客服第一版上补齐真实交易咨询所需的上下文和富消息：

- 从商品详情进入客服时，当前商品自动成为本次咨询对象。
- 从订单详情进入客服时，当前用户自己的订单自动成为本次咨询对象。
- 从个人中心进入时创建普通咨询，不带默认对象。
- 用户和客服双方都能发送图片、商品卡片和订单卡片。
- 关闭后再次咨询开启新轮次，旧订单和商品不再出现在新轮次的“当前咨询”区域。
- 在线客服移动到“交易管理”，客服角色默认获得订单和售后的查看权限。

## 咨询轮次

每个小程序用户继续保留一个长期聊天线程，避免割裂历史记录；线程内部增加递增的 `consultation_no`：

- 首次打开或 `CLOSED` 后再次进入时，建立 `DRAFT` 轮次并保存入口对象；草稿不进入客服队列。
- 用户首次发送文字、图片、订单卡片或商品卡片后，草稿才转为 `WAITING`，并补写默认入口卡片。
- `WAITING` 或 `ACTIVE` 时，从新的商品或订单入口进入，仍使用当前轮次，并把新对象加入当前轮次。
- `CLOSED` 后直接发送消息，会建立并立即激活一个新的普通咨询轮次。
- 历史消息仍可见，并使用系统消息标记新轮次；顶部和右侧只查询当前轮次的关联对象。

这样既保留连续聊天体验，也不会把上一次咨询的订单继续误认为当前问题。

## 当前咨询对象与卡片

会话保存一个最新的当前对象：

- `GENERAL`：普通咨询。
- `PRODUCT`：商品 SPU。
- `ORDER`：订单。

从业务页面进入时，服务端同时完成两件事：

1. 将该资源设为当前咨询对象。
2. 幂等地向当前轮次写入一条 `PRODUCT_CARD` 或 `ORDER_CARD` 消息。

聊天中双方还能继续发送其他卡片。新卡片成为最新当前对象，但同轮次之前的卡片继续保留。订单必须属于当前小程序用户；客服选择订单时也只能看到该用户的订单。商品只允许发送当前仍可展示的商品。

订单卡片展示订单号、状态、金额、首件商品和商品数量；商品卡片展示图片、标题和价格区间。卡片点击后分别进入小程序业务详情或后台业务页面。发送卡片不会自动关闭订单、发货、创建售后或退款。

## 消息类型

- `TEXT`
- `IMAGE`
- `ORDER_CARD`
- `PRODUCT_CARD`
- `SYSTEM`

结构化消息保留资源 ID，由服务端重新读取经过授权的展示数据，客户端不能提交伪造标题、价格、图片或订单状态。

## 图片附件

图片使用现有存储模型新增的 `CUSTOMER_SERVICE_IMAGE` 上传策略：

- `scope = ATTACHMENT`
- `media_kind = IMAGE`
- `visibility = PRIVATE`
- `upload_context_type = CUSTOMER_SERVICE_CONVERSATION`
- `upload_context_id = conversation_id`

用户只能读取自己的客服线程附件；管理员必须拥有客服会话查看权限。发送端必须是当前用户或当前负责客服。浏览器和小程序通过带 Bearer Token 的 Blob/临时文件请求读取图片，不暴露公开直链。

每张图片是一条独立消息。小程序可一次选择多张并顺序上传，后台可多次选择发送；第一阶段不支持视频、语音和任意文件。

## 数据模型增量

### `customer_service_conversation`

新增：

- `consultation_no`
- `context_type`
- `context_id`

### `customer_service_message`

新增：

- `consultation_no`
- `resource_id`，订单卡片、商品卡片或图片资产 ID

### `customer_service_consultation_resource`

统一记录当前轮次的订单和商品，字段包括 `conversation_id`、`consultation_no`、
`resource_type`、`resource_id`、发送方和时间；唯一键为
`(conversation_id, consultation_no, resource_type, resource_id)`。V23 的订单关联迁移到该表，
原表保留用于兼容已经执行的迁移，但新代码不再写入。

## API 增量

### 小程序

- `POST /app/customer-service/conversation/open` 接受 `contextType` 和 `contextId`。
- `POST /app/customer-service/conversation/images` 上传并发送图片。
- `GET /app/customer-service/conversation/messages/{messageId}/image` 读取私有图片。
- `GET /app/customer-service/conversation/order-candidates` 查询当前用户自己的订单。
- `GET /app/customer-service/conversation/product-candidates` 查询可发送的在售商品。
- `POST /app/customer-service/conversation/products/{productId}` 发送商品卡片。
- 现有订单接口语义改为发送订单卡片。

### 管理后台

- `POST /admin/customer-service/conversations/{id}/images`
- `GET /admin/customer-service/messages/{messageId}/image`
- `GET /admin/customer-service/conversations/{id}/product-candidates`
- `POST /admin/customer-service/conversations/{id}/products/{productId}`
- 现有订单接口语义改为发送订单卡片。

## 菜单与权限

- 菜单 `840` 移到交易管理 `830` 下，路径改为 `customer-service`，顺序为 53。
- 客服角色获得交易管理、订单列表、售后列表和在线客服菜单。
- 客服角色默认新增 `order:read`、`aftersale:read`。
- 不默认授予 `order:close`、`order:ship`、`order:shipping:retry` 或 `aftersale:audit`。
- 订单后台接口补齐 `order:read` / `order:close` 的服务端授权，避免权限只影响菜单和按钮。

## 暂不包含

- 语音、视频、文件、图片编辑和消息撤回。
- 自动识图、AI 回复和敏感内容审核。
- 客服默认退款审核或订单操作权限。
- 多实例 WebSocket/Redis 扩展，继续遵循第一版的单后端实例约束。
