# Shop 首页装修后端与后台设计

日期：2026-07-14

## 1. 目标与范围

本阶段完成首页装修的后端和管理后台能力，为后续小程序重构提供稳定接口。本阶段不修改 `miniprogram/`。

后台新增“装修管理”父菜单，包含：

1. 首页轮播图（复用现有页面与接口）
2. 首页分类
3. 首页热门商品
4. 首页推荐商品
5. 素材库（复用现有页面与接口）
6. 联系设置（提前提供后续小程序“联系我”所需的电话配置）

后端新增一个公开首页聚合接口和一个公开联系方式接口。首页聚合结果与联系电话分别缓存到 Redis。

## 2. 设计原则

- MySQL 是唯一事实来源，Redis 只保存公开读取模型，不把运营配置只存入 Redis。
- 四类首页内容分别建模，公开接口统一聚合；不在数据库中保存一整块不可约束的首页 JSON。
- 轮播图和素材库复用现有表、权限、页面与素材引用关系。
- 首页分类独立编排，不等同于“所有已启用商品分类”。
- 热门商品与推荐商品共用一张编排表，通过 `section_type` 区分，避免重复结构。
- 首页商品卡不缓存实时库存，商品详情与下单继续以实时库存为准。
- 分类和商品跳转路径由后端根据关联目标生成，避免后台手填无效路径。
- 现有小程序接口 `/app/home/banners` 保留，避免在后续小程序重构前破坏兼容性。

## 3. 数据模型

### 3.1 `home_category_item`

用于选择首页展示的商品分类：

- `category_id`：关联商品分类，同一分类只能编排一次
- `image_file_id` / `image_url`：首页专用展示图及稳定公开 URL 快照
- `sort_order`：升序展示
- `status`：`ENABLED` / `DISABLED`
- 创建、更新时间

公开读取时只返回编排状态和商品分类状态均为启用的记录。

### 3.2 `home_product_item`

用于热门与推荐商品编排：

- `section_type`：`HOT` / `RECOMMENDED`
- `spu_id`：关联商品 SPU；同一分区内不可重复
- `image_file_id` / `image_url`：可选首页覆盖图；为空时使用商品主图
- `sort_order`
- `status`：`ENABLED` / `DISABLED`
- 创建、更新时间

公开读取时只返回编排启用、商品已上架、商品未回收且分类启用的记录。商品永久删除前，启用中的首页编排会阻止删除；禁用编排会随永久删除清理。

### 3.3 `app_contact_setting`

单例配置：

- `id = 1`
- `phone_number`
- `updated_at`

电话号码允许手机号、座机、400 号码以及常见的 `+`、空格、括号和短横线格式。

## 4. 素材引用

新增素材用途与归属：

- `HOME_CATEGORY_IMAGE` / `HOME_CATEGORY_ITEM`
- `HOME_PRODUCT_IMAGE` / `HOME_PRODUCT_ITEM`

创建或修改装修记录时必须验证素材为 `LIBRARY + IMAGE + PUBLIC + ACTIVE`，并写入 `storage_asset_usage`。删除装修记录时将引用标记为移除，保证素材库删除保护与使用明细准确。

## 5. 接口

### 5.1 管理接口

- `GET/POST /admin/home/categories`
- `PUT/DELETE /admin/home/categories/{itemId}`
- `GET/POST /admin/home/hot-products`
- `PUT/DELETE /admin/home/hot-products/{itemId}`
- `GET/POST /admin/home/recommended-products`
- `PUT/DELETE /admin/home/recommended-products/{itemId}`
- `GET /admin/home/options/categories`
- `GET /admin/home/options/products`
- `GET/PUT /admin/contact`

轮播图继续使用 `/admin/home/banners`。

### 5.2 公开接口

- `GET /app/home`
- `GET /app/contact`

首页响应结构：

```json
{
  "banners": [],
  "categories": [],
  "hotProducts": [],
  "recommendedProducts": []
}
```

商品卡包含当前标题、副标题、展示图、价格区间与生成后的详情路径，不包含实时库存。

## 6. Redis 策略

缓存键：

- `shop:public:home:v1`
- `shop:public:contact:v1`

读取采用 Cache Aside：命中直接反序列化返回；未命中从数据库构建并写入 Redis；Redis 连接、读取、反序列化或写入失败时记录警告并直接返回数据库结果。

后台事务提交后发布内容变更事件，由事务提交后的监听器删除对应缓存。轮播图存在定时生效区间，因此首页缓存 TTL 取“配置 TTL”和“下一个轮播开始/结束时间”之间的较短值，避免定时内容长时间延迟生效。

商品标题、主图、价格、上下架和回收状态变化，以及分类状态变化，均触发首页缓存失效。

## 7. 菜单与权限

新增装修父菜单并保留现有菜单 ID：

- 现有首页轮播菜单 `610` 移入装修管理
- 现有素材库菜单 `600` 移入装修管理
- 已拥有 `600` 或 `610` 的角色自动补齐装修父菜单，避免历史角色失去入口

现有权限 `content:banner:*` 与 `asset:*` 不改名。新增：

- `content:home-category:read/write`
- `content:home-hot:read/write`
- `content:home-recommended:read/write`
- `content:contact:read/write`

分类和商品选择器使用装修模块自己的只读选项接口，不要求运营角色额外拥有商品管理权限。

## 8. 后台页面

- 首页分类：表格、素材选择、分类选择、排序、启停、删除
- 首页热门商品：首页商品选择、可选覆盖图、排序、启停、删除
- 首页推荐商品：复用热门商品编排组件，权限和数据分区独立
- 联系设置：读取和更新一个联系电话

后台继续使用现有 `AssetPicker`，不复制素材上传与选择逻辑。

## 9. 非目标

- 不修改或重构小程序首页
- 不实现微信在线客服按钮
- 不做可视化拖拽装修、草稿预览或多版本发布
- 不缓存实时库存，不改变下单库存校验
- 不删除现有 `/app/home/banners`、商品分类和商品列表接口
