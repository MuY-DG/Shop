declare namespace Api {
  namespace Waybill {
    type ExpressMode = 'DISABLED' | 'SANDBOX' | 'PRODUCTION'
    type Environment = 'SANDBOX' | 'PRODUCTION'
    type AttemptStatus =
      | 'CREATING'
      | 'CREATED'
      | 'CANCELING'
      | 'CANCELED'
      | 'UNKNOWN'
      | 'FAILED'
      | 'CONFIRMED'
    type PrintType = 0 | 1
    type ShipmentSource = 'MANUAL' | 'WECHAT_WAYBILL'
    type RegistrationKind = 'TRACE' | 'FOLLOW'
    type RegistrationStatus =
      | 'PENDING'
      | 'REGISTERING'
      | 'REGISTERED'
      | 'FAILED'
      | 'UNKNOWN'
      | 'UNAVAILABLE'
      | 'SKIPPED'
    type SandboxActionType = 100001 | 200001 | 300002 | 300003

    interface Sender {
      name: string
      mobile: string
      company: string
      province: string
      city: string
      district: string
      detailAddress: string
    }

    interface Receiver extends Sender {
      locationName: string
      doorplate: string
    }

    interface ProductionAccount {
      deliveryId: string
      deliveryName: string
      bizIdMasked: string
      serviceType: number | null
      serviceName: string
    }

    type EffectiveAccount = ProductionAccount

    interface Parcel {
      count: number
      weightKg: number
      lengthCm: number
      widthCm: number
      heightCm: number
    }

    interface WechatExpressConfig {
      mode: ExpressMode
      messageEnabled: boolean
      sender: Sender
      production: ProductionAccount
      effective: EffectiveAccount
      defaultParcel: Parcel
      revision: number
      updatedAt: string | null
    }

    interface ProductionAccountUpdate {
      deliveryId: string
      deliveryName: string
      bizId?: string
      clearBizId: boolean
      serviceType: number | null
      serviceName: string
    }

    interface WechatExpressConfigUpdate {
      mode: ExpressMode
      messageEnabled: boolean
      sender: Sender
      production: ProductionAccountUpdate
      defaultParcel: Parcel
      revision: number
    }

    interface ProductionAccountForm extends ProductionAccountUpdate {
      bizId: string
      bizIdMasked: string
    }

    interface WechatExpressConfigForm {
      mode: ExpressMode
      messageEnabled: boolean
      sender: Sender
      production: ProductionAccountForm
      defaultParcel: Parcel
      revision: number
    }

    interface SandboxEvent {
      actionType: SandboxActionType
      actionMessage: string
    }

    interface Attempt {
      id: number
      orderId: number
      environment: Environment
      status: AttemptStatus
      deliveryId: string
      deliveryName: string
      bizIdMasked: string
      serviceType: number
      serviceName: string
      waybillNo: string | null
      parcel: Parcel
      remark: string | null
      expectTime: number | null
      printCount: number
      lastPrintedAt: string | null
      createdAt: string
      cancelledAt: string | null
      confirmedAt: string | null
      canRefresh: boolean
      canCancel: boolean
      canPrint: boolean
      canConfirmShipment: boolean
      canSimulate: boolean
    }

    interface Context {
      mode: ExpressMode
      canCreate: boolean
      blockers: string[]
      sender: Sender | null
      receiver: Receiver | null
      defaultParcel: Parcel
      remainingItems?: Api.Order.ShipmentItem[]
      currentAttempt: Attempt | null
      sandboxActions: SandboxEvent[]
    }

    interface CreateRequest extends Parcel {
      idempotencyKey: string
      remark?: string
      expectTime?: number
      items?: Array<{ orderItemId: number; quantity: number }>
    }

    type SandboxEventRequest = SandboxEvent
  }
}
