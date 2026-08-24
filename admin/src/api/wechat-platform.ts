import request from '@/utils/http'

const baseUrl = '/admin/wechat/platform-config'

export function fetchWechatPlatformConfig() {
  return request.get<Api.WechatPlatform.Config>({ url: baseUrl })
}

export function updateWechatPlatformConfig(data: Api.WechatPlatform.ConfigUpdate) {
  return request.put<Api.WechatPlatform.Config>({
    url: baseUrl,
    data,
    showSuccessMessage: true
  })
}
