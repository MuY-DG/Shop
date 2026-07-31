export function isPersistedCustomerServiceMessageId(messageId: unknown): messageId is number {
  return typeof messageId === 'number' && Number.isSafeInteger(messageId) && messageId > 0
}

export function requirePersistedCustomerServiceMessageId(messageId: unknown): number {
  if (!isPersistedCustomerServiceMessageId(messageId)) {
    throw new RangeError('Customer-service image requests require a persisted message id')
  }
  return messageId
}
