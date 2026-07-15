export interface PaymentConfigUseState {
  active: boolean
  disabled: boolean
  label: '正在使用' | '使用此配置' | '保存并使用' | '配置不完整'
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

export function environmentConfigUseState(
  effectiveConfig?: Api.Payment.EffectiveConfig | null,
  available = false
): PaymentConfigUseState {
  const active = effectiveConfig?.source === 'ENV'
  return {
    active,
    disabled: active || !available,
    label: active ? '正在使用' : available ? '使用此配置' : '配置不完整'
  }
}
