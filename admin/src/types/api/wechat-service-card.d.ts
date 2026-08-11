declare namespace Api {
  namespace WechatServiceCard {
    type Identifier = string
    type DeliveryState =
      | 'PENDING'
      | 'SENDING'
      | 'UNKNOWN'
      | 'RECONCILING'
      | 'SUCCEEDED'
      | 'FAILED'
      | 'SKIPPED'

    interface Status {
      captureEnabled: boolean
      workerEnabled: boolean
      captureReady: boolean
      templateConfigured: boolean
      imageReady: boolean
      miniProgramCredentialsReady: boolean
      workerReady: boolean
      callbackEnabled: boolean
      callbackReady: boolean
      blockedCards: number
      pendingDeliveries: number
      sendingDeliveries: number
      unknownDeliveries: number
      failedDeliveries: number
      runtimePersisted: boolean
      version: number
      defaultCaptureEnabled: boolean
      defaultWorkerEnabled: boolean
      reason: string
      updatedBy: Identifier | null
      updatedAt: string | null
      repairEligibleCount: number
      repairEligibleEarliestPaidAt: string | null
      repairEligibleLatestPaidAt: string | null
    }

    interface Delivery {
      id: Identifier
      cardId: Identifier
      orderId: Identifier
      orderNo: string
      sequenceNo: number
      targetStatus: number
      state: DeliveryState
      cardSendBlocked: boolean
      cardSendBlockReason: string
      cardSendBlockedAt: string | null
      setAttempts: number
      reconciliationAttempts: number
      notAppliedObservations: number
      errorCode: string
      errorMessage: string
      nextActionAt: string | null
      appliedAt: string | null
      messageResultState: 'UNKNOWN' | 'FAILED' | string
      messageFailureCode: number | null
      messageFailureMessage: string
      messageResultAt: string | null
      createdAt: string
      updatedAt: string
    }

    type DeliveryList = Api.Common.PaginatedResponse<Delivery>

    interface DeliveryQuery {
      current: number
      size: number
      orderId?: Identifier
      state?: DeliveryState
    }

    interface RuntimeUpdate {
      captureEnabled: boolean
      workerEnabled: boolean
      version: number
      reason: string
    }
  }
}
