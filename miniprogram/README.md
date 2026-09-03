# 灶香集微信小程序

原生微信小程序，业务接口以当前 Spring Boot 后端为准。

## 主要能力

- 微信登录、手机号授权、会话刷新和账号注销
- 首页、分类、搜索、商品/SKU、购物车、优惠券和结算
- 微信支付、订单、评价、部分发货、物流和确认收货
- 仅退款、退货退款、凭证上传和售后进度
- 地址簿、微信地址导入、地图选点、收藏和足迹
- COS 直传、客服消息/图片、合规内容和食品信息披露

## 目录

```text
miniprogram/
  custom-tab-bar/  自定义底部导航
  components/      通用与业务组件
  config/          运行环境
  constants/       稳定常量
  features/        DTO 到页面模型的纯映射
  pages/           页面与交互编排
  services/        会话和业务 API
  styles/          设计令牌
  types/           TypeScript 契约
  utils/           请求、错误和系统能力
```

页面不直接调用 `wx.request`，统一使用 `utils/request.ts`；业务样式复用
`styles/tokens.less`。组件边界见
[`miniprogram/components/README.md`](miniprogram/components/README.md)。

## 环境路由

`miniprogram/config/app-config.ts` 先识别
`wx.getAccountInfoSync().miniProgram.appId`，再判断 `envVersion`：

| AppID | envVersion | API |
| --- | --- | --- |
| 开发 `wx2c59f00275b9057a` | `develop` / `trial` | `https://api.muybaby6.icu` |
| 开发 AppID | `release` | 拒绝启动 |
| 生产 `wxd2c02e4864389d80` | 任意版本 | `https://api.junxiangshiping.cn` |
| 未知 AppID | 任意版本 | 拒绝启动 |

不同组合使用独立存储命名空间，生产 AppID 不允许连接开发 API。

版本库中的 `project.config.json` 使用生产 AppID。开发者个人 AppID 放在被忽略的
`project.private.config.json`，不要提交。

## 本地检查

```bash
pnpm install --frozen-lockfile
pnpm check
```

`check` 包含生产代码/测试代码类型检查和业务测试。随后用微信开发者工具导入当前目录
进行预览、真机调试和上传。

## 微信公众平台

按实际环境配置：

- request 合法域名：对应业务 API 域名。
- uploadFile 合法域名：COS 客户端域名；仍由业务 API 接收的兼容类型再加入 API 域名。
- downloadFile 合法域名：COS 客户端域名，以及实际仍被对象 URL 使用的微信头像域名。
- 隐私保护指引：登录所需信息、手机号、位置、图片等必须与真实调用一致。

地图选址使用 `wx.chooseLocation`：

1. 在“开发管理 → 接口设置”申请“选择地理位置”权限。
2. 在隐私保护指引中说明位置只用于收货地址。
3. `app.json` 保留 `requiredPrivateInfos.chooseLocation` 和
   `scope.userLocation` 用途说明。
4. 真机验证选点、取消、拒绝授权、地区补充和地址保存。

## 发布验收

- 用正确 AppID 验证登录 Code 只进入对应后端。
- 使用真实测试账号验证支付、退款、发货、物流插件、COS、地图和客服。
- 检查商品食品分类、真实标签、商家资质、用户协议、隐私指引和注销须知。
- 核对上传包清单和主包体积，再在微信开发者工具上传体验版或正式版。

服务器部署不包含小程序上传。完整业务验收见
[验收清单](../docs/smoke-checks.md)。
