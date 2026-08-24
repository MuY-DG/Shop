declare namespace Api {
  namespace WechatPlatform {
    type Source = 'DATABASE' | 'NONE'

    interface Config {
      configured: boolean
      source: Source
      appId: string
      appSecretMasked: string
      appSecretConfigured: boolean
      version: number
      updatedBy: string | null
      updatedAt: string | null
    }

    interface ConfigUpdate {
      appId: string
      appSecret?: string
      version: number
    }

    interface ConfigForm {
      appId: string
      appSecret: string
    }
  }
}
