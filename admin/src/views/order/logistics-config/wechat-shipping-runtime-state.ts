const CAPABILITY_FAILURE_REASONS: Readonly<Record<string, string>> = {
  UPLOAD_DISABLED: '微信发货同步尚未开启',
  MOCK_PROVIDER: '当前为模拟模式，无法向真实微信平台同步',
  MISSING_APP_ID: '小程序 AppID 尚未配置',
  TRADE_NOT_MANAGED: '小程序尚未接入微信发货信息管理服务',
  WECHAT_40013: '微信认为当前小程序 AppID 无效',
  WECHAT_40014: '微信访问凭证无效',
  WECHAT_40125: '小程序密钥无效',
  WECHAT_42001: '微信访问凭证已过期',
  WECHAT_48001: '微信接口未授权，请先开通发货信息管理服务',
  WECHAT_61007: '微信平台拒绝了当前账号的接口调用',
  PAYLOAD_ERROR: '能力检测请求生成失败',
  REQUEST_AMBIGUOUS: '微信接口请求失败，当前状态无法确认',
  AMBIGUOUS_RESPONSE: '微信接口返回异常，当前状态无法确认',
  CAPABILITY_LOOKUP_FAILED: '微信能力检测失败，当前状态无法确认'
}

export function isWechatShippingCapabilityAvailable(
  capability: Api.Order.WechatShippingCapability
): boolean {
  return (
    capability.uploadEnabled &&
    capability.providerMode === 'REAL' &&
    capability.state === 'AVAILABLE' &&
    capability.tradeManaged === true
  )
}

export function describeWechatShippingCapabilityFailure(
  capability: Api.Order.WechatShippingCapability
): string {
  const errorCode = capability.errorCode?.trim()
  if (errorCode && CAPABILITY_FAILURE_REASONS[errorCode]) {
    return CAPABILITY_FAILURE_REASONS[errorCode]
  }
  if (capability.providerMode === 'MOCK') {
    return CAPABILITY_FAILURE_REASONS.MOCK_PROVIDER
  }
  if (capability.providerMode !== 'REAL') {
    return '微信发货服务提供方当前不可用'
  }
  if (capability.tradeManaged === false) {
    return CAPABILITY_FAILURE_REASONS.TRADE_NOT_MANAGED
  }
  if (capability.state === 'UNKNOWN') {
    return '暂时无法确认微信发货能力，请稍后重新开启'
  }
  if (errorCode) {
    return `微信平台返回错误 ${errorCode}`
  }
  return '微信发货能力当前不可用'
}

export function disabledWechatShippingRuntimeUpdate(
  version: number
): Api.Order.WechatShippingRuntimeUpdate {
  return {
    uploadEnabled: false,
    deliveryEnabled: false,
    receiptReconciliationEnabled: false,
    version
  }
}
