export interface BlobErrorResponse {
  code: number
  msg: string
  data?: unknown
}

const MAX_BLOB_ERROR_BYTES = 64 * 1024

/** Blob 下载接口失败时，Axios 仍会把 JSON 错误体包装成 Blob。 */
export async function decodeErrorResponse(data: unknown): Promise<BlobErrorResponse | null> {
  if (isErrorResponse(data)) return data
  if (typeof Blob === 'undefined' || !(data instanceof Blob) || data.size > MAX_BLOB_ERROR_BYTES) {
    return null
  }
  try {
    const parsed = JSON.parse(await data.text()) as unknown
    return isErrorResponse(parsed) ? parsed : null
  } catch {
    return null
  }
}

function isErrorResponse(data: unknown): data is BlobErrorResponse {
  if (!data || typeof data !== 'object') return false
  const candidate = data as Partial<BlobErrorResponse>
  return typeof candidate.code === 'number' && typeof candidate.msg === 'string' && !!candidate.msg
}
