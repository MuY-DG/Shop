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
| 素材库图片 | WebP，最长边 2560，质量 85 | 公有读 |
| 用户头像 | WebP，最长边 1024，质量 85 | 公有读 |
| 售后凭证 | WebP，最长边 4096，质量 90 | 私有签名 |
| 客服正图 | WebP，最长边 1920，质量 82 | 私有签名 |
| 客服缩略图 | WebP，最长边 720，质量 76 | 私有签名 |
| 素材库视频 | 保留原编码 | 公有读 |

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

`GetBucketDomain` 用于校验自定义客户端域名确实绑定当前地域和存储桶；它不能代替
DNS、HTTPS、CORS 或真实上传检查。

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
`private/direct-upload/` 下超过 1 天的临时对象，但不能扩大到 `public/` 或其他
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

## 验收

1. Admin 上传 JPG、PNG、WebP、GIF 和视频，确认文件正文直接发送到 COS。
2. 真机验证头像、售后凭证和客服图片。
3. 检查公开 URL、私有签名 URL、WebP 正图和缩略图均可读取。
4. 确认失败或取消上传不会长期占用活跃会话，临时对象能够清理。
5. 检查后端没有接收普通图片正文，日志中没有输出 COS Secret 或签名 URL 凭据。
