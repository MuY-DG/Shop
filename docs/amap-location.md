# 微信原生选址与高德备用实现

当前收货地址页使用微信原生 `wx.chooseLocation` 打开地图选点，不再调用
`wx.getLocation`，也不再从运行链路初始化高德 SDK。

`wx.chooseLocation` 返回地点名称、详细地址和 `gcj02` 经纬度，但不单独返回
省、市、区。地址表单把地点名称和完整地址合并显示在同一个“地址”入口中，
并从完整地址自动识别省、市、区后随表单提交。正常情况下不再单独显示地区；
识别不完整时才显示微信原生 `picker mode="region"` 供用户补充。历史地址或
微信通讯地址没有独立地点名时，只显示一条完整地址，避免伪造或重复地点名。

## 微信后台配置

1. 在小程序管理后台的“开发 → 开发管理 → 接口设置”申请“选择地理位置”
   (`wx.chooseLocation`) 权限。
2. 在用户隐私保护指引中说明位置信息仅用于选择和填写收货地址。
3. `app.json` 的 `requiredPrivateInfos` 声明 `chooseLocation`，并保留
   `scope.userLocation` 用途说明。
4. 使用真机验证地图选点、取消、拒绝授权、地区补充、门牌号填写和地址保存。

## 暂停使用的高德实现

原高德地图选址页面、类型、服务和 `AMapWX_SDK_V1.3.0` 文件暂时保留，但该
页面已从 `app.json` 移除，地址编辑页也不再导航到该页面。项目已开启
`ignoreUploadUnusedFiles`，未引用的高德选址文件不会进入正常运行链路。

后端 `/app/location/config`、管理后台高德配置和相关数据库迁移同样保留，以便
后续需要恢复高级搜索、附近地点和逆地址解析时复用。恢复前需重新声明并获批
`getLocation`，配置高德微信小程序 Key 及 `https://restapi.amap.com` 合法域名。

原实现参考：[高德小程序入门](https://lbs.amap.com/api/wx/gettingstarted)、
[获取地址描述](https://lbs.amap.com/api/wx/guide/get-data/regeo)、
[获取 POI](https://lbs.amap.com/api/wx/guide/get-data/poi)、
[获取输入提示](https://lbs.amap.com/api/wx/guide/get-data/get-inputtips)。
