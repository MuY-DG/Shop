# 高德地图小程序选址配置

收货地址页使用微信 `wx.getLocation` 获取 `gcj02` 坐标，使用微信 `<map>` 组件展示地图，并通过高德微信小程序 SDK 完成地址解析、附近地点和关键词搜索。

本功能使用高德官方 `AMapWX_SDK_V1.3.0`：

- `getRegeo`：将选中坐标解析为省、市、区和详细地址。
- `getPoiAround`：获取选中坐标附近的小区、大厦、门店等 POI。
- `getInputtips`：根据用户输入返回可选地点。

参考高德官方文档：[小程序入门](https://lbs.amap.com/api/wx/gettingstarted)、[获取地址描述](https://lbs.amap.com/api/wx/guide/get-data/regeo)、[获取 POI](https://lbs.amap.com/api/wx/guide/get-data/poi)、[获取输入提示](https://lbs.amap.com/api/wx/guide/get-data/get-inputtips)。

## 启用步骤

1. 在[高德开放平台](https://console.amap.com/dev/key/app)创建应用，申请“微信小程序”类型 Key，不要选择“Web 服务”。
2. 部署后端并执行 Flyway `V51__amap_location_config.sql` 和 `V52__amap_mini_program_sdk.sql`。V52 会自动停用旧 Web 服务 Key，需重新填写小程序 Key。
3. 登录管理后台，进入“配置管理 → 高德地图配置”，填写微信小程序 Key 并开启地图选址。
4. 登录微信公众平台，在“开发 → 开发管理 → 开发设置 → 服务器域名”中，把 `https://restapi.amap.com` 加入 **request 合法域名**。这是高德小程序入门文档明确要求的步骤。
5. 在微信公众平台完成用户隐私保护指引中的位置信息用途声明。小程序 `app.json` 已声明 `getLocation` 和 `scope.userLocation` 用途。
6. 使用真机验证定位授权、地图拖动、附近地点、搜索、选点回填和保存地址。

## 安全与运行说明

- Key 在后台以 AES-GCM 加密存储，只通过需要登录的 `/app/location/config` 接口下发给小程序。
- 微信小程序 Key 属于客户端 Key，运行时会在小程序网络请求中可见，这与 Web 服务 Key 的服务端保密模型不同。
- 禁用后已有地址仍可查看和编辑，但小程序无法新增或重新选择地图地址。
- 选址结果用于辅助填写，用户保存前仍应核对门牌号、楼栋和房间号。
