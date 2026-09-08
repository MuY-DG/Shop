import { API_ENDPOINTS } from '../constants/api-endpoints'
import type { AfterSaleEvidenceFile } from '../types/after-sale'
import { downloadAuthenticatedFile, downloadExternalFile } from '../utils/authenticated-download'

/** Private evidence URLs never receive the app's bearer token; only the API fallback does. */
export async function downloadAfterSaleEvidence(
  afterSaleId: number,
  file: AfterSaleEvidenceFile
): Promise<string> {
  if (!Number.isSafeInteger(afterSaleId) || afterSaleId <= 0
    || !Number.isSafeInteger(file.fileId) || file.fileId <= 0
    || file.status !== 'ACTIVE'
    || (file.mediaKind !== 'IMAGE' && file.mediaKind !== 'VIDEO')) {
    throw new Error('该凭证暂不可查看')
  }
  const timeoutMs = file.mediaKind === 'VIDEO' ? 120_000 : 30_000
  if (file.accessMode === 'SIGNED_URL' && file.accessUrl) {
    try {
      return await downloadExternalFile(file.accessUrl, timeoutMs)
    } catch {
      // Expired signatures and temporary COS failures can use the owner-checked API stream.
    }
  }
  return downloadAuthenticatedFile(
    `${API_ENDPOINTS.afterSales.detail(afterSaleId)}/evidence/${file.fileId}`,
    timeoutMs
  )
}
