export function createWechatPlatformForm(
  config?: Api.WechatPlatform.Config | null
): Api.WechatPlatform.ConfigForm {
  return {
    appId: config?.appId ?? '',
    appSecret: ''
  }
}

export function canRetainWechatPlatformSecret(config?: Api.WechatPlatform.Config | null) {
  return config?.source === 'DATABASE' && config.appSecretConfigured
}

export function buildWechatPlatformUpdate(
  config: Api.WechatPlatform.Config,
  form: Api.WechatPlatform.ConfigForm
): Api.WechatPlatform.ConfigUpdate {
  const payload: Api.WechatPlatform.ConfigUpdate = {
    appId: form.appId.trim(),
    version: config.version
  }
  const appSecret = form.appSecret.trim()
  if (appSecret) payload.appSecret = appSecret
  return payload
}
