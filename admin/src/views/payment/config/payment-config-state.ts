export interface PaymentConfigUseState {
  active: boolean
  disabled: boolean
  label: '正在使用' | '使用此配置' | '保存并使用'
}

export interface PaymentConfigDeleteState {
  disabled: boolean
  reason: string
}

export function paymentConfigUseState(
  config: Api.Payment.Config,
  effectiveConfig?: Api.Payment.EffectiveConfig | null,
  dirty = false
): PaymentConfigUseState {
  const active = effectiveConfig?.source === 'DB' && effectiveConfig.id === config.id
  return {
    active,
    disabled: active,
    label: active ? '正在使用' : dirty ? '保存并使用' : '使用此配置'
  }
}

export function paymentConfigDeleteState(
  config: Api.Payment.Config,
  effectiveConfig?: Api.Payment.EffectiveConfig | null
): PaymentConfigDeleteState {
  if (effectiveConfig?.source === 'DB' && effectiveConfig.id === config.id) {
    return {
      disabled: true,
      reason: '正在使用的配置不能删除'
    }
  }
  if (config.enabled) {
    return {
      disabled: true,
      reason: '已启用的配置不能删除'
    }
  }
  if (config.legacySecretFilesPendingImport) {
    return {
      disabled: true,
      reason: '请先迁移旧秘密文件'
    }
  }
  return {
    disabled: false,
    reason: ''
  }
}
