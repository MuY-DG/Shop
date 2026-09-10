# COS 图片直传

## 当前链路

```text
Admin / 小程序
  -> 向业务 API 申请一次性上传会话
  -> 直接 POST 到 COS 临时对象
  -> 业务 API 执行 HEAD 校验和数据万象处理
  -> 写入 storage_asset，删除临时对象
```

业务服务器不转发普通图片字节。SVG 仍经过后端安全解析；支付私钥等非图片敏感文件使用
对应业务配置接口。

## 输出

| 场景 | 输出 | 访问 |
| --- | --- | --- |
| 素材库高清图 | WebP，最长边 2560，质量 85 | 公有读 |
| 素材库展示图 | WebP，最长边 1080，质量 82 | 公有读 |
| 素材库缩略图 | WebP，最长边 480，质量 78 | 公有读 |
| 用户头像 | WebP，最长边 1024，质量 85 | 公有读 |
| 售后凭证 | WebP，最长边 4096，质量 90 | 私有签名 |
| 客服正图 | WebP，最长边 1920，质量 82 | 私有签名 |
| 客服缩略图 | WebP，最长边 720，质量 76 | 私有签名 |
| 素材库视频 | 保留原编码 | 公有读 |

素材库普通图片只上传一次，三档图片从同一份 COS 临时源文件生成，均属于同一个
`storage_asset`。公开源地址保留为高清图；展示图和缩略图分别使用
`.display-1080.webp`、`.thumb-480.webp` 后缀。只有三档生成并校验成功才激活新素材。
SVG 维持原安全上传流程，不做光栅缩图。

商品详情 API 的 `imageVariants` 按高清地址返回已就绪的展示图和缩略图地址；没有派生图
时回退原地址。商品列表、首页商品卡片、购物车展示图片使用已就绪缩略图。
小程序轮播只预加载当前和相邻图片；SKU 图片按可见区域加载，放大预览再请求高清图。
商品本地缓存使用异步读写、内存索引和最多 3 个并发下载，缓存维护不会阻塞打开弹层。

### 已有素材补生成

部署 `V23` 迁移和后端代码后，后台任务自动为公开的 Admin 素材库 JPG/PNG/WebP/GIF
补生成两档派生图，不需要重新上传。任务默认启动 30 秒后运行，每批最多 2 个，批次间隔
30 秒；失败素材 1 小时后重试，期间继续返回高清地址。可通过
`shop.storage.public-image-variants.enabled=false` 关闭，或调整 `initial-delay` / `fixed-delay`。

补生成使用独立的单线程执行器，不占用支付、物流等任务的调度线程；不在商品查询请求中执行，也不下载图片正文到业务服务器。每次仅锁定一个素材行，
持有锁直到 COS 处理完成，以串行化同一素材的补生成和删除；其他商品查询不需等待该锁。
删除素材会清理高清图和两档派生图，包括失败时可能已生成的部分文件。

此任务会在部署后实际调用 COS 图片处理并增加两份对象；仅提交或推送代码不会触发它。

客户端上传会话会限制对象键、MIME、精确文件长度、私有 ACL、禁止覆盖和 15 分钟有效期。
SecretKey 不会下发客户端。

## COS 控制台

### 数据万象

确认目标存储桶已开通图片处理，并能生成 WebP。参考：

- [图片基础压缩](https://cloud.tencent.com/document/product/460/60524)
- [缩放参数](https://cloud.tencent.com/document/product/460/36540)
- [去除元信息](https://cloud.tencent.com/document/product/460/36547)

### CAM

存储桶保持私有读写，`private/` 不能匿名公开。后端凭证只授予目标存储桶和实际前缀：

- `cos:PostObject`
- `cos:GetObject`
- `cos:PutObject`
- `cos:PutObjectACL`
- `cos:DeleteObject`
- `cos:GetBucketDomain`

如需在 Admin 中自动列出账号下的存储桶，还需额外授予账号级
`cos:GetService`；没有该权限时仍可手动填写存储桶和地域。

`GetBucketDomain` 用于自动列出当前存储桶已启用的 REST 自定义域名，并在保存时校验
所选自定义客户端域名确实绑定当前地域和存储桶；它不能代替 DNS、HTTPS、CORS 或
真实上传检查。默认 COS 域名由存储桶和地域直接生成，不依赖该权限。

### CORS

为每个真实 Admin Origin 配置：

```text
Methods:        POST, GET, HEAD
Allow-Headers:  *
Expose-Headers: ETag, Content-Length, Location, x-cos-request-id
Max-Age:        600
Vary: Origin:   开启
```

生产 Origin 不使用 `*`。浏览器可能先发送 `OPTIONS`，需要同时确认预检与 POST 成功。

参考：

- [Web 端直传](https://cloud.tencent.com/document/product/436/9067)
- [设置跨域访问](https://cloud.tencent.com/document/product/436/13318)
- [自定义源站域名](https://cloud.tencent.com/document/product/436/36638)

### 微信合法域名

```text
uploadFile:
  COS 客户端域名
  业务 API 域名（仅兼容仍走业务接口的类型）

downloadFile:
  COS 客户端域名
  实际仍被历史对象使用的 COS 源站域名
```

域名必须是 HTTPS 根域名，不带端口、路径、参数或凭据。自定义源站应直接 CNAME 到当前
存储桶默认域名，不启用 CDN。

### 临时对象

应用会清理失败和过期会话。COS 生命周期可再清理
`private/direct-upload/` 与 `private/config-check/` 下超过 1 天的临时对象，但不能扩大到 `public/` 或其他
`private/` 前缀。

直传依赖 `x-cos-forbid-overwrite=true`。目标桶不能处于已启用版本控制而导致该约束
失效的状态；上线前必须用同一对象键做第二次 POST，确认被拒绝。

## Admin 配置

在对应环境录入：

- Region
- Bucket
- SecretId / SecretKey
- COS 客户端 HTTPS 域名

自定义域名保存时，后端会校验其绑定关系并保存指纹。换域名的顺序是：先添加 CNAME、
证书、CORS 和微信合法域名，再修改 Admin 配置。

保存前，后端会在 `private/config-check/` 下上传一个专用探测对象、读取元数据并删除，
用于确认存储桶、地域、凭据和最小读写权限；该探测不覆盖浏览器 CORS 和真实直传验收。

## 验收

1. Admin 上传 JPG、PNG、WebP、GIF 和视频，确认文件正文直接发送到 COS。
2. 真机验证头像、售后凭证和客服图片。
3. 检查公开 URL、私有签名 URL、WebP 正图和缩略图均可读取。
4. 确认失败或取消上传不会长期占用活跃会话，临时对象能够清理。
5. 检查后端没有接收普通图片正文，日志中没有输出 COS Secret 或签名 URL 凭据。
